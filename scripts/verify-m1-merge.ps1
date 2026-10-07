param(
    [string]$BaseUrl = 'http://127.0.0.1:8080'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$runId = (Get-Date -Format 'yyyyMMddHHmmss') + (Get-Random -Minimum 1000 -Maximum 9999)
$owner = "m16owner$runId"
$slug = "merge-smoke-$runId"
$passwordBytes = New-Object byte[] 24
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($passwordBytes) } finally { $rng.Dispose() }
$password = [Convert]::ToBase64String($passwordBytes)
$workRoot = Join-Path $env:TEMP "codetrove-m16-$runId"
$askPass = Join-Path $env:TEMP "codetrove-m16-askpass-$runId.cmd"
$ownerId = $null
$repositoryId = $null
$storagePath = $null
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
    if ($null -ne $body) { $params.Body = $body | ConvertTo-Json -Depth 6 -Compress }
    return Invoke-RestMethod @params
}

function Invoke-CodeTroveGet([string]$path, [string]$authorization) {
    return Invoke-RestMethod -Method Get -Uri "$BaseUrl$path" -Headers @{
        Authorization = $authorization
    } -TimeoutSec 20
}

function Invoke-Git([string]$workingDirectory, [string[]]$arguments) {
    $stdout = Join-Path $env:TEMP "codetrove-m16-git-out-$([Guid]::NewGuid().ToString('N')).txt"
    $stderr = Join-Path $env:TEMP "codetrove-m16-git-err-$([Guid]::NewGuid().ToString('N')).txt"
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
    $stdout = Join-Path $env:TEMP "codetrove-m16-mysql-out-$runId.txt"
    $stderr = Join-Path $env:TEMP "codetrove-m16-mysql-err-$runId.txt"
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
    Write-Output '[1/7] Check final JAR readiness and Flyway V5'
    $readiness = Invoke-RestMethod -Uri "$BaseUrl/actuator/health/readiness" -TimeoutSec 10
    Assert-True ($readiness.status -eq 'UP') 'Backend readiness is not UP'
    Assert-True ((Invoke-CodeTroveAdminSql 'SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1;') -eq '5') 'Flyway V5 is not active'

    [IO.File]::WriteAllText($askPass, "@echo off`r`necho %CODETROVE_M16_PASSWORD%`r`n", [Text.Encoding]::ASCII)
    $env:GIT_ASKPASS = $askPass
    $env:GIT_TERMINAL_PROMPT = '0'
    $env:CODETROVE_M16_PASSWORD = $password
    New-Item -ItemType Directory -Path $workRoot | Out-Null

    Write-Output '[2/7] Create temporary owner, repository, and feature branch'
    $registration = Invoke-CodeTroveJson 'POST' '/api/v1/auth/register' @{
        username = $owner; password = $password; displayName = 'M1.6 Owner'
    } $null
    $ownerId = [long]$registration.data.id
    $login = Invoke-CodeTroveJson 'POST' '/api/v1/auth/login' @{
        username = $owner; password = $password
    } $null
    $authorization = "Bearer $($login.data.accessToken)"
    $created = Invoke-CodeTroveJson 'POST' '/api/v1/repositories' @{
        name = 'M1.6 Merge Smoke'; slug = $slug
        description = 'Temporary real environment merge verification'
        visibility = 'PRIVATE'; initializeWithReadme = $true
    } $authorization
    $repositoryId = [long]$created.data.id
    $storagePath = Invoke-CodeTroveAdminSql "SELECT storage_path FROM codetrove_repository WHERE id=$repositoryId;"
    $repositoryUrl = "$BaseUrl/git/$owner/$slug.git" -replace '^http://', "http://$owner@"
    $clone = Join-Path $workRoot 'clone'
    Invoke-Git $workRoot @('clone', $repositoryUrl, $clone) | Out-Null
    Invoke-Git $clone @('config', 'user.name', 'CodeTrove M1.6') | Out-Null
    Invoke-Git $clone @('config', 'user.email', 'm16@codetrove.local') | Out-Null
    Invoke-Git $clone @('switch', '-c', 'feature/m16-merge') | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $clone 'src') | Out-Null
    [IO.File]::WriteAllText(
        (Join-Path $clone 'src\MergeTarget.java'),
        "class MergeTarget {`n    int version = 1;`n}`n",
        (New-Object Text.UTF8Encoding $false)
    )
    Invoke-Git $clone @('add', '.') | Out-Null
    Invoke-Git $clone @('commit', '-m', 'feat: add M1.6 merge target') | Out-Null
    Invoke-Git $clone @('push', 'origin', 'HEAD:refs/heads/feature/m16-merge') | Out-Null
    $initialHead = (Invoke-Git $clone @('rev-parse', 'HEAD')).Trim()
    $targetBefore = (Invoke-Git $clone @('rev-parse', 'origin/main')).Trim()

    Write-Output '[3/7] Create MR, push again, and verify automatic head history sync'
    $mr = Invoke-CodeTroveJson 'POST' "/api/v1/repositories/$repositoryId/merge-requests" @{
        title = 'Merge M1.6 feature'; description = 'Real environment merge verification'
        sourceBranch = 'feature/m16-merge'; targetBranch = 'main'
    } $authorization
    Assert-True ($mr.data.headCommit -eq $initialHead -and $mr.data.version -eq 0) 'MR initial head is invalid'
    [IO.File]::AppendAllText(
        (Join-Path $clone 'src\MergeTarget.java'),
        "// synchronized after MR creation`n",
        (New-Object Text.UTF8Encoding $false)
    )
    Invoke-Git $clone @('add', '.') | Out-Null
    Invoke-Git $clone @('commit', '-m', 'feat: update M1.6 merge target') | Out-Null
    Invoke-Git $clone @('push', 'origin', 'HEAD:refs/heads/feature/m16-merge') | Out-Null
    $latestHead = (Invoke-Git $clone @('rev-parse', 'HEAD')).Trim()
    $detail = Invoke-CodeTroveGet "/api/v1/repositories/$repositoryId/merge-requests/1" $authorization
    Assert-True ($detail.data.headCommit -eq $latestHead) 'Successful HTTP push did not update the OPEN MR head'
    Assert-True ($detail.data.version -eq 1) 'MR version did not advance after successful push'
    $history = Invoke-CodeTroveAdminSql "SELECT CONCAT(sequence_number, ':', commit_id) FROM codetrove_merge_request_commit WHERE merge_request_id=(SELECT id FROM codetrove_merge_request WHERE repository_id=$repositoryId AND iid=1) ORDER BY sequence_number;"
    $historyLines = @($history -split "`r?`n")
    Assert-True ($historyLines.Count -eq 2) 'Expected initial and updated MR head history records'
    Assert-True ($historyLines[0] -eq "1:$initialHead" -and $historyLines[1] -eq "2:$latestHead") 'MR head history sequence is incorrect'

    Write-Output '[4/7] Satisfy the M2 blocking Check prerequisite, then verify two-parent Merge'
    $assayRun = Invoke-CodeTroveAdminSql "SELECT r.id FROM codetrove_check_run r JOIN codetrove_check_suite s ON s.id=r.check_suite_id WHERE s.merge_request_id=(SELECT id FROM codetrove_merge_request WHERE repository_id=$repositoryId AND iid=1) AND s.head_commit='$latestHead' AND s.is_current=TRUE AND r.name='assay.integration';"
    if (-not [string]::IsNullOrWhiteSpace($assayRun)) {
        Invoke-CodeTroveAdminSql "UPDATE codetrove_check_run SET status='SUCCESS', conclusion='M1_ISOLATED_VERIFICATION', finished_at=CURRENT_TIMESTAMP(6), updated_at=CURRENT_TIMESTAMP(6) WHERE id=$assayRun; UPDATE codetrove_check_suite SET status='SUCCESS', version=version+1, updated_at=CURRENT_TIMESTAMP(6) WHERE id=(SELECT check_suite_id FROM codetrove_check_run WHERE id=$assayRun);" | Out-Null
    }
    $key = "m16-merge-$runId"
    $merge = Invoke-CodeTroveJson 'POST' "/api/v1/repositories/$repositoryId/merge-requests/1/merge" @{
        expectedHeadCommit = $latestHead; strategy = 'MERGE_COMMIT'
    } $authorization @{ 'Idempotency-Key' = $key }
    Assert-True ($merge.data.status -eq 'MERGED') 'MR status is not MERGED'
    Assert-True (-not $merge.data.idempotentReplay) 'First merge was incorrectly marked as a replay'
    $mergeCommit = [string]$merge.data.mergeCommit
    Assert-True ($mergeCommit -match '^[0-9a-f]{40}$') 'Merge commit is not a full object id'
    Invoke-Git $clone @('fetch', 'origin', 'main') | Out-Null
    $remoteMain = (Invoke-Git $clone @('rev-parse', 'origin/main')).Trim()
    Assert-True ($remoteMain -eq $mergeCommit) 'Remote main does not point to the merge commit'
    $parents = ((Invoke-Git $clone @('rev-list', '--parents', '-n', '1', 'origin/main')).Trim() -split '\s+')
    Assert-True ($parents.Count -eq 3) 'Merge commit does not have exactly two parents'
    Assert-True ($parents[1] -eq $targetBefore -and $parents[2] -eq $latestHead) 'Merge commit parents do not match target-before and source-head'

    Write-Output '[5/7] Replay the same idempotency key and verify database terminal state'
    $replay = Invoke-CodeTroveJson 'POST' "/api/v1/repositories/$repositoryId/merge-requests/1/merge" @{
        expectedHeadCommit = $latestHead; strategy = 'MERGE_COMMIT'
    } $authorization @{ 'Idempotency-Key' = $key }
    Assert-True ($replay.data.idempotentReplay -and $replay.data.mergeCommit -eq $mergeCommit) 'Idempotent replay did not return the original result'
    $terminalCount = Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_merge_request m JOIN codetrove_merge_operation o ON o.merge_request_id=m.id WHERE m.repository_id=$repositoryId AND m.iid=1 AND m.status='MERGED' AND RTRIM(m.merge_commit)='$mergeCommit' AND o.status='SUCCEEDED' AND RTRIM(o.merge_commit)='$mergeCommit';"
    Assert-True ($terminalCount -eq '1') 'MR and merge operation terminal states are inconsistent'

    Write-Output '[6/7] Verify source push history and Git/database consistency'
    Assert-True ((Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_merge_request_commit WHERE merge_request_id=(SELECT id FROM codetrove_merge_request WHERE repository_id=$repositoryId AND iid=1);") -eq '2') 'Unexpected MR head history count'
    Assert-True ((Invoke-CodeTroveAdminSql "SELECT COUNT(*) FROM codetrove_merge_operation WHERE repository_id=$repositoryId AND status='SUCCEEDED';") -eq '1') 'Expected one successful merge operation'

    Write-Output '[7/7] Real M1.6 merge verification passed'
} finally {
    Remove-Item Env:GIT_ASKPASS -ErrorAction SilentlyContinue
    Remove-Item Env:GIT_TERMINAL_PROMPT -ErrorAction SilentlyContinue
    Remove-Item Env:CODETROVE_M16_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $askPass -Force -ErrorAction SilentlyContinue
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
        $deadline = (Get-Date).AddMinutes(2)
        $health = $null
        do {
            try {
                $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health/readiness" -TimeoutSec 3
                if ($health.status -eq 'UP') { break }
            } catch {}
            Start-Sleep -Seconds 2
        } while ((Get-Date) -lt $deadline)
        if ($null -eq $health -or $health.status -ne 'UP') {
            throw 'Backend did not recover after M1.6 verification cleanup'
        }
    }
}
