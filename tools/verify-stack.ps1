<#
.SYNOPSIS
  Verifies a running RDSS Docker Compose stack: container health, HTTP endpoints, access control,
  published ports, non-root containers and (optionally) data persistence across a full restart.

.EXAMPLE
  .\tools\verify-stack.ps1                      # production-oriented stack (docker-compose.yml only)
  .\tools\verify-stack.ps1 -Dev                 # stack started with the dev override
  .\tools\verify-stack.ps1 -Dev -RestartTest    # also: down (keeping volumes), up, compare data

.NOTES
  Exit code 0 when every check passes, 1 otherwise. Requires Docker Desktop and a configured .env.
  -RestartTest stops and restarts the stack; it never removes volumes.
#>
[CmdletBinding()]
param(
    [switch]$Dev,
    [switch]$RestartTest,
    [int]$TimeoutSeconds = 300,
    [string]$BaseUrl
)

# Native commands report through exit codes; keep stderr from becoming terminating errors in PowerShell 5.1.
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$composeArgs = @('compose', '-f', 'docker-compose.yml')
if ($Dev) { $composeArgs += @('-f', 'docker-compose.dev.yml') }

if (-not $BaseUrl) {
    $port = '8080'
    if (Test-Path .env) {
        $line = Get-Content .env | Where-Object { $_ -match '^RDSS_HTTP_PORT=' } | Select-Object -First 1
        if ($line) { $port = ($line -split '=', 2)[1].Trim() }
    }
    $BaseUrl = "http://localhost:$port"
}

$script:failures = 0
function Pass([string]$msg) { Write-Host "  [PASS] $msg" -ForegroundColor Green }
function Fail([string]$msg) { Write-Host "  [FAIL] $msg" -ForegroundColor Red; $script:failures++ }
function Check([bool]$ok, [string]$msg) { if ($ok) { Pass $msg } else { Fail $msg } }

function Invoke-Compose([string[]]$extra) {
    $out = & docker @composeArgs @extra 2>&1
    return @{ Code = $LASTEXITCODE; Out = ($out | Out-String) }
}

function Get-HttpStatus([string]$url, [hashtable]$headers = @{}) {
    try {
        $r = Invoke-WebRequest -Uri $url -Headers $headers -UseBasicParsing -TimeoutSec 20
        return [int]$r.StatusCode
    } catch [System.Net.WebException] {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
        return 0
    }
}

function Wait-Healthy {
    $services = @('postgres', 'orthanc', 'backend', 'viewer', 'frontend')
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $states = @{}
        foreach ($s in $services) {
            $id = (& docker @composeArgs ps -q $s 2>$null | Select-Object -First 1)
            if (-not $id) { $states[$s] = 'missing'; continue }
            $h = (& docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $id).Trim()
            $states[$s] = $h
        }
        $pending = $states.GetEnumerator() | Where-Object { $_.Value -notin @('healthy', 'running') }
        if (-not $pending) { return $states }
        Start-Sleep -Seconds 5
    } while ((Get-Date) -lt $deadline)
    return $states
}

function Get-Snapshot {
    $db = @{}
    $sql = "SELECT (SELECT count(*) FROM patient) || ',' || (SELECT count(*) FROM app_user) || ',' || " +
           "(SELECT max(version) FROM flyway_schema_history WHERE success) || ',' || (SELECT count(*) FROM audit_log) || ',' || " +
           "(SELECT count(*) FROM imaging_study)"
    # SQL and Python are piped through stdin: Windows PowerShell 5.1 mangles quotes in native arguments.
    $raw = ($sql | & docker @composeArgs exec -T postgres sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB -tA' 2>&1 | Out-String).Trim()
    $parts = $raw -split ','
    $db.Patients = $parts[0]; $db.Users = $parts[1]; $db.Schema = $parts[2]; $db.Audit = [int]$parts[3]; $db.LinkedStudies = $parts[4]
    # Orthanc study count, queried inside the container with its own configured credentials.
    $py = @'
import base64, json, os, urllib.request
user, pw = next(iter(json.loads(os.environ["ORTHANC__REGISTERED_USERS"]).items()))
req = urllib.request.Request("http://127.0.0.1:8042/statistics")
req.add_header("Authorization", "Basic " + base64.b64encode((user + ":" + pw).encode()).decode())
print(json.load(urllib.request.urlopen(req))["CountStudies"])
'@
    $db.OrthancStudies = ($py | & docker @composeArgs exec -T orthanc python3 - 2>&1 | Out-String).Trim()
    return $db
}

Write-Host "RDSS stack verification ($(if ($Dev) { 'dev override' } else { 'production-oriented' })) against $BaseUrl" -ForegroundColor Cyan

Write-Host "`n1. Containers"
$states = Wait-Healthy
foreach ($kv in $states.GetEnumerator() | Sort-Object Name) {
    Check ($kv.Value -in @('healthy', 'running')) "$($kv.Name): $($kv.Value)"
}
foreach ($svc in @('backend', 'frontend')) {
    $uid = (& docker @composeArgs exec -T $svc id -u 2>$null | Out-String).Trim()
    Check ($uid -ne '' -and $uid -ne '0') "$svc runs as non-root (uid $uid)"
}

Write-Host "`n2. HTTP endpoints"
Check ((Get-HttpStatus "$BaseUrl/") -eq 200) 'Web UI served'
Check ((Get-HttpStatus "$BaseUrl/ohif/") -eq 200) 'OHIF viewer served under /ohif/'
try {
    $status = Invoke-RestMethod "$BaseUrl/api/v1/system/status" -TimeoutSec 20
    Check ($status.status -eq 'UP') "API status $($status.status)"
    foreach ($d in $status.dependencies) { Check ($d.status -eq 'UP') "$($d.name): $($d.status) ($($d.detail))" }
} catch { Fail "API status endpoint unreachable: $($_.Exception.Message)" }

Write-Host "`n3. Access control"
Check ((Get-HttpStatus "$BaseUrl/api/v1/patients") -eq 401) 'Anonymous patient list is refused (401)'
Check ((Get-HttpStatus "$BaseUrl/api/v1/reports/summary") -eq 401) 'Anonymous reports are refused (401)'
Check ((Get-HttpStatus "$BaseUrl/api/v1/dicomweb/studies") -in @(401, 403)) 'Anonymous DICOMweb is refused'
# nginx answers unknown paths with the SPA shell, so check the body, and probe the backend directly too.
$envBody = try { (Invoke-WebRequest "$BaseUrl/actuator/env" -UseBasicParsing -TimeoutSec 20).Content } catch { '' }
Check ($envBody -notmatch 'propertySources') 'Actuator env is not exposed through the web entry point'
$direct = (& docker @composeArgs exec -T backend sh -c 'wget -qO- http://127.0.0.1:8080/actuator/env >/dev/null 2>&1; echo $?' 2>&1 | Out-String).Trim()
Check ($direct -ne '0') 'Actuator env is not exposed by the backend itself'

Write-Host "`n4. Published ports"
foreach ($p in @(@('postgres', 5432), @('orthanc', 8042), @('orthanc', 4242), @('backend', 8080))) {
    $r = Invoke-Compose @('port', $p[0], "$($p[1])")
    # `compose port` prints ":0" (exit 0) for a port that is exposed but not published.
    $published = ($r.Code -eq 0 -and $r.Out -match ':[1-9]\d*\s*$')
    if ($Dev) {
        if ($published) { Check ($r.Out -match '127\.0\.0\.1') "$($p[0]):$($p[1]) published on loopback only ($($r.Out.Trim()))" }
        else { Pass "$($p[0]):$($p[1]) not published" }
    } else {
        Check (-not $published) "$($p[0]):$($p[1]) not published to the host"
    }
}

if ($RestartTest) {
    Write-Host "`n5. Persistence across a full restart (volumes kept)"
    $before = Get-Snapshot
    Write-Host "  before: patients=$($before.Patients) users=$($before.Users) schema=V$($before.Schema) audit=$($before.Audit) linkedStudies=$($before.LinkedStudies) orthancStudies=$($before.OrthancStudies)"
    $down = Invoke-Compose @('down')
    Check ($down.Code -eq 0) 'docker compose down (volumes kept)'
    $up = Invoke-Compose @('up', '-d')
    Check ($up.Code -eq 0) 'docker compose up -d'
    $states = Wait-Healthy
    Check (-not ($states.Values | Where-Object { $_ -notin @('healthy', 'running') })) 'all services healthy again'
    $after = Get-Snapshot
    Write-Host "  after:  patients=$($after.Patients) users=$($after.Users) schema=V$($after.Schema) audit=$($after.Audit) linkedStudies=$($after.LinkedStudies) orthancStudies=$($after.OrthancStudies)"
    Check ($after.Patients -eq $before.Patients) 'patients persisted'
    Check ($after.Users -eq $before.Users) 'user accounts persisted (seeding is idempotent)'
    Check ($after.Schema -eq $before.Schema) 'schema version unchanged'
    Check ($after.Audit -ge $before.Audit) 'audit log retained'
    Check ($after.LinkedStudies -eq $before.LinkedStudies) 'imaging study links persisted'
    Check ($after.OrthancStudies -eq $before.OrthancStudies) 'Orthanc DICOM storage persisted'
    Check ((Get-HttpStatus "$BaseUrl/") -eq 200) 'Web UI served after restart'
}

Write-Host ''
if ($script:failures -eq 0) {
    Write-Host 'All checks passed.' -ForegroundColor Green
    exit 0
}
Write-Host "$($script:failures) check(s) failed." -ForegroundColor Red
exit 1
