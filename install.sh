#!/usr/bin/env bash
# OnePort installer: installs the patched Samsung apps on the phone connected over adb and grants the
# special access they need ("All files access" and "Media management").
#
#   ./install.sh                     install every OnePort APK found next to this script (or in dist/)
#   ./install.sh gallery photoeditor install only the named apps (myfiles, gallery, photoeditor, video)
#   ./install.sh --replace ...       if a copy signed by someone else is installed, uninstall it without asking
#                                    (this deletes that copy's data)
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
minsdk_of() { case $1 in gallery) echo 30 ;; myfiles) echo 34 ;; video) echo 37 ;; *) echo 36 ;; esac; }
android_of() { case $1 in 30) echo 11 ;; 34) echo 14 ;; 35) echo 15 ;; 36) echo 16 ;; 37) echo 17 ;; *) echo "API $1" ;; esac; }
find_apk() { for d in . dist; do for f in $d/$(glob_of "$1"); do [ -f "$f" ] && { echo "$f"; return; }; done; done; }
die() { echo "error: $*" >&2; exit 1; }

replace=0
args=()
for a in "$@"; do case $a in --replace) replace=1 ;; *) args+=("$a") ;; esac; done

command -v adb >/dev/null || die "adb not found. Install Android platform-tools: https://developer.android.com/tools/releases/platform-tools"
n=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
# ANDROID_SERIAL picks one device when several are connected (adb honours it for every command below).
if [ -n "${ANDROID_SERIAL:-}" ]; then
  adb devices | awk 'NR>1 && $2=="device" {print $1}' | grep -qx "$ANDROID_SERIAL" \
    || die "ANDROID_SERIAL=$ANDROID_SERIAL is not a connected, authorised device"
elif [ "$n" != 1 ]; then
  adb devices | awk 'NR>1 && $2=="unauthorized"' | grep -q . \
    && die "the phone hasn't allowed USB debugging yet. Unlock it and accept the 'Allow USB debugging?' prompt."
  die "expected exactly 1 phone in 'adb devices', found $n. Enable USB debugging and accept the prompt on the phone."
fi

prop() { adb shell getprop "$1" | tr -d '\r'; }
maker=$(prop ro.product.manufacturer)
sdk=$(prop ro.build.version.sdk)
abis=$(prop ro.product.cpu.abilist)
echo "Phone: $maker $(prop ro.product.model), Android $(prop ro.build.version.release) (API $sdk), $abis"
if [ "$(echo "$maker" | tr '[:upper:]' '[:lower:]')" = samsung ]; then
  die "this is a Samsung phone. It already has these apps; OnePort builds are for non-Samsung phones."
fi
case ",$abis," in *,arm64-v8a,*) ;; *) die "OnePort builds need a 64-bit ARM (arm64-v8a) phone; this one reports: $abis" ;; esac

# Explain an 'adb install' failure in plain words.
explain() { # $1 = app, $2 = package, $3 = adb output
  case $3 in
    *OLDER_SDK*) echo "   This app needs Android $(android_of "$(minsdk_of "$1")") or newer." ;;
    *NO_MATCHING_ABIS*) echo "   This phone's CPU type isn't supported (OnePort builds are arm64-v8a only)." ;;
    *MISSING_SHARED_LIBRARY*) echo "   The phone lacks a library this build needs. Please open an issue with the line above." ;;
    *SHARED_USER_INCOMPATIBLE*)
      echo "   Another Samsung app from a different source shares this app's user ID. Uninstall other copies of"
      echo "   Samsung Photo Editor / Video Player (not from OnePort), then run this again." ;;
    *DUPLICATE_PERMISSION*|*CONFLICTING_PROVIDER*)
      echo "   Another installed app (usually another port of the same Samsung app) already claims a name this"
      echo "   app needs. The line above names it; uninstall that app, then run this again." ;;
    *INSUFFICIENT_STORAGE*) echo "   Not enough free storage on the phone." ;;
    *USER_RESTRICTED*)
      echo "   The phone blocks installs over USB. On Xiaomi/Redmi/POCO turn on Developer options ->"
      echo "   'Install via USB' (and 'USB debugging (Security settings)'), then run this again." ;;
    *VERIFICATION_FAILURE*|*ABORTED*|*Play\ Protect*)
      echo "   Play Protect stopped the install. Allow it on the phone ('Install anyway'), or turn off"
      echo "   Play Store -> Play Protect -> Settings -> 'Scan apps with Play Protect' while installing." ;;
    *INVALID_APK*|*PARSE_FAILED*|*NO_CERTIFICATES*)
      echo "   The APK file is damaged. Check it against SHA256SUMS and download it again." ;;
  esac
}

# A copy signed with another key (stock Samsung APK, another person's port) blocks the install. Android never
# lets a different signature replace an app, so the only way is to uninstall that copy, which deletes its data.
replace_foreign() { # $1 = package
  echo "   $1 is already installed, signed by someone else (for example a stock Samsung APK or another port)."
  echo "   Android won't let OnePort replace it. Uninstalling it deletes that app's data."
  if [ "$replace" != 1 ]; then
    if [ -t 0 ]; then
      printf "   Uninstall it now and install OnePort's build? [y/N] "
      read -r ans
      case $ans in y|Y|yes|YES) ;; *) echo "   Skipped. To do it later: adb uninstall $1 && ./install.sh"; return 1 ;; esac
    else
      echo "   Run again with --replace to uninstall it, or: adb uninstall $1"; return 1
    fi
  fi
  if ! out=$(adb uninstall "$1" 2>&1) || ! echo "$out" | grep -q Success; then
    echo "   Could not uninstall it ($out). It is probably part of the phone's system image and can't be replaced."
    return 1
  fi
}

wanted=${args[*]:-$apps}
installed=0
for app in $wanted; do
  pkg=$(pkg_of "$app"); [ -n "$pkg" ] || die "unknown app '$app' (choose from: $apps)"
  apk=$(find_apk "$app")
  if [ -z "$apk" ]; then [ ${#args[@]} -gt 0 ] && echo "!! $app: no APK found next to install.sh or in dist/"; continue; fi
  minsdk=$(minsdk_of "$app")
  if [ "$sdk" -lt "$minsdk" ]; then echo "!! $app needs Android $(android_of "$minsdk") or newer; skipped"; continue; fi

  echo "== $app ($pkg) <- $apk"
  if ! out=$(adb install -r -g "$apk" 2>&1); then
    case $out in
      *UPDATE_INCOMPATIBLE*|*signatures\ do\ not\ match*)
        replace_foreign "$pkg" || continue
        out=$(adb install -r -g "$apk" 2>&1) || { echo "$out" | tail -2; explain "$app" "$pkg" "$out"; continue; } ;;
      *)
        echo "$out" | tail -2
        explain "$app" "$pkg" "$out"
        continue ;;
    esac
  fi
  adb shell appops set "$pkg" MANAGE_EXTERNAL_STORAGE allow
  adb shell appops set "$pkg" MANAGE_MEDIA allow 2>/dev/null || true
  installed=$((installed + 1))
done

[ "$installed" -gt 0 ] || die "nothing was installed"
echo "Done: $installed app(s) installed. Open them from the launcher (Video opens from Gallery or 'Open with')."
