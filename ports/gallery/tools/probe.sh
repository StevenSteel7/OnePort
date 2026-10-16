#!/bin/zsh
# Run SQL against Gallery's in-process secmedia mirror: tools/probe.sh "select ..."
# (executed on the app's next secmedia query; switching tabs triggers one)
D=/sdcard/Android/data/com.sec.android.gallery3d/files
adb shell mkdir -p $D
print -r -- "$1" > "${TMPDIR:-/tmp}/probe.sql"
adb push "${TMPDIR:-/tmp}/probe.sql" $D/probe.sql >/dev/null
adb logcat -c
for t in "Albums" "Pictures"; do python3 -I ${0:A:h}/ui.py tap "$t" >/dev/null; sleep 1.5; done
adb logcat -d -v raw -s 'SecMediaCompat:*' | grep PROBE
