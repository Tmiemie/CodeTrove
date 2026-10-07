param(
    [string]$BaseUrl = 'http://127.0.0.1:8080'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$runId = (Get-Date -Format 'yyyyMMddHHmmss') + (Get-Random -Minimum 1000 -Maximum 9999)
$owner = "m4owner$runId"
$slug = "assay-$runId"
$passwordBytes = New-Object byte[] 24
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($passwordBytes) } finally { $rng.Dispose() }
$password = [Convert]::ToBase64String($passwordBytes)
$workRoot = Join-Path $env:TEMP "codetrove-m4-$runId"
$askPass = Join-Path $env:TEMP "codetrove-m4-askpass-$runId.cmd"
$eventFile = Join-Path $env:TEMP "codetrove-m4-event-$runId.json"
$containerEvent = "/tmp/codetrove-m4-event-$runId.json"
$ownerId = $null
$repositoryId = $null
$mergeRequestId = $null
$storagePath = $null
$backendStoppedForCleanup = $false

function Invoke-CodeTroveJson(
    [string]$method,
    [string]$path,
    [object]$body,
    [string]$authorization
) {
    $headers = @{}
    if (-not [string]::IsNullOrWhiteSpace($authorization)) { $headers.Authorization = $authorization }
    $params = @{
        Method = $method
        Uri = "$BaseUrl$path"
        Headers = $headers
        ContentType = 'application/json; charset=utf-8'
        TimeoutSec = 30
    }
    if ($null -ne $body) { $params.Body = $body | ConvertTo-Json -Depth 12 -Compress }
    return Invoke-RestMethod @params
}

function Invoke-CodeTroveGet([string]$path, [string]$authorization) {
    return Invoke-RestMethod -Method Get -Uri "$BaseUrl$path" -Headers @{
        Authorization = $authorization
    } -TimeoutSec 20
}

function Invoke-Git([string]$workingDirectory, [string[]]$arguments) {
    $stdout = Join-Path $env:TEMP "codetrove-m4-git-out-$([Guid]::NewGuid().ToString('N')).txt"
    $stderr = Join-Path $env:TEMP "codetrove-m4-git-err-$([Guid]::NewGuid().ToString('N')).txt"
    try {
        Push-Location $workingDirectory
        $previousErrorAction = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            & git -c credential.helper= @arguments 1> $stdout 2> $stderr
            $exitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousErrorAction
            Pop-Location
        }
        $output = ''
        if (Test-Path -LiteralPath $stdout) { $output += [IO.File]::ReadAllText($stdout, [Text.Encoding]::UTF8) }
        if (Test-Path -LiteralPath $stderr) { $output += [IO.File]::ReadAllText($stderr, [Text.Encoding]::UTF8) }
        if ($exitCode -ne 0) { throw "Git command failed with exit code $exitCode. Output: $output" }
        return $output
    } finally {
        Remove-Item -LiteralPath $stdout, $stderr -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-CodeTroveAdminSql([string]$sql) {
    $stdout = Join-Path $env:TEMP "codetrove-m4-mysql-out-$runId.txt"
    $stderr = Join-Path $env:TEMP "codetrove-m4-mysql-err-$runId.txt"
    try {
        & mysql --login-path=codetrove-admin codetrove --batch --skip-column-names --execute $sql 1> $stdout 2> $stderr
        if ($LASTEXITCODE -ne 0) {
            $errorText = if (Test-Path -LiteralPath $stderr) {
                [IO.File]::ReadAllText($stderr, [Text.Encoding]::UTF8)
            } else { 'unknown MySQL CLI error' }
            throw "MySQL command failed: $errorText"
        }
        if (Test-Path -LiteralPath $stdout) {
            return [IO.File]::ReadAllText($stdout, [Text.Encoding]::UTF8).Trim()
        }
        return ''
    } finally {
        Remove-Item -LiteralPath $stdout, $stderr -Force -ErrorAction SilentlyContinue
    }
}

function Wait-Until([scriptblock]$condition, [int]$timeoutSeconds, [string]$message) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    do {
        try {
            if (& $condition) { return }
        } catch {}
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw $message
}

function Publish-KafkaJson([string]$topic, [string]$json) {
    [IO.File]::WriteAllText($eventFile, $json + "`n", (New-Object Text.UTF8Encoding $false))
    & docker cp $eventFile "codetrove-kafka:$containerEvent"
    if ($LASTEXITCODE -ne 0) { throw 'Failed to copy event into Kafka container' }
    & docker exec codetrove-kafka /bin/bash -lc "cat '$containerEvent' | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic '$topic'"
    if ($LASTEXITCODE -ne 0) { throw 'Failed to publish event to Kafka' }
    & docker exec -u 0 codetrove-kafka rm -f $containerEvent | Out-Null
}

function Stop-CodeTroveBackend {
    $connections = @(Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue)
    foreach ($connection in $connections) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($connection.OwningProcess)"
        if ([string]$process.CommandLine -notlike '*codetrove-bootstrap*') {
            throw 'Port 8080 is owned by an unrelated process; refusing cleanup stop.'
        }
        Stop-Process -Id $connection.OwningProcess -Force
        Wait-Process -Id $connection.OwningProcess -ErrorAction SilentlyContinue
    }
}

function Remove-Tree([string]$path) {
    if ([string]::IsNullOrWhiteSpace($path) -or -not (Test-Path -LiteralPath $path)) { return }
    Get-ChildItem -LiteralPath $path -Recurse -Force -ErrorAction SilentlyContinue |
        ForEach-Object { $_.Attributes = 'Normal' }
    Remove-Item -LiteralPath $path -Recurse -Force
}

function Assert-True([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
}

$passingCase = @'
{
  "schema_version":"1.0",
  "case_key":"assay.real.success",
  "description":"real M4 passing case",
  "data_pre":[{
    "key":"prepare",
    "type":"http",
    "request":{"target":"mock","method":"GET","path":"/prepare"},
    "save_as":"prepared",
    "extract":{"value":"$.body.value"}
  }],
  "mocks":[
    {
      "id":"prepare-mock",
      "type":"http",
      "match":{"method":"GET","path":"/prepare"},
      "respond":{"status":200,"body":{"value":"ready"}},
      "expect_calls":{"min":1,"max":1}
    },
    {
      "id":"verify-mock",
      "type":"http",
      "match":{"method":"POST","path":"/verify"},
      "respond":{"status":201,"body":{"status":"SUCCESS"}},
      "expect_calls":{"min":1,"max":1}
    }
  ],
  "request":{
    "target":"mock",
    "method":"POST",
    "path":"/verify",
    "headers":{"X-CodeTrove-Test-Case":"${context.prepared.value}"},
    "body":{"prepared":"${context.prepared.value}","requestId":"${#uuid()}"}
  },
  "assertions":[
    {"type":"response","operator":"equals","path":"$.status","expected":201},
    {"type":"response","operator":"equals","path":"$.body.status","expected":"SUCCESS"}
  ]
}
'@

$failingCase = @'
{
  "schema_version":"1.0",
  "case_key":"assay.real.failure",
  "description":"real M4 failing case",
  "mocks":[{
    "id":"status-mock",
    "type":"http",
    "match":{"method":"GET","path":"/status"},
    "respond":{"status":200,"body":{"status":"FAILED"}},
    "expect_calls":{"min":1,"max":1}
  }],
  "request":{"target":"mock","method":"GET","path":"/status"},
  "assertions":[{
    "type":"response",
    "operator":"equals",
    "path":"$.body.status",
    "expected":"SUCCESS"
  }]
}
'@

try {
    Write-Output '[1/8] Check final JAR, readiness, Kafka topics, and Flyway V8'
    $readiness = Invoke-RestMethod -Uri "$BaseUrl/actuator/health/readiness" -TimeoutSec 10
    Assert-True ($readiness.status -eq 'UP') 'Backend readiness is not UP'
    Assert-True ((Invoke-CodeTroveAdminSql 'SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1;') -eq '8') 'Flyway V8 is not active'
    $topics = @(& docker exec codetrove-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list)
    foreach ($required in @('codetrove.mr.events.v1', 'codetrove.assay.commands.v1', 'codetrove.assay.results.v1')) {
        Assert-True ($topics -contains $required) "Kafka topic missing: $required"
    }

    [IO.File]::WriteAllText($askPass, "@echo off`r`necho %CODETROVE_M4_PASSWORD%`r`n", [Text.Encoding]::ASCII)
    $env:GIT_ASKPASS = $askPass
    $env:GIT_TERMINAL_PROMPT = '0'
    $env:CODETROVE_M4_PASSWORD = $password
    New-Item -ItemType Directory -Path $workRoot | Out-Null

    Write-Output '[2/8] Create repository and push a passing declarative case through real Git HTTP'
    $registration = Invoke-CodeTroveJson 'POST' '/api/v1/auth/register' @{
        username = $owner; password = $password; displayName = 'M4 Owner'
    } $null
    $ownerId = [long]$registration.data.id
    $login = Invoke-CodeTroveJson 'POST' '/api/v1/auth/login' @{
        username = $owner; password = $password
    } $null
    $authorization = "Bearer $($login.data.accessToken)"
    $created = Invoke-CodeTroveJson 'POST' '/api/v1/repositories' @{
        name = 'M4 Assay'; slug = $slug
        description = 'Temporary real Assay verification'
        visibility = 'PRIVATE'; initializeWithReadme = $true
    } $authorization
    $repositoryId = [long]$created.data.id
    $storagePath = Invoke-CodeTroveAdminSql "SELECT storage_path FROM codetrove_repository WHERE id=$repositoryId;"
    $repositoryUrl = "$BaseUrl/git/$owner/$slug.git" -replace '^http://', "http://$owner@"
    $clone = Join-Path $workRoot 'clone'
    Invoke-Git $workRoot @('clone', $repositoryUrl, $clone) | Out-Null
    Invoke-Git $clone @('config', 'user.name', 'CodeTrove M4') | Out-Null
    Invoke-Git $clone @('config', 'user.email', 'm4@codetrove.local') | Out-Null
    Invoke-Git $clone @('switch', '-c', 'feature/assay') | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $clone 'testcases') | Out-Null
    [IO.File]::WriteAllText((Join-Path $clone 'testcases\integration.json'), $passingCase, (New-Object Text.UTF8Encoding $false))
    Invoke-Git $clone @('add', '.') | Out-Null
    Invoke-Git $clone @('commit', '-m', 'test: add passing assay case') | Out-Null
    Invoke-Git $clone @('push', 'origin', 'HEAD:refs/heads/feature/assay') | Out-Null
    $passingHead = (Invoke-Git $clone @('rev-parse', 'HEAD')).Trim()
    $mr = Invoke-CodeTroveJson 'POST' "/api/v1/repositories/$repositoryId/merge-requests" @{
        title = 'Verify M4 Assay'; sourceBranch = 'feature/assay'; targetBranch = 'main'
    } $authorization
    $mergeRequestId = [long]$mr.data.id

    Write-Output '[3/8] Verify Kafka command/result, Execution, TEST_REPORT, and blocking SUCCESS'
    Wait-Until {
        (Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_assay_execution WHERE merge_request_id=$mergeRequestId AND head_commit='$passingHead' AND status='SUCCESS' AND conclusion='ALL_CASES_PASSED' AND total_count=1 AND passed_count=1;") -eq '1'
    } 120 'Assay did not complete the passing head'
    Wait-Until {
        (Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_check_run r JOIN codetrove_check_suite s ON s.id=r.check_suite_id WHERE s.merge_request_id=$mergeRequestId AND s.head_commit='$passingHead' AND r.name='assay.integration' AND r.blocking=TRUE AND r.status='SUCCESS';") -eq '1'
    } 60 'Assay blocking Check did not become SUCCESS'
    Assert-True ((Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_assay_case_result c JOIN codetrove_assay_execution e ON e.id=c.execution_id WHERE e.merge_request_id=$mergeRequestId AND c.status='PASSED';") -eq '1') 'Expected one passed CaseResult'
    Assert-True ((Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_merge_request_comment WHERE merge_request_id=$mergeRequestId AND type='TEST_REPORT' AND author_type='SYSTEM';") -eq '1') 'Expected one system TEST_REPORT comment'

    Write-Output '[4/8] Verify report API and duplicate command idempotency'
    $report = Invoke-CodeTroveGet "/api/v1/repositories/$repositoryId/merge-requests/1/test-report" $authorization
    Assert-True ($report.data.execution.headCommit -eq $passingHead) 'Test report head mismatch'
    Assert-True ($report.data.execution.status -eq 'SUCCESS') 'Test report status mismatch'
    Assert-True (@($report.data.cases).Count -eq 1) 'Test report case count mismatch'
    $commandPayload = Invoke-CodeTroveAdminSql "SELECT payload FROM codetrove_outbox_event WHERE event_type='assay.execution-requested' AND aggregate_id='$mergeRequestId' ORDER BY id LIMIT 1;"
    Publish-KafkaJson 'codetrove.assay.commands.v1' $commandPayload
    Publish-KafkaJson 'codetrove.assay.commands.v1' $commandPayload
    Start-Sleep -Seconds 5
    Assert-True ((Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_assay_execution WHERE merge_request_id=$mergeRequestId AND head_commit='$passingHead';") -eq '1') 'Duplicate command created another Execution'
    Assert-True ((Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_assay_case_result c JOIN codetrove_assay_execution e ON e.id=c.execution_id WHERE e.merge_request_id=$mergeRequestId AND e.head_commit='$passingHead';") -eq '1') 'Duplicate command created another CaseResult'
    Assert-True ((Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_merge_request_comment WHERE merge_request_id=$mergeRequestId AND type='TEST_REPORT';") -eq '1') 'Duplicate command created another report comment'

    Write-Output '[5/8] Push a failing case and verify current-head isolation'
    [IO.File]::WriteAllText((Join-Path $clone 'testcases\integration.json'), $failingCase, (New-Object Text.UTF8Encoding $false))
    Invoke-Git $clone @('add', '.') | Out-Null
    Invoke-Git $clone @('commit', '-m', 'test: make assay assertion fail') | Out-Null
    Invoke-Git $clone @('push', 'origin', 'HEAD:refs/heads/feature/assay') | Out-Null
    $failingHead = (Invoke-Git $clone @('rev-parse', 'HEAD')).Trim()
    Wait-Until {
        (Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_assay_execution WHERE merge_request_id=$mergeRequestId AND head_commit='$failingHead' AND status='FAILED' AND conclusion='ASSERTION_MISMATCH';") -eq '1'
    } 120 'Assay did not complete the failing head'
    Wait-Until {
        (Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_check_run r JOIN codetrove_check_suite s ON s.id=r.check_suite_id WHERE s.merge_request_id=$mergeRequestId AND s.head_commit='$failingHead' AND s.is_current=TRUE AND r.name='assay.integration' AND r.status='FAILED';") -eq '1'
    } 60 'Failing Assay Check did not become FAILED'

    Write-Output '[6/8] Verify structured assertion diff and current blocking semantics'
    $failedReport = Invoke-CodeTroveGet "/api/v1/repositories/$repositoryId/merge-requests/1/test-report" $authorization
    Assert-True ($failedReport.data.execution.headCommit -eq $failingHead) 'Report API did not switch to current head'
    Assert-True ($failedReport.data.execution.status -eq 'FAILED') 'Current report is not FAILED'
    Assert-True ($failedReport.data.cases[0].assertionDiff[0].path -eq '$.body.status') 'Assertion diff path mismatch'
    Assert-True ($failedReport.data.cases[0].assertionDiff[0].expected -eq '"SUCCESS"') 'Assertion diff expected mismatch'
    Assert-True ($failedReport.data.cases[0].assertionDiff[0].actual -eq '"FAILED"') 'Assertion diff actual mismatch'
    Assert-True ((Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_assay_execution WHERE merge_request_id=$mergeRequestId;") -eq '2') 'Expected one Execution per head'
    Assert-True ((Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_merge_request_comment WHERE merge_request_id=$mergeRequestId AND type='TEST_REPORT';") -eq '2') 'Expected one report comment per head'

    Write-Output '[7/8] Verify result publication and no sensitive payload leakage'
    Wait-Until {
        (Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_outbox_event WHERE event_type='assay.execution-completed' AND status='PUBLISHED' AND aggregate_type='ASSAY_EXECUTION';") -ge '2'
    } 60 'Assay result events were not published'
    Assert-True ((Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_assay_execution WHERE merge_request_id=$mergeRequestId AND run_token IS NOT NULL;") -eq '0') 'Assay run token was not cleared'
    Assert-True ((Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_merge_request_comment WHERE merge_request_id=$mergeRequestId AND body LIKE '%CODETROVE_M4_PASSWORD%';") -eq '0') 'Report leaked a sensitive environment key'

    Write-Output '[8/8] Real M4 Assay declarative HTTP verification passed'
} finally {
    Remove-Item Env:GIT_ASKPASS -ErrorAction SilentlyContinue
    Remove-Item Env:GIT_TERMINAL_PROMPT -ErrorAction SilentlyContinue
    Remove-Item Env:CODETROVE_M4_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $askPass, $eventFile -Force -ErrorAction SilentlyContinue
    try { & docker exec -u 0 codetrove-kafka rm -f $containerEvent | Out-Null } catch {}
    Remove-Tree $workRoot

    if ($null -ne $repositoryId -or $null -ne $ownerId) {
        Stop-CodeTroveBackend
        $backendStoppedForCleanup = $true
        if ($null -ne $repositoryId) {
            Invoke-CodeTroveAdminSql "DELETE FROM codetrove_consumed_event WHERE event_id IN (SELECT event_id FROM codetrove_outbox_event WHERE aggregate_id IN (SELECT CAST(id AS CHAR) FROM codetrove_merge_request WHERE repository_id=$repositoryId)); DELETE FROM codetrove_outbox_event WHERE aggregate_id IN (SELECT CAST(id AS CHAR) FROM codetrove_merge_request WHERE repository_id=$repositoryId); DELETE FROM codetrove_consumed_event WHERE event_id IN (SELECT event_id FROM codetrove_outbox_event WHERE aggregate_type='REVIEW_TASK' AND aggregate_id IN (SELECT CAST(id AS CHAR) FROM codetrove_review_task WHERE repository_id=$repositoryId)); DELETE FROM codetrove_outbox_event WHERE aggregate_type='REVIEW_TASK' AND aggregate_id IN (SELECT CAST(id AS CHAR) FROM codetrove_review_task WHERE repository_id=$repositoryId); DELETE FROM codetrove_consumed_event WHERE event_id IN (SELECT event_id FROM codetrove_outbox_event WHERE aggregate_type='ASSAY_EXECUTION' AND aggregate_id IN (SELECT CAST(id AS CHAR) FROM codetrove_assay_execution WHERE repository_id=$repositoryId)); DELETE FROM codetrove_outbox_event WHERE aggregate_type='ASSAY_EXECUTION' AND aggregate_id IN (SELECT CAST(id AS CHAR) FROM codetrove_assay_execution WHERE repository_id=$repositoryId); DELETE FROM codetrove_assay_case_result WHERE execution_id IN (SELECT id FROM codetrove_assay_execution WHERE repository_id=$repositoryId); DELETE FROM codetrove_assay_execution WHERE repository_id=$repositoryId; DELETE FROM codetrove_review_finding WHERE review_task_id IN (SELECT id FROM codetrove_review_task WHERE repository_id=$repositoryId); DELETE FROM codetrove_review_task WHERE repository_id=$repositoryId; DELETE FROM codetrove_check_run WHERE check_suite_id IN (SELECT id FROM codetrove_check_suite WHERE merge_request_id IN (SELECT id FROM codetrove_merge_request WHERE repository_id=$repositoryId)); DELETE FROM codetrove_check_suite WHERE merge_request_id IN (SELECT id FROM codetrove_merge_request WHERE repository_id=$repositoryId); DELETE FROM codetrove_merge_request_comment WHERE merge_request_id IN (SELECT id FROM codetrove_merge_request WHERE repository_id=$repositoryId); DELETE FROM codetrove_merge_operation WHERE repository_id=$repositoryId; DELETE FROM codetrove_merge_request_commit WHERE merge_request_id IN (SELECT id FROM codetrove_merge_request WHERE repository_id=$repositoryId); DELETE FROM codetrove_merge_request WHERE repository_id=$repositoryId; DELETE FROM codetrove_repository_member WHERE repository_id=$repositoryId; DELETE FROM codetrove_repository WHERE id=$repositoryId;" | Out-Null
        }
        if ($null -ne $ownerId) {
            Invoke-CodeTroveAdminSql "DELETE FROM codetrove_user WHERE id=$ownerId;" | Out-Null
        }
        if (-not [string]::IsNullOrWhiteSpace($storagePath)) {
            $configuredRoot = [Environment]::GetEnvironmentVariable('CODETROVE_REPOSITORY_ROOT', 'User')
            if ([string]::IsNullOrWhiteSpace($configuredRoot)) {
                $configuredRoot = Join-Path $root 'runtime\repositories'
            } elseif (-not [IO.Path]::IsPathRooted($configuredRoot)) {
                $configuredRoot = Join-Path $root $configuredRoot
            }
            $safeRoot = [IO.Path]::GetFullPath($configuredRoot).TrimEnd('\') + '\'
            $safeStorage = [IO.Path]::GetFullPath($storagePath)
            $insideControlledRoot = $safeStorage.StartsWith($safeRoot, [StringComparison]::OrdinalIgnoreCase)
            $expectedRepositoryName = [IO.Path]::GetFileName($safeStorage) -eq "$slug.git"
            if (-not $insideControlledRoot -or -not $expectedRepositoryName) {
                throw 'Refusing to delete a repository path outside the controlled root'
            }
            Remove-Tree $safeStorage
            $ownerDirectory = Split-Path -Parent $safeStorage
            $ownerChildren = @(Get-ChildItem -LiteralPath $ownerDirectory -Force -ErrorAction SilentlyContinue)
            if ([IO.Path]::GetFileName($ownerDirectory) -eq $owner -and $ownerChildren.Count -eq 0) {
                Remove-Item -LiteralPath $ownerDirectory -Force
            }
        }
    }

    if ($backendStoppedForCleanup) {
        & (Join-Path $PSScriptRoot 'start-local.ps1') -SkipFrontend
        Wait-Until {
            try { (Invoke-RestMethod -Uri "$BaseUrl/actuator/health/readiness" -TimeoutSec 5).status -eq 'UP' } catch { $false }
        } 180 'Backend did not recover after M4 verification cleanup'
    }
}
