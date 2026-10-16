# Troubleshooting

## Collecting logs for a bug report

Run this **right after** the problem happens, with the phone connected over adb:

```sh
adb logcat -b crash -d > crash.log     # Java crashes
adb logcat -d > full.log               # everything (native crashes show up here as DEBUG / tombstone)
```

Attach both files to a [bug report](https://github.com/StevenSteel7/OnePort/issues/new?template=bug_report.yml). The log buffer is small and gets overwritten quickly. To make it larger until the next reboot, run `adb logcat -G 64M`.

> [!CAUTION]
> `full.log` can contain file names and other details from your phone. Look through it before you post it, or ask in the issue for a private way to send it.

## Install errors

| Error | Cause | Fix |
|---|---|---|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | A copy signed with a different key is installed (a stock Samsung APK, or someone else's build) | `adb uninstall <package>`, then install again. This deletes that copy's data. |
| `INSTALL_FAILED_MISSING_SHARED_LIBRARY` | The phone lacks a system library the build expects | Please report it, with the full line |
| `INSTALL_FAILED_OLDER_SDK` | Android is too old | My Files and Photo Editor need Android 16, Gallery needs Android 11, and Video needs Android 17. See [Compatibility](../README.md#compatibility). |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | The phone isn't arm64 | Not supported |
| "App not installed as package appears to be invalid" | Corrupted download | Check `SHA256SUMS` and download again |

## App behaviour

| Symptom | Fix |
|---|---|
| Can't delete, edit, rename or favourite media | Turn on *Settings → Apps → Special app access → All files access* (and *Media management*) for the app |
| App closes right after granting access | Expected. Android restarts an app when its special access changes. |
| Gallery's **Edit** does nothing | Photo Editor must be installed from the **same** OnePort release (same signing key) |
| Gallery thumbnail still shows the old image | Known issue when the photo was edited in *another* app. *Settings → Apps → Gallery → Storage → Clear cache* should refresh it. |
| Light balance, auto-straighten or blur does nothing | Expected: these need Samsung firmware libraries. See the README. |
| Photo Assist / AI options are missing | Expected: Galaxy AI is deliberately not included (see the README) |

## Starting over

```sh
adb shell pm clear <package>    # wipe one app's data and keep it installed
adb uninstall <package>         # remove it completely
```
