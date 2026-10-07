$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

Write-Output '[1/6] Verify backend tests and static checks'
Push-Location (Join-Path $root 'backend')
try {
    .\mvnw.cmd --batch-mode --no-transfer-progress verify
    if ($LASTEXITCODE -ne 0) { throw 'Backend verification failed' }
} finally { Pop-Location }

Write-Output '[2/6] Verify frontend formatting, types and build'
Push-Location (Join-Path $root 'frontend')
try {
    npm run format:check
    if ($LASTEXITCODE -ne 0) { throw 'Frontend formatting failed' }
    npm run typecheck
    if ($LASTEXITCODE -ne 0) { throw 'Frontend typecheck failed' }
    npm run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' }
} finally { Pop-Location }

Write-Output '[3/6] Validate Docker Compose'
$env:CODETROVE_REDIS_PASSWORD = [Environment]::GetEnvironmentVariable('CODETROVE_REDIS_PASSWORD', 'User')
$env:CODETROVE_REDIS_PORT = [Environment]::GetEnvironmentVariable('CODETROVE_REDIS_PORT', 'User')
docker compose -f (Join-Path $root 'deploy\compose.yml') config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Docker Compose validation failed' }

Write-Output '[4/6] Check Redis health'
$redisHealth = docker inspect --format '{{.State.Health.Status}}' codetrove-redis 2>$null
if ($redisHealth -ne 'healthy') { throw "Redis is not healthy: $redisHealth" }

Write-Output '[5/6] Check backend liveness and readiness'
$liveness = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/actuator/health/liveness' -TimeoutSec 10
$readiness = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/actuator/health/readiness' -TimeoutSec 10
if ($liveness.status -ne 'UP' -or $readiness.status -ne 'UP') { throw 'Backend health check failed' }

Write-Output '[6/6] Check API response and Trace ID'
$response = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:8080/api/v1/platform/status' -Headers @{ 'X-Trace-Id' = 'm0-verification' } -TimeoutSec 10
$payload = $response.Content | ConvertFrom-Json
if ($response.StatusCode -ne 200 -or $response.Headers['X-Trace-Id'] -ne 'm0-verification' -or $payload.meta.traceId -ne 'm0-verification') {
    throw 'API contract verification failed'
}

Write-Output 'M0 local verification passed.'
