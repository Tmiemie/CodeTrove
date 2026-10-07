param(
    [string]$BaseUrl = 'http://127.0.0.1:8080'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$runId = (Get-Date -Format 'yyyyMMddHHmmss') + (Get-Random -Minimum 1000 -Maximum 9999)
$owner = "m15owner$runId"
$outsider = "m15outsider$runId"
$slug = "mr-smoke-$runId"
$passwordBytes = New-Object byte[] 24
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($passwordBytes) } finally { $rng.Dispose() }
$password = [Convert]::ToBase64String($passwordBytes)
$workRoot = Join-Path $env:TEMP "codetrove-m15-$runId"
$askPass = Join-Path $env:TEMP "codetrove-m15-askpass-$runId.cmd"
$ownerId = $null
$outsiderId = $null
$repositoryId = $null
$storagePath = $null
$backendStoppedForCleanup = $false

function Invoke-CodeTroveJson([string]$method, [string]$path, [object]$body, [string]$authorization) {
    $headers = @{}
    if (-not [string]::IsNullOrWhiteSpace($authorization)) { $headers.Authorization = $authorization }
    $params = @{
        Method = $method
        Uri = "$BaseUrl$path"
        Headers = $headers
        ContentType = 'application/json; charset=utf-8'
        TimeoutSec = 20
    }
    if ($null -ne $body) { $params.Body = $body | ConvertTo-Json -Depth 6 -Compress }
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
    [int]$status,
    [string]$code
) {
    try {
        Invoke-CodeTroveJson $method $path $body $authorization | Out-Null
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
    $stdout = Join-Path $env:TEMP "codetrove-m15-git-out-$([Guid]::NewGuid().ToString('N')).txt"
    $stderr = Join-Path $env:TEMP "codetrove-m15-git-err-$([Guid]::NewGuid().ToString('N')).txt"
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
    $stdout = Join-Path $env:TEMP "codetrove-m15-mysql-out-$runId.txt"
    $stderr = Join-Path $env:TEMP "codetrove-m15-mysql-err-$runId.txt"
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
    Write-Output '[1/8] Check final JAR readiness and Flyway V4'
    $readiness = Invoke-RestMethod -Uri "$BaseUrl/actuator/health/readiness" -TimeoutSec 10
    Assert-True ($readiness.status -eq 'UP') 'Backend readiness is not UP'
    Assert-True ((Invoke-CodeTroveAdminSql 'SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1;') -eq '4') 'Flyway V4 is not active'

    [IO.File]::WriteAllText($askPass, "@echo off`r`necho %CODETROVE_M15_PASSWORD%`r`n", [Text.Encoding]::ASCII)
    $env:GIT_ASKPASS = $askPass
    $env:GIT_TERMINAL_PROMPT = '0'
    $env:CODETROVE_M15_PASSWORD = $password
    New-Item -ItemType Directory -Path $workRoot | Out-Null

    Write-Output '[2/8] Create temporary users and private repository'
    $ownerRegistration = Invoke-CodeTroveJson 'POST' '/api/v1/auth/register' @{
        username = $owner; password = $password; displayName = 'M1.5 Owner'
    } $null
    $ownerId = [long]$ownerRegistration.data.id
    $outsiderRegistration = Invoke-CodeTroveJson 'POST' '/api/v1/auth/register' @{
        username = $outsider; password = $password; displayName = 'M1.5 Outsider'
    } $null
    $outsiderId = [long]$outsiderRegistration.data.id
    $ownerLogin = Invoke-CodeTroveJson 'POST' '/api/v1/auth/login' @{
        username = $owner; password = $password
    } $null
    $outsiderLogin = Invoke-CodeTroveJson 'POST' '/api/v1/auth/login' @{
        username = $outsider; password = $password
    } $null
    $ownerAuthorization = "Bearer $($ownerLogin.data.accessToken)"
    $outsiderAuthorization = "Bearer $($outsiderLogin.data.accessToken)"
    $created = Invoke-CodeTroveJson 'POST' '/api/v1/repositories' @{
        name = 'M1.5 Merge Request Smoke'; slug = $slug
        description = 'Temporary real environment merge request verification'
        visibility = 'PRIVATE'; initializeWithReadme = $true
    } $ownerAuthorization
    $repositoryId = [long]$created.data.id
    $storagePath = Invoke-CodeTroveAdminSql "SELECT storage_path FROM codetrove_repository WHERE id=$repositoryId;"

    Write-Output '[3/8] Clone and push a real feature branch'
    $repositoryUrl = "$BaseUrl/git/$owner/$slug.git" -replace '^http://', "http://$owner@"
    $clone = Join-Path $workRoot 'clone'
    Invoke-Git $workRoot @('clone', $repositoryUrl, $clone) | Out-Null
    Invoke-Git $clone @('config', 'user.name', 'CodeTrove M1.5') | Out-Null
    Invoke-Git $clone @('config', 'user.email', 'm15@codetrove.local') | Out-Null
    Invoke-Git $clone @('switch', '-c', 'feature/m15-review') | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $clone 'src') | Out-Null
    [IO.File]::WriteAllText(
        (Join-Path $clone 'src\ReviewTarget.java'),
        "class ReviewTarget {`n    int value = 1;`n}`n",
        (New-Object Text.UTF8Encoding $false)
    )
    Invoke-Git $clone @('add', '.') | Out-Null
    Invoke-Git $clone @('commit', '-m', 'feat: add M1.5 review target') | Out-Null
    Invoke-Git $clone @('push', 'origin', 'HEAD:refs/heads/feature/m15-review') | Out-Null
    $headCommit = (Invoke-Git $clone @('rev-parse', 'HEAD')).Trim()
    $baseCommit = (Invoke-Git $clone @('rev-parse', 'origin/main')).Trim()

    Write-Output '[4/8] Create MR and verify persisted snapshot'
    $mr = Invoke-CodeTroveJson 'POST' "/api/v1/repositories/$repositoryId/merge-requests" @{
        title = 'Review M1.5 feature'; description = 'Real environment MR verification'
        sourceBranch = 'feature/m15-review'; targetBranch = 'main'
    } $ownerAuthorization
    Assert-True ($mr.data.iid -eq 1) 'Expected repository-local MR iid 1'
    Assert-True ($mr.data.baseCommit -eq $baseCommit) 'MR base commit does not match origin/main'
    Assert-True ($mr.data.headCommit -eq $headCommit) 'MR head commit does not match feature HEAD'
    Assert-True ($mr.data.status -eq 'OPEN' -and $mr.data.version -eq 0) 'MR initial state is invalid'
    Assert-CodeTroveError 'POST' "/api/v1/repositories/$repositoryId/merge-requests" @{
        title = 'Duplicate'; sourceBranch = 'feature/m15-review'; targetBranch = 'main'
    } $ownerAuthorization 409 'MR_ALREADY_OPEN'

    Write-Output '[5/8] Verify list, detail, and snapshot Diff'
    $list = Invoke-CodeTroveGet "/api/v1/repositories/$repositoryId/merge-requests?status=OPEN&limit=1" $ownerAuthorization
    Assert-True ($list.data.Count -eq 1 -and $list.data[0].iid -eq 1) 'MR list did not return the created MR'
    $detail = Invoke-CodeTroveGet "/api/v1/repositories/$repositoryId/merge-requests/1" $ownerAuthorization
    Assert-True ($detail.data.headCommit -eq $headCommit) 'MR detail head commit mismatch'
    $diff = Invoke-CodeTroveGet "/api/v1/repositories/$repositoryId/merge-requests/1/diff" $ownerAuthorization
    $targetFile = @($diff.data.files | Where-Object { $_.newPath -eq 'src/ReviewTarget.java' })
    Assert-True ($targetFile.Count -eq 1) 'Expected file missing from MR Diff'
    Assert-True ($targetFile[0].status -eq 'ADD' -and $targetFile[0].additions -eq 3) 'Unexpected Diff statistics'
    Assert-True ($targetFile[0].patch -like '*int value = 1*') 'Diff patch content missing expected line'

    Write-Output '[6/8] Verify general and line comments with position validation'
    $general = Invoke-CodeTroveJson 'POST' "/api/v1/repositories/$repositoryId/merge-requests/1/comments" @{
        body = 'Please add a boundary test.'
    } $ownerAuthorization
    Assert-True ($general.data.type -eq 'GENERAL') 'General comment type mismatch'
    $lineComment = Invoke-CodeTroveJson 'POST' "/api/v1/repositories/$repositoryId/merge-requests/1/comments" @{
        body = 'Please review this assignment.'
        position = @{
            commitId = $headCommit; filePath = 'src/ReviewTarget.java'; side = 'NEW'; line = 2
        }
    } $ownerAuthorization
    Assert-True ($lineComment.data.type -eq 'DIFF') 'Diff comment type mismatch'
    Assert-True ($lineComment.data.position.line -eq 2) 'Diff comment line mismatch'
    Assert-CodeTroveError 'POST' "/api/v1/repositories/$repositoryId/merge-requests/1/comments" @{
        body = 'Invalid line'
        position = @{
            commitId = $headCommit; filePath = 'src/ReviewTarget.java'; side = 'NEW'; line = 99
        }
    } $ownerAuthorization 400 'MR_DIFF_POSITION_INVALID'
    $commentsPage1 = Invoke-CodeTroveGet "/api/v1/repositories/$repositoryId/merge-requests/1/comments?limit=1" $ownerAuthorization
    Assert-True ($commentsPage1.data.Count -eq 1) 'First comments page size mismatch'
    Assert-True (-not [string]::IsNullOrWhiteSpace($commentsPage1.meta.nextCursor)) 'Comment cursor is missing'
    $commentCursor = [Uri]::EscapeDataString([string]$commentsPage1.meta.nextCursor)
    $commentsPage2 = Invoke-CodeTroveGet "/api/v1/repositories/$repositoryId/merge-requests/1/comments?limit=1&cursor=$commentCursor" $ownerAuthorization
    Assert-True ($commentsPage2.data.Count -eq 1 -and $commentsPage2.data[0].type -eq 'DIFF') 'Second comments page mismatch'

    Write-Output '[7/8] Verify isolation, optimistic lock, and closed-state behavior'
    Assert-CodeTroveError 'GET' "/api/v1/repositories/$repositoryId/merge-requests/1" $null $outsiderAuthorization 404 'REPOSITORY_NOT_FOUND'
    $updated = Invoke-CodeTroveJson 'PATCH' "/api/v1/repositories/$repositoryId/merge-requests/1" @{
        title = 'Reviewed M1.5 feature'; version = 0
    } $ownerAuthorization
    Assert-True ($updated.data.version -eq 1) 'MR version did not advance after update'
    Assert-CodeTroveError 'PATCH' "/api/v1/repositories/$repositoryId/merge-requests/1" @{
        title = 'Stale update'; version = 0
    } $ownerAuthorization 409 'STATE_CONFLICT'
    $closed = Invoke-CodeTroveJson 'PATCH' "/api/v1/repositories/$repositoryId/merge-requests/1" @{
        status = 'CLOSED'; version = 1
    } $ownerAuthorization
    Assert-True ($closed.data.status -eq 'CLOSED' -and $closed.data.version -eq 2) 'MR close failed'
    Assert-CodeTroveError 'POST' "/api/v1/repositories/$repositoryId/merge-requests/1/comments" @{
        body = 'Comment after close'
    } $ownerAuthorization 409 'MR_NOT_OPEN'

    Write-Output '[8/8] Real M1.5 merge request verification passed'
} finally {
    Remove-Item Env:GIT_ASKPASS -ErrorAction SilentlyContinue
    Remove-Item Env:GIT_TERMINAL_PROMPT -ErrorAction SilentlyContinue
    Remove-Item Env:CODETROVE_M15_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $askPass -Force -ErrorAction SilentlyContinue
    Remove-Tree $workRoot

    if ($null -ne $repositoryId -or $null -ne $ownerId -or $null -ne $outsiderId) {
        Stop-CodeTroveBackend
        $backendStoppedForCleanup = $true
        if ($null -ne $repositoryId) {
            Invoke-CodeTroveAdminSql "DELETE FROM codetrove_consumed_event WHERE event_id IN (SELECT event_id FROM codetrove_outbox_event WHERE aggregate_id IN (SELECT CAST(id AS CHAR) FROM codetrove_merge_request WHERE repository_id=$repositoryId)); DELETE FROM codetrove_outbox_event WHERE aggregate_id IN (SELECT CAST(id AS CHAR) FROM codetrove_merge_request WHERE repository_id=$repositoryId); DELETE FROM codetrove_consumed_event WHERE event_id IN (SELECT event_id FROM codetrove_outbox_event WHERE aggregate_type='REVIEW_TASK' AND aggregate_id IN (SELECT CAST(id AS CHAR) FROM codetrove_review_task WHERE repository_id=$repositoryId)); DELETE FROM codetrove_outbox_event WHERE aggregate_type='REVIEW_TASK' AND aggregate_id IN (SELECT CAST(id AS CHAR) FROM codetrove_review_task WHERE repository_id=$repositoryId); DELETE FROM codetrove_consumed_event WHERE event_id IN (SELECT event_id FROM codetrove_outbox_event WHERE aggregate_type='ASSAY_EXECUTION' AND aggregate_id IN (SELECT CAST(id AS CHAR) FROM codetrove_assay_execution WHERE repository_id=$repositoryId)); DELETE FROM codetrove_outbox_event WHERE aggregate_type='ASSAY_EXECUTION' AND aggregate_id IN (SELECT CAST(id AS CHAR) FROM codetrove_assay_execution WHERE repository_id=$repositoryId); DELETE FROM codetrove_assay_case_result WHERE execution_id IN (SELECT id FROM codetrove_assay_execution WHERE repository_id=$repositoryId); DELETE FROM codetrove_assay_execution WHERE repository_id=$repositoryId; DELETE FROM codetrove_review_finding WHERE review_task_id IN (SELECT id FROM codetrove_review_task WHERE repository_id=$repositoryId); DELETE FROM codetrove_review_task WHERE repository_id=$repositoryId; DELETE FROM codetrove_check_run WHERE check_suite_id IN (SELECT id FROM codetrove_check_suite WHERE merge_request_id IN (SELECT id FROM codetrove_merge_request WHERE repository_id=$repositoryId)); DELETE FROM codetrove_check_suite WHERE merge_request_id IN (SELECT id FROM codetrove_merge_request WHERE repository_id=$repositoryId); DELETE FROM codetrove_merge_request_comment WHERE merge_request_id IN (SELECT id FROM codetrove_merge_request WHERE repository_id=$repositoryId); DELETE FROM codetrove_merge_request WHERE repository_id=$repositoryId; DELETE FROM codetrove_repository_member WHERE repository_id=$repositoryId; DELETE FROM codetrove_repository WHERE id=$repositoryId;" | Out-Null
        }
        $userIds = @($ownerId, $outsiderId) | Where-Object { $null -ne $_ }
        if ($userIds.Count -gt 0) {
            Invoke-CodeTroveAdminSql "DELETE FROM codetrove_user WHERE id IN ($($userIds -join ','));" | Out-Null
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
            throw 'Backend did not recover after M1.5 verification cleanup'
        }
    }
}
