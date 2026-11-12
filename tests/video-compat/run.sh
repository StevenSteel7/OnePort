#!/bin/bash
# Run only against an explicitly selected emulator, never a physical device.
set -euo pipefail
if [ "$#" -lt 3 ] || [ "$#" -gt 4 ]; then
  echo 'usage: run.sh emulator-SERIAL player|copy|replace content://media/external/video/media/ID [results-directory]' >&2
  exit 2
fi
serial=$1
mode=$2
uri=$3
case "$serial" in emulator-[0-9]*) ;; *) echo 'An explicit emulator serial is required; physical devices are refused.' >&2; exit 2 ;; esac
[[ "$uri" =~ ^content://media/(external|external_primary)/video/media/[0-9]+$ ]] || { echo 'Expected a MediaStore video URI for a generated test fixture.' >&2; exit 2; }
[ "$(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || { echo 'Target is not an Android emulator.' >&2; exit 2; }
api=$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')
case "$api" in 31|32|33|34|35|36) ;; *) echo "Unsupported test API: $api" >&2; exit 2 ;; esac
case "$mode" in
  player) package=com.samsung.android.video; runner=PlayerTest; screenshot=oneport-player-pip.png ;;
  copy|replace) package=com.samsung.app.newtrim; runner=EditorTest; screenshot=oneport-editor.png ;;
  *) echo "Unknown test mode: $mode" >&2; exit 2 ;;
esac
here=$(cd "$(dirname "$0")" && pwd)
results=${4:-"$here/results/api$api/$mode"}
mkdir -p "$results"
adb -s "$serial" shell getprop > "$results/getprop.txt"
adb -s "$serial" shell dumpsys package "$package" > "$results/package.txt"
adb -s "$serial" shell am force-stop "$package"
adb -s "$serial" shell rm -f "/sdcard/Android/data/$package/files/$screenshot"
adb -s "$serial" logcat -c
adb -s "$serial" shell am instrument -w -e uri "$uri" -e mode "$mode" "oneport.test/.$runner" | tee "$results/instrumentation.txt"
adb -s "$serial" logcat -d > "$results/logcat.txt"
adb -s "$serial" logcat -b crash -d > "$results/crashes.txt"
adb -s "$serial" shell dumpsys media_session > "$results/media-session.txt"
adb -s "$serial" pull "/sdcard/Android/data/$package/files/$screenshot" "$results/$screenshot" || true
if ! grep -q SUCCESS "$results/instrumentation.txt" || grep -q 'FAIL ' "$results/instrumentation.txt"; then
  echo "Test failed; inspect $results/instrumentation.txt and logcat.txt" >&2
  exit 1
fi
echo "Test passed; evidence: $results"
