<div align="center">

# OnePort

**Samsung One UI apps, patched to run on non-Samsung Android phones.**

My Files · Gallery · Photo Editor · Video Player (beta)

[![Release](https://img.shields.io/github/v/release/StevenSteel7/OnePort?include_prereleases&label=release)](https://github.com/StevenSteel7/OnePort/releases)
[![Android](https://img.shields.io/badge/Android-16%2B-3ddc84?logo=android&logoColor=white)](#compatibility)
[![arm64](https://img.shields.io/badge/ABI-arm64--v8a-informational)](#compatibility)
[![License: MIT (tooling)](https://img.shields.io/badge/license-MIT%20(tooling)-blue)](LICENSE)
[![Maintained](https://img.shields.io/badge/maintained-yes-brightgreen)](#maintenance-promise)

</div>

---

OnePort adapts Samsung's own One UI apps so they install and run like normal apps on a stock-ish Android phone. It doesn't need root, a custom ROM or a Samsung account.

> [!IMPORTANT]
> This is an **unofficial** community project, and it is **not affiliated with or endorsed by Samsung**. Read [Caveats](#caveats--read-before-installing) before installing.

## Contents

- [The apps](#the-apps)
- [Compatibility](#compatibility)
- [Install](#install)
- [Updating and uninstalling](#updating-and-uninstalling)
- [What works and what doesn't](#what-works-and-what-doesnt)
- [Caveats](#caveats--read-before-installing)
- [Troubleshooting](#troubleshooting)
- [Maintenance promise](#maintenance-promise)
- [Roadmap](#roadmap)
- [Building from source](#building-from-source)
- [Contributing](#contributing)
- [Legal](#legal)

## The apps

| App | Package | Samsung version | Status |
|---|---|---|---|
| **My Files** | `com.sec.android.app.myfiles` | 15.6.03.3 | ✅ Stable |
| **Gallery** | `com.sec.android.gallery3d` | 15.9.00.45 | ✅ Stable |
| **Photo Editor** | `com.sec.android.mimage.photoretouching` | 3.9.09.16 | ✅ Stable |
| **Video Player** | `com.samsung.android.video` | 7.4.00.6 | 🧪 Beta: playback and picture-in-picture work |

The three stable apps are signed with the same key, so they work together the way they do on a Galaxy phone. For example, Gallery's **Edit** button opens Photo Editor, and items you delete go to the Recycle bin that My Files and Gallery share.

## Compatibility

### General requirements

| | |
|---|---|
| **Phone** | Any non-Samsung Android phone with a 64-bit ARM processor (arm64-v8a) |
| **Not supported** | Samsung phones (they already have these apps), 32-bit-only phones and x86 devices |
| **Not needed** | Root, a custom ROM, or a Samsung account |
| **To install** | A computer with `adb` (recommended), or install the APKs directly on the phone |
| **Tested on** | Motorola Edge 60 Pro, Android 17 (API 37), MediaTek Dimensity 8300. Installing, and basic use of My Files, also tested on Android 14–17 emulators (Google Play images). |

### Requirements per app

| App | Minimum Android | Special access to grant | Works with | Opens from | Size |
|---|---|---|---|---|---|
| **My Files** | 14 (API 34) | All files access | Shares the Recycle bin with Gallery | Launcher | 68 MB |
| **Gallery** | 11 (API 30) | All files access, Media management | **Edit** needs Photo Editor from the same release | Launcher | 59 MB |
| **Photo Editor** | 16 (API 36) | All files access, Media management | Gallery | Gallery's **Edit** button (no launcher icon, as on a Galaxy phone) | 87 MB |
| **Video Player** (beta) | 17 (API 37) | All files access | Gallery | Gallery, or any app's **Open with** menu (no launcher icon) | 16 MB |

Each app can be installed on its own. Install Gallery and Photo Editor together if you want to edit photos from Gallery. `install.sh` grants the special access for you; on the phone you can grant it under *Settings → Apps → Special app access*.

### Android versions

| Android | My Files | Gallery | Photo Editor | Video Player (beta) |
|---|---|---|---|---|
| **17** (API 37) | ✅ Tested | ✅ Tested | ✅ Tested | 🧪 Tested, beta |
| **16** (API 36) | ✅ Tested (emulator) | ✅ Installs (emulator) | ✅ Installs (emulator) | ❌ Won't install |
| **14 and 15** (API 34–35) | ✅ Tested (emulator) | ✅ Installs (emulator) | ❌ Won't install | ❌ Won't install |
| **11 to 13** (API 30–33) | ❌ Won't install | ⚠️ Installs, untested | ❌ Won't install | ❌ Won't install |
| **10 or older** | ❌ | ❌ | ❌ | ❌ |

Phones differ in their framework, so an app can crash on one brand and work on another. If you try OnePort on a phone that isn't listed here, please [file a device report](https://github.com/StevenSteel7/OnePort/issues/new?template=device_report.yml), whether it works or not. Each report makes the list above more useful for everyone.

## Install

Download the APKs, `install.sh` and `SHA256SUMS` from the **[latest release](https://github.com/StevenSteel7/OnePort/releases/latest)**. APKs are never committed to the repository itself.

**Verify the download first.** In the folder with the files, run:

```sh
shasum -a 256 -c SHA256SUMS      # macOS
sha256sum -c SHA256SUMS          # Linux
```

Every line should say `OK`. If one doesn't, delete the files and download them again from the official release page only.

### Option A: with a computer (recommended)

You need [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) (`adb`), with **USB debugging** turned on under *Settings → System → Developer options*.

```sh
chmod +x install.sh
./install.sh                    # every app in the folder
./install.sh gallery photoeditor   # or only some
```

The script checks the phone, installs each app, and grants **All files access** and **Media management**. It also explains what to do if something fails.

<details>
<summary><b>Windows (or doing it by hand)</b></summary>

Run these for each APK, in PowerShell or any terminal:

```sh
adb install -r -g SamsungGallery_15.9.00.45_OnePort.apk
adb shell appops set com.sec.android.gallery3d MANAGE_EXTERNAL_STORAGE allow
adb shell appops set com.sec.android.gallery3d MANAGE_MEDIA allow
```

The package names are in [the apps table](#the-apps).
</details>

### Option B: phone only

1. Copy the APKs to the phone and open each one. Allow "Install unknown apps" for your browser or file manager when Android asks.
2. Go to *Settings → Apps → Special app access → **All files access***, and turn it on for each OnePort app.
3. In the same menu, open ***Media management*** and turn it on for Gallery and Photo Editor.
4. Open the apps.

> [!NOTE]
> Without **All files access**, the apps open but can't edit, move, favourite or delete media that another app created. Granting it force-closes the app once. That's normal: just open it again.

## Updating and uninstalling

- **Updating:** install the newer OnePort release over the old one, using `./install.sh` or by opening the APK. Your app data is kept, because every release is signed with the same key.
- **Uninstalling:** do it as for any app, or run `adb uninstall <package>`.
- **Don't install Samsung's official updates** of these apps from APK sites. They aren't patched, they won't run on a non-Samsung phone, and they won't install over OnePort anyway, because they're signed with a different key.

## What works and what doesn't

### ✅ Works

**My Files**
- Browsing and all file operations.
- Recycle bin: move to bin, restore, empty, and the 30-day auto-expiry.

**Gallery**
- Timeline, albums, viewer and video playback.
- Favourites.
- Recycle bin: delete, restore, empty, 30-day expiry.
- **Edit**, which opens Photo Editor and refreshes the photo afterwards.

**Photo Editor**
- Crop, flip and rotate. Rotation is lossless: only the EXIF orientation changes.
- Filters and the Tone panel.
- S Pen drawing, stickers and text.
- Tools: Colour mix, Spot colour and Lasso.
- Save over the original, or save as a copy.
- **Re-edit and Revert** after saving.

**Video Player (beta)**
- Local playback, seeking and the on-screen controls.
- **Picture-in-picture**: press Home during playback, or tap the player's floating-player button. Samsung's own floating window is replaced by Android's built-in PiP.
- A standard media notification while playing.
- Opens from Gallery or any app's **Open with** menu. The app has no launcher icon, as on a Galaxy phone.

### ❌ Doesn't work, and why

| Feature | Status | Why |
|---|---|---|
| **Galaxy AI**: Photo Assist, generative edit, AI eraser | Not included, by choice | Galaxy AI runs on Samsung's servers and is offered only for Galaxy devices with a Samsung account. Enabling it here would mean impersonating a Galaxy phone to reach Samsung's services. OnePort doesn't impersonate Galaxy phones or access Samsung's servers, out of respect for Samsung's terms. |
| Light balance, auto-straighten, background blur, face and segmentation tools | No-op (the buttons do nothing) | They need libraries that are part of Samsung's firmware. |
| Gallery **Stories, People, Scenes** | Empty | They depend on Samsung's on-device vision services. |
| Video: Samsung effects, dynamic viewing, frame interpolation, background music, frame capture | No-op | Samsung-only media framework. |
| Video: Samsung's floating pop-up player and its custom mini-controller notification | Replaced | They need One UI window behaviour, and Android 17 rejects the custom notification layout. System PiP and a standard notification are used instead. |
| Video: auto-pause on incoming call | Off | Needs a privileged phone-state permission. |

### ⚠️ Known issues

- Gallery can show the **old thumbnail** after a photo is edited in *another* app. Edits started from Gallery refresh correctly.
- Gallery **delete occasionally fails**. This was reported once and hasn't been reproduced. If it happens to you, please run `adb logcat -d > fail.log` right away and attach the file to an issue.
- **HEIC photos and very large photos (50 MP+)** are untested in Photo Editor. The editor decodes the preview at full size, so these may be slow or run out of memory.

The full, current list is in [the issue tracker](https://github.com/StevenSteel7/OnePort/issues).

## Caveats (read before installing)

> [!WARNING]
> **Try it on photos you have backed up.** OnePort has been tested carefully, but Gallery and Photo Editor *write to your real photos* (save over original, delete, move to bin). These are Samsung apps running somewhere they were never designed to run. Until you trust the apps on your phone, keep a backup (Google Photos, a PC copy, anything).

- **Unofficial and unsupported by Samsung.** Don't contact Samsung support about these builds. Report problems [here](https://github.com/StevenSteel7/OnePort/issues).
- **Broad permissions.** The apps ask for *All files access* and *Media management*, because on a Samsung phone they're privileged system apps. OnePort adds **no network code, analytics or trackers**. Apart from the patches in this repository, the code is Samsung's original app code as published. You can audit every change, because the patchers and the compatibility code are all here in plain Python and Java.
- **Not signed by Samsung.** The APKs are re-signed with the OnePort key. Only install OnePort APKs from this repository's [Releases](https://github.com/StevenSteel7/OnePort/releases), and check `SHA256SUMS`. An APK with the same package name from anywhere else could be anything.
- **Galaxy AI is deliberately not included.** OnePort doesn't impersonate Galaxy phones or access Samsung's servers, out of respect for Samsung's terms. Feature requests for it will be closed.
- **Some features are gone for good** (see above), because they need Samsung's firmware.
- **No Samsung account or cloud.** Samsung Cloud sync, sharing to Samsung services and similar features won't work.
- **A phone OS update can break things.** A major Android update can, rarely, change something the apps rely on. If an update breaks an app, open an issue. Fixing these is the [maintenance promise](#maintenance-promise).
- **Your phone is not Samsung's test target.** Expect the odd rough edge: a missing animation, a settings item that does nothing, a vibration that doesn't fire.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | A copy signed with a different key is already installed. `adb uninstall <package>` (this deletes that copy's data), then install again. |
| `INSTALL_FAILED_MISSING_SHARED_LIBRARY` | Your phone lacks a library the build expects. Open an issue with the full error line. |
| `INSTALL_FAILED_OLDER_SDK` / "App not installed" | Your Android version is too old. See [Compatibility](#compatibility). |
| App can't delete, edit or favourite photos | Turn on *All files access* (and *Media management*) for it. See [Option B](#option-b-phone-only), steps 2 and 3. |
| App closes right after granting access | Expected: Android restarts an app when its access changes. Open it again. |
| Edit button in Gallery does nothing | Install Photo Editor from the same OnePort release. Both apps must come from OnePort. |
| Crash on launch | Run `adb logcat -b crash -d > crash.log` and attach it to a [bug report](https://github.com/StevenSteel7/OnePort/issues/new?template=bug_report.yml). |

More detail is in [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).

## Maintenance promise

OnePort is actively maintained, and I'm committing to these:

1. **Bugs get triaged.** Every issue gets a response, with a label and a next step, within **7 days**.
2. **Android updates are tracked.** When a new major Android release (or a big update on a tested phone) breaks a port, fixing it is the top priority, ahead of new features.
3. **One signing key, forever.** Every future release is signed with the same OnePort key, so updates always install over the previous version **without losing data**. The key is kept offline and is never committed.
4. **Reproducible from source.** Every release can be rebuilt from this repository plus Samsung's original APKs, which are pinned by SHA-256. The release notes say exactly which Samsung versions were used.
5. **Honest status.** The tables in this README are updated with every release. If something stops working, it's marked here, not hidden.
6. **Newer Samsung versions.** When Samsung ships an app version with something worth having, it gets re-ported and released.

If I ever have to step back, I'll say so clearly at the top of this README and in a pinned issue. I won't let the project go silent, and I'll hand over to a trusted co-maintainer where possible.

## Roadmap

- [ ] **Video Player**: wider testing (codecs, subtitles, Gallery hand-off), then promote from beta to stable
- [ ] Gallery: reproduce and fix the intermittent delete failure
- [ ] Gallery: refresh the thumbnail cache after outside edits
- [ ] Photo Editor: test HEIC and 50 MP+ photos; add a downscaled preview path
- [ ] Device reports: build a compatibility list across brands (Pixel, OnePlus, Xiaomi, Nothing, …)
- [ ] Builds on Linux (currently only macOS is tested)
- [ ] More One UI apps. [Request one](https://github.com/StevenSteel7/OnePort/issues/new?template=app_request.yml).

Release history is in [CHANGELOG.md](CHANGELOG.md).

## Building from source

The short version, on macOS with Android Studio, `apktool` and `python3`:

```sh
scripts/dump_framework.sh                   # once per phone/OS version, with the phone connected
# put Samsung's original APK in ports/<app>/orig/ (the exact version is checked by SHA-256)
scripts/build.sh gallery --repatch --install
```

The full guide covers prerequisites, where to get the exact original APKs, the signing key, and how to port a new app. It's in **[docs/BUILDING.md](docs/BUILDING.md)**.

## Contributing

Device reports, bug reports with logs, and pull requests are all welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Legal

- **Samsung's terms are respected.** Only features that run entirely on your phone are ported. OnePort never impersonates a Galaxy device, and never accesses Samsung's online services (Galaxy AI, Samsung account, Samsung Cloud).
- **Samsung**, **Galaxy** and **One UI** are trademarks of Samsung Electronics Co., Ltd. This project is not affiliated with, sponsored by or endorsed by Samsung.
- **This repository contains no Samsung code.** It contains only original tooling (patchers, build scripts and compatibility code), released under the [MIT License](LICENSE). Samsung's APKs and decompiled sources are deliberately excluded (see `.gitignore`).
- The **release APKs** are modified versions of Samsung's freely distributed apps, provided for **personal, non-commercial interoperability use** on devices Samsung doesn't support. All rights to Samsung's code and assets remain with Samsung. Don't sell them or bundle them into commercial products.
- **Rights holders:** if you'd like something removed, open an issue or contact the maintainer through GitHub. It will be handled promptly and in good faith.
- The software is provided **as is**, without warranty of any kind. See [LICENSE](LICENSE). You install it at your own risk.

---

<div align="center">

Made by [@StevenSteel7](https://github.com/StevenSteel7) · If OnePort is useful to you, a ⭐ helps other people find it.

</div>
