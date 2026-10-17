<#
.SYNOPSIS
  Sends HL7 v2 sample messages to RDSS over HTTP and prints the ACK (MSA) for each.
.EXAMPLE
  .\tools\send-hl7.ps1                                  # all files in tools\hl7-samples
  .\tools\send-hl7.ps1 -Path .\tools\hl7-samples\02-oru-r01-vitals.hl7
#>
param(
    [string]$Path = (Join-Path $PSScriptRoot "hl7-samples"),
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Key = $env:HL7_INBOUND_API_KEY
)
$ErrorActionPreference = "Stop"
if (-not $Key) {
    $envFile = Join-Path (Split-Path -Parent $PSScriptRoot) ".env"
    if (Test-Path $envFile) {
        $line = Get-Content $envFile | Where-Object { $_ -match '^HL7_INBOUND_API_KEY=' } | Select-Object -First 1
        if ($line) { $Key = $line.Substring($line.IndexOf('=') + 1) }
    }
}
if (-not $Key) { throw "Set -Key or HL7_INBOUND_API_KEY (environment or .env)." }
$files = if (Test-Path $Path -PathType Container) { Get-ChildItem $Path -Filter *.hl7 | Sort-Object Name } else { Get-Item $Path }
foreach ($f in $files) {
    # HL7 segments are separated by CR.
    $body = ((Get-Content $f.FullName) -join "`r") + "`r"
    $ack = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/hl7/messages" -Headers @{ "X-Integration-Key" = $Key } `
        -ContentType "application/hl7-v2" -Body $body
    $msa = ($ack -split "`r") | Where-Object { $_ -like "MSA*" }
    Write-Host ("{0,-32} {1}" -f $f.Name, $msa)
}
