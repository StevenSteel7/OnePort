#!/bin/zsh
# Relaunch Gallery and print crashes + SecMediaCompat log lines.
PKG=com.sec.android.gallery3d
adb shell setprop log.tag.SecMediaCompat VERBOSE
adb shell am force-stop $PKG; adb logcat -c; adb logcat -b crash -c
adb shell input keyevent KEYCODE_WAKEUP
adb shell am start -n $PKG/com.samsung.android.gallery.app.activity.GalleryActivity >/dev/null
sleep ${1:-8}
echo "pid: $(adb shell pidof $PKG)"
echo "== crash =="; adb logcat -b crash -d | grep -vE "^-----" | grep -E "FATAL|Exception|Error|Caused by|samsungcompat|^.{0,60}at com.samsung" | head -${2:-40}
echo "== SecMedia =="; adb logcat -d -s 'SecMediaCompat:*' | grep -vE "^-----" | cut -c1-400 | head -${3:-60}
