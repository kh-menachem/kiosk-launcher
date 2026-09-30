# Putting the launcher on a device

One QR code. Factory reset, scan, walk away — the device installs the launcher,
installs your apps, applies your app list and locks itself down.

---

## Once, on your machine

**1. Build the launcher.**

```powershell
gradle :app:assembleRelease
copy app\build\outputs\apk\release\app-release.apk tools\serve\launcher.apk
```

**2. Write the configuration.** Copy `tools/config.example.json` and edit it:

```json
{
  "apps": ["com.example.one", "com.example.two"],
  "hideOthers": true,
  "allowBrowser": true,
  "keepAwake": false,
  "showStatus": true,
  "masterPassword": "something-you-will-remember",
  "appManifestUrl": "http://YOUR-MACHINE-IP:8000/apps.json"
}
```

| key | what it does |
|---|---|
| `apps` | package names that appear on the grid |
| `hideOthers` | hide every other app from the device |
| `allowBrowser` | let a browser run inside the lock — **needed for OAuth sign-in** |
| `keepAwake` | screen stays on |
| `showStatus` | battery and Wi-Fi in the corner |
| `masterPassword` | set here instead of prompting on first run |
| `appManifestUrl` | where to find apps to install and update |

Every key is optional. Leave `apps` out and the device comes up with an empty
grid; leave `masterPassword` out and it asks on first run.

**3. List the apps to install**, at the URL you gave as `appManifestUrl`:

```json
{
  "com.example.one": {
    "versionCode": 42,
    "url": "http://YOUR-MACHINE-IP:8000/one.apk"
  },
  "com.example.two": {
    "versionCode": 7,
    "url": "http://YOUR-MACHINE-IP:8000/two.apk",
    "splits": ["http://YOUR-MACHINE-IP:8000/two.config.arm64_v8a.apk"]
  }
}
```

`splits` is for apps that ship as a bundle rather than a single APK. They install
as one session, because they are one package.

Put the APKs and `apps.json` in `tools/serve/`.

**4. Make the QR.**

```powershell
cd tools
.\make-provisioning-qr.ps1 -Apk ..\app\build\outputs\apk\release\app-release.apk `
                           -Url http://YOUR-MACHINE-IP:8000/launcher.apk `
                           -Config config.json `
                           -Wifi MyNetwork -WifiPassword secret
```

Use your machine's own LAN address, not `localhost` — the device has to reach it.
`serve-apk.ps1` prints the right one.

---

## For each device

**1. Serve the files**, in a terminal of its own, and leave it running:

```powershell
cd tools
.\serve-apk.ps1
```

Windows Firewall asks the first time. It must be allowed on **Private**, or
nothing will reach it.

**2. Factory reset the device.** No accounts signed in — Android refuses device
owner outright if any account exists, and the error does not mention accounts.

**3. On the first setup screen, tap six times.** A QR scanner opens.

**4. Show it the code.**

The device joins Wi-Fi, downloads the launcher, installs it, makes it device
owner, applies your configuration, installs the apps from your manifest, and
locks itself in. A `GET /launcher.apk` in the server's terminal is your sign it
is working.

---

## Afterwards

**Getting into settings:** seven taps in the top-left corner, then two in the
top-right, then the master
password.

**Updating an app:** replace the APK on the server and raise its `versionCode`
in `apps.json`. Devices pick it up on their own — no visit, no QR, no reinstall.
Nothing downloads unless the version code is higher than what is installed.

**Updating the launcher itself:** it is an ordinary app upgrade. Over adb with
`adb install -r`, or add the launcher to its own manifest so it updates like
anything else.

---

## Things that will bite

**One device owner per device.** A device already locked by another app cannot
take this one. Release the other first, or factory reset.

**Updates need the same signing key.** An APK from a different source, signed by
someone else, will not install over one already there — it needs an uninstall,
which wipes that app's data. Pin one source per app and stay with it.

**The release build is signed with the debug key**, so the QR's checksum matches
debug-signed builds only. Sign with a real key before deploying anything that
matters, then regenerate the QR once — after that it survives rebuilds, because
the key does not change.

**The QR is a secret.** It carries the master password and the Wi-Fi password in
readable form, and so does the `.json` beside it. Anyone who photographs the code
has both. Delete both when the devices are built; `.gitignore` already keeps them
out of the repository.

**A silent-install channel is real power.** Whatever that server serves gets
installed on your kiosks with no prompt. Keep it on your own network.

**Configuration applies once.** Changing the list by hand on a device sticks —
the QR's list does not come back at the next launch. Re-provisioning is the way
to reset a device to the configured state.

---

## Getting back out

In the launcher's settings, three levels, least to most final:

| | reversible | survives reboot |
|---|---|---|
| Unlock for maintenance | yes, re-locks in 15 min | no |
| **Turn the lockdown off** | **yes, from the panel** | **yes** |
| Release device owner | **no — factory reset only** | n/a |

**Turn the lockdown off** is almost always the one you want: the device behaves
normally, everything is unhidden, and switching back on is one button, because
device owner is kept.

**Release device owner** is for decommissioning. Android will not grant it again
without a factory reset and provisioning from scratch. Some devices do allow a
re-grant over adb while no account exists — that is luck, not the rule:

```powershell
adb shell dpm set-device-owner com.mk.launcher/com.mk.kiosk.KioskAdminReceiver
```

---

## No cable, no QR scanner?

`make-provisioning-nfc.ps1` turns the same payload into an NFC tag — hold it to
the back of the device on the first setup screen. Needs an NTAG216 once Wi-Fi
credentials are in the payload; the script works out the size and tells you.

`provision.ps1` does it over USB instead, for a device already in your hand.
