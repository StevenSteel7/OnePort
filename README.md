# OnePort

Samsung One UI apps, patched to run on non-Samsung Android phones.

> Work in progress. First release coming soon.

## Progress

- My Files 15.6.03.3: browsing and file operations.
- My Files: Recycle bin (move, restore, empty, 30-day expiry).
- A generic `scripts/build.sh` that reads each port's `port.conf`; original APKs pinned by SHA-256.
- Gallery 15.9.00.45: timeline, albums, viewer, video and favourites.
- Gallery: the Recycle bin, shared with My Files.
- Photo Editor 3.9.09.16: crop, rotate and flip (lossless), filters, Tone, S Pen drawing, stickers and text.
- Photo Editor: Colour mix, Spot colour and Lasso; save over the original or as a copy.
- Photo Editor: Re-edit and Revert after saving.
- Gallery: the Edit round trip with Photo Editor.
- Galaxy AI is deliberately not included: it runs on Samsung's servers for Galaxy devices only, and OnePort doesn't impersonate Galaxy phones or access Samsung's services.
- Video Player 7.4.00.6 (beta): local playback, seeking and the on-screen controls.
- Video Player: Android system picture-in-picture; fixes for startup and playback crashes on non-Samsung phones.
- `install.sh`: a one-command installer that also grants the access the apps need.

## Compatibility

### General requirements

| | |
|---|---|
| **Phone** | Any non-Samsung Android phone with a 64-bit ARM processor (arm64-v8a) |
| **Not supported** | Samsung phones (they already have these apps), 32-bit-only phones and x86 devices |
| **Not needed** | Root, a custom ROM, or a Samsung account |
| **To install** | A computer with `adb` (recommended), or install the APKs directly on the phone |
| **Tested on** | Motorola Edge 60 Pro, Android 17 (API 37), MediaTek Dimensity 8300 |

### Requirements per app

| App | Minimum Android | Special access to grant | Works with | Opens from | Size |
|---|---|---|---|---|---|
| **My Files** | 16 (API 36) | All files access | Shares the Recycle bin with Gallery | Launcher | 68 MB |
| **Gallery** | 11 (API 30) | All files access, Media management | **Edit** needs Photo Editor from the same release | Launcher | 59 MB |
| **Photo Editor** | 16 (API 36) | All files access, Media management | Gallery | Gallery's **Edit** button (no launcher icon, as on a Galaxy phone) | 87 MB |
| **Video Player** (beta) | 17 (API 37) | All files access | Gallery | Gallery, or any app's **Open with** menu (no launcher icon) | 16 MB |

Each app can be installed on its own. Install Gallery and Photo Editor together if you want to edit photos from Gallery. `install.sh` grants the special access for you; on the phone you can grant it under *Settings → Apps → Special app access*.

### Android versions

| Android | My Files | Gallery | Photo Editor | Video Player (beta) |
|---|---|---|---|---|
| **17** (API 37) | ✅ Tested | ✅ Tested | ✅ Tested | 🧪 Tested, beta |
| **16** (API 36) | Should work, untested | Should work, untested | Should work, untested | ❌ Won't install |
| **11 to 15** (API 30–35) | ❌ Won't install | ⚠️ Installs, untested | ❌ Won't install | ❌ Won't install |
| **10 or older** | ❌ | ❌ | ❌ | ❌ |
