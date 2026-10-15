# Shared toolchain settings for OnePort scripts. Sourced by scripts/build.sh and scripts/dump_framework.sh.
# Every value can be overridden from the environment.
ONEPORT=${ONEPORT:-${${(%):-%x}:A:h:h}}

if [[ -z $ANDROID_HOME ]]; then
  for d in "$ANDROID_SDK_ROOT" ~/Library/Android/sdk ~/Android/Sdk; do [[ -n $d && -d $d ]] && { ANDROID_HOME=$d; break; }; done
fi
# Pinned versions reproduce the published release byte-for-byte; the newest installed one is used as a fallback.
BT=${BT:-$ANDROID_HOME/build-tools/37.0.0}
[[ -d $BT ]] || BT=$(ls -d $ANDROID_HOME/build-tools/* 2>/dev/null | sort -V | tail -1)
JAR=${JAR:-$ANDROID_HOME/platforms/android-36.1/android.jar}
[[ -f $JAR ]] || JAR=$(ls $ANDROID_HOME/platforms/*/android.jar 2>/dev/null | sort -V | tail -1)

if [[ -z $JAVA_HOME && -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]]; then
  JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
[[ -n $JAVA_HOME ]] && export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
[[ -d $ANDROID_HOME/platform-tools ]] && export PATH="$PATH:$ANDROID_HOME/platform-tools"

FW=${FW:-$ONEPORT/framework/fw_dexdump.txt}
KEYSTORE=${KEYSTORE:-$ONEPORT/keys/oneport.keystore}
KS_PASS=${KS_PASS:-android}
DIST=${DIST:-$ONEPORT/dist}

die() { print -u2 "error: $*"; exit 1; }
need() { for c in "$@"; do command -v $c >/dev/null || die "'$c' not found on PATH (see docs/BUILDING.md)"; done; }
