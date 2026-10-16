#!/bin/zsh
# Repeated Gallery viewer delete on throwaway del_test_*.jpg in CompatTest. Refuses to confirm unless the
# DeleteSingleCmd target id belongs to a del_test_* file.   tools/del_test.sh [rounds]
H=${0:A:h}; export PKG=com.sec.android.gallery3d
for r in $(seq ${1:-5}); do
  for i in 1 2 3; do
    adb shell "[ -f /sdcard/Pictures/CompatTest/del_test_$i.jpg ]" || { adb push $H/../../../testmedia/compat_test_photo.jpg /sdcard/Pictures/CompatTest/del_test_$i.jpg >/dev/null;
      adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/CompatTest/del_test_$i.jpg >/dev/null; }
  done
  sleep 2
  IDS=$(adb shell "content query --uri content://media/external/images/media --projection _id --where \"_display_name LIKE 'del_test_%' AND relative_path LIKE 'Pictures/CompatTest%'\"" | grep -oE "_id=[0-9]+" | cut -d= -f2 | tr '\n' ' ')
  adb logcat -c
  $H/open_test.sh 1 >/dev/null 2>&1 || { echo "r$r: open failed"; continue; }
  python3 -I $H/ui.py tap Delete >/dev/null; sleep 2
  T=$(adb logcat -d | grep -oE "execute DeleteSingleCmd S[0-9]+" | tail -1 | grep -oE "[0-9]+$")
  [[ " $IDS " == *" $T "* ]] || { echo "r$r: target $T not a test id ($IDS) - cancel"; python3 -I $H/ui.py tap Cancel >/dev/null; continue; }
  NAME=$(adb shell "content query --uri content://media/external/images/media --projection _display_name --where \"_id=$T\"" | grep -oE "del_test_[0-9].jpg")
  python3 -I $H/ui.py tap "Move to Recycle bin" >/dev/null; sleep 5
  ROW=$(adb shell "content query --uri content://media/external/images/media --projection _id --where \"_id=$T\"" | grep -c _id)
  FILE=$(adb shell "[ -f /sdcard/Pictures/CompatTest/$NAME ] && echo present || echo gone")
  ERR=$(adb logcat -d | grep -E "TrashDeleteHelper|DeleteSingleCmd|MpUTrashFactory|TrashHelper" | grep -iE "fail|unable|rollback|abnormal|exception" | cut -c19-200 | head -3)
  CR=$(adb logcat -b crash -d | grep -m1 -E "FATAL|Exception")
  echo "r$r: $NAME id=$T  mediastore_row=$ROW file=$FILE ${ERR:+ERR: $ERR} ${CR:+CRASH: $CR}"
  [[ $ROW != 0 || $FILE != gone || -n $ERR || -n $CR ]] && adb logcat -d > $H/../../../.shots/del_fail_r$r.log
done
