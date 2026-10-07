param(
    [string]$BaseUrl = 'http://127.0.0.1:8080'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$runId = (Get-Date -Format 'yyyyMMddHHmmss') + (Get-Random -Minimum 1000 -Maximum 9999)
$owner = "m13owner$runId"
$reporter = "m13reporter$runId"
$slug = "git-smoke-$runId"
$passwordBytes = New-Object byte[] 24
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($passwordBytes)
$password = [Convert]::ToBase64String($passwordBytes)
$workRoot = Join-Path $env:TEMP "codetrove-m13-$runId"
$askPass = Join-Path $env:TEMP "codetrove-askpass-$runId.cmd"
$ownerId = $null
$reporterId = $null
$repositoryId = $null
$storagePath = $null
$backendStoppedForCleanup = $false

function Invoke-CodeTroveJson([string]$method, [string]$path, [object]$body, [string]$authorization) {
    $headers = @{}
    if (-not [string]::IsNullOrWhiteSpace($authorization)) {
        $headers.Authorization = $authorization
    }
    $params = @{
        Method = $method
        Uri = "$BaseUrl$path"
        Headers = $headers
        ContentType = 'application/json; charset=utf-8'
        TimeoutSec = 15
    }
    if ($null -ne $body) {
        $params.Body = $body | ConvertTo-Json -Compress
    }
    return Invoke-RestMethod @params
}

function Invoke-Git([string]$workingDirectory, [string[]]$arguments, [bool]$expectSuccess = $true) {
    $stdout = Join-Path $env:TEMP "codetrove-git-out-$runId-$([Guid]::NewGuid().ToString('N')).txt"
    $stderr = Join-Path $env:TEMP "codetrove-git-err-$runId-$([Guid]::NewGuid().ToString('N')).txt"
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
        if (Test-Path -LiteralPath $stdout) {
            $output += [IO.File]::ReadAllText($stdout, [Text.Encoding]::UTF8)
        }
        if (Test-Path -LiteralPath $stderr) {
            $output += [IO.File]::ReadAllText($stderr, [Text.Encoding]::UTF8)
        }
        if ($expectSuccess -and $exitCode -ne 0) {
            throw "Git command failed with exit code $exitCode. Output: $output"
        }
        return [PSCustomObject]@{ ExitCode = $exitCode; Output = $output }
    } finally {
        Remove-Item -LiteralPath $stdout, $stderr -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-CodeTroveAdminSql([string]$sql) {
    $stdout = Join-Path $env:TEMP "codetrove-mysql-out-$runId.txt"
    $stderr = Join-Path $env:TEMP "codetrove-mysql-err-$runId.txt"
    try {
        & mysql --login-path=codetrove-admin codetrove --batch --skip-column-names --execute $sql 1> $stdout 2> $stderr
        if ($LASTEXITCODE -ne 0) {
            $errorText = if (Test-Path -LiteralPath $stderr) {
                [IO.File]::ReadAllText($stderr, [Text.Encoding]::UTF8)
            } else { 'unknown MySQL CLI error' }
            throw "MySQL cleanup command failed: $errorText"
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
    if ([string]::IsNullOrWhiteSpace($path) -or -not (Test-Path -LiteralPath $path)) {
        return
    }
    Get-ChildItem -LiteralPath $path -Recurse -Force -ErrorAction SilentlyContinue |
        ForEach-Object { $_.Attributes = 'Normal' }
    Remove-Item -LiteralPath $path -Recurse -Force
}

try {
    Write-Output '[1/7] Check real backend readiness and MySQL admin login path'
    $readiness = Invoke-RestMethod -Uri "$BaseUrl/actuator/health/readiness" -TimeoutSec 10
    if ($readiness.status -ne 'UP') { throw 'Backend readiness is not UP' }
    if ((Invoke-CodeTroveAdminSql 'SELECT 1;') -ne '1') { throw 'MySQL admin login path check failed' }

    [IO.File]::WriteAllText(
        $askPass,
        "@echo off`r`necho %CODETROVE_GIT_TEST_PASSWORD%`r`n",
        [Text.Encoding]::ASCII
    )
    $env:GIT_ASKPASS = $askPass
    $env:GIT_TERMINAL_PROMPT = '0'
    $env:CODETROVE_GIT_TEST_PASSWORD = $password
    New-Item -ItemType Directory -Path $workRoot | Out-Null

    Write-Output '[2/7] Create temporary users and private repository'
    $ownerRegistration = Invoke-CodeTroveJson 'POST' '/api/v1/auth/register' @{
        username = $owner
        password = $password
        displayName = 'M1.3 Owner'
    } $null
    $ownerId = [long]$ownerRegistration.data.id
    $reporterRegistration = Invoke-CodeTroveJson 'POST' '/api/v1/auth/register' @{
        username = $reporter
        password = $password
        displayName = 'M1.3 Reporter'
    } $null
    $reporterId = [long]$reporterRegistration.data.id
    $login = Invoke-CodeTroveJson 'POST' '/api/v1/auth/login' @{
        username = $owner
        password = $password
    } $null
    $authorization = "Bearer $($login.data.accessToken)"
    $created = Invoke-CodeTroveJson 'POST' '/api/v1/repositories' @{
        name = 'M1.3 Git Smoke'
        slug = $slug
        description = 'Temporary real environment Git Smart HTTP verification'
        visibility = 'PRIVATE'
        initializeWithReadme = $true
    } $authorization
    $repositoryId = [long]$created.data.id
    Invoke-CodeTroveAdminSql "INSERT INTO codetrove_repository_member (repository_id,user_id,role,created_at,updated_at) VALUES ($repositoryId,$reporterId,'REPORTER',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6));" | Out-Null
    $storagePath = Invoke-CodeTroveAdminSql "SELECT storage_path FROM codetrove_repository WHERE id=$repositoryId;"

    Write-Output '[3/7] Clone private repository with the owner account'
    $ownerUrl = "$BaseUrl/git/$owner/$slug.git" -replace '^http://', "http://$owner@"
    $ownerClone = Join-Path $workRoot 'owner'
    Invoke-Git $workRoot @('clone', $ownerUrl, $ownerClone) | Out-Null
    if (-not (Test-Path -LiteralPath (Join-Path $ownerClone 'README.md') -PathType Leaf)) {
        throw 'README.md was not cloned'
    }

    Write-Output '[4/7] Push and fetch an ordinary feature branch'
    Invoke-Git $ownerClone @('config', 'user.name', 'CodeTrove M1.3') | Out-Null
    Invoke-Git $ownerClone @('config', 'user.email', 'm13@codetrove.local') | Out-Null
    Invoke-Git $ownerClone @('switch', '-c', 'feature/m13-smoke') | Out-Null
    [IO.File]::WriteAllText((Join-Path $ownerClone 'm13-smoke.txt'), "real environment`r`n", [Text.Encoding]::UTF8)
    Invoke-Git $ownerClone @('add', 'm13-smoke.txt') | Out-Null
    Invoke-Git $ownerClone @('commit', '-m', 'test: real M1.3 Git smoke') | Out-Null
    Invoke-Git $ownerClone @('push', 'origin', 'HEAD:refs/heads/feature/m13-smoke') | Out-Null
    $fetchClone = Join-Path $workRoot 'fetch'
    Invoke-Git $workRoot @('clone', $ownerUrl, $fetchClone) | Out-Null
    Invoke-Git $fetchClone @('fetch', 'origin', 'feature/m13-smoke') | Out-Null
    $fetched = Invoke-Git $fetchClone @('rev-parse', 'refs/remotes/origin/feature/m13-smoke')
    if ($fetched.Output.Trim() -notmatch '^[0-9a-f]{40}$') { throw 'Fetched feature ref is invalid' }

    Write-Output '[5/7] Verify protected main rejects direct push'
    $protected = Invoke-Git $ownerClone @('push', 'origin', 'HEAD:refs/heads/main') $false
    if ($protected.ExitCode -eq 0 -or $protected.Output -notlike '*protected branch requires merge request*') {
        throw 'Protected main push was not rejected with the expected reason'
    }

    Write-Output '[6/7] Verify Reporter can clone but cannot push'
    $reporterUrl = "$BaseUrl/git/$owner/$slug.git" -replace '^http://', "http://$reporter@"
    $reporterClone = Join-Path $workRoot 'reporter'
    Invoke-Git $workRoot @('clone', $reporterUrl, $reporterClone) | Out-Null
    Invoke-Git $reporterClone @('config', 'user.name', 'CodeTrove Reporter') | Out-Null
    Invoke-Git $reporterClone @('config', 'user.email', 'reporter@codetrove.local') | Out-Null
    Invoke-Git $reporterClone @('switch', '-c', 'feature/reporter-denied') | Out-Null
    [IO.File]::WriteAllText((Join-Path $reporterClone 'reporter.txt'), "must not persist`r`n", [Text.Encoding]::UTF8)
    Invoke-Git $reporterClone @('add', 'reporter.txt') | Out-Null
    Invoke-Git $reporterClone @('commit', '-m', 'test: reporter denied') | Out-Null
    $denied = Invoke-Git $reporterClone @('push', 'origin', 'HEAD:refs/heads/feature/reporter-denied') $false
    if ($denied.ExitCode -eq 0) { throw 'Reporter push unexpectedly succeeded' }
    $missing = Invoke-Git $ownerClone @('ls-remote', 'origin', 'refs/heads/feature/reporter-denied')
    if (-not [string]::IsNullOrWhiteSpace($missing.Output)) { throw 'Reporter branch exists remotely' }

    Write-Output '[7/7] Real Git Smart HTTP verification passed'
} finally {
    Remove-Item Env:GIT_ASKPASS -ErrorAction SilentlyContinue
    Remove-Item Env:GIT_TERMINAL_PROMPT -ErrorAction SilentlyContinue
    Remove-Item Env:CODETROVE_GIT_TEST_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $askPass -Force -ErrorAction SilentlyContinue
    Remove-Tree $workRoot

    if ($null -ne $repositoryId -or $null -ne $ownerId -or $null -ne $reporterId) {
        Stop-CodeTroveBackend
        $backendStoppedForCleanup = $true
        if ($null -ne $repositoryId) {
            Invoke-CodeTroveAdminSql "DELETE FROM codetrove_repository_member WHERE repository_id=$repositoryId; DELETE FROM codetrove_repository WHERE id=$repositoryId;" | Out-Null
        }
        $userIds = @($ownerId, $reporterId) | Where-Object { $null -ne $_ }
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
            $insideControlledRoot = $safeStorage.StartsWith(
                $safeRoot,
                [StringComparison]::OrdinalIgnoreCase
            )
            $expectedRepositoryName = [IO.Path]::GetFileName($safeStorage) -eq "$slug.git"
            if (-not $insideControlledRoot -or -not $expectedRepositoryName) {
                throw 'Refusing to delete a repository path outside the controlled root'
            }
            Remove-Tree $safeStorage
            $ownerDirectory = Split-Path -Parent $safeStorage
            $ownerDirectoryName = [IO.Path]::GetFileName($ownerDirectory)
            $ownerChildren = @(Get-ChildItem -LiteralPath $ownerDirectory -Force -ErrorAction SilentlyContinue)
            if ($ownerDirectoryName -eq $owner -and $ownerChildren.Count -eq 0) {
                Remove-Item -LiteralPath $ownerDirectory -Force
            }
        }
    }

    if ($backendStoppedForCleanup) {
        & (Join-Path $PSScriptRoot 'start-local.ps1') -SkipFrontend
        $deadline = (Get-Date).AddMinutes(2)
        do {
            try {
                $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health/readiness" -TimeoutSec 3
                if ($health.status -eq 'UP') { break }
            } catch {}
            Start-Sleep -Seconds 2
        } while ((Get-Date) -lt $deadline)
        if ($health.status -ne 'UP') { throw 'Backend did not recover after verification cleanup' }
    }
}
