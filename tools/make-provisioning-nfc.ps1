<#
    Turns the provisioning payload into the form an NFC tag has to carry.

    Run make-provisioning-qr.ps1 first - it works out the signing checksum and
    writes the JSON. This converts that same payload into the java.util.Properties
    text Android's NFC provisioning expects, and tells you how to get it onto a
    tag.

    Usage:
        .\make-provisioning-nfc.ps1                       # uses demo-qr.json
        .\make-provisioning-nfc.ps1 -Json my-payload.json

    On the device: factory reset, then hold the tag against the back of the
    device while the very first setup screen is showing. Nothing to tap.
#>
param(
    [string] $Json = "demo-qr.json",
    [string] $Out  = ""
)

$ErrorActionPreference = "Stop"

# .NET path APIs use the process working directory, which is not where
# PowerShell thinks it is. Resolve against PowerShell's location instead -
# and leave a path that is already absolute alone, or Join-Path mangles it.
function Resolve-OutPath([string] $p) {
    if ([IO.Path]::IsPathRooted($p)) { return [IO.Path]::GetFullPath($p) }
    return [IO.Path]::GetFullPath((Join-Path (Get-Location).Path $p))
}

$Json = Resolve-OutPath $Json
if (-not (Test-Path $Json)) {
    throw "Payload not found: $Json`nRun make-provisioning-qr.ps1 first - it computes the signing checksum."
}
if (-not $Out) { $Out = [IO.Path]::ChangeExtension($Json, ".properties") }
$Out = Resolve-OutPath $Out

$payload = Get-Content $Json -Raw | ConvertFrom-Json

$lines = New-Object System.Collections.Generic.List[string]
foreach ($p in $payload.PSObject.Properties) {
    $value = $p.Value
    # Properties files carry everything as text; JSON booleans must not arrive
    # as .NET's "True"/"False", which Android parses as false.
    if ($value -is [bool]) { $value = $value.ToString().ToLower() }
    # a backslash is the escape character in a properties file
    # A backslash escapes the next character in a properties file, so each
    # one in a value has to be doubled. Replace() is a plain string swap;
    # -replace would read the pattern as a regex and reject a lone backslash.
    $value = $value.Replace([string][char]92, [string][char]92 + [char]92)
    $lines.Add("$($p.Name)=$value")
}

# NFC provisioning wants LF and no BOM; a UTF-8 BOM ends up inside the first key
$text = ($lines -join "`n") + "`n"
[IO.File]::WriteAllText($Out, $text, (New-Object Text.UTF8Encoding $false))

Write-Host ""
Write-Host "  Wrote $Out"
Write-Host ""
Write-Host $text
# ---- what will actually hold it ------------------------------------------
# An NDEF MIME record is 1 header byte + 1 type-length + 4 payload-length (the
# long form, since this payload is over 255 bytes) + the type + the payload,
# and the tag wraps the message in a TLV of 1 + 3 bytes. Worked out rather than
# guessed, because the difference decides which tag you have to buy.
$mimeType = "application/com.android.managedprovisioning"
$record   = 1 + 1 + 4 + $mimeType.Length + $text.Length
$onTag    = 1 + 3 + $record

Write-Host "  Size"
Write-Host "  ----"
Write-Host "  Payload $($text.Length) bytes, $onTag bytes once wrapped as NDEF."
Write-Host ""

$tags = @(
    @{ Name = "MIFARE Ultralight"; Cap =  48 },
    @{ Name = "Ultralight C";      Cap = 144 },
    @{ Name = "NTAG213";           Cap = 144 },
    @{ Name = "NTAG215";           Cap = 504 },
    @{ Name = "NTAG216";           Cap = 888 }
)
foreach ($t in $tags) {
    $verdict = if ($t.Cap -ge $onTag) { "fits" } else { "TOO SMALL" }
    Write-Host ("    {0,-20} {1,3} bytes   {2}" -f $t.Name, $t.Cap, $verdict)
}
Write-Host ""
if ($onTag -gt 504) {
    Write-Host "  This payload needs an NTAG216. To get it onto an NTAG215, drop the"
    Write-Host "  Wi-Fi lines and connect the device to Wi-Fi by hand in the setup"
    Write-Host "  wizard before you tap the tag - it works just as well."
}
Write-Host ""
Write-Host "  To get it onto a tag"
Write-Host "  --------------------"
Write-Host "  The tag has to be NDEF-formatted. NFC-A is only the radio layer and"
Write-Host "  says nothing about capacity - NTAG21x and MIFARE Ultralight are all"
Write-Host "  NFC-A, and most of them are far too small for this."
Write-Host ""
Write-Host "  Avoid MIFARE Classic: it is big enough, but only Android devices with"
Write-Host "  an NXP chipset can read it, so a tag that works on one phone silently"
Write-Host "  does nothing on the next."
Write-Host ""
Write-Host "  With the free NFC Tools app on any phone:"
Write-Host "    1. Write > Add a record > Data > MIME type"
Write-Host "    2. MIME type:  $mimeType"
Write-Host "    3. Paste the text above as the content, exactly, newlines and all"
Write-Host "    4. Write, and hold the tag to the phone"
Write-Host ""
Write-Host "  Then: factory reset the target device and hold the tag against its"
Write-Host "  back while the first setup screen is showing."
Write-Host ""
Write-Host "  Worth knowing: the APK still comes over the network from the URL in"
Write-Host "  the payload, so that host has to be reachable. The tag carries the"
Write-Host "  instructions, not the app."
Write-Host ""
