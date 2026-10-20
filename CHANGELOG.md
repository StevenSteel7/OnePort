# Changelog

All notable changes to OnePort. The format follows [Keep a Changelog](https://keepachangelog.com/), and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.1.0] - 2026-10-20

### Added
- **My Files now runs on Android 14 and 15** (it needed Android 16). The 17 newer platform APIs it calls go through version-checked backports (`ApiCompat`), and three version checks that Samsung's optimiser had stripped (scroll feedback, audio loudness, S Pen high-contrast text) are restored. Tested on Android 14, 15, 16 and 17: browsing, fast scrolling, copy, delete to Trash, restore and search.

### Fixed
- **Gallery failed to install on many phones** with `INSTALL_FAILED_MISSING_SHARED_LIBRARY … libOpenCL.so`. One component declared OpenCL as required, and many GPU drivers don't expose it to apps. Every port is now built with Samsung-only libraries marked optional.
- `install.sh` explains every common install error in plain words. A copy signed by someone else (a stock Samsung APK, another port), which shows on the phone as "package conflicts with an existing package", can now be replaced with `--replace` or after a confirmation.
- `install.sh` checks for a 64-bit ARM phone and honours `ANDROID_SERIAL` when several devices are connected.
- My Files now shows its One UI 8 icon instead of the default Android icon.
- Gallery's *Set as wallpaper* opens the standard Android wallpaper screen; Samsung's Wallpaper and style app isn't available off-Samsung.

### Tested
- Installs verified on Android 14, 15, 16 and 17 emulators (Google Play images), clean and over v1.0.1.
- Photo Editor is now built from the public source exactly; its compat dex no longer carries an unused Galaxy AI loader method.

## [1.0.1] - 2026-10-20

### Fixed
- Gallery now refreshes when images are deleted outside Gallery instead of continuing to show stale entries.
- Gallery delete and Recycle bin operations no longer fail on Android 17 when MediaStore rejects Samsung's transient `is_trashed` update.

## [1.0.0] - 2026-10-19

First public release. Tested on a Motorola Edge 60 Pro (Android 17).

### Added
- **My Files 15.6.03.3:** browsing, file operations, and the Recycle bin (move, restore, empty, 30-day expiry).
- **Gallery 15.9.00.45:**
  - Timeline, albums, viewer, video and favourites.
  - The Recycle bin.
  - The Edit round trip with Photo Editor.
- **Photo Editor 3.9.09.16:**
  - Crop, rotate and flip (lossless), filters, Tone, S Pen drawing, stickers and text.
  - Colour mix, Spot colour and Lasso.
  - Save over the original, or save as a copy.
  - Re-edit and Revert after saving.
- **Video Player 7.4.00.6 (beta):**
  - Local playback, seeking and the on-screen controls.
  - Android system picture-in-picture, from Home or from the player's floating-player button.
  - Fixed: a crash on Android 17 when the playback notification is shown.
  - Fixed: crashes from the pop-up player and from screen-lock handling.
  - Fixed: startup failures on non-Samsung phones.
- `install.sh`: a one-command installer that also grants the access the apps need.
- Build tooling:
  - A generic `scripts/build.sh` that reads each port's `port.conf`.
  - `scripts/dump_framework.sh`.
  - Original APKs pinned by SHA-256.

### Not included
- **Galaxy AI**, deliberately. It runs on Samsung's servers and is offered only for Galaxy devices. OnePort doesn't impersonate Galaxy phones or access Samsung's servers, out of respect for Samsung's terms.

### Known issues
- Light balance, auto-straighten, blur, and Gallery Stories/People/Scenes are unavailable. They need Samsung firmware.
- Gallery's thumbnail can be stale after an edit made in another app.
- An intermittent Gallery delete failure has been reported, but not reproduced.

[Unreleased]: https://github.com/StevenSteel7/OnePort/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/StevenSteel7/OnePort/compare/v1.0.1...v1.1.0
[1.0.1]: https://github.com/StevenSteel7/OnePort/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/StevenSteel7/OnePort/releases/tag/v1.0.0
