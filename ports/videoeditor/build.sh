#!/bin/zsh
# Build the source-based Gallery video editor for Android 12 and newer.
set -e
source ${0:A:h:h:h}/scripts/env.sh
cd ${0:A:h}
OUT=VideoEditor-1.3.0.apk
INSTALL=0
for arg in "$@"; do
  case $arg in
    --install) INSTALL=1 ;;
    --repatch) ;; # Source is always rebuilt from scratch.
    *) die "unknown option $arg" ;;
  esac
done
need javac zip
[[ -f $KEYSTORE ]] || die "no signing key at $KEYSTORE"
mkdir -p $DIST
rm -rf .build
mkdir -p .build/classes .build/dex
trap 'rm -rf .build' EXIT
$BT/aapt2 compile --dir res -o .build/res.zip
$BT/aapt2 link -o .build/base.apk -I $JAR --manifest AndroidManifest.xml \
  --min-sdk-version 31 --target-sdk-version 36 --java .build/gen .build/res.zip
javac -nowarn --release 17 -cp $JAR -d .build/classes $(find src .build/gen -name '*.java')
$BT/d8 --release --min-api 31 --lib $JAR --output .build/dex $(find .build/classes -name '*.class')
cp .build/base.apk .build/unsigned.apk
(cd .build/dex && zip -q -0 ../unsigned.apk classes.dex)
$BT/zipalign -f -P 16 4 .build/unsigned.apk .build/aligned.apk
$BT/apksigner sign --ks $KEYSTORE --ks-pass pass:$KS_PASS --out $DIST/$OUT .build/aligned.apk
rm -f $DIST/$OUT.idsig
echo "built dist/$OUT"
if (( INSTALL )); then
  adb install -r $DIST/$OUT
  adb shell appops set com.samsung.app.newtrim MANAGE_MEDIA allow
fi
