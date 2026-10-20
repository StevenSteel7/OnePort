#!/bin/zsh
# Build one OnePort app.
#   scripts/build.sh <port> [--repatch] [--install]
#     <port>      a folder in ports/ (myfiles, gallery, photoeditor, video)
#     --repatch   decode the original APK again and re-run the patchers (needed after changing tools/*.py)
#     --install   install the result over adb and grant its app-ops
#
# Only the dex files (and, where a port needs it, a binary-patched manifest) are swapped into the original APK.
# Resources are never rebuilt: Samsung's obfuscated resource names differ only by case and break on case-insensitive
# filesystems (macOS APFS).
set -e
source ${0:A:h}/env.sh

PORT=$1; shift || true
[[ -n $PORT && -f $ONEPORT/ports/$PORT/port.conf ]] || die "usage: scripts/build.sh <$(ls $ONEPORT/ports | tr '\n' '|' | sed 's/|$//')> [--repatch] [--install]"
for a in "$@"; do case $a in --repatch) REPATCH=1;; --install) INSTALL=1;; *) die "unknown option $a";; esac; done

P=$ONEPORT/ports/$PORT
PERMS=() OPTIONAL_LIBS=1 APP_ICON= MIN_SDK= APP_PATCH= EXTRA_SRC=()
source $P/port.conf
cd $P

need apktool python3 javac zip unzip
[[ -x $BT/d8 ]] || die "Android build-tools not found (ANDROID_HOME=$ANDROID_HOME)"
[[ -f $JAR ]] || die "android.jar not found (install an Android SDK platform)"
[[ -f $KEYSTORE ]] || die "no signing key at $KEYSTORE (see docs/BUILDING.md#signing-key)"

ORIG=(orig/*.apk(N))
(( ${#ORIG} == 1 )) || die "put exactly one original APK in ports/$PORT/orig/ ($ORIG_NAME, SHA-256 $ORIG_SHA256)"
ORIG=$ORIG[1]
SUM=$(shasum -a 256 $ORIG | cut -d' ' -f1)
[[ $SUM == $ORIG_SHA256 ]] || die "$ORIG has SHA-256 $SUM; this port expects $ORIG_NAME ($ORIG_SHA256)"

if (( REPATCH )) || [[ ! -d work ]]; then
  [[ -f $FW ]] || die "framework dump missing: run scripts/dump_framework.sh with the phone connected"
  rm -rf work
  apktool d -r -f -o work $ORIG >/dev/null
  python3 -I tools/patch.py $FW work
  [[ -n $APP_PATCH ]] && python3 -I tools/$APP_PATCH work
  (( $+functions[post_patch] )) && post_patch
fi

rm -rf .dex build_unsigned.apk aligned.apk && mkdir -p .dex/javac .dex/d8 $DIST
apktool b work -o .dex/smali_build.apk >/dev/null
unzip -q .dex/smali_build.apk 'classes*.dex' -d .dex
# compat_src (hand-written Java: CR, SecMedia, Trash, ...) -> one extra classesN.dex
javac -nowarn --release 17 -cp $JAR -d .dex/javac $(find compat_src $EXTRA_SRC -name '*.java')
$BT/d8 --min-api $MINAPI --lib $JAR --output .dex/d8 $(find .dex/javac -name '*.class')
mv .dex/d8/classes.dex .dex/classes$(( $(ls .dex/classes*.dex | wc -l) + 1 )).dex

cp $ORIG build_unsigned.apk
zip -q -d build_unsigned.apk 'META-INF/*' 'classes*.dex'
# Binary manifest edits (resources are never rebuilt). Tools live in scripts/manifest/.
M=$ONEPORT/scripts/manifest
unzip -q -o $ORIG AndroidManifest.xml -d .dex/m
cp .dex/m/AndroidManifest.xml .dex/m/step.xml
if (( ${#PERMS} )); then
  python3 -I $M/axml_add_perm.py .dex/m/step.xml .dex/m/next.xml $PERMS >/dev/null && mv .dex/m/next.xml .dex/m/step.xml
fi
if (( OPTIONAL_LIBS )); then
  # A Samsung-only <uses-library>/<uses-native-library> marked required="true" (Gallery's libOpenCL.so) fails with
  # INSTALL_FAILED_MISSING_SHARED_LIBRARY on phones that don't expose it. Every port is built with this by default.
  python3 -I $M/axml_optional_libs.py .dex/m/step.xml .dex/m/next.xml >/dev/null && mv .dex/m/next.xml .dex/m/step.xml
fi
if [[ -n $APP_ICON ]]; then
  # Samsung leaves android:icon out where One UI themes the launcher icon; elsewhere that shows the default robot.
  python3 -I $M/axml_add_attr.py .dex/m/step.xml .dex/m/next.xml application icon 0x01010002 $APP_ICON >/dev/null \
    && mv .dex/m/next.xml .dex/m/step.xml
fi
if [[ -n $MIN_SDK ]]; then
  # Run on older Android than Samsung's build targets; the port backports the newer APIs it calls.
  python3 -I $M/axml_set_minsdk.py .dex/m/step.xml .dex/m/next.xml $MIN_SDK >/dev/null && mv .dex/m/next.xml .dex/m/step.xml
fi
if ! cmp -s .dex/m/step.xml .dex/m/AndroidManifest.xml; then
  cp .dex/m/step.xml .dex/AndroidManifest.xml
  (cd .dex && zip -q ../build_unsigned.apk AndroidManifest.xml)
fi
(cd .dex && zip -q -0 ../build_unsigned.apk classes*.dex)
$BT/zipalign -f -P 16 4 build_unsigned.apk aligned.apk
$BT/apksigner sign --ks $KEYSTORE --ks-pass pass:$KS_PASS --out $DIST/$OUT aligned.apk 2>/dev/null
rm -rf .dex build_unsigned.apk aligned.apk $DIST/$OUT.idsig
echo "built dist/$OUT"

if (( INSTALL )); then
  adb install -r -g $DIST/$OUT
  for p in $PERMS MANAGE_EXTERNAL_STORAGE MANAGE_MEDIA; do
    adb shell appops set $PKG ${p#android.permission.} allow 2>/dev/null || true
  done
fi
