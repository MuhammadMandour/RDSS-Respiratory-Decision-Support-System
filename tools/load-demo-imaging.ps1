<#
.SYNOPSIS
  Generates SYNTHETIC chest imaging (CT current + prior, chest radiograph) and uploads it to RDSS
  for a demo patient through the normal authorized API, then adds DEMONSTRATION findings.

.EXAMPLE
  # From the repository root, with the dev stack running:
  .\tools\load-demo-imaging.ps1
  .\tools\load-demo-imaging.ps1 -BaseUrl http://localhost:8080 -Username dr.hale -Mrn MRN100002
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Username = "dr.hale",
    [string]$Password = $env:DEMO_USER_PASSWORD,
    [string]$Mrn = "MRN100002"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root "tools/synthetic-dicom/out"

if (-not $Password) {
    $envFile = Join-Path $root ".env"
    if (Test-Path $envFile) {
        $line = Get-Content $envFile | Where-Object { $_ -match '^DEMO_USER_PASSWORD=' } | Select-Object -First 1
        if ($line) { $Password = $line.Substring($line.IndexOf('=') + 1) }
    }
}
if (-not $Password) { throw "Set -Password or DEMO_USER_PASSWORD (in the environment or .env)." }

Write-Host "1/4 Generating synthetic DICOM (Docker)..."
docker build -q -t rdss-synthetic-dicom (Join-Path $root "tools/synthetic-dicom") | Out-Null
if ($LASTEXITCODE -ne 0) { throw "docker build failed" }
New-Item -ItemType Directory -Force $out | Out-Null
docker run --rm --user 0 -v "${out}:/out" rdss-synthetic-dicom --out /out --patient-id $Mrn
if ($LASTEXITCODE -ne 0) { throw "generator failed" }

Write-Host "2/4 Signing in as $Username..."
$login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType "application/json" `
    -Body (@{ username = $Username; password = $Password } | ConvertTo-Json)
$headers = @{ Authorization = "Bearer $($login.accessToken)" }

$patients = Invoke-RestMethod -Uri "$BaseUrl/api/v1/patients?q=$Mrn" -Headers $headers
$patient = $patients.content | Where-Object { $_.mrn -eq $Mrn } | Select-Object -First 1
if (-not $patient) { throw "Patient $Mrn not found on $Username's care team." }

Write-Host "3/4 Uploading studies for $($patient.firstName) $($patient.lastName) ($Mrn)..."
$uploaded = @{}
foreach ($dir in @("ct-prior", "ct-current", "dx-chest")) {
    $files = Get-ChildItem (Join-Path $out $dir) -Filter *.dcm | Sort-Object Name
    $curlArgs = @("-s", "-f", "-H", "Authorization: Bearer $($login.accessToken)")
    foreach ($f in $files) { $curlArgs += @("-F", "files=@$($f.FullName);type=application/dicom") }
    $curlArgs += "$BaseUrl/api/v1/patients/$($patient.id)/imaging-studies/upload"
    $json = & curl.exe @curlArgs
    if ($LASTEXITCODE -ne 0) { throw "Upload of $dir failed (curl exit $LASTEXITCODE)" }
    $result = $json | ConvertFrom-Json
    $uploaded[$dir] = $result.studies[0].id
    Write-Host ("   {0}: study {1}, {2} new instance(s)" -f $dir, $result.studies[0].id, $result.instancesStored)
}

Write-Host "4/4 Adding DEMONSTRATION findings to the current CT..."
$findings = @(
    @{ type = "PULMONARY_NODULE"; laterality = "RIGHT"; sizeMm = 9; location = "Right upper lobe";
       description = "Synthetic 9 mm solid nodule in the phantom (demonstration data, not a real finding)." },
    @{ type = "PLEURAL_EFFUSION"; laterality = "LEFT"; location = "Left base, dependent";
       description = "Synthetic small dependent left effusion in the phantom (demonstration data)." }
)
foreach ($f in $findings) {
    $f.source = "DEMONSTRATION"; $f.status = "FINAL"
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/imaging-studies/$($uploaded['ct-current'])/findings" `
        -Headers $headers -ContentType "application/json" -Body ($f | ConvertTo-Json) | Out-Null
}
Write-Host "Done. Open $BaseUrl/patients/$($patient.id)?tab=imaging"
