param(
    [string]$BaseUrl = 'http://127.0.0.1:8080'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$runId = (Get-Date -Format 'yyyyMMddHHmmss') + (Get-Random -Minimum 1000 -Maximum 9999)
$owner = "m14owner$runId"
$outsider = "m14outsider$runId"
$slug = "browse-smoke-$runId"
$passwordBytes = New-Object byte[] 24
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($passwordBytes) } finally { $rng.Dispose() }
$password = [Convert]::ToBase64String($passwordBytes)
$workRoot = Join-Path $env:TEMP "codetrove-m14-$runId"
$askPass = Join-Path $env:TEMP "codetrove-m14-askpass-$runId.cmd"
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
    if ($null -ne $body) { $params.Body = $body | ConvertTo-Json -Compress }
    return Invoke-RestMethod @params
}

function Invoke-CodeTroveGet([string]$path, [string]$authorization) {
    return Invoke-RestMethod -Method Get -Uri "$BaseUrl$path" -Headers @{
        Authorization = $authorization
    } -TimeoutSec 20
}

function Invoke-Git([string]$workingDirectory, [string[]]$arguments) {
    $stdout = Join-Path $env:TEMP "codetrove-m14-git-out-$([Guid]::NewGuid().ToString('N')).txt"
    $stderr = Join-Path $env:TEMP "codetrove-m14-git-err-$([Guid]::NewGuid().ToString('N')).txt"
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
    $stdout = Join-Path $env:TEMP "codetrove-m14-mysql-out-$runId.txt"
    $stderr = Join-Path $env:TEMP "codetrove-m14-mysql-err-$runId.txt"
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

function Assert-HttpError([string]$path, [string]$authorization, [int]$status, [string]$code) {
    try {
        Invoke-CodeTroveGet $path $authorization | Out-Null
        throw "Expected HTTP $status for $path"
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

try {
    Write-Output '[1/8] Check real backend readiness and MySQL admin login path'
    $readiness = Invoke-RestMethod -Uri "$BaseUrl/actuator/health/readiness" -TimeoutSec 10
    Assert-True ($readiness.status -eq 'UP') 'Backend readiness is not UP'
    Assert-True ((Invoke-CodeTroveAdminSql 'SELECT 1;') -eq '1') 'MySQL admin login path check failed'

    [IO.File]::WriteAllText($askPass, "@echo off`r`necho %CODETROVE_M14_PASSWORD%`r`n", [Text.Encoding]::ASCII)
    $env:GIT_ASKPASS = $askPass
    $env:GIT_TERMINAL_PROMPT = '0'
    $env:CODETROVE_M14_PASSWORD = $password
    New-Item -ItemType Directory -Path $workRoot | Out-Null

    Write-Output '[2/8] Create temporary users and private repository'
    $ownerRegistration = Invoke-CodeTroveJson 'POST' '/api/v1/auth/register' @{
        username = $owner; password = $password; displayName = 'M1.4 Owner'
    } $null
    $ownerId = [long]$ownerRegistration.data.id
    $outsiderRegistration = Invoke-CodeTroveJson 'POST' '/api/v1/auth/register' @{
        username = $outsider; password = $password; displayName = 'M1.4 Outsider'
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
        name = 'M1.4 Browse Smoke'; slug = $slug
        description = 'Temporary real environment repository browse verification'
        visibility = 'PRIVATE'; initializeWithReadme = $true
    } $ownerAuthorization
    $repositoryId = [long]$created.data.id
    $storagePath = Invoke-CodeTroveAdminSql "SELECT storage_path FROM codetrove_repository WHERE id=$repositoryId;"

    Write-Output '[3/8] Push a real feature branch with nested, binary, and large files'
    $repositoryUrl = "$BaseUrl/git/$owner/$slug.git" -replace '^http://', "http://$owner@"
    $clone = Join-Path $workRoot 'clone'
    Invoke-Git $workRoot @('clone', $repositoryUrl, $clone) | Out-Null
    Invoke-Git $clone @('config', 'user.name', 'CodeTrove M1.4') | Out-Null
    Invoke-Git $clone @('config', 'user.email', 'm14@codetrove.local') | Out-Null
    Invoke-Git $clone @('switch', '-c', 'feature/m14-browse') | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $clone 'src\util') | Out-Null
    [IO.File]::WriteAllText((Join-Path $clone 'src\App.java'), "class App {}`r`n", (New-Object Text.UTF8Encoding $false))
    [IO.File]::WriteAllText((Join-Path $clone 'src\util\Helper.java'), "class Helper {}`r`n", (New-Object Text.UTF8Encoding $false))
    [IO.File]::WriteAllBytes((Join-Path $clone 'binary.dat'), [byte[]](1,2,0,3))
    [IO.File]::WriteAllText((Join-Path $clone 'large.txt'), ('x' * 1048577), (New-Object Text.UTF8Encoding $false))
    Invoke-Git $clone @('add', '.') | Out-Null
    Invoke-Git $clone @('commit', '-m', 'test: add M1.4 browse fixture') | Out-Null
    Invoke-Git $clone @('push', 'origin', 'HEAD:refs/heads/feature/m14-browse') | Out-Null
    $commitId = (Invoke-Git $clone @('rev-parse', 'HEAD')).Trim()

    Write-Output '[4/8] Verify branch metadata and commit identity'
    $branches = Invoke-CodeTroveGet "/api/v1/repositories/$repositoryId/branches" $ownerAuthorization
    Assert-True ($branches.data.Count -eq 2) 'Expected main and feature branches'
    $feature = $branches.data | Where-Object { $_.name -eq 'feature/m14-browse' }
    Assert-True ($null -ne $feature) 'Feature branch missing from branch list'
    Assert-True ($feature.commitId -eq $commitId) 'Feature branch commit does not match git rev-parse'
    Assert-True ($feature.default -eq $false) 'Feature branch must not be marked default'

    Write-Output '[5/8] Verify root pagination and nested tree browsing'
    $treePage1 = Invoke-CodeTroveGet "/api/v1/repositories/$repositoryId/tree?ref=feature%2Fm14-browse&limit=2" $ownerAuthorization
    Assert-True ($treePage1.data.entries.Count -eq 2) 'First tree page must contain two entries'
    Assert-True (-not [string]::IsNullOrWhiteSpace($treePage1.data.nextCursor)) 'First tree page must have a cursor'
    $cursor = [Uri]::EscapeDataString([string]$treePage1.data.nextCursor)
    $treePage2 = Invoke-CodeTroveGet "/api/v1/repositories/$repositoryId/tree?ref=feature%2Fm14-browse&limit=2&cursor=$cursor" $ownerAuthorization
    Assert-True ($treePage2.data.entries.Count -ge 2) 'Second tree page must contain remaining entries'
    $nested = Invoke-CodeTroveGet "/api/v1/repositories/$repositoryId/tree?ref=$commitId&path=src" $ownerAuthorization
    Assert-True ($nested.data.commitId -eq $commitId) 'Tree commit ID mismatch'
    Assert-True (@($nested.data.entries | Where-Object { $_.path -eq 'src/App.java' }).Count -eq 1) 'Nested text file missing'
    Assert-True (@($nested.data.entries | Where-Object { $_.path -eq 'src/util' -and $_.type -eq 'TREE' }).Count -eq 1) 'Nested directory missing'

    Write-Output '[6/8] Verify text, binary, and oversized blob behavior'
    $textBlob = Invoke-CodeTroveGet "/api/v1/repositories/$repositoryId/blob?ref=feature%2Fm14-browse&path=src%2FApp.java" $ownerAuthorization
    Assert-True ($textBlob.data.contentIncluded -eq $true) 'Small UTF-8 text must be included'
    Assert-True ($textBlob.data.encoding -eq 'UTF-8') 'Text encoding must be UTF-8'
    Assert-True ($textBlob.data.content -like 'class App*') 'Unexpected text content'
    $binaryBlob = Invoke-CodeTroveGet "/api/v1/repositories/$repositoryId/blob?ref=feature%2Fm14-browse&path=binary.dat" $ownerAuthorization
    Assert-True ($binaryBlob.data.binary -eq $true -and $binaryBlob.data.notIncludedReason -eq 'BINARY') 'Binary classification failed'
    $largeBlob = Invoke-CodeTroveGet "/api/v1/repositories/$repositoryId/blob?ref=feature%2Fm14-browse&path=large.txt" $ownerAuthorization
    Assert-True ($largeBlob.data.binary -eq $false -and $largeBlob.data.notIncludedReason -eq 'TOO_LARGE') 'Large text classification failed'

    Write-Output '[7/8] Verify ref/path rejection and private isolation'
    Assert-HttpError "/api/v1/repositories/$repositoryId/tree?ref=HEAD~1" $ownerAuthorization 400 'VALIDATION_FAILED'
    Assert-HttpError "/api/v1/repositories/$repositoryId/tree?ref=feature%2Fm14-browse&path=..%2FREADME.md" $ownerAuthorization 400 'VALIDATION_FAILED'
    Assert-HttpError "/api/v1/repositories/$repositoryId/branches" $outsiderAuthorization 404 'REPOSITORY_NOT_FOUND'

    Write-Output '[8/8] Real repository browse verification passed'
} finally {
    Remove-Item Env:GIT_ASKPASS -ErrorAction SilentlyContinue
    Remove-Item Env:GIT_TERMINAL_PROMPT -ErrorAction SilentlyContinue
    Remove-Item Env:CODETROVE_M14_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $askPass -Force -ErrorAction SilentlyContinue
    Remove-Tree $workRoot

    if ($null -ne $repositoryId -or $null -ne $ownerId -or $null -ne $outsiderId) {
        Stop-CodeTroveBackend
        $backendStoppedForCleanup = $true
        if ($null -ne $repositoryId) {
            Invoke-CodeTroveAdminSql "DELETE FROM codetrove_repository_member WHERE repository_id=$repositoryId; DELETE FROM codetrove_repository WHERE id=$repositoryId;" | Out-Null
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
            throw 'Backend did not recover after M1.4 verification cleanup'
        }
    }
}
