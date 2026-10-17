#!/bin/zsh
# Gallery -> Edit round trip on the throwaway pe_test_1.jpg ONLY.
# Opens the viewer by media id (VIEW intent, no album navigation / coordinate taps) and refuses to tap Edit unless
# Gallery's log shows that exact id loaded in the viewer.
H=${0:A:h}; G=com.sec.android.gallery3d
ID=$(adb shell "content query --uri content://media/external/images/media --projection _id --where \"_display_name='pe_test_1.jpg' AND relative_path LIKE 'Pictures/CompatTest%'\"" | sed -nE 's/.*_id=([0-9]+).*/\1/p' | head -1)
[[ -z $ID ]] && { echo "test image missing"; exit 1; }
adb shell am force-stop $G; adb shell am force-stop com.sec.android.mimage.photoretouching; adb logcat -c; adb logcat -b crash -c
adb shell input keyevent KEYCODE_WAKEUP
adb shell am start -a android.intent.action.VIEW -t image/jpeg -d content://media/external/images/media/$ID -n $G/com.samsung.android.gallery.app.activity.external.GalleryExternalActivity >/dev/null 2>&1 \
  || adb shell am start -a android.intent.action.VIEW -t image/jpeg -d content://media/external/images/media/$ID -p $G >/dev/null
for i in {1..8}; do sleep 1.5; adb logcat -d | grep -qE "publishBitmap \{bitmap/viewer/[^,]*,[^,]*,$ID," && break; done
adb logcat -d | grep -qE "publishBitmap \{bitmap/viewer/[^,]*,[^,]*,$ID," || { echo "viewer did not load id $ID - refusing to tap Edit"; exit 1; }
echo "viewer shows test id $ID"
PKG=$G python3 -I $H/ui.py tap Edit || exit 1
sleep 6
adb logcat -d | grep -E "StartSimplePhotoEditorCmd" | cut -c19-200
adb logcat -d | grep -qE "StartSimplePhotoEditorCmd S$ID\b" || { echo "editor started on a different item - abort"; adb shell am force-stop com.sec.android.mimage.photoretouching; exit 1; }
echo "top: $(adb shell dumpsys activity activities | grep -m1 topResumedActivity | sed 's/.*{//')"
adb logcat -b crash -d | grep -E "FATAL|Exception|Caused|	at " | head -10
