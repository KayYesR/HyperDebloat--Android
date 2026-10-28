# HyperDebloat for Android

> Not tested on a phone yet. Reports from Xiaomi, Redmi and POCO phones are welcome: turn on **Save raw phone output** on the home screen, run a scan, and attach `raw_adb_output.txt` and the report CSV (both under **Reports**) to an issue.

The Android version of [HyperDebloat](https://github.com/KayYesR/HyperDebloat), the device-aware debloater for Xiaomi, Redmi and POCO phones running HyperOS or MIUI. It runs on the phone itself over **wireless debugging**: no root, no PC, no second app.

It reads every package and running process on **your** phone and rates each package from what the phone itself reports about it. Community debloat lists are used only to describe packages and to cross-check the phone's verdict. The rating rules are the same as in the Windows version.

| Colour | Rating | Meaning |
|---|---|---|
| 🟢 Green | SAFE | Independent app. Removing it breaks nothing else. |
| 🟡 Yellow | MODERATE | A minor feature may stop working. |
| 🟠 Orange | HIGH | A notable or core feature may stop working. |
| 🔴 Red | UNSAFE | Can stop the phone booting or working properly. |
| ⚪ Grey | FLAGGED | Unknown to every list, contradicts the lists, or the phone gave no data. Never ticked for you; in Custom you can tick it after a warning. |
| 🔵 Blue | APP | Ordinary app (not a system app). Never ticked for you; setup-installed apps are tagged. |

## Requirements

- Android 11 or newer (wireless debugging pairing does not exist on Android 10 and older; use the [Windows version](https://github.com/KayYesR/HyperDebloat) for those phones).
- A Wi-Fi connection, so that wireless debugging can be switched on.

## Install

Download `HyperDebloat-<version>.apk` from [Releases](https://github.com/KayYesR/HyperDebloat-Android/releases) and open it on the phone. Allow installing from your browser or file manager when asked.

## How to use

1. **Developer options:** Settings > About phone, tap **OS version** 7 times. Then Settings > Additional settings > Developer options > **USB debugging: ON**.
2. **Wireless debugging:** connect to Wi-Fi, then in Developer options turn on **Wireless debugging** and allow it on this network.
3. **Pair (first time only):** in HyperDebloat tap **Start pairing**. In Wireless debugging tap **Pair device with pairing code** and leave that dialog open. Pull down the notification shade and type the 6-digit code into the HyperDebloat notification.
   - No reply box in the notification? On Xiaomi phones, set the notification style to Android (search Settings for "notification style"), or use split screen and type the code into HyperDebloat itself.
4. HyperDebloat connects by itself from then on, whenever Wireless debugging is on. Wireless debugging may switch itself off after a restart or a Wi-Fi change; turn it on again before using the app.
5. Open the apps you rely on (file manager, camera, gallery, payment apps) and come back, so the scan can see what they use.
6. Choose an action:
   - **Recommended** shows every green package. Background junk is ticked; anything with an icon is not. Untick anything you want to keep and press **Remove selected**.
   - **Custom** opens the full colour-coded list, with the same packages ticked as in Recommended. Pick a category or type a name to narrow it, tick what you want and press **Remove selected**. Red and grey packages ask for an extra confirmation. Tap **(i)** on a row for the phone's evidence, the lists' ratings and the final reasoning.
   - **Restore** asks the phone what has been removed or disabled, by this tool or any other (Xiaomi ADB/Fastboot Tools, UAD, Canta, plain adb), and puts back what you tick.
   - **Rescan phone** and **Reports**.

To see the ratings without changing anything, open Custom and press **< Back**.

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

The lists are [UAD-ng](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation), [fadeltd/xiaomi-debloater](https://github.com/fadeltd/xiaomi-debloater) and [ovsky/hyperos-debloater](https://github.com/ovsky/hyperos-debloater), downloaded fresh on every scan. If GitHub is unreachable, the last saved copy is used.

- A list rates it higher than the phone does: the rating goes up to the list's rating.
- All three lists say it is junk and nothing red applies: green, or yellow if other apps clearly rely on it or it sits with the hardware drivers.
- The two lists that know it both say safe and nothing is using it right now: one step lower.
- Only one list knows it: never green, because it cannot be cross-checked.
- No list knows it: flagged grey, because nothing says what it does. Recommended never offers it; Custom lets you tick it after a warning.

### Ordinary apps and setup junk

Apps that are not system apps are listed in blue. An app installed within 2 hours of the first app on the phone is tagged **Installed during setup**: on Xiaomi phones that is when the preloaded junk arrives. Apps restored from your backup arrive at the same time, so blue apps are never ticked for you. Ordinary apps are removed completely, so Restore can only open their Play Store page.

YouTube, Chrome, Google Lens, Google Meet, WhatsApp, Instagram, Google Drive and Gmail are never ticked for you, even on phones where they have no icon. Before anything is removed, the confirmation names every ticked app that has an icon on the home screen.

### Packages HyperDebloat needs while it runs (Android version only)

Because the app runs on the phone it is cleaning, a few packages are locked (shown red, cannot be ticked): Settings (holds the Wireless debugging switch and pairing screen), Shell (runs the commands), System UI (shows the pairing notification), the package installers (show the phone's own uninstall screen), and the network stack and Wi-Fi resources (keep the Wi-Fi connection that wireless debugging runs over). HyperDebloat itself is left out of the list.

## Differences from the Windows version

- Android 11 or newer only, and Wi-Fi must be on.
- The live-connection check sees fewer apps while HyperDebloat is on screen, because the others are in the background. Open the apps you rely on just before scanning.
- The packages above are locked.
- App names and icons are shown next to package names (display only; they do not affect ratings).
- No dry-run mode: open Custom and press Back to see the ratings without changing anything.

## Safety

- System apps are removed for your user only (`pm uninstall -k --user 0`) and can always be restored. Nothing is deleted from the system partition, OTA updates keep working, and a factory reset restores everything.
- Restore does not depend on this app's logs: it reads the phone's own record of removed, disabled and frozen packages.
- After each removal the app checks the package is really gone; some HyperOS versions answer `Success` and keep it.
- Packages MIUI/HyperOS refuses to remove (`Failure [-1000]`) can be offered to the phone's own uninstall screen. Anything still refused is frozen.
- Every scan saves a dated report: phone model, HyperOS build, every rating, the phone's evidence, the lists' ratings and the final reasoning. Removals are logged too. Copies are written to `Documents/HyperDebloat/<model>_<build>/` on the phone.
- On the phone itself there is no PC to recover from a bootloop. If you remove red packages and the phone stops booting, the fix may be a factory reset.

## Keeping removed apps gone

Without root, no tool can stop Android putting an app back. HyperDebloat does what the phone allows:

1. **Update removed first.** If a system app runs an update from the Play Store or GetApps, the update is removed before the app (`pm uninstall-system-updates`, the same idea as Canta's *Reset to factory version*).
2. **Frozen if the phone will not remove it.** If the phone refuses the removal, keeps the app despite `Success`, or reinstalls it within seconds, the app is frozen instead (`pm suspend`): it stays on the phone with a greyed-out icon, cannot open, and its notifications are hidden.
3. **Removed again if it comes back, frozen if it comes back twice.** Every scan checks the apps you removed. The first time one returns, it is removed again. If it returns a second time, it is frozen in place. The phone's log from that moment is saved as `came_back_<date>.txt` under Reports.

If you want an app back that you removed, use Restore (not the Play Store), so the app knows to leave it alone.

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
