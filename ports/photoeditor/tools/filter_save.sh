#!/bin/zsh
# Open pe_test_1.jpg directly, apply filter thumbnail N (default 3), Save, confirm "Save over original" if asked.
H=${0:A:h}; export PKG=com.sec.android.mimage.photoretouching
$H/run.sh "" spe_crop 7 10 | head -2
python3 -I $H/ui.py tap Filters >/dev/null; sleep 3
T=(${(z)$(adb exec-out uiautomator dump /dev/tty 2>/dev/null | grep -oE '<node [^>]*filter_thumbnail_layout[^>]*>' | sed -n ${1:-3}p | grep -oE 'bounds="[^"]*"' | grep -oE '[0-9]+' | tr '\n' ' ')})
(( ${#T} == 4 )) || { echo "filter strip not found"; exit 1; }
adb shell dumpsys activity activities | grep -q "topResumedActivity.*$PKG" || exit 1
adb shell input tap $(( (T[1]+T[3])/2 )) $(( (T[2]+T[4])/2 )); sleep 3
python3 -I $H/ui.py tap Save >/dev/null; sleep 2.5
D=(${(z)$(adb exec-out uiautomator dump /dev/tty 2>/dev/null | grep -oE '<node [^>]*resource-id="android:id/button1"[^>]*>' | grep -oE 'bounds="[^"]*"' | grep -oE '[0-9]+' | tr '\n' ' ')})
(( ${#D} == 4 )) && adb shell dumpsys activity activities | grep -q "topResumedActivity.*$PKG" && adb shell input tap $(( (D[1]+D[3])/2 )) $(( (D[2]+D[4])/2 ))
sleep 8
echo "top: $(adb shell dumpsys activity activities | grep -m1 topResumedActivity | sed 's/.*{//')"
adb logcat -b crash -d | grep -E "FATAL|Exception|Caused|	at " | head -10
