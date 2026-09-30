<#
    Serves the demo APK so a device being provisioned can download it.

    Leave this running, show the device demo-qr.png, and stop it with Ctrl+C
    when the device has finished installing.

    Windows Firewall will ask to allow Python the first time. It has to be
    allowed on the Private network or the device cannot reach the file.
#>
param([int] $Port = 8000)

$ErrorActionPreference = "Stop"

$root = Join-Path $PSScriptRoot "serve"
if (-not (Test-Path (Join-Path $root "launcher.apk"))) {
    throw "serve\launcher.apk is missing. Copy the built APK there first."
}

$ip = (Get-NetIPAddress -AddressFamily IPv4 |
       Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" } |
       Select-Object -First 1).IPAddress

Write-Host ""
Write-Host "  Serving $root"
Write-Host "  URL: http://${ip}:$Port/launcher.apk"
Write-Host ""
Write-Host "  This address must match the one inside the QR code. If it does not,"
Write-Host "  rebuild the QR with:  .\make-provisioning-qr.ps1 -Url http://${ip}:$Port/launcher.apk ..."
Write-Host ""
Write-Host "  Ctrl+C to stop."
Write-Host ""

Push-Location $root
try { python -m http.server $Port } finally { Pop-Location }
