# Building OnePort from source

This guide covers rebuilding the released apps yourself, changing a port, and porting a new Samsung app.

## Prerequisites

| Tool | How to get it |
|---|---|
| macOS (Linux should work, but is untested) | |
| `zsh` | Preinstalled on macOS. On Linux: `apt install zsh` |
| Android SDK: build-tools **37.0.0**, platform **android-36.1**, platform-tools | Android Studio → SDK Manager |
| Java 17+ | Android Studio's bundled JBR is picked up automatically |
| `apktool`, `jadx` | `brew install apktool jadx` |
| `python3` | Preinstalled on macOS |
| A non-Samsung phone with USB debugging | Used for the framework dump and for testing |

`scripts/env.sh` finds the SDK in `~/Library/Android/sdk` or `~/Android/Sdk`, or uses `$ANDROID_HOME`. Every path can be overridden with an environment variable: `BT`, `JAR`, `JAVA_HOME`, `FW`, `KEYSTORE`, `KS_PASS` or `DIST`.

> [!TIP]
> The pinned build-tools and platform versions produce output that is **byte-identical** to the release (before signing). Other versions work too, but the dex files will differ.

## 1. Dump your phone's framework

```sh
scripts/dump_framework.sh
```

This pulls every jar on the phone's `BOOTCLASSPATH` and `SYSTEMSERVERCLASSPATH` and runs `dexdump` on them, writing about 600 MB to `framework/fw_dexdump.txt`. It's read-only on the phone. **Redo it after every OS update.** The patchers use it to decide what is missing.

## 2. Get Samsung's original APKs

The repository contains no Samsung binaries. Download the **exact** versions below, for example from APKMirror (search by package name and version code), and put each one in its port's `orig/` folder. The file name doesn't matter, but there must be exactly one APK per folder. `build.sh` refuses to build if the SHA-256 doesn't match.

| Port | Package | Version (code) | Variant | SHA-256 |
|---|---|---|---|---|
| `myfiles` | `com.sec.android.app.myfiles` | 15.6.03.3 (1560303000) | arm64-v8a + armeabi-v7a, nodpi | `57a4844af4b8daa1a450ecf30d75a55d2544ede602965306bd707c84ae547840` |
| `gallery` | `com.sec.android.gallery3d` | 15.9.00.45 (1590000045) | arm64-v8a, nodpi | `d3559b7db868dea56887cd66b831f54e2a8f046123769b9f3838d3488c0bbbbe` |
| `photoeditor` | `com.sec.android.mimage.photoretouching` | 3.9.09.16 (390916100) | arm64-v8a, nodpi | `0fcf05f0f79c1cea1ca5871d6b22a0b3966c458529e22b37584153f026c39486` |
| `video` | `com.samsung.android.video` | 7.4.00.6 (740010006) | arm64-v8a, nodpi | `35f5629bd518a507e47b24b5e185e47589b5a2b8a9b0f9f5bfa7e234356844ba` |

These values also live in each `ports/<app>/port.conf`.

## 3. Signing key

`build.sh` signs with `keys/oneport.keystore`, using password `android` by default. Override these with `KEYSTORE=` and `KS_PASS=`.

- **The official OnePort key is not in the repository**, and never will be. Anyone holding it could publish an "update" that installs over users' apps with all their permissions.
- **For your own builds, make your own key:**

  ```sh
  mkdir -p keys
  keytool -genkeypair -keystore keys/oneport.keystore -storepass android -keypass android \
    -alias oneport -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=OnePort local build"
  ```

  Use **one key for all the apps you build**. Samsung's apps guard each other with signature-level permissions, so Gallery ↔ Photo Editor only works when both have the same signature.
- An app signed with your key won't install over the official release, or the reverse. Uninstall first, which deletes that app's data.

## 4. Build

```sh
scripts/build.sh <port> [--repatch] [--install]
```

| Flag | Meaning |
|---|---|
| *(none)* | Rebuild from the existing `ports/<port>/work/` (fast; use after editing `compat_src/`) |
| `--repatch` | Decode the original APK again and re-run the patchers (needed after changing `tools/*.py`) |
| `--install` | `adb install` the result and grant its app-ops |

The output goes to `dist/<App>_<version>_OnePort.apk`.

## Repository layout

```
scripts/
  env.sh               toolchain discovery (sourced)
  build.sh             generic builder driven by ports/<app>/port.conf
  dump_framework.sh    phone framework → framework/fw_dexdump.txt
ports/<app>/
  port.conf            package, pinned original APK, output name, manifest needs, hooks
  tools/patch.py       generic auto-patcher (per-port copy; they have diverged slightly)
  tools/analyze.py     report what the app uses that the phone lacks
  tools/*_patch.py     app-specific smali edits
  tools/*.sh, ui.py    on-device test helpers (safe: they refuse blind taps)
  compat_src/          hand-written Java compatibility layer → extra classesN.dex
  orig/                Samsung's original APK (git-ignored)
  work/                apktool decode being patched (git-ignored)
```

## The build pipeline

1. `apktool d -r` decodes the original into `work/` (smali, with resources left raw).
2. `patch.py` runs, then the app's own patcher, then any `post_patch` hook from `port.conf`.
3. `apktool b` assembles the smali, and **only `classes*.dex` is kept**.
4. `compat_src/` is compiled with `javac` and `d8` and added as one extra `classesN.dex`.
5. The original APK is copied. Its `META-INF/` and dex files are deleted, and the new dex files are added uncompressed. If `PERMS` is set, a binary-patched `AndroidManifest.xml` is added as well (`axml_add_perm.py`, plus `axml_optional_libs.py` if `OPTIONAL_LIBS=1`).
6. `zipalign -P 16` and `apksigner` run last.

Resources are never rebuilt. Samsung's obfuscated resource names differ only by case (`vB.xml`/`vb.xml`), and the case-insensitive macOS filesystem merges them.

## Porting another Samsung app

1. Copy the closest existing port: `cp -R ports/photoeditor ports/newapp`. Then clear `work/` and `orig/`, and edit `port.conf`.
2. Install the untouched original and read `adb logcat -b crash -d`. This shows what actually breaks.
3. Run `jadx -j 8 --no-res -d ports/newapp/java_src orig.apk` to get readable Java (it's git-ignored).
4. Run `python3 ports/newapp/tools/analyze.py framework/fw_dexdump.txt ports/newapp/work out.json` to see the size of the job.
5. Run `scripts/build.sh newapp --repatch --install`, and iterate on crash logs.
