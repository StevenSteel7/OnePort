#!/bin/zsh
# Build test instrumentation, signed with the same local key as the target apps.
set -e
REPO=${0:A:h:h:h:h}
source "$REPO/scripts/env.sh"
cd "${0:A:h}"
need javac zip
[[ -f $KEYSTORE ]] || die "no signing key at $KEYSTORE"
rm -rf build
mkdir -p build/classes build/dex
"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml
javac --release 17 -cp "$JAR" -d build/classes $(find src -name '*.java')
"$BT/d8" --min-api 31 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -0 ../unsigned.apk classes.dex)
"$BT/zipalign" -f -P 16 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --out harness.apk build/aligned.apk
echo "built tests/video-compat/harness/harness.apk"
