#!/bin/zsh
# Relaunch Gallery and open item N (1-based, default 2) of the CompatTest album in the viewer.
H=${0:A:h}
adb shell am force-stop com.sec.android.gallery3d; sleep 1
adb shell am start -n com.sec.android.gallery3d/com.samsung.android.gallery.app.activity.GalleryActivity >/dev/null
for i in 1 2 3 4 5 6; do sleep 1.5; python3 -I $H/ui.py find "Albums" >/dev/null && break; done
python3 -I $H/ui.py tap "Albums" >/dev/null || exit 1
ok=0; for i in 1 2 3 4; do sleep 1.5; python3 -I $H/ui.py tap "CompatTest" >/dev/null && { ok=1; break; }; done
(( ok )) || { echo "CompatTest album not found - refusing to tap blindly"; exit 1; }
for i in 1 2 3 4; do sleep 1.5; adb exec-out uiautomator dump /dev/tty 2>/dev/null | grep -q 'content-desc="Button"' && break; done
x=$(( 153 + (${1:-2} - 1) * 304 )); adb shell input tap $x 461; sleep 2.5
