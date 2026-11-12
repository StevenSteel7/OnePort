# Video compatibility tests

Original instrumentation for the ported Video Player and source-based Video Editor.
It contains no Samsung binaries or decompiled sources. The player test refers to
obfuscated class/field names in the pinned Samsung 7.4.00.6 build; update those
references deliberately when changing the original player APK.

Run on disposable Android **12 / API31, 13 / API33, 14 / API34, 15 / API35 and
16 / API36** ARM64 emulators. Android 12L / API32 is optional additional coverage.
Keep at most two lightweight emulators running on a 16 GB host. Never select a
physical device: `run.sh` requires an `emulator-*` serial and verifies
`ro.kernel.qemu=1`. Every command below explicitly selects an emulator.

## Build

From the repository root, using the prerequisites in `docs/BUILDING.md`:

```sh
scripts/build.sh video --repatch
scripts/build.sh videoeditor
scripts/build.sh gallery --repatch  # Needed for the separate Gallery handoff check.
tests/video-compat/harness/build.sh
```

The harness and target apps must use the **same signing key**, because Android
requires that for instrumentation. `harness/build.sh` sources `scripts/env.sh`
and accepts its toolchain/key overrides (`ANDROID_HOME`, `BT`, `JAR`, `KEYSTORE`,
`KS_PASS`). Keys and APKs remain ignored.

## Prepare one emulator

Choose the actual serial printed by `adb devices`; change it for every emulator.
Use the current filenames produced by the build scripts when installing.

```sh
export SERIAL=emulator-5580
case "$SERIAL" in emulator-*) ;; *) echo 'Select an emulator'; exit 1 ;; esac
[ "$(adb -s "$SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || exit 1
adb -s "$SERIAL" shell getprop ro.build.version.sdk
adb -s "$SERIAL" install -r -g dist/Video-1.3.0.apk
adb -s "$SERIAL" install -r -g dist/VideoEditor-1.3.0.apk
adb -s "$SERIAL" install -r tests/video-compat/harness/harness.apk
adb -s "$SERIAL" shell appops set com.samsung.android.video MANAGE_EXTERNAL_STORAGE allow
adb -s "$SERIAL" shell appops set com.samsung.android.video MANAGE_MEDIA allow
adb -s "$SERIAL" shell appops set com.samsung.app.newtrim MANAGE_MEDIA allow
```

Unlock the emulator before testing. The player test marks the emulator's immersive
mode introduction as confirmed so that it does not cover the PiP screenshot.
The editor test confirms its own **Replace**
dialog and Android's **Allow** dialog, so replacement tests must use generated
fixtures only. No user media belongs on these test emulators.

## Generate fixtures

Requires `ffmpeg` and `ffprobe`. These commands create entirely synthetic H.264
video and AAC audio. The editor clip has one keyframe at 0, so moving the start
handle about 30% exercises frame-accurate export rather than keyframe remux.

```sh
mkdir -p tests/video-compat/fixtures
ffmpeg -y -f lavfi -i 'testsrc2=size=640x360:rate=30' \
  -f lavfi -i 'sine=frequency=440:sample_rate=48000' -t 5 \
  -c:v libx264 -preset ultrafast -crf 24 -pix_fmt yuv420p -g 150 -keyint_min 150 -sc_threshold 0 \
  -c:a aac tests/video-compat/fixtures/editor-copy.mp4
cp tests/video-compat/fixtures/editor-copy.mp4 tests/video-compat/fixtures/editor-replace.mp4
ffmpeg -y -f lavfi -i 'testsrc2=size=640x360:rate=30' \
  -f lavfi -i 'sine=frequency=440:sample_rate=48000' -t 20 \
  -c:v libx264 -preset ultrafast -crf 24 -pix_fmt yuv420p -g 150 \
  -c:a aac tests/video-compat/fixtures/player.mp4
adb -s "$SERIAL" shell mkdir -p /sdcard/Movies/OnePortCompat
adb -s "$SERIAL" push tests/video-compat/fixtures/. /sdcard/Movies/OnePortCompat/
for name in editor-copy editor-replace player; do
  adb -s "$SERIAL" shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
    -d "file:///sdcard/Movies/OnePortCompat/$name.mp4"
done
adb -s "$SERIAL" shell "content query --uri content://media/external/video/media --projection _id:_display_name:duration --where \"relative_path='Movies/OnePortCompat/'\""
```

Wait for all three rows to appear. Assign their actual IDs below; never guess
IDs or reuse IDs from another emulator. Restore `editor-replace.mp4` from the
host fixture and scan it again before repeating its test.

## Run and inspect evidence

```sh
# Replace the numbers with this emulator's matching MediaStore IDs.
COPY_URI=content://media/external/video/media/101
REPLACE_URI=content://media/external/video/media/102
PLAYER_URI=content://media/external/video/media/103
tests/video-compat/run.sh "$SERIAL" copy "$COPY_URI"
tests/video-compat/run.sh "$SERIAL" replace "$REPLACE_URI"
tests/video-compat/run.sh "$SERIAL" player "$PLAYER_URI"
```

The script force-stops only the selected target app, runs instrumentation and
saves logs, package/device state and screenshots under ignored
`tests/video-compat/results/api<API>/<mode>/`. Its exit status is nonzero when
instrumentation does not report success. Individual instrumentation commands are:

```sh
adb -s "$SERIAL" shell am instrument -w -e uri "$COPY_URI" -e mode copy oneport.test/.EditorTest
adb -s "$SERIAL" shell am instrument -w -e uri "$REPLACE_URI" -e mode replace oneport.test/.EditorTest
adb -s "$SERIAL" shell am instrument -w -e uri "$PLAYER_URI" oneport.test/.PlayerTest
```

Do not run `uiautomator dump` while instrumentation is active: both require the
same UiAutomation connection. Screenshots are captured directly through UiAutomation into the target app's
external files directory; no shell screenshot/media-scanner command runs during
instrumentation. After completion, screenshots can also be pulled explicitly:

```sh
adb -s "$SERIAL" pull /sdcard/Android/data/com.samsung.app.newtrim/files/oneport-editor.png tests/video-compat/results/
adb -s "$SERIAL" pull /sdcard/Android/data/com.samsung.android.video/files/oneport-player-pip.png tests/video-compat/results/
adb -s "$SERIAL" pull /sdcard/Movies/OnePortCompat/ tests/video-compat/results/output-media/
ffprobe -v error -show_entries format=duration:stream=codec_type,width,height:stream_tags=rotate:stream_side_data=rotation \
  -of json tests/video-compat/results/output-media/editor-copy_edit.mp4
ffprobe -v error -show_entries format=duration:stream=codec_type,width,height \
  -of json tests/video-compat/results/output-media/editor-replace.mp4
```

The copy should be shorter than 5 seconds, rotated 90 degrees and have no audio;
the original `editor-copy.mp4` should remain unchanged. Replacement should retain
its MediaStore ID and audio, have no added rotation and be shorter than 5 seconds.
Confirm those output facts using `ffprobe` and MediaStore queries; instrumentation
success alone verifies UI completion and is not a substitute for checking output.

Repeat the same final APKs and fixtures on each API in the matrix. Save APK
SHA-256 hashes alongside results so that test evidence identifies the exact
builds exercised.

## Coverage and separate manual checks

- Editor: live preview prepares, plays, advances and pauses; scrubbing; start
  trim-handle drag; rotate/mute for copy; save choice; overwrite permission flow;
  export completes and closes the activity. Screenshots capture the edited UI.
- Player: the app's actual MediaPlayer advances; pause, seek and resume; system
  PiP is entered, its window shrinks below 80% of the full width, and playback
  remains active after 2.5 seconds before capture. Pause/seek act directly on
  the app's live player instance; PiP uses the activity API. These checks do **not** prove that every
  on-screen button or Home gesture is wired correctly.
- Manually verify player controls, Home-triggered PiP and the floating-player
  button. Verify both the legacy Back key and supported gesture Back behavior.
- Install the rebuilt Gallery and open only the synthetic test album. Check
  **Gallery → video Edit → Save as copy** moves the viewer to the copy, and
  **Replace original** reloads the same item. This Gallery round trip is outside
  the two instrumentation classes and must be recorded separately.
- Codec/HDR/subtitle coverage beyond the synthetic H.264/AAC clips is separate.
