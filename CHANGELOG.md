# Changelog

All notable changes to OnePort. The format follows [Keep a Changelog](https://keepachangelog.com/), and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

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

[Unreleased]: https://github.com/StevenSteel7/OnePort/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/StevenSteel7/OnePort/releases/tag/v1.0.0
