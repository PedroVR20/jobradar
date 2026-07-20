# Health-check unificado — Job Radar + Agenda Pessoal.
# Checa containers Docker (status/health) e endpoints HTTP dos dois projetos
# de uma vez, pra não precisar caçar qual dos dois caiu (ex: conflito de porta,
# postgres derrubado ao subir o outro projeto).
#
# Uso: .\scripts\health-check.ps1

$ErrorActionPreference = 'SilentlyContinue'

function Get-ContainerStatus($name) {
    $raw = docker inspect --format '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}n/a{{end}}' $name 2>$null
    if (-not $raw) { return $null }
    $parts = $raw -split '\|'
    return [PSCustomObject]@{ Status = $parts[0]; Health = $parts[1] }
}

function Test-HttpOk($url, $timeoutSec = 3) {
    if (-not $url) { return $true }
    try {
        $res = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec $timeoutSec -Method Get
        return $res.StatusCode -ge 200 -and $res.StatusCode -lt 500
    } catch {
        return $false
    }
}

function Write-Row($label, $container, $port, $httpUrl) {
    $c = Get-ContainerStatus $container
    if (-not $c) {
        Write-Host ("  [--] {0,-10} {1,-22} container não encontrado (está rodando?)" -f $label, $container) -ForegroundColor DarkGray
        return
    }

    $running = $c.Status -eq 'running'
    $healthOk = ($c.Health -eq 'n/a') -or ($c.Health -eq 'healthy')
    $httpOk = Test-HttpOk $httpUrl

    if ($running -and $healthOk -and $httpOk) {
        Write-Host ("  [OK] {0,-10} {1,-22} :{2,-6} {3}" -f $label, $container, $port, $c.Health) -ForegroundColor Green
    } elseif ($running) {
        Write-Host ("  [!!] {0,-10} {1,-22} :{2,-6} status={3} health={4} http={5}" -f $label, $container, $port, $c.Status, $c.Health, $httpOk) -ForegroundColor Yellow
    } else {
        Write-Host ("  [XX] {0,-10} {1,-22} :{2,-6} status={3}" -f $label, $container, $port, $c.Status) -ForegroundColor Red
    }
}

Write-Host "`n=== Job Radar ===" -ForegroundColor Cyan
Write-Row "postgres" "job-radar-postgres-1" 5432 $null
Write-Row "backend"  "job-radar-backend-1"  8080 "http://localhost:8080/api/jobs/stats"
Write-Row "frontend" "job-radar-frontend-1" 3000 "http://localhost:3000"

Write-Host "`n=== Agenda Pessoal ===" -ForegroundColor Cyan
Write-Row "postgres" "agenda-postgres" 5433 $null
Write-Row "redis"    "agenda-redis"    6379 $null
Write-Row "backend"  "agenda-backend"  8081 "http://localhost:8081/actuator/health/readiness"
Write-Row "frontend" "agenda-frontend" 4200 "http://localhost:4200"

Write-Host ""
