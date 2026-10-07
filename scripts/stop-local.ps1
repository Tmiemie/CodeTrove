$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

function Stop-CodeTroveProcessOnPort([int]$port, [string]$expectedCommandFragment) {
    $connections = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
    foreach ($connection in $connections) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($connection.OwningProcess)"
        $commandLine = [string]$process.CommandLine
        if ($commandLine -notlike "*$expectedCommandFragment*") {
            throw "Port $port is owned by an unrelated process. Stop it manually if intended."
        }
        Stop-Process -Id $connection.OwningProcess -Force
    }
}

Stop-CodeTroveProcessOnPort 8080 'codetrove-bootstrap'
Stop-CodeTroveProcessOnPort 28741 'frontend'

$env:CODETROVE_REDIS_PASSWORD = [Environment]::GetEnvironmentVariable('CODETROVE_REDIS_PASSWORD', 'User')
$env:CODETROVE_REDIS_PORT = [Environment]::GetEnvironmentVariable('CODETROVE_REDIS_PORT', 'User')
docker compose -f (Join-Path $root 'deploy\compose.yml') down

Write-Output 'CodeTrove local services stopped. Redis data volume was preserved.'
