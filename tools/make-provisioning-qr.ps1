<#
    Builds the QR code that turns a factory-fresh Android device into a kiosk,
    with no adb and no cable.

    On the device: factory reset, then tap the very first "Hello" setup screen
    six times. A QR scanner opens. Show it this code. Android downloads the APK
    from the URL below, installs it, and makes it device owner - all before the
    setup wizard finishes.

    Usage:
        .\make-provisioning-qr.ps1 -Apk ..\demo\build\outputs\apk\release\demo-release.apk `
                                   -Url http://YOUR-MACHINE-IP:8000/demo.apk `
                                   -Wifi MyNetwork -WifiPassword secret

    The URL must be reachable by the device before it has been set up, so a
    laptop on the same Wi-Fi running `python -m http.server 8000` is usually the
    easiest thing. Serve the same APK file you point -Apk at: the checksum below
    is what stops the device installing anything else.
#>
param(
    [Parameter(Mandatory = $true)] [string] $Apk,
    [Parameter(Mandatory = $true)] [string] $Url,
    [string] $Component = "com.mk.launcher/com.mk.kiosk.KioskAdminReceiver",
    [string] $Wifi,
    [string] $WifiPassword,
    [string] $WifiSecurity = "WPA",
    [string] $Out = "provisioning-qr.png",
    [string] $Checksum,
    [string] $Config
)

$ErrorActionPreference = "Stop"

# .NET path APIs use the process working directory, which is not where
# PowerShell thinks it is. Resolve against PowerShell's location instead -
# and leave a path that is already absolute alone, or Join-Path mangles it.
function Resolve-OutPath([string] $p) {
    if ([IO.Path]::IsPathRooted($p)) { return [IO.Path]::GetFullPath($p) }
    return [IO.Path]::GetFullPath((Join-Path (Get-Location).Path $p))
}

if (-not (Test-Path $Apk)) { throw "APK not found: $Apk" }

# apksigner is a Java program and will not run without JAVA_HOME. Find a JDK
# rather than making that the caller's problem.
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $candidates = @(
        "$env:LOCALAPPDATA\Programs\jdk-17",
        "$env:ProgramFiles\Android\Android Studio\jbr",
        "$env:ProgramFiles\Eclipse Adoptium\jdk-17"
    ) + (Get-ChildItem "$env:ProgramFiles\Java\jdk*" -Directory -ErrorAction SilentlyContinue |
         Select-Object -ExpandProperty FullName)

    $jdk = $candidates | Where-Object { $_ -and (Test-Path "$_\bin\java.exe") } | Select-Object -First 1
    if (-not $jdk) {
        throw "No JDK found. Set JAVA_HOME to a JDK 17 installation and try again."
    }
    $env:JAVA_HOME = $jdk
}

# ---- the signing certificate's SHA-256, base64url -------------------------
# Android checks this before installing, so the device cannot be talked into
# fetching a different APK from the same URL. Taken from the signature rather
# than the file, so rebuilding the APK does not invalidate the QR.
# The SDK is not always where the default puts it, and a terminal running under
# a different profile or sandbox can fail to see a path that plainly exists. Look
# in every sensible place, and let -Checksum skip this altogether.
function Find-ApkSigner {
    $roots = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        (Join-Path $env:LOCALAPPDATA "Android\Sdk"),
        (Join-Path $env:USERPROFILE "AppData\Local\Android\Sdk"),
        (Join-Path ${env:ProgramFiles} "Android\Android Studio"),
        "C:\Android\Sdk"
    ) | Where-Object { $_ }

    foreach ($r in $roots) {
        $pattern = Join-Path $r "build-tools\*\apksigner.bat"
        $hit = Get-ChildItem $pattern -ErrorAction SilentlyContinue |
               Sort-Object FullName -Descending |     # newest build-tools first
               Select-Object -First 1 -ExpandProperty FullName
        if ($hit) { return $hit }
    }
    return $null
}

if ($Checksum) {
    Write-Host ""
    Write-Host "  Using the checksum passed in; apksigner not needed."
} else {
    $apksigner = Find-ApkSigner
    if (-not $apksigner) {
        throw @"
apksigner not found. Looked under ANDROID_HOME, ANDROID_SDK_ROOT, \"$env:LOCALAPPDATA\Android\Sdk\",
the user profile and Program Files.

Either install the Android SDK build-tools, or pass the checksum directly:
    .\make-provisioning-qr.ps1 ... -Checksum <the value this script printed before>
The checksum is of the signing certificate, so it does not change between builds.
"@
    }

    $certs = & $apksigner verify --print-certs $Apk 2>&1 | Out-String
    $sha = [regex]::Match($certs, 'SHA-256 digest:\s*([0-9a-fA-F]{64})')
    if (-not $sha.Success) { throw "Could not read the signing certificate:`n$certs" }

    $bytes = [byte[]]::new(32)
    for ($i = 0; $i -lt 32; $i++) {
        $bytes[$i] = [Convert]::ToByte($sha.Groups[1].Value.Substring($i * 2, 2), 16)
    }
    # base64url, unpadded - what the provisioning extra expects
    $Checksum = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

# ---- the payload ----------------------------------------------------------
$payload = [ordered]@{
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME"           = $Component
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM"       = $Checksum
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION" = $Url
    "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED"         = $true
    "android.app.extra.PROVISIONING_SKIP_ENCRYPTION"                       = $true
}
if ($Wifi) {
    $payload["android.app.extra.PROVISIONING_WIFI_SSID"] = $Wifi
    $payload["android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE"] = $WifiSecurity
    if ($WifiPassword) { $payload["android.app.extra.PROVISIONING_WIFI_PASSWORD"] = $WifiPassword }
}

# ---- configuration the device arrives with -------------------------------
# Android hands this bundle to the app once, when provisioning finishes, and
# never offers it again. It is how a device comes up already configured rather
# than showing an empty grid and asking for a password.
if ($Config) {
    $Config = Resolve-OutPath $Config
    if (-not (Test-Path $Config)) { throw "Config file not found: $Config" }

    $cfg = Get-Content $Config -Raw | ConvertFrom-Json
    $bundle = [ordered]@{}
    foreach ($p in $cfg.PSObject.Properties) {
        $v = $p.Value
        # PowerShell hands JSON arrays back as objects the QR encoder writes
        # oddly; force a plain array of strings, which is what a
        # PersistableBundle accepts.
        if ($v -is [Array]) { $v = @($v | ForEach-Object { [string] $_ }) }
        $bundle[$p.Name] = $v
    }
    $payload["android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"] = $bundle

    Write-Host ""
    Write-Host "  Config    : $Config"
    foreach ($k in $bundle.Keys) {
        $shown = if ($k -eq "masterPassword") { "(set, hidden here)" } else { $bundle[$k] -join ", " }
        Write-Host ("                {0,-16} {1}" -f $k, $shown)
    }
}

$json = $payload | ConvertTo-Json -Compress -Depth 6

$Out = Resolve-OutPath $Out
$jsonPath = [IO.Path]::ChangeExtension($Out, ".json")
[IO.File]::WriteAllText($jsonPath, $json)

Write-Host ""
Write-Host "  Component : $Component"
Write-Host "  APK URL   : $Url"
Write-Host "  Checksum  : $Checksum"
if ($Wifi) { Write-Host "  Wi-Fi     : $Wifi" }
Write-Host ""
Write-Host "  Payload written to $jsonPath"

# ---- render it --------------------------------------------------------------
# qrcode if it is installed; otherwise the JSON is there to paste into any
# QR generator that runs locally.
$py = (Get-Command python -ErrorAction SilentlyContinue)
$rendered = $false
if ($py) {
    $script = @"
try:
    import qrcode
except ImportError:
    print('no-qrcode')
else:
    data = open(r'$jsonPath', encoding='utf-8').read()
    qrcode.make(data).save(r'$Out')
    print('ok')
"@
    # a missing library is not a failure of this script, so keep it non-fatal
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $result = ($script | & python - 2>&1 | Out-String)
    $ErrorActionPreference = $prev
    $rendered = $result -match 'ok'
}
if ($rendered) {
    Write-Host "  QR code written to $Out"
} else {
    Write-Host "  QR not rendered. Either:"
    Write-Host "      python -m pip install `"qrcode[pil]`"     then run this again"
    Write-Host "  or paste $jsonPath into any offline QR generator."
}
Write-Host ""
Write-Host "  On the device: factory reset, then tap the first setup screen six times."
Write-Host ""
