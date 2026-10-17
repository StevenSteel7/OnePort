#!/bin/zsh
# Restore the throwaway test image and rescan it.
H=${0:A:h}
adb push $H/../../../testmedia/compat_test_photo.jpg /sdcard/Pictures/CompatTest/pe_test_1.jpg >/dev/null
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/CompatTest/pe_test_1.jpg >/dev/null
sleep 2
