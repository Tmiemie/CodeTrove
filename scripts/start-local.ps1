param(
    [switch]$SkipFrontend,
    [switch]$SkipBackend
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

function Get-RequiredUserVariable([string]$name) {
    $value = [Environment]::GetEnvironmentVariable($name, 'User')
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "Missing Windows user environment variable: $name"
    }
    return $value
}

function Test-PortListening([int]$port) {
    return $null -ne (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
}

$env:CODETROVE_REDIS_PASSWORD = Get-RequiredUserVariable 'CODETROVE_REDIS_PASSWORD'
$env:CODETROVE_REDIS_PORT = [Environment]::GetEnvironmentVariable('CODETROVE_REDIS_PORT', 'User')
if ([string]::IsNullOrWhiteSpace($env:CODETROVE_REDIS_PORT)) { $env:CODETROVE_REDIS_PORT = '6379' }
$env:CODETROVE_KAFKA_PORT = [Environment]::GetEnvironmentVariable('CODETROVE_KAFKA_PORT', 'User')
if ([string]::IsNullOrWhiteSpace($env:CODETROVE_KAFKA_PORT)) { $env:CODETROVE_KAFKA_PORT = '9092' }

docker compose -f (Join-Path $root 'deploy\compose.yml') up -d redis kafka
if ($LASTEXITCODE -ne 0) { throw 'Redis or Kafka startup failed' }

if (-not $SkipBackend) {
    foreach ($name in @(
        'CODETROVE_DB_URL',
        'CODETROVE_DB_USERNAME',
        'CODETROVE_DB_PASSWORD',
        'CODETROVE_REDIS_HOST',
        'CODETROVE_JWT_SECRET'
    )) {
        $value = Get-RequiredUserVariable $name
        Set-Item -Path "Env:$name" -Value $value
    }
    $env:CODETROVE_KAFKA_BOOTSTRAP_SERVERS = "127.0.0.1:$($env:CODETROVE_KAFKA_PORT)"

    if (Test-PortListening 8080) {
        try {
            $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/actuator/health/liveness' -TimeoutSec 3
            if ($health.status -ne 'UP') { throw 'Unexpected health status' }
            Write-Output 'Backend is already running on port 8080.'
        } catch {
            throw 'Port 8080 is occupied by a service that is not a healthy CodeTrove backend.'
        }
    } else {
        $jar = Join-Path $root 'backend\codetrove-bootstrap\target\codetrove-bootstrap-0.1.0-SNAPSHOT.jar'
        if (-not (Test-Path -LiteralPath $jar)) {
            Push-Location (Join-Path $root 'backend')
            try {
                .\mvnw.cmd clean package -DskipTests
                if ($LASTEXITCODE -ne 0) { throw 'Backend package failed' }
            } finally { Pop-Location }
        }
        Start-Process -FilePath 'java' -ArgumentList '-jar', $jar -WorkingDirectory $root
    }
}

if (-not $SkipFrontend) {
    if (Test-PortListening 28741) {
        Write-Output 'Frontend is already running on port 28741.'
    } else {
        Start-Process -FilePath 'npm.cmd' -ArgumentList '--prefix', (Join-Path $root 'frontend'), 'run', 'dev', '--', '--host', '127.0.0.1', '--port', '28741' -WorkingDirectory $root
    }
}

Write-Output 'CodeTrove services are starting.'
Write-Output 'Backend: http://127.0.0.1:8080'
Write-Output 'Frontend: http://127.0.0.1:28741'
