#!/bin/zsh
# Dump the connected phone's framework (every jar on BOOTCLASSPATH and SYSTEMSERVERCLASSPATH) with dexdump.
# patch.py/analyze.py diff each Samsung app against this to find the Samsung classes and members the phone lacks.
# Read-only on the phone. Re-run after every OS update.
#   scripts/dump_framework.sh            -> framework/fw_dexdump.txt
set -e
source ${0:A:h}/env.sh
need adb unzip
[[ -x $BT/dexdump ]] || die "dexdump not found in $BT"
[[ $(adb get-state 2>/dev/null) == device ]] || die "no phone over adb (enable USB debugging and accept the prompt)"

T=$(mktemp -d); trap 'rm -rf $T' EXIT
JARS=(${(f)"$(adb shell 'echo $BOOTCLASSPATH:$SYSTEMSERVERCLASSPATH' | tr -d '\r' | tr ':' '\n' | grep .)"})
print "pulling ${#JARS} framework jars from $(adb shell getprop ro.product.model | tr -d '\r') (Android $(adb shell getprop ro.build.version.release | tr -d '\r'))"
for j in $JARS; do
  n=${j//\//_}
  adb pull $j $T/$n.zip >/dev/null 2>&1 || { print "  skipped $j (not readable)"; continue; }
  unzip -q -o $T/$n.zip 'classes*.dex' -d $T/$n 2>/dev/null || true   # some jars carry no dex
done
mkdir -p ${FW:h}
(cd $T && $BT/dexdump */classes*.dex) > $FW.tmp 2>/dev/null
mv $FW.tmp $FW
print "wrote $FW ($(grep -c '^Processing' $FW) dex files, $(grep -c 'Class descriptor' $FW) classes)"
