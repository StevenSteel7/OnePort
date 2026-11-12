# OnePort Video Editor

Source-built video editor for Android 12 (API 31) and newer. This implements Gallery's
`com.samsung.app.newtrim/.trimactivity.TrimActivity` contract; it is independent of
Samsung Studio's firmware-dependent engine.

Build with `scripts/build.sh videoeditor [--install]` from the repository root.
The output is `dist/VideoEditor-1.3.0.apk`, signed with the shared OnePort key.
No Samsung APK or framework dump is needed for this app.

## Features and compatibility

- Preview, play/pause, scrubbing, filmstrip and draggable trim handles.
- Rotate in 90-degree steps and remove audio.
- Save a copy beside the source, or replace a MediaStore original after confirmation.
- A launcher icon opens a video picker. Android 12 uses the Storage Access Framework;
  Android 13 and newer use the system photo picker.
- Android 12 uses `onBackPressed`; Android 13 and newer register the platform back callback.
- Replacement requests Android's MediaStore write grant. Media management access can
  suppress the additional system permission dialog; without it Android asks the user.
- Gallery receives the saved URI and refreshes the viewer. Install the matching v1.3.0
  Gallery build for the URI-grant and saved-event compatibility fixes.

## Export behavior

At a keyframe, compressed samples are remuxed losslessly. A trim beginning between
keyframes uses a frame-accurate AVC/HEVC re-encode while copying audio. HDR/10-bit
sources and unavailable codecs fall back to a lossless export beginning at the previous
keyframe. Rotation updates container metadata. WebM can be remuxed, but cannot be rotated.
Replacing writes a temporary export before opening the original for replacement.

Speed, crop, filters, text and stickers are not implemented.

Emulator validation and its exact coverage are recorded in
[the compatibility report](../../docs/VIDEO_COMPATIBILITY.md).
