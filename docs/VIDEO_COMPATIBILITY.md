# Video compatibility validation

2026-11-12. OnePort v1.3.0. Changes are based
on commit `767bf3e` (v1.2.1). The final player build was rechecked on every version in the matrix.

## Emulator matrix

ARM64 Android emulators. The release player and Gallery APKs are byte-identical to the tested builds, with v1.3.0 filenames. The editor release changes only its version metadata (versionName 1.3.0, versionCode 10300); its executable DEX, resources and every other unsigned payload entry are byte-identical to the tested 1.0.1 build.

| Android | API | Install both apps | Player and Settings | Editor copy | Editor replace |
|---|---:|---|---|---|---|
| 12 | 31 | Pass | Pass | Pass | Pass |
| 13 | 33 | Pass | Pass | Pass | Pass |
| 14 | 34 | Pass | Pass | Pass | Pass |
| 15 | 35 | Pass | Pass | Pass | Pass |
| 16 | 36 | Pass | Pass | Pass | Pass |

Player checks use the app's live MediaPlayer: position advances, pause, seek to
2 seconds, resume, enter system PiP, resize to a floating window, and continue
playing after 2.5 seconds. Settings is separately opened, its accessibility tree
queried, the list swiped, and Back exercised. The installed player APK is pulled
back and its SHA-256 checked against the final build.

Editor checks exercise preview playback/pause/scrubbing, dragging the trim handle,
Rotate and Mute, the Save choice, replacement confirmation and Android's write
permission flow. Five-second H.264/AAC fixtures with only an initial keyframe force
frame-accurate re-encoding when trimmed from approximately 1.6 seconds. Outputs are
pulled and checked with `ffprobe`; frame decoding is additionally checked where
recorded in the evidence. Copy exports have 101 frames (~3.367 seconds), a
90-degree rotation and no audio, and preserve the source hash. Replacements keep
the source MediaStore ID and audio, with the expected shortened duration.

Gallery's real UI round trip was separately verified on Android 13:
**Gallery grid → video → Edit → Rotate → Save as copy → Gallery viewer**.
Gallery received the saved URI and logged `saveAsCopy`. The Gallery replacement
round trip was not separately exercised; editor replacement itself passed on all
five versions.

## Compatibility fixes

- Lower the player's manifest and DEX minimum to API 31, with version-aware
  backports for newer Java APIs, package queries, permissions, text layout,
  scrolling, insets, parcels and accessibility properties.
- Restore legacy Back below API 33 and predictive-back callbacks on API 34+.
- Backport `ExecutorService.close`, which otherwise crashed playback preparation
  on older Android, and `WindowInsets.Type.systemOverlays`, absent before API 34.
- Skip the optional guiding-light shader on Android 12, where its public shader
  constructor is unavailable. Skip shader-based list fading below API 33 and use
  equivalent PorterDuff transfer modes before API 36.
- Restore AndroidX fallbacks for five newer accessibility actions. Without these,
  querying the Settings accessibility tree crashed on Android 12.
- Track the source-built editor in `ports/videoeditor`, build it with the shared
  toolchain/key, and support it in `install.sh`.
- Include Gallery's read-only editor URI grants and saved-event/media-mirror
  refresh fixes so its video Edit button can complete the copy handoff.

The original player remains beta. Samsung-specific media effects and other
firmware-dependent features retain their existing limitations.

## Artifacts

| APK | Minimum API | SHA-256 |
|---|---:|---|
| `Video-1.3.0.apk` | 31 | `391e0ae5dc0bc2bfd470e6ab3c8696d4ca2ca09c0cacffcd7efe007e0578b130` |
| `VideoEditor-1.3.0.apk` | 31 | `3010585ea72075c5c9669191a49b3dbbb9234aeb391331b0f904d55908ed41fd` |
| `Gallery-1.3.0.apk` | 30 | `4821c8891f706aa975539029115b3ea39c85a60cb038fdb83e4f247e94d17845` |

The APKs use the existing OnePort signing certificate, SHA-256
`29ff51e5faebc9d0f89d6aa35855f0a9dba1d434d6f2762ef3b18c6a7716b680`.
The player manifest was independently inspected with REA/JADX and its signature
verified with `apksigner`. Its Samsung target SDK remains 37; its minimum is 31.

## Evidence, reproduction and limits

- Reproducible source and instructions: [tests/video-compat](../tests/video-compat/README.md).
- Local logs, screenshots, installed APK hashes, exported clips and metadata:
  `test-results/android12-16/api31`, `api33`, `api34`, `api35`, and `api36`.
  Large evidence files are ignored by Git; this report and the harness are source files.
- A 16 GB Mac ran at most two emulators at once, with 2–3 GB guest RAM. Initial
  emulator System UI, Messages, Google Play Services and Digital Wellbeing ANR
  dialogs were diagnosed separately from OnePort crashes. Android 16 copy export
  was completed on a fresh isolated API 36 AVD with hardware GPU rendering;
  replacement also passed on the original API 36 AVD after dismissing its system
  ANR dialog. Existing AVDs were preserved.
- No physical device was used for these tests.
- This validates the recorded H.264/AAC flows. HEVC/HDR, WebM, subtitles, large
  files, every on-screen player button, Home-triggered PiP and all phone brands
  are outside this matrix. Player pause/seek use its actual engine; PiP uses the
  activity API. Android 12L/API 32 and Android 17/API 37 were not rerun here.
