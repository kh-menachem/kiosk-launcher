# Kiosk Launcher

An Android home screen that shows only the apps you choose. Everything else on
the device is out of reach.

**The apps don't need to cooperate.** Only the launcher is device owner. It names
the allowed packages and Android lets them run inside the lock — whoever wrote or
signed them, with no SDK and no changes on their side.

Android 8.0 (API 26) and up.

---

## What you get

- A grid of the apps you picked, and nothing else
- Home returns to the grid; a reboot lands back here
- Recents, the notification shade and the status bar are blocked
- Battery and Wi-Fi shown in a corner, since the system bar is gone
- A Wi-Fi picker inside the app, so a device can be reconnected without unlocking
- Devices can **arrive configured** from the QR — app list, settings, password
- Apps **install and update by themselves** from a file you host

---

## Quick start

### 1. Build it

```powershell
gradle :app:assembleRelease
copy app\build\outputs\apk\release\app-release.apk tools\serve\launcher.apk
```

### 2. Say what the device should look like

Copy `tools/config.example.json` to `tools/config.json`:

```json
{
  "apps": ["com.example.first", "com.example.second"],
  "hideOthers": false,
  "allowBrowser": true,
  "appManifestUrl": "http://YOUR-MACHINE-IP:8000/apps.json"
}
```

Every key is optional. Leave `apps` out and you pick them on the device.

| key | meaning |
|---|---|
| `apps` | packages shown on the grid |
| `hideOthers` | hide every other app from the device |
| `allowBrowser` | let a browser run inside the lock — **needed for OAuth sign-in** |
| `keepAwake` | screen stays on |
| `showStatus` | battery and Wi-Fi in the corner |
| `masterPassword` | set it here, or leave it out and the device asks |
| `appManifestUrl` | where to find apps to install and keep updated |

### 3. List any apps to install

Put the APKs and this `apps.json` in `tools/serve/`:

```json
{
  "com.example.first": {
    "versionCode": 42,
    "url": "http://YOUR-MACHINE-IP:8000/first.apk"
  }
}
```

Add `"splits": [...]` for apps that ship as a bundle rather than one APK.

### 4. Make the QR

```powershell
cd tools
.\make-provisioning-qr.ps1 -Apk ..\app\build\outputs\apk\release\app-release.apk `
                           -Url http://YOUR-MACHINE-IP:8000/launcher.apk `
                           -Config config.json
```

Add `-Wifi MyNetwork -WifiPassword secret` to have the device join Wi-Fi by
itself. Use your machine's LAN address, not `localhost` — `serve-apk.ps1` prints
the right one.

### 5. Set up a device

```powershell
.\serve-apk.ps1
```

Leave that running. Then on the device:

1. **Factory reset**, and sign into no accounts
2. On the first setup screen, **tap six times** — a QR scanner opens
3. Connect to Wi-Fi if asked
4. **Scan the code**

It installs the launcher, becomes device owner, applies your configuration,
installs your apps, and locks itself in.

You'll see `GET /launcher.apk` in the server's terminal when it starts.

---

## Using it

**Settings:** seven taps in the top-left corner, then two in the top-right, then
the master password.

From there: choose apps, Wi-Fi, keep-screen-on, show or hide the status strip,
change the password, and the three ways out below.

**Wi-Fi:** tap the Wi-Fi icon in the corner. No password needed — someone
standing at a kiosk that has dropped offline is rarely the person who holds it.

---

## Getting back out

| | reversible | survives reboot |
|---|---|---|
| Unlock for maintenance | yes, re-locks in 15 minutes | no |
| **Turn the lockdown off** | **yes, from settings** | **yes** |
| Release device owner | **no — factory reset only** | n/a |

**Turn the lockdown off** is almost always the one you want. The device behaves
normally, everything is unhidden, and switching back on is one button — device
owner is kept.

**Release device owner** is for decommissioning. Android will not grant it again
without a factory reset.

---

## Updating apps

Replace the APK on your server and raise its `versionCode` in `apps.json`.
Devices pick it up on their own — no visit, no QR, no reinstall. Nothing
downloads unless the version is newer than what is installed.

---

## Worth knowing

**One device owner per device.** A device already locked by another app can't
take this one.

**Updates need the same signing key.** An APK signed by someone else won't
install over one already there; it needs an uninstall, which wipes that app's
data. Pick one source per app and stay with it.

**The QR is a secret.** It carries the master password and Wi-Fi password in
readable form, as does the `.json` beside it. `.gitignore` keeps both out of the
repository; delete them once your devices are built.

**Debug signing.** `assembleRelease` is signed with the debug key so it installs
out of the box. Sign with a real key before deploying anything that matters, then
regenerate the QR once — after that it survives rebuilds.

**`allowBrowser` matters more than it looks.** Most apps sign in through OAuth in
a browser. With no browser allowed, the sign-in button does nothing at all and
the app looks broken.

**`hideOthers` is the risky one.** It hides apps system-wide. Browsers, Settings
and home screens are protected so the way back can't disappear, but leave it off
until the rest works.

---

## No QR scanner?

```powershell
.\provision.ps1                  # over a USB cable
.\make-provisioning-nfc.ps1      # writes the payload for an NFC tag
```

---

## Layout

```
app/src/main/java/com/mk/launcher/
    LauncherActivity.java     the home screen and the corner gesture
    LauncherPolicy.java       device-owner policy: allow-list, hiding, lock task
    SettingsActivity.java     settings
    AppPickerActivity.java    choose apps, with search
    WifiActivity.java         the in-app Wi-Fi picker
    AppInstaller.java         silent install and update from the manifest
    ProvisionConfig.java      applies what the QR carried, once
tools/                        provisioning and the APK server
```

Built on [android-kiosk](https://github.com/kh-menachem/android-kiosk), which
supplies the device-admin plumbing, the master password and the status strip.

`DEPLOY.md` has the same ground in more detail.

MIT licensed.
