#!/bin/zsh
# Open pe_test_1.jpg (CompatTest, throwaway) in the editor, tap the given tool buttons, Save over original, report.
#   tools/save_test.sh Rotate [more content-desc...]
H=${0:A:h}; export PKG=com.sec.android.mimage.photoretouching
$H/run.sh "" spe_crop 7 10 | head -2
for b in "$@"; do python3 -I $H/ui.py tap "$b"; sleep 2.5; done
adb exec-out screencap -p > $H/../../../.shots/save_test_before.png
python3 -I $H/ui.py tap Save; sleep 2.5
B=(${(z)$(adb exec-out uiautomator dump /dev/tty 2>/dev/null | grep -oE '<node [^>]*resource-id="android:id/button1"[^>]*>' | grep -oE 'bounds="[^"]*"' | grep -oE '[0-9]+' | tr '\n' ' ')})
if (( ${#B} == 4 )) && adb shell dumpsys activity activities | grep -q "topResumedActivity.*$PKG"; then
  adb shell input tap $(( (B[1]+B[3])/2 )) $(( (B[2]+B[4])/2 ))
else echo "no confirm dialog"; fi
sleep 7
echo "top: $(adb shell dumpsys activity activities | grep -m1 topResumedActivity | sed 's/.*{//')"
echo "== crash =="; adb logcat -b crash -d | grep -E "FATAL|Exception|Caused|	at " | head -12
echo "== compat =="; adb logcat -d | grep -E "PeMediaCompat|SumCompat|SPE_UniImgpEncoder" | cut -c19-260 | tail -15
adb shell "content query --uri content://media/external/images/media --projection _id:orientation:_size:date_modified --where \"_display_name='pe_test_1.jpg'\""
adb pull /sdcard/Pictures/CompatTest/pe_test_1.jpg $H/../../../.shots/pe_test_1_saved.jpg >/dev/null
