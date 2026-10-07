param(
    [string]$BaseUrl = 'http://127.0.0.1:8080'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$runId = (Get-Date -Format 'yyyyMMddHHmmss') + (Get-Random -Minimum 1000 -Maximum 9999)
$owner = "m2owner$runId"
$slug = "event-gate-$runId"
$passwordBytes = New-Object byte[] 24
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($passwordBytes) } finally { $rng.Dispose() }
$password = [Convert]::ToBase64String($passwordBytes)
$workRoot = Join-Path $env:TEMP "codetrove-m2-$runId"
$askPass = Join-Path $env:TEMP "codetrove-m2-askpass-$runId.cmd"
$eventFile = Join-Path $env:TEMP "codetrove-m2-event-$runId.json"
$containerEvent = "/tmp/codetrove-m2-event-$runId.json"
$ownerId = $null
$repositoryId = $null
$storagePath = $null
$resultEventId = $null
$backendStoppedForCleanup = $false

function Invoke-CodeTroveJson(
    [string]$method,
    [string]$path,
    [object]$body,
    [string]$authorization,
    [hashtable]$extraHeaders = @{}
) {
    $headers = @{}
    if (-not [string]::IsNullOrWhiteSpace($authorization)) { $headers.Authorization = $authorization }
    foreach ($key in $extraHeaders.Keys) { $headers[$key] = $extraHeaders[$key] }
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

function Assert-CodeTroveError(
    [string]$method,
    [string]$path,
    [object]$body,
    [string]$authorization,
    [hashtable]$extraHeaders,
    [int]$status,
    [string]$code
) {
    try {
        Invoke-CodeTroveJson $method $path $body $authorization $extraHeaders | Out-Null
        throw "Expected HTTP $status for $method $path"
    } catch {
        $response = $_.Exception.Response
        if ($null -eq $response -or [int]$response.StatusCode -ne $status) { throw }
        $reader = New-Object IO.StreamReader($response.GetResponseStream(), [Text.Encoding]::UTF8)
        try { $payload = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
        if ($payload.error.code -ne $code) {
            throw "Expected error $code but got $($payload.error.code)"
        }
    }
}

function Invoke-Git([string]$workingDirectory, [string[]]$arguments) {
    $stdout = Join-Path $env:TEMP "codetrove-m2-git-out-$([Guid]::NewGuid().ToString('N')).txt"
    $stderr = Join-Path $env:TEMP "codetrove-m2-git-err-$([Guid]::NewGuid().ToString('N')).txt"
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
    $stdout = Join-Path $env:TEMP "codetrove-m2-mysql-out-$runId.txt"
    $stderr = Join-Path $env:TEMP "codetrove-m2-mysql-err-$runId.txt"
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

function Get-KafkaTopicEndOffset([string]$topic, [int]$partition = 0) {
    $line = (& docker exec codetrove-kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic $topic --time -1 |
        Where-Object { $_ -match "^$([regex]::Escape($topic)):${partition}:(\d+)$" } |
        Select-Object -First 1)
    if ([string]::IsNullOrWhiteSpace($line)) {
        throw "Failed to read end offset for Kafka topic $topic partition $partition"
    }
    return [long]($line -split ':')[-1]
}

function Publish-KafkaJson([string]$topic, [object]$event) {
    $json = $event | ConvertTo-Json -Depth 10 -Compress
    [IO.File]::WriteAllText($eventFile, $json + "`n", (New-Object Text.UTF8Encoding $false))
    & docker cp $eventFile "codetrove-kafka:$containerEvent"
    if ($LASTEXITCODE -ne 0) { throw 'Failed to copy event into Kafka container' }
    & docker exec codetrove-kafka /bin/bash -lc "cat '$containerEvent' | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic '$topic'"
    if ($LASTEXITCODE -ne 0) { throw 'Failed to publish result event to Kafka' }
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
    Write-Output '[1/10] Check final JAR, Kafka readiness, topics, and Flyway V8'
    $readiness = Invoke-RestMethod -Uri "$BaseUrl/actuator/health/readiness" -TimeoutSec 10
    Assert-True ($readiness.status -eq 'UP') 'Backend readiness is not UP'
    Assert-True ((Invoke-CodeTroveAdminSql 'SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1;') -eq '8') 'Flyway V8 is not active'
    $topics = @(& docker exec codetrove-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list)
    foreach ($required in @('codetrove.mr.events.v1', 'codetrove.curator.commands.v1', 'codetrove.curator.results.v1', 'codetrove.assay.commands.v1', 'codetrove.assay.results.v1', 'codetrove.dlq.v1')) {
        Assert-True ($topics -contains $required) "Kafka topic missing: $required"
    }

    [IO.File]::WriteAllText($askPass, "@echo off`r`necho %CODETROVE_M2_PASSWORD%`r`n", [Text.Encoding]::ASCII)
    $env:GIT_ASKPASS = $askPass
    $env:GIT_TERMINAL_PROMPT = '0'
    $env:CODETROVE_M2_PASSWORD = $password
    New-Item -ItemType Directory -Path $workRoot | Out-Null

    Write-Output '[2/10] Create temporary owner, repository, feature branch, and MR'
    $registration = Invoke-CodeTroveJson 'POST' '/api/v1/auth/register' @{
        username = $owner; password = $password; displayName = 'M2 Owner'
    } $null
    $ownerId = [long]$registration.data.id
    $login = Invoke-CodeTroveJson 'POST' '/api/v1/auth/login' @{
        username = $owner; password = $password
    } $null
    $authorization = "Bearer $($login.data.accessToken)"
    $created = Invoke-CodeTroveJson 'POST' '/api/v1/repositories' @{
        name = 'M2 Event Gate'; slug = $slug
        description = 'Temporary real Kafka and check gate verification'
        visibility = 'PRIVATE'; initializeWithReadme = $true
    } $authorization
    $repositoryId = [long]$created.data.id
    $storagePath = Invoke-CodeTroveAdminSql "SELECT storage_path FROM codetrove_repository WHERE id=$repositoryId;"
    $repositoryUrl = "$BaseUrl/git/$owner/$slug.git" -replace '^http://', "http://$owner@"
    $clone = Join-Path $workRoot 'clone'
    Invoke-Git $workRoot @('clone', $repositoryUrl, $clone) | Out-Null
    Invoke-Git $clone @('config', 'user.name', 'CodeTrove M2') | Out-Null
    Invoke-Git $clone @('config', 'user.email', 'm2@codetrove.local') | Out-Null
    Invoke-Git $clone @('switch', '-c', 'feature/m2-gate') | Out-Null
    [IO.File]::WriteAllText(
        (Join-Path $clone 'M2.txt'),
        "initial M2 event`n",
        (New-Object Text.UTF8Encoding $false)
    )
    Invoke-Git $clone @('add', '.') | Out-Null
    Invoke-Git $clone @('commit', '-m', 'feat: add M2 event fixture') | Out-Null
    Invoke-Git $clone @('push', 'origin', 'HEAD:refs/heads/feature/m2-gate') | Out-Null
    $initialHead = (Invoke-Git $clone @('rev-parse', 'HEAD')).Trim()
    $mr = Invoke-CodeTroveJson 'POST' "/api/v1/repositories/$repositoryId/merge-requests" @{
        title = 'Verify M2 event gate'; sourceBranch = 'feature/m2-gate'; targetBranch = 'main'
    } $authorization
    $mergeRequestId = [long]$mr.data.id

    Write-Output '[3/10] Verify Outbox publishes mr.created and Kafka consumer creates one Suite'
    Wait-Until {
        (Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_outbox_event WHERE aggregate_id='$mergeRequestId' AND event_type='mr.created' AND status='PUBLISHED';") -eq '1'
    } 60 'mr.created Outbox event was not published'
    Wait-Until {
        (Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_check_suite WHERE merge_request_id=$mergeRequestId AND head_commit='$initialHead' AND is_current=TRUE;") -eq '1'
    } 60 'mr.created was not consumed into a current Check Suite'
    $checks = Invoke-CodeTroveGet "/api/v1/repositories/$repositoryId/merge-requests/1/checks" $authorization
    Assert-True ($checks.data.current.headCommit -eq $initialHead) 'Current Suite head mismatch'
    $runs = @($checks.data.current.runs)
    $curator = @($runs | Where-Object { $_.name -eq 'curator.review' })
    $assay = @($runs | Where-Object { $_.name -eq 'assay.integration' })
    Assert-True ($curator.Count -eq 1 -and -not $curator[0].blocking -and $curator[0].status -in @('PENDING', 'RUNNING', 'SUCCESS', 'SKIPPED')) 'Curator non-blocking Run is invalid'
    Assert-True ($assay.Count -eq 1 -and $assay[0].status -in @('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED') -and $assay[0].blocking) 'Assay blocking Run is invalid'

    Write-Output '[4/10] Stop Kafka, push a new head, and verify transactional Outbox backlog'
    & docker stop codetrove-kafka | Out-Null
    [IO.File]::AppendAllText(
        (Join-Path $clone 'M2.txt'),
        "head updated while Kafka unavailable`n",
        (New-Object Text.UTF8Encoding $false)
    )
    Invoke-Git $clone @('add', '.') | Out-Null
    Invoke-Git $clone @('commit', '-m', 'feat: update while Kafka unavailable') | Out-Null
    Invoke-Git $clone @('push', 'origin', 'HEAD:refs/heads/feature/m2-gate') | Out-Null
    $latestHead = (Invoke-Git $clone @('rev-parse', 'HEAD')).Trim()
    $detail = Invoke-CodeTroveGet "/api/v1/repositories/$repositoryId/merge-requests/1" $authorization
    Assert-True ($detail.data.headCommit -eq $latestHead) 'MR head update did not commit while Kafka was unavailable'
    Wait-Until {
        (Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_outbox_event WHERE aggregate_id='$mergeRequestId' AND event_type='mr.head-updated' AND status='PENDING';") -eq '1'
    } 30 'Expected PENDING head-updated Outbox event while Kafka was unavailable'
    $readinessDown = $false
    try {
        Invoke-RestMethod -Uri "$BaseUrl/actuator/health/readiness" -TimeoutSec 10 | Out-Null
    } catch {
        if ($null -ne $_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 503) {
            $readinessDown = $true
        }
    }
    Assert-True $readinessDown 'Readiness did not become DOWN while Kafka was unavailable'

    Write-Output '[5/10] Restore Kafka and verify eventual publish plus stale Suite invalidation'
    & docker start codetrove-kafka | Out-Null
    Wait-Until {
        (docker inspect --format '{{.State.Health.Status}}' codetrove-kafka 2>$null) -eq 'healthy'
    } 180 'Kafka did not recover to healthy'
    Wait-Until {
        try { (Invoke-RestMethod -Uri "$BaseUrl/actuator/health/readiness" -TimeoutSec 5).status -eq 'UP' } catch { $false }
    } 120 'Backend readiness did not recover after Kafka restart'
    Wait-Until {
        (Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_outbox_event WHERE aggregate_id='$mergeRequestId' AND event_type='mr.head-updated' AND status='PUBLISHED';") -eq '1'
    } 120 'Pending head-updated Outbox event was not eventually published'
    Wait-Until {
        (Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_check_suite WHERE merge_request_id=$mergeRequestId AND head_commit='$latestHead' AND is_current=TRUE;") -eq '1'
    } 120 'New head did not create the current Check Suite'
    Assert-True ((Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_check_suite WHERE merge_request_id=$mergeRequestId AND head_commit='$initialHead' AND is_current=FALSE;") -eq '1') 'Old Suite was not made historical'
    Assert-True ((Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_check_run r JOIN codetrove_check_suite s ON s.id=r.check_suite_id WHERE s.merge_request_id=$mergeRequestId AND s.head_commit='$initialHead' AND r.blocking=TRUE AND r.status='CANCELLED';") -eq '1') 'Old blocking Run was not cancelled'

    Write-Output '[6/10] Verify pending blocking Check rejects Merge'
    $mergePath = "/api/v1/repositories/$repositoryId/merge-requests/1/merge"
    Assert-CodeTroveError 'POST' $mergePath @{
        expectedHeadCommit = $latestHead; strategy = 'MERGE_COMMIT'
    } $authorization @{ 'Idempotency-Key' = "m2-blocked-$runId" } 409 'MR_CHECKS_NOT_PASSED'

    Write-Output '[7/10] Publish a real Assay result event and verify consumer idempotency'
    $assayRunId = [long](Invoke-CodeTroveAdminSql "SELECT r.id FROM codetrove_check_run r JOIN codetrove_check_suite s ON s.id=r.check_suite_id WHERE s.merge_request_id=$mergeRequestId AND s.head_commit='$latestHead' AND s.is_current=TRUE AND r.name='assay.integration';")
    Invoke-CodeTroveAdminSql "UPDATE codetrove_check_run SET status='PENDING', conclusion=NULL, finished_at=NULL WHERE id=$assayRunId;" | Out-Null
    $resultEventId = [Guid]::NewGuid().ToString()
    $resultEvent = [ordered]@{
        event_id = $resultEventId
        event_type = 'assay.execution-completed'
        schema_version = 1
        occurred_at = [DateTime]::UtcNow.ToString('o')
        producer = 'codetrove-m2-verifier'
        trace_id = "m2-verification-$runId"
        aggregate = @{ type = 'CHECK_RUN'; id = [string]$assayRunId; version = 1 }
        data = @{
            repository_id = [string]$repositoryId
            mr_id = [string]$mergeRequestId
            head_commit = $latestHead
            check_run_id = [string]$assayRunId
            check_type = 'ASSAY'
            status = 'SUCCESS'
            conclusion = 'VERIFICATION_PASSED'
        }
    }
    Publish-KafkaJson 'codetrove.assay.results.v1' $resultEvent
    Publish-KafkaJson 'codetrove.assay.results.v1' $resultEvent
    Wait-Until {
        (Invoke-CodeTroveAdminSql "SELECT status FROM codetrove_check_run WHERE id=$assayRunId;") -eq 'SUCCESS'
    } 60 'Assay result event did not update the blocking Run'
    Assert-True ((Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_consumed_event WHERE consumer_name='codetrove-check-v1' AND event_id='$resultEventId';") -eq '1') 'Duplicate result event was not consumed idempotently'

    Write-Output '[8/10] Verify invalid schema reaches the DLQ after bounded retries'
    $badEventId = [Guid]::NewGuid().ToString()
    $badEvent = [ordered]@{
        event_id = $badEventId
        event_type = 'mr.created'
        schema_version = 99
        occurred_at = [DateTime]::UtcNow.ToString('o')
        producer = 'codetrove-m2-verifier'
        trace_id = "m2-dlq-$runId"
        aggregate = @{ type = 'MERGE_REQUEST'; id = [string]$mergeRequestId; version = 1 }
        data = @{ mr_id = [string]$mergeRequestId; head_commit = $latestHead }
    }
    $dlqResult = Join-Path $env:TEMP "codetrove-m2-dlq-$runId.txt"
    $dlqStartOffset = Get-KafkaTopicEndOffset 'codetrove.dlq.v1'
    Publish-KafkaJson 'codetrove.mr.events.v1' $badEvent
    try {
        & docker exec codetrove-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic codetrove.dlq.v1 --partition 0 --offset $dlqStartOffset --max-messages 1 --timeout-ms 30000 1> $dlqResult
        if ($LASTEXITCODE -ne 0) { throw 'Failed to read the new DLQ message' }
        $dlqText = [IO.File]::ReadAllText($dlqResult, [Text.Encoding]::UTF8)
        Assert-True ($dlqText -like "*$badEventId*") 'The new DLQ message did not retain this run''s invalid event payload'
    } finally {
        Remove-Item -LiteralPath $dlqResult -Force -ErrorAction SilentlyContinue
    }

    Write-Output '[9/10] Merge after current blocking checks pass'
    $merged = Invoke-CodeTroveJson 'POST' $mergePath @{
        expectedHeadCommit = $latestHead; strategy = 'MERGE_COMMIT'
    } $authorization @{ 'Idempotency-Key' = "m2-success-$runId" }
    Assert-True ($merged.data.status -eq 'MERGED') 'Merge did not pass after blocking Check succeeded'
    Wait-Until {
        (Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_outbox_event WHERE aggregate_id='$mergeRequestId' AND event_type='mr.merged' AND status='PUBLISHED';") -eq '1'
    } 60 'mr.merged Outbox event was not published'

    Write-Output '[10/10] Real M2 event and check gate verification passed'
} finally {
    Remove-Item Env:GIT_ASKPASS -ErrorAction SilentlyContinue
    Remove-Item Env:GIT_TERMINAL_PROMPT -ErrorAction SilentlyContinue
    Remove-Item Env:CODETROVE_M2_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $askPass, $eventFile -Force -ErrorAction SilentlyContinue
    try { & docker start codetrove-kafka | Out-Null } catch {}
    try { & docker exec codetrove-kafka rm -f $containerEvent | Out-Null } catch {}
    Remove-Tree $workRoot

    if ($null -ne $repositoryId -or $null -ne $ownerId) {
        Stop-CodeTroveBackend
        $backendStoppedForCleanup = $true
        if ($null -ne $resultEventId) {
            Invoke-CodeTroveAdminSql "DELETE FROM codetrove_consumed_event WHERE consumer_name='codetrove-check-v1' AND event_id='$resultEventId';" | Out-Null
        }
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
        } 180 'Backend did not recover after M2 verification cleanup'
    }
}
