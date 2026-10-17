<#
.SYNOPSIS
  Clean-checkout deployment test: copies the source tree (without build output, dependencies or
  secrets) to a temporary folder, generates a fresh .env from .env.example, builds and starts the
  production-oriented stack under a separate Compose project and port, verifies it, then removes
  the containers, volumes and folder.

.EXAMPLE
  .\tools\clean-deploy-test.ps1               # uses port 18090 and project "rdss-clean"
  .\tools\clean-deploy-test.ps1 -Keep         # leave the stack running for inspection

.NOTES
  Does not touch the stack or volumes of the main checkout. Build time is several minutes on first run.
#>
[CmdletBinding()]
param(
    [int]$Port = 18090,
    [string]$Project = 'rdss-clean',
    [switch]$Keep
)

$ErrorActionPreference = 'Continue'
$source = Split-Path -Parent $PSScriptRoot
$target = Join-Path ([IO.Path]::GetTempPath()) "rdss-clean-$([guid]::NewGuid().ToString('N').Substring(0, 8))"

function New-Secret([int]$length = 40) {
    $chars = [char[]]'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
    $bytes = New-Object byte[] $length
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
}

Write-Host "Copying source tree to $target" -ForegroundColor Cyan
& robocopy $source $target /E /NFL /NDL /NJH /NJS /NP `
    /XD node_modules target dist .git playwright-report test-results out .vite .idea .vscode `
    /XF .env *.log | Out-Null
if ($LASTEXITCODE -ge 8) { Write-Host "robocopy failed ($LASTEXITCODE)" -ForegroundColor Red; exit 1 }
if (Test-Path (Join-Path $target '.env')) { Write-Host '.env must not be copied' -ForegroundColor Red; exit 1 }

$adminPassword = 'Adm-' + (New-Secret 20)
$values = @{
    POSTGRES_PASSWORD        = New-Secret
    JWT_SECRET               = New-Secret 48
    ORTHANC_PASSWORD         = New-Secret
    RDSS_HTTP_PORT           = "$Port"
    CORS_ALLOWED_ORIGINS     = "http://localhost:$Port"
    BOOTSTRAP_ADMIN_PASSWORD = $adminPassword
    DEMO_USER_PASSWORD       = New-Secret
    HL7_INBOUND_API_KEY      = New-Secret 48
}
$envLines = Get-Content (Join-Path $target '.env.example') | ForEach-Object {
    $line = $_
    foreach ($k in $values.Keys) { if ($line -match "^$k=") { $line = "$k=$($values[$k])" } }
    $line
}
$envLines += "COMPOSE_PROJECT_NAME=$Project"
Set-Content -Path (Join-Path $target '.env') -Value $envLines -Encoding ascii
if (Select-String -Path (Join-Path $target '.env') -Pattern '^[A-Z0-9_]+=.*CHANGE_ME' -Quiet) {
    Write-Host 'Unreplaced CHANGE_ME values remain in .env' -ForegroundColor Red
    Remove-Item -Recurse -Force $target -ErrorAction SilentlyContinue
    exit 1
}

# The base file declares `name: rdss`; the environment variable takes precedence over it. Because cleanup
# runs `down -v`, refuse to continue unless Compose resolves to the isolated project name.
$env:COMPOSE_PROJECT_NAME = $Project
function Assert-Project {
    $resolved = (& docker compose config --format json 2>$null | Out-String | ConvertFrom-Json).name
    if ($resolved -ne $Project) { throw "Compose resolved project '$resolved', expected '$Project'; aborting to protect other stacks" }
}

$failed = $false
$started = $false
Push-Location $target
try {
    Assert-Project
    Write-Host "Building and starting project '$Project' on 127.0.0.1:$Port (production compose file only)" -ForegroundColor Cyan
    $started = $true
    & docker compose up --build -d
    if ($LASTEXITCODE -ne 0) { throw 'docker compose up failed' }

    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $target 'tools\verify-stack.ps1') -BaseUrl "http://localhost:$Port"
    if ($LASTEXITCODE -ne 0) { $failed = $true }

    Write-Host "`nBootstrap administrator and empty production data" -ForegroundColor Cyan
    try {
        $login = Invoke-RestMethod -Method Post "http://localhost:$Port/api/v1/auth/login" -ContentType 'application/json' `
            -Body (@{ username = 'admin'; password = $adminPassword } | ConvertTo-Json)
        Write-Host '  [PASS] bootstrap admin can sign in' -ForegroundColor Green
        $h = @{ Authorization = "Bearer $($login.accessToken)" }
        $patients = Invoke-RestMethod "http://localhost:$Port/api/v1/patients" -Headers $h
        if ($patients.totalElements -eq 0) { Write-Host '  [PASS] no demo patients seeded (SEED_DEMO_DATA=false)' -ForegroundColor Green }
        else { Write-Host "  [FAIL] expected no patients, found $($patients.totalElements)" -ForegroundColor Red; $failed = $true }
        $summary = Invoke-RestMethod "http://localhost:$Port/api/v1/reports/summary" -Headers $h
        Write-Host "  [PASS] reports work on an empty database (scope $($summary.scope))" -ForegroundColor Green
    } catch {
        Write-Host "  [FAIL] $($_.Exception.Message)" -ForegroundColor Red; $failed = $true
    }
    try {
        Invoke-RestMethod -Method Post "http://localhost:$Port/api/v1/auth/login" -ContentType 'application/json' `
            -Body (@{ username = 'dr.hale'; password = $values.DEMO_USER_PASSWORD } | ConvertTo-Json) | Out-Null
        Write-Host '  [FAIL] demo account exists in production mode' -ForegroundColor Red; $failed = $true
    } catch { Write-Host '  [PASS] demo accounts absent' -ForegroundColor Green }
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    $failed = $true
} finally {
    if ($Keep) {
        Write-Host "`nStack left running in $target (project $Project). Remove with: docker compose -p $Project down -v" -ForegroundColor Yellow
    } elseif ($started) {
        try { Assert-Project; & docker compose down -v --remove-orphans 2>&1 | Out-Null }
        catch { Write-Host "Skipped cleanup: $($_.Exception.Message)" -ForegroundColor Red }
    }
    Pop-Location
    if (-not $Keep) {
        Remove-Item -Recurse -Force $target -ErrorAction SilentlyContinue
        Write-Host "`nCleaned up project '$Project' (containers, volumes, temporary folder)."
    }
}

if ($failed) { Write-Host 'Clean deployment test FAILED.' -ForegroundColor Red; exit 1 }
Write-Host 'Clean deployment test passed.' -ForegroundColor Green
exit 0
