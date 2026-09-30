<#
    Grants device owner over a USB cable.

    This is the route that needs adb. It is also the only one that does not
    need a factory reset first *if* the device happens to be fresh already -
    Android will refuse the moment any account has been added to it.

    Usage:
        .\provision.ps1                                  # installs the demo
        .\provision.ps1 -Apk ..\path\to\your.apk -Package com.example.app

    What it does, in order: checks exactly one device is attached, warns about
    accounts that will make Android refuse, installs the APK, grants device
    owner, and reads the state back to prove it took.
#>
param(
    [string] $Apk = "..\app\build\outputs\apk\release\app-release.apk",
    [string] $Package = "com.mk.launcher",
    [string] $Receiver = "com.mk.kiosk.KioskAdminReceiver",
    [switch] $SkipInstall
)

$ErrorActionPreference = "Stop"

# ---- find adb -------------------------------------------------------------
$adbExe = (Get-Command adb.exe -ErrorAction SilentlyContinue).Source
if (-not $adbExe) {
    $sdk = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $sdk) { $adbExe = $sdk }
}
if (-not $adbExe) {
    throw "adb not found. Install Android platform-tools, or use the QR route instead (make-provisioning-qr.ps1), which needs no cable at all."
}

# Named Invoke-Adb, not Adb: PowerShell matches command names without regard
# to case, so a function called Adb swallows its own call to adb and
# recurses until the stack gives out.
function Invoke-Adb {
    # Windows PowerShell wraps a native program's stderr in an error record,
    # and with ErrorActionPreference = Stop that kills the script over adb
    # merely saying "daemon not running". adb talks on stderr routinely, so
    # let it, and judge the result by what it prints.
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $out = & $adbExe @args 2>&1 | Out-String
    } finally {
        $ErrorActionPreference = $prev
    }
    return $out.Trim()
}

# ---- exactly one device ---------------------------------------------------
$devices = (Invoke-Adb devices) -split "`n" |
           Where-Object { $_ -match "\tdevice$" } |
           ForEach-Object { ($_ -split "`t")[0] }

if ($devices.Count -eq 0) {
    throw "No device. Plug it in, turn on USB debugging in Developer options, and accept the prompt on the screen."
}
if ($devices.Count -gt 1) {
    throw "More than one device attached: $($devices -join ', '). Unplug the others."
}
Write-Host ""
Write-Host "  Device    : $($devices[0])"
Write-Host "  Android   : $(Invoke-Adb shell getprop ro.build.version.release)  (API $(Invoke-Adb shell getprop ro.build.version.sdk))"
Write-Host "  Model     : $(Invoke-Adb shell getprop ro.product.model)"

# ---- the thing that actually blocks people --------------------------------
# Android refuses device owner outright once any account exists on the device,
# with an error that does not say so. Check first and say it plainly.
$accounts = Invoke-Adb shell dumpsys account
$count = ([regex]::Matches($accounts, 'Account \{name=')).Count
if ($count -gt 0) {
    Write-Host ""
    Write-Host "  $count account(s) are signed in on this device." -ForegroundColor Yellow
    Write-Host "  Android will refuse device owner while any account exists."
    Write-Host "  Remove every account (Settings > Accounts), or factory reset and"
    Write-Host "  skip the sign-in step, then run this again."
    Write-Host ""
    $go = Read-Host "  Try anyway? (y/N)"
    if ($go -ne "y") { return }
}

# ---- install --------------------------------------------------------------
if (-not $SkipInstall) {
    if (-not (Test-Path $Apk)) { throw "APK not found: $Apk" }
    Write-Host ""
    Write-Host "  Installing $(Split-Path $Apk -Leaf) ..."
    $install = Invoke-Adb install -r $Apk
    if ($install -notmatch "Success") { throw "Install failed:`n$install" }
}

# ---- grant ----------------------------------------------------------------
Write-Host "  Granting device owner ..."
$result = Invoke-Adb shell dpm set-device-owner "$Package/$Receiver"

if ($result -match "Success") {
    Write-Host ""
    Write-Host "  Device owner granted." -ForegroundColor Green
} else {
    Write-Host ""
    Write-Host "  Refused:" -ForegroundColor Red
    Write-Host "  $result"
    if ($result -match "already .*(provisioned|set)") {
        Write-Host ""
        Write-Host "  The device has been set up. Factory reset it, skip Wi-Fi and"
        Write-Host "  skip the account, then run this before finishing setup."
    }
    return
}

# ---- read it back ---------------------------------------------------------
# Trusting the Success line alone has been wrong before; ask the device.
$owner = Invoke-Adb shell dumpsys device_policy
if ($owner -match [regex]::Escape($Package)) {
    Write-Host "  Confirmed by the device: $Package holds device owner."
} else {
    Write-Host "  Warning: the grant reported success but the device does not list it." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "  Open the app. It should lock itself in - Home and Recents stop working,"
Write-Host "  and it will ask you to set a master password the first time."
Write-Host ""
