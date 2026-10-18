#!/usr/bin/env bash
# OnePort installer: installs the patched Samsung apps on the phone connected over adb and grants the
# special access they need ("All files access" and "Media management").
#
#   ./install.sh                     install every OnePort APK found next to this script (or in dist/)
#   ./install.sh gallery photoeditor install only the named apps (myfiles, gallery, photoeditor, video)
#
# Reinstalling a newer OnePort release keeps your app data. Works on macOS and Linux (bash 3.2+).
set -euo pipefail
cd "$(dirname "$0")"

apps="myfiles gallery photoeditor video"
pkg_of() { case $1 in
  myfiles) echo com.sec.android.app.myfiles ;;
  gallery) echo com.sec.android.gallery3d ;;
  photoeditor) echo com.sec.android.mimage.photoretouching ;;
  video) echo com.samsung.android.video ;;
esac; }
glob_of() { case $1 in
  myfiles) echo 'SamsungMyFiles_*_OnePort.apk' ;;
  gallery) echo 'SamsungGallery_*_OnePort.apk' ;;
  photoeditor) echo 'SamsungPhotoEditor_*_OnePort.apk' ;;
  video) echo 'SamsungVideo_*_OnePort.apk' ;;
esac; }
find_apk() { for d in . dist; do for f in $d/$(glob_of "$1"); do [ -f "$f" ] && { echo "$f"; return; }; done; done; }
die() { echo "error: $*" >&2; exit 1; }

command -v adb >/dev/null || die "adb not found. Install Android platform-tools: https://developer.android.com/tools/releases/platform-tools"
n=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
[ "$n" = 1 ] || die "expected exactly 1 phone in 'adb devices', found $n. Enable USB debugging and accept the prompt on the phone."

maker=$(adb shell getprop ro.product.manufacturer | tr -d '\r')
sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
echo "Phone: $maker $(adb shell getprop ro.product.model | tr -d '\r'), Android $(adb shell getprop ro.build.version.release | tr -d '\r') (API $sdk)"
if [ "$(echo "$maker" | tr '[:upper:]' '[:lower:]')" = samsung ]; then
  die "this is a Samsung phone. It already has these apps; OnePort builds are for non-Samsung phones."
fi

wanted=${*:-$apps}
installed=0
for app in $wanted; do
  pkg=$(pkg_of "$app"); [ -n "$pkg" ] || die "unknown app '$app' (choose from: $apps)"
  apk=$(find_apk "$app")
  if [ -z "$apk" ]; then [ $# -gt 0 ] && echo "!! $app: no APK found next to install.sh or in dist/"; continue; fi
  minsdk=36; [ "$app" = gallery ] && minsdk=30; [ "$app" = video ] && minsdk=37
  if [ "$sdk" -lt "$minsdk" ]; then echo "!! $app needs API $minsdk or newer; skipped"; continue; fi

  echo "== $app ($pkg) <- $apk"
  if ! out=$(adb install -r -g "$apk" 2>&1); then
    echo "$out" | tail -2
    case $out in
      *UPDATE_INCOMPATIBLE*|*signatures\ do\ not\ match*)
        echo "   A copy signed with a different key is installed. Uninstalling it deletes that app's data:"
        echo "   adb uninstall $pkg && ./install.sh $app" ;;
      *MISSING_SHARED_LIBRARY*) echo "   Your phone lacks a library this build needs. Please open an issue with the line above." ;;
    esac
    continue
  fi
  adb shell appops set "$pkg" MANAGE_EXTERNAL_STORAGE allow
  adb shell appops set "$pkg" MANAGE_MEDIA allow 2>/dev/null || true
  installed=$((installed + 1))
done

[ "$installed" -gt 0 ] || die "nothing was installed"
echo "Done: $installed app(s) installed. Open them from the launcher (Video opens from Gallery or 'Open with')."
