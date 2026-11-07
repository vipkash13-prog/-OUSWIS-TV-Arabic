# OUSWIS TV 1.0: Android 14 installation and updates

The update package remains `io.freemytv`, the identity reported for the installed app. Java component names and provider authorities keep that identity. The build must not rename it to `io.ouswis.tv.standalone`.

The display name is OUSWIS TV and `versionName` is 1.0. The internal `versionCode` is 10002, greater than the saved previous io.freemytv build (10001). Android compares versionCode for installation; the user-visible version can remain 1.0.

The required signing certificate SHA-256 is:

```
01fe551ebdc98f87521e57223787da29ad660cbc87cfc16ef3e9e18678cc7bee
```

This is the certificate of the saved previous APK and the recovered private signing key. A device with another certificate cannot accept this update merely because its package name matches. Obtain the installed APK's certificate before claiming device-specific compatibility. No uninstall, data clear, or package-name workaround is part of this repair.

## Build

Restore the existing key privately, set `FREEMYTV_KEYSTORE` and `FREEMYTV_KEYSTORE_PASS`, then run:

```sh
./build.sh --generic --release
python3 tools/verify-release-apk.py FreeMyTV.apk
```

`--release` requires an explicit key and checks its certificate. It never creates a replacement key. Use GitHub Actions secrets `KEYSTORE_BASE64` and `KEYSTORE_PASSWORD` for this existing key. The separately encrypted key in `signing/` belongs to the previous standalone signing identity; it must not be used for io.freemytv updates. Never commit the recovered private key or password.

## Actual installation test

Use a fresh disposable, rootable AOSP Android 14 / API 34 emulator:

```sh
bash tools/test-install-upgrade.sh previous.apk FreeMyTV.apk install-evidence
```

The script refuses physical devices, other API levels, and an emulator which already has io.freemytv. It installs the baseline, writes a private-data sentinel, installs the update with `adb install -r`, verifies unchanged UID and first-install timestamp, reads the sentinel, and launches SettingsActivity. It never uninstalls or clears anything. Optional `REJECTED_APK` and `DOWNGRADE_APK` test expected rejections before the successful update.

CI builds a previous-code fixture with the same signing key and current source, then performs the real update test on API 34. The local repair audit additionally uses the actual saved previous 10001 APK. A successful build or API 30 installation alone is insufficient for release.

## Scope and device limits

The installation repair preserves the matches 3D carousel, upcoming matches, Xtream Codes player, Arabic catalogue and remote control logic. Surface application names were updated to OUSWIS TV. No other repository is part of this repair.

The emulator tests package installation and updates on AOSP Android 14. It is not a physical MECOOL KM2 Plus Deluxe test, nor a test of Google Play Protect or a particular file manager. Xtream server credentials and live playback must be tested on the device with the user's own account. The minimum SDK remains 26 and target SDK 33; target 33 meets Android 14's install target floor and is retained to avoid unrelated runtime behavior changes.
