<img src="docs/icon_512.png" width="96" alt="HyperDebloat icon">

# HyperDebloat for Android

> Tested on a POCO X8 Pro Max (HyperOS 3, Android 16). Reports from other Xiaomi, Redmi and POCO phones are welcome: turn on **Save raw output for bug reports** in the ⋮ menu, run a check, and attach `raw_adb_output.txt`, `connection_log.txt` and the report CSV (all under **Reports**) to an issue.

The Android version of [HyperDebloat](https://github.com/KayYesR/HyperDebloat), the device-aware debloater for Xiaomi, Redmi and POCO phones running HyperOS or MIUI. It runs on the phone itself over **wireless debugging**: no root, no PC, no second app.

It reads every package and running process on **your** phone and rates each package from what the phone itself reports about it. Community debloat lists are used only to describe packages and to cross-check the phone's verdict. The rating rules are the same as in the Windows version.

| Colour | Rating | Meaning |
|---|---|---|
| 🟢 Green | SAFE | Independent app. The phone and all three lists agree it can go. Ticked for you. |
| 🟩 Light green | MOSTLY OK | Very likely junk, but not every source confirms it: two lists agree and the phone sees nothing using it, or all three lists agree and something small uses it. Not ticked for you. |
| 🟡 Yellow | MODERATE | A minor feature may stop working. |
| 🟠 Orange | HIGH | A notable or core feature may stop working. |
| 🔴 Red | UNSAFE | Can stop the phone booting or working properly. |
| ⚪ Grey | FLAGGED | Unknown to every list, contradicts the lists, or the phone gave no data. Never ticked for you; in Custom you can tick it after a warning. |
| 🔵 Blue | APP | Ordinary app (not a system app). Never ticked for you; setup-installed apps are tagged. |

## Requirements

- Android 11 or newer (wireless debugging pairing does not exist on Android 10 and older; use the [Windows version](https://github.com/KayYesR/HyperDebloat) for those phones).
- A Wi-Fi connection, so that wireless debugging can be switched on. Any network works, including another phone's hotspot; it does not need internet (but the debloat lists are downloaded when it has internet).

### Staying connected

Android switches Wireless debugging off by itself whenever Wi-Fi disconnects or the phone moves to a different access point (a mesh system, or a router that switches between 2.4 and 5 GHz). HyperDebloat then shows **Reconnect** with the step that is off; everything already done stays ticked, and the last check is kept for 12 hours. When the connection drops, `connection_log.txt` under Reports shows when Wi-Fi dropped and when Wireless debugging was switched off. **Work without Wi-Fi** (below) avoids most of these drops.

### Without Wi-Fi

Android only allows wireless debugging while the phone is connected to a Wi-Fi network as a client; its own hotspot does not count. The only tools that remove that limit need root ([nowifi-adb](https://github.com/iflyabd/nowifi-adb), [hotspotadb](https://github.com/droserasprout/io.drsr.hotspotadb)).

Without root, HyperDebloat offers **Work without Wi-Fi** (⋮ menu): after connecting once over Wi-Fi, the phone's ADB service also listens on `127.0.0.1:5555` (the same as `adb tcpip 5555`, and the same idea as the TCP mode in [thedjchi's Shizuku](https://github.com/thedjchi/Shizuku/wiki/Features)). You can then turn Wi-Fi off. Android closes that port when the phone restarts, so Wi-Fi is needed once after each restart; HyperDebloat switches the mode back on by itself. While it is on, other devices on the same network can ask for ADB access too: the phone shows an "Allow USB debugging?" prompt, and you should only allow it when HyperDebloat asks.

## Install

Download `HyperDebloat-<version>.apk` from [Releases](https://github.com/KayYesR/HyperDebloat--Android/releases) and open it on the phone. Allow installing from your browser or file manager when asked.

## How to use

The app walks you through setup one step at a time and moves on by itself. The step to do now is highlighted, and a bar at the bottom shows what the app is doing.

1. **Allow notifications**, so you can type the pairing code into one.
2. Turn on **Developer options** (About phone, tap **OS version** 7 times).
3. Connect to **Wi-Fi**. Any network works, including another phone's hotspot.
4. Turn on **Wireless debugging** in Developer options.
5. **Pair** (first time only): tap **Start pairing**, tap **Pair device with pairing code** in Wireless debugging, then type the 6-digit code into the HyperDebloat notification. No reply box in the notification? Tap **Type the code here** and use split screen.

Then clean the phone in three steps:

1. **Check your phone**: reads every app and rates it. Nothing is changed.
2. **Remove junk**: the removable apps in sections (Ads & tracking, Games, Media & music, Xiaomi apps, and so on). Safe junk is already ticked; untick anything you want to keep and tap **Remove**.
3. **Restart your phone**.

Other options:

- **Bring apps back**: restores anything removed, disabled or frozen, by this app or any other tool.
- **Choose apps yourself**: every app, colour-coded, with search. Tap **ⓘ** to see why a package got its rating.

Open the apps you use most before checking, so the check can see what they depend on. To see the ratings without changing anything, open **Choose apps yourself** and go back.

## How ratings are decided

### Step 1: what depends on it, according to the phone

| Signal (read over ADB) | Effect |
|---|---|
| Persistent (Android keeps it running) | Red |
| Current default launcher, dialer, SMS app or keyboard | Red |
| Device administrator | Red |
| Provides location services (network/fused location, geocoding, geofencing) | Red |
| Another app is using its services or content providers right now | Orange |
| Active overlay on the Android framework, System UI or Settings | Orange |
| 5 or more other apps hold permissions it defines | Orange |
| 1 to 4 other apps hold permissions it defines | Yellow |
| Installed with the hardware drivers (`vendor` or `odm` partition) | Yellow at least |

No dependency means green. How a package is built (signed with the platform key, privileged, no app icon, running as a system user) is shown for information but does not count against it: on Xiaomi phones nearly every package, junk included, looks like that.

### Step 2: cross-check with the lists

The lists are [UAD-ng](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation), [fadeltd/xiaomi-debloater](https://github.com/fadeltd/xiaomi-debloater) and [ovsky/hyperos-debloater](https://github.com/ovsky/hyperos-debloater), downloaded at most once a day. If GitHub is unreachable, the last saved copy is used.

- A list rates it higher than the phone does: the rating goes up to the list's rating.
- All three lists say it is junk and nothing red applies: green, or yellow if other apps clearly rely on it or it sits with the hardware drivers.
- The two lists that know it both say safe and nothing is using it right now: one step lower.
- Only one list knows it: never green, because it cannot be cross-checked.
- No list knows it: flagged grey, because nothing says what it does. Remove junk never offers it; Choose apps yourself lets you tick it after a warning.
- What ends up green is split in two: **Safe** when the phone and all three lists agree, **Mostly okay** otherwise.

### Ordinary apps and setup junk

Apps that are not system apps are listed in blue. An app installed within 2 hours of the first app on the phone is tagged **Installed during setup**: on Xiaomi phones that is when the preloaded junk arrives. Apps restored from your backup arrive at the same time, so blue apps are never ticked for you. Ordinary apps are removed completely, so Bring apps back can only open their Play Store page.

YouTube, Chrome, Google Lens, Google Meet, WhatsApp, Instagram, Google Drive and Gmail are never ticked for you, even on phones where they have no icon. Before anything is removed, the confirmation names every ticked app that has an icon on the home screen.

### Essential Google apps

Remove junk lists these in a box of their own, never ticked, each with one line of advice:

| App | Advice |
|---|---|
| Play Store, Google Play services | Locked everywhere, including Choose apps yourself. Apps cannot be installed, updated or run properly without them. |
| Google app | Kept at orange: search, Assistant and the Discover feed need it. |
| Chrome | Removable only when another browser is set as default. |
| Gmail, YouTube, Google Meet, Google Maps | Remove only if you don't use it. |

### Packages HyperDebloat needs while it runs (Android version only)

Because the app runs on the phone it is cleaning, a few packages are locked (shown red, cannot be ticked), besides the Play Store and Google Play services: Settings (holds the Wireless debugging switch and pairing screen), Shell (runs the commands), System UI (shows the pairing notification), the package installers (show the phone's own uninstall screen), and the network stack and Wi-Fi resources (keep the Wi-Fi connection that wireless debugging runs over). HyperDebloat itself is left out of the list.

## Differences from the Windows version

- Android 11 or newer only, and Wi-Fi is needed to connect (see [Without Wi-Fi](#without-wi-fi)).
- The live-connection check sees fewer apps while HyperDebloat is on screen, because the others are in the background. Open the apps you rely on just before checking.
- The packages above are locked.
- App names and icons are shown next to package names (display only; they do not affect ratings).
- No dry-run mode: open Choose apps yourself and press Back to see the ratings without changing anything.
- Play Store and Google Play services are locked.

## Safety

- System apps are removed for your user only (`pm uninstall -k --user 0`) and can always be restored. Nothing is deleted from the system partition, OTA updates keep working, and a factory reset restores everything.
- Bring apps back does not depend on this app's logs: it reads the phone's own record of removed, disabled and frozen packages.
- After each removal the app checks the package is really gone; some HyperOS versions answer `Success` and keep it.
- Packages MIUI/HyperOS refuses to remove (`Failure [-1000]`) can be offered to the phone's own uninstall screen. Anything still refused is frozen.
- Every check saves a dated report: phone model, HyperOS build, every rating, the phone's evidence, the lists' ratings and the final reasoning. Removals are logged too. Copies are written to `Documents/HyperDebloat/<model>_<build>/` on the phone.
- On the phone itself there is no PC to recover from a bootloop. If you remove red packages and the phone stops booting, the fix may be a factory reset.

## Keeping removed apps gone

Without root, no tool can stop Android putting an app back. HyperDebloat does what the phone allows:

1. **Update removed first.** If a system app runs an update from the Play Store or GetApps, the update is removed before the app (`pm uninstall-system-updates`, the same idea as Canta's *Reset to factory version*).
2. **Frozen if the phone will not remove it.** If the phone refuses the removal, keeps the app despite `Success`, or reinstalls it within seconds, the app is frozen instead (`pm suspend`): it stays on the phone with a greyed-out icon, cannot open, and its notifications are hidden.
3. **Removed again if it comes back, frozen if it comes back twice.** Every check looks at the apps you removed. The first time one returns, it is removed again. If it returns a second time, it is frozen in place. The phone's log from that moment is saved as `came_back_<date>.txt` under Reports.

If you want an app back that you removed, use **Bring apps back** (not the Play Store), so the app knows to leave it alone.

## Releasing (maintainer notes)

Pushing a tag such as `v0.1.0` builds a signed APK and attaches it to a GitHub release. The workflow needs four repository secrets (Settings > Secrets and variables > Actions):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | The release keystore (`.jks`) encoded as base64. In PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("hyperdebloat.jks")) \| Set-Clipboard` |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

Keep the keystore safe: every update must be signed with the same key, or phones refuse to install it over the old version. Pushes to `main` build a debug APK, available under the workflow run's artifacts.

## Building

Android Studio, or `./gradlew assembleDebug` with JDK 17 and the Android SDK (platform 35).

## Credits

Made by **kayyesr**, perfected by **Claude** (Anthropic, Claude Opus 5.5).

Package descriptions and cross-check data come from the [UAD-ng](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation), [fadeltd/xiaomi-debloater](https://github.com/fadeltd/xiaomi-debloater) and [ovsky/hyperos-debloater](https://github.com/ovsky/hyperos-debloater) projects and their contributors. The lists are downloaded at run time and are not included in this repository.

Wireless debugging is handled by [libadb-android](https://github.com/MuntashirAkon/libadb-android) (Apache-2.0), with [Conscrypt](https://github.com/google/conscrypt) (Apache-2.0) and [Bouncy Castle](https://www.bouncycastle.org/) (MIT-style licence). The pairing-by-notification approach follows [Shizuku](https://github.com/RikkaApps/Shizuku); the update-first removal follows [Canta](https://github.com/samolego/Canta).

## Licence

MIT - see [LICENSE](LICENSE). Use at your own risk: removing system packages can break features, and in the worst case may need a factory reset.
