#!/bin/zsh
# Relaunch the Photo Editor on a CompatTest image the way Gallery does, then print crashes.
#   tools/run.sh [media_id] [service] [wait_s]
PKG=com.sec.android.mimage.photoretouching
ID=${1:-$(adb shell "content query --uri content://media/external/images/media --projection _id --where \"_display_name='pe_test_1.jpg'\"" | sed -nE 's/.*_id=([0-9]+).*/\1/p' | head -1)}
adb shell am force-stop $PKG; adb logcat -c; adb logcat -b crash -c
adb shell input keyevent KEYCODE_WAKEUP
adb shell am start -n $PKG/.SPEActivity \
  --eu filepath content://media/external/images/media/$ID --es service ${2:-spe_crop} \
  --ez need_result true --ez isFromGalleryDetails true --grant-read-uri-permission --grant-write-uri-permission >/dev/null
sleep ${3:-6}
echo "pid: $(adb shell pidof $PKG)  top: $(adb shell dumpsys activity activities | grep -m1 topResumedActivity | sed 's/.*{//')"
echo "== crash =="; adb logcat -b crash -d | grep -vE "^-----" | grep -E "FATAL|Exception|Error|Caused by|samsungcompat|^.{0,60}at (com\.s|android\.app)" | head -${4:-40}
echo "== native/link =="; adb logcat -d | grep -E "UnsatisfiedLink|dlopen failed|DEBUG.*(signal|Abort|backtrace|#0[0-5])|NoClassDefFound|NoSuchMethod" | cut -c1-300 | head -20
