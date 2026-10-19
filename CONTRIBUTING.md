# Contributing to OnePort

Thanks for helping! The most useful contributions, roughly in order:

1. **Device reports.** Did OnePort work, or not, on your phone? [File a device report](https://github.com/StevenSteel7/OnePort/issues/new?template=device_report.yml). Even a "works fine" report is valuable.
2. **Bug reports with logs.** See [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md#collecting-logs-for-a-bug-report). A crash log turns a bug from "can't reproduce" into a fix.
3. **Pull requests:** fixes to `compat_src/`, the patchers, or new ports.

## Ground rules

- **Never commit Samsung material.** That means APKs, decompiled `java_src/`, apktool `work/` trees and smali dumps. Only original code belongs in this repository. `.gitignore` covers the usual paths; check `git status` before every commit anyway.
- **Never commit a keystore.**
- **No Samsung online services.** Don't submit code that impersonates a Galaxy device or accesses Samsung's servers (Galaxy AI, Samsung account, Samsung Cloud). OnePort only ports features that run on the phone itself, in line with Samsung's terms.
- **Never test on real media.** Use throwaway files in `Pictures/CompatTest/`, and open items by media ID. Test scripts must refuse to tap if the screen isn't what they expect: no blind coordinate taps after a navigation step that might have failed.
- **Prefer the smallest patch.** In order of preference:
  - Fix the behaviour generically in `compat_src/` (Java).
  - Fix it in `patch.py`.
  - Only then make an app-specific smali edit.
- **Explain the why.** A short comment saying which Samsung behaviour you're replacing saves the next person hours.

## Pull request checklist

- [ ] `scripts/build.sh <port> --repatch` builds cleanly
- [ ] Tested on a real device (say which one, and the Android version)
- [ ] `adb logcat -b crash -d` is clean for the flows you touched
- [ ] README status tables and `CHANGELOG.md` (*Unreleased* section) updated if behaviour changed

## Requesting a new app

Open an [app request](https://github.com/StevenSteel7/OnePort/issues/new?template=app_request.yml). Apps that are mostly local work best. Apps built around Samsung cloud services or Galaxy AI are out of scope.
