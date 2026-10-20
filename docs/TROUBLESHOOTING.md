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

`./install.sh` recognises all of these and prints the fix. Run it with `--replace` to uninstall a conflicting copy
automatically (it asks first when run interactively).

| On the phone | With adb | Cause | Fix |
|---|---|---|---|
| "App not installed as package conflicts with an existing package" | `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (signatures do not match) | A copy of the same app signed with a different key is installed: a stock Samsung APK (e.g. from APKMirror) or someone else's port | Uninstall that copy, then install again: `adb uninstall <package>` or Settings → Apps. This deletes that copy's data. Android never lets one signature replace another, so there's no way around this. Updating from one OnePort release to the next keeps your data. |
| "App not installed as app isn't compatible with your phone" | `INSTALL_FAILED_OLDER_SDK` | Android is too old | Gallery needs Android 11, My Files Android 14, Photo Editor Android 16, and Video Android 17. See [Compatibility](../README.md#compatibility). |
| Same | `INSTALL_FAILED_NO_MATCHING_ABIS` | The phone isn't 64-bit ARM | Not supported |
| Same, on some phones | `INSTALL_FAILED_MISSING_SHARED_LIBRARY` … `libOpenCL.so` | Gallery v1.0.0–v1.0.1 required OpenCL, which many GPUs don't expose to apps | Fixed in v1.1.0. Update. |
| "App not installed as package conflicts…" (another app named in the adb line) | `INSTALL_FAILED_DUPLICATE_PERMISSION` / `CONFLICTING_PROVIDER` / `SHARED_USER_INCOMPATIBLE` | Another port of a Samsung app claims the same permission, provider or shared user ID | Uninstall the app named in the error, then install again |
| "Unsafe app blocked" / "App scan recommended" | `INSTALL_FAILED_VERIFICATION_FAILURE` | Play Protect | Tap *More details → Install anyway*, or turn off Play Store → Play Protect → Settings → *Scan apps with Play Protect* while installing |
| (adb only, Xiaomi/Redmi/POCO) | `INSTALL_FAILED_USER_RESTRICTED` | The phone blocks installs over USB | Developer options → turn on *Install via USB* |
| "App not installed as package appears to be invalid" | `INSTALL_PARSE_FAILED_…` | Damaged or incomplete download | Check `SHA256SUMS` and download again |

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
