param(
    [string]$BaseUrl = 'http://127.0.0.1:8080'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$runId = (Get-Date -Format 'yyyyMMddHHmmss') + (Get-Random -Minimum 1000 -Maximum 9999)
$owner = "m3owner$runId"
$slug = "curator-$runId"
$passwordBytes = New-Object byte[] 24
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($passwordBytes) } finally { $rng.Dispose() }
$password = [Convert]::ToBase64String($passwordBytes)
$workRoot = Join-Path $env:TEMP "codetrove-m3-$runId"
$askPass = Join-Path $env:TEMP "codetrove-m3-askpass-$runId.cmd"
$eventFile = Join-Path $env:TEMP "codetrove-m3-event-$runId.json"
$containerEvent = "/tmp/codetrove-m3-event-$runId.json"
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
    if ($null -ne $body) { $params.Body = $body | ConvertTo-Json -Depth 8 -Compress }
    return Invoke-RestMethod @params
}

function Invoke-CodeTroveGet([string]$path, [string]$authorization) {
    return Invoke-RestMethod -Method Get -Uri "$BaseUrl$path" -Headers @{
        Authorization = $authorization
    } -TimeoutSec 20
}

function Invoke-Git([string]$workingDirectory, [string[]]$arguments) {
    $stdout = Join-Path $env:TEMP "codetrove-m3-git-out-$([Guid]::NewGuid().ToString('N')).txt"
    $stderr = Join-Path $env:TEMP "codetrove-m3-git-err-$([Guid]::NewGuid().ToString('N')).txt"
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
    $stdout = Join-Path $env:TEMP "codetrove-m3-mysql-out-$runId.txt"
    $stderr = Join-Path $env:TEMP "codetrove-m3-mysql-err-$runId.txt"
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

try {
    Write-Output '[1/8] Check final JAR, readiness, Kafka topics, and Flyway V8'
    $readiness = Invoke-RestMethod -Uri "$BaseUrl/actuator/health/readiness" -TimeoutSec 10
    Assert-True ($readiness.status -eq 'UP') 'Backend readiness is not UP'
    Assert-True ((Invoke-CodeTroveAdminSql 'SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1;') -eq '8') 'Flyway V8 is not active'
    $topics = @(& docker exec codetrove-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list)
    foreach ($required in @('codetrove.mr.events.v1', 'codetrove.curator.commands.v1', 'codetrove.curator.results.v1', 'codetrove.assay.commands.v1', 'codetrove.assay.results.v1')) {
        Assert-True ($topics -contains $required) "Kafka topic missing: $required"
    }

    [IO.File]::WriteAllText($askPass, "@echo off`r`necho %CODETROVE_M3_PASSWORD%`r`n", [Text.Encoding]::ASCII)
    $env:GIT_ASKPASS = $askPass
    $env:GIT_TERMINAL_PROMPT = '0'
    $env:CODETROVE_M3_PASSWORD = $password
    New-Item -ItemType Directory -Path $workRoot | Out-Null

    Write-Output '[2/8] Create owner, repository, vulnerable commit, and MR through real HTTP/Git'
    $registration = Invoke-CodeTroveJson 'POST' '/api/v1/auth/register' @{
        username = $owner; password = $password; displayName = 'M3 Owner'
    } $null
    $ownerId = [long]$registration.data.id
    $login = Invoke-CodeTroveJson 'POST' '/api/v1/auth/login' @{
        username = $owner; password = $password
    } $null
    $authorization = "Bearer $($login.data.accessToken)"
    $created = Invoke-CodeTroveJson 'POST' '/api/v1/repositories' @{
        name = 'M3 Curator'; slug = $slug
        description = 'Temporary real Curator verification'
        visibility = 'PRIVATE'; initializeWithReadme = $true
    } $authorization
    $repositoryId = [long]$created.data.id
    $storagePath = Invoke-CodeTroveAdminSql "SELECT storage_path FROM codetrove_repository WHERE id=$repositoryId;"
    $repositoryUrl = "$BaseUrl/git/$owner/$slug.git" -replace '^http://', "http://$owner@"
    $clone = Join-Path $workRoot 'clone'
    Invoke-Git $workRoot @('clone', $repositoryUrl, $clone) | Out-Null
    Invoke-Git $clone @('config', 'user.name', 'CodeTrove M3') | Out-Null
    Invoke-Git $clone @('config', 'user.email', 'm3@codetrove.local') | Out-Null
    Invoke-Git $clone @('switch', '-c', 'feature/curator') | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $clone 'src') | Out-Null
    $vulnerable = @'
class Feature {
    String apiKey = "secret-value-123";
    void run(boolean ready) {
        if (ready);
        System.out.println("run");
    }
}
'@
    [IO.File]::WriteAllText((Join-Path $clone 'src\Feature.java'), $vulnerable, (New-Object Text.UTF8Encoding $false))
    Invoke-Git $clone @('add', '.') | Out-Null
    Invoke-Git $clone @('commit', '-m', 'feat: add curator verification fixture') | Out-Null
    Invoke-Git $clone @('push', 'origin', 'HEAD:refs/heads/feature/curator') | Out-Null
    $vulnerableHead = (Invoke-Git $clone @('rev-parse', 'HEAD')).Trim()
    $mr = Invoke-CodeTroveJson 'POST' "/api/v1/repositories/$repositoryId/merge-requests" @{
        title = 'Verify M3 Curator'; sourceBranch = 'feature/curator'; targetBranch = 'main'
    } $authorization
    $mergeRequestId = [long]$mr.data.id

    Write-Output '[3/8] Verify Kafka-driven review task, two findings, comments, and Curator Check'
    Wait-Until {
        (Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_review_task WHERE merge_request_id=$mergeRequestId AND head_commit='$vulnerableHead' AND status='SUCCESS' AND finding_count=2;") -eq '1'
    } 120 'Curator did not complete the vulnerable head review'
    Wait-Until {
        (Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_check_run r JOIN codetrove_check_suite s ON s.id=r.check_suite_id WHERE s.merge_request_id=$mergeRequestId AND s.head_commit='$vulnerableHead' AND r.name='curator.review' AND r.status='SUCCESS';") -eq '1'
    } 60 'Curator Check Run did not become SUCCESS'
    Assert-True ((Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_review_finding f JOIN codetrove_review_task t ON t.id=f.review_task_id WHERE t.merge_request_id=$mergeRequestId;") -eq '2') 'Expected two Curator findings'
    Assert-True ((Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_merge_request_comment WHERE merge_request_id=$mergeRequestId AND type='AI_REVIEW' AND author_type='SYSTEM';") -eq '2') 'Expected two Curator system comments'

    Write-Output '[4/8] Verify API filters, exact locations, and credential redaction'
    $review = Invoke-CodeTroveGet "/api/v1/repositories/$repositoryId/merge-requests/1/review-findings" $authorization
    Assert-True ($review.data.task.headCommit -eq $vulnerableHead) 'Review API head mismatch'
    Assert-True (@($review.data.findings).Count -eq 2) 'Review API finding count mismatch'
    $rules = @($review.data.findings | ForEach-Object { $_.ruleId })
    Assert-True ($rules -contains 'SEC001_HARDCODED_CREDENTIAL') 'Security finding missing'
    Assert-True ($rules -contains 'LOGIC001_DANGLING_IF') 'Logic finding missing'
    $security = Invoke-CodeTroveGet "/api/v1/repositories/$repositoryId/merge-requests/1/review-findings?skill=SECURITY" $authorization
    Assert-True (@($security.data.findings).Count -eq 1) 'Security filter did not return one finding'
    $serializedReview = $review | ConvertTo-Json -Depth 12 -Compress
    Assert-True ($serializedReview -notlike '*secret-value-123*') 'Review API leaked the credential-like value'
    Assert-True ((Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_review_finding WHERE evidence LIKE '%secret-value-123%' OR message LIKE '%secret-value-123%';") -eq '0') 'Finding storage leaked the credential-like value'
    Assert-True ((Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_merge_request_comment WHERE body LIKE '%secret-value-123%';") -eq '0') 'Comment storage leaked the credential-like value'

    Write-Output '[5/8] Replay the exact Curator command twice and verify idempotency'
    $commandPayload = Invoke-CodeTroveAdminSql "SELECT payload FROM codetrove_outbox_event WHERE event_type='curator.review-requested' AND aggregate_id='$mergeRequestId' ORDER BY id LIMIT 1;"
    Publish-KafkaJson 'codetrove.curator.commands.v1' $commandPayload
    Publish-KafkaJson 'codetrove.curator.commands.v1' $commandPayload
    Start-Sleep -Seconds 5
    Assert-True ((Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_review_task WHERE merge_request_id=$mergeRequestId AND head_commit='$vulnerableHead';") -eq '1') 'Duplicate command created another ReviewTask'
    Assert-True ((Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_review_finding f JOIN codetrove_review_task t ON t.id=f.review_task_id WHERE t.merge_request_id=$mergeRequestId;") -eq '2') 'Duplicate command created findings'
    Assert-True ((Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_merge_request_comment WHERE merge_request_id=$mergeRequestId AND type='AI_REVIEW';") -eq '2') 'Duplicate command created comments'

    Write-Output '[6/8] Push a clean head and verify current-head isolation'
    $clean = @'
class Feature {
    void run(boolean ready) {
        if (ready) {
            System.out.println("run");
        }
    }
}
'@
    [IO.File]::WriteAllText((Join-Path $clone 'src\Feature.java'), $clean, (New-Object Text.UTF8Encoding $false))
    Invoke-Git $clone @('add', '.') | Out-Null
    Invoke-Git $clone @('commit', '-m', 'fix: remove curator findings') | Out-Null
    Invoke-Git $clone @('push', 'origin', 'HEAD:refs/heads/feature/curator') | Out-Null
    $cleanHead = (Invoke-Git $clone @('rev-parse', 'HEAD')).Trim()
    Wait-Until {
        (Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_review_task WHERE merge_request_id=$mergeRequestId AND head_commit='$cleanHead' AND status='SUCCESS' AND conclusion='NO_FINDINGS';") -eq '1'
    } 120 'Curator did not complete the clean head review'
    $currentReview = Invoke-CodeTroveGet "/api/v1/repositories/$repositoryId/merge-requests/1/review-findings" $authorization
    Assert-True ($currentReview.data.task.headCommit -eq $cleanHead) 'Review API did not switch to the current head'
    Assert-True (@($currentReview.data.findings).Count -eq 0) 'Historical findings leaked into current head results'
    Assert-True ((Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_review_task WHERE merge_request_id=$mergeRequestId;") -eq '2') 'Expected one ReviewTask per head'

    Write-Output '[7/8] Verify event publication, history, and non-blocking semantics'
    Wait-Until {
        (Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_outbox_event WHERE event_type='curator.review-completed' AND status='PUBLISHED' AND aggregate_type='REVIEW_TASK';") -ge '2'
    } 60 'Curator result events were not published'
    Assert-True ((Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_check_run r JOIN codetrove_check_suite s ON s.id=r.check_suite_id WHERE s.merge_request_id=$mergeRequestId AND s.head_commit='$cleanHead' AND s.is_current=TRUE AND r.name='curator.review' AND r.blocking=FALSE AND r.status='SUCCESS';") -eq '1') 'Current Curator Check is not a successful non-blocking Run'
    Wait-Until {
        (Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_check_run r JOIN codetrove_check_suite s ON s.id=r.check_suite_id WHERE s.merge_request_id=$mergeRequestId AND s.head_commit='$cleanHead' AND s.is_current=TRUE AND r.name='assay.integration' AND r.blocking=TRUE AND r.status='SKIPPED' AND r.conclusion='NO_ENABLED_CASES';") -eq '1'
    } 60 'Assay blocking Run did not finish as SKIPPED/NO_ENABLED_CASES'

    Write-Output '[8/8] Real M3 Curator static review verification passed'
} finally {
    Remove-Item Env:GIT_ASKPASS -ErrorAction SilentlyContinue
    Remove-Item Env:GIT_TERMINAL_PROMPT -ErrorAction SilentlyContinue
    Remove-Item Env:CODETROVE_M3_PASSWORD -ErrorAction SilentlyContinue
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
        } 180 'Backend did not recover after M3 verification cleanup'
    }
}
