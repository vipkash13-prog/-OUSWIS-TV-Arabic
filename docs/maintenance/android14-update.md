# OUSWIS TV 1.0 official signing and updates

## One permanent identity

The official package is `io.freemytv`, with visible name OUSWIS TV and visible version 1.0. Every official APK uses the repository's already-existing encrypted key, `signing/ouswis-release.jks.b64`, and the existing `OUSWIS_SIGNING_PASSWORD` Actions secret. The workflow uses the same SHA-256 password normalization as the workflow which originally created this key.

The official signing certificate SHA-256 is:

```
aa97cc826f59fa9ba8d37f4ba3978bd968bcc8f94a0f5aa9b458f55151a177b7
```

The workflow never generates a key. Missing key/password or a wrong certificate stops publication. Do not replace the key file or generate a new signing identity for an update. No password or decrypted private key is committed or uploaded as an artifact.

## Initial transition, then updates without deleting

The historical TiviMate APK supplied by the user is signed by `4a922ee77ad8441a8448847bb9ac2a14bfe1fd451b09f4063482ce9e4a14e86f`. The previously tested compatibility APK in `dist/legacy/` uses `01fe551ebdc98f87521e57223787da29ad660cbc87cfc16ef3e9e18678cc7bee`. Neither updates to the official identity in place. The user authorized removing the old differently-signed installation once.

If the old app is the active launcher, switch its Home Screen mode to Original before removing it and confirm the device's original Home screen opens. Install the first official APK. Thereafter install official updates over it without deleting it or clearing data. Their package and signing certificate remain identical.

## Automatic future builds

Push app/build/tool changes to main. `.github/workflows/ouswis-build.yml` automatically:

1. Restores the existing encrypted key using the existing secret; no key replacement fallback.
2. Adds GITHUB_RUN_NUMBER to the manifest's base versionCode (10003 initially). Keep that base monotonic; never lower it. Display version remains 1.0.
3. Builds a preceding-code fixture and the final APK with that same key.
4. Verifies package, version, app name and pinned signing certificate.
5. Installs the fixture and updates it with the final APK on a fresh rootable AOSP Android 14/API 34 emulator. Verifies private data, app ID and first-install time survive and SettingsActivity launches. No uninstall/data clear is used in the test.
6. Publishes the APK and the actual install log in Releases only after that test succeeds.

The signature policy is enforced by `./build.sh --generic --release` and `tools/verify-release-apk.py`. Local release builds also require the permanent repository key. The prior one-off publisher has been retired so it cannot republish an alternate key as the current official APK.

## Scope and limits

Matches 3D, upcoming matches, Xtream Codes player, Arabic catalogue and remote control logic are preserved. The older audit file `android14-install-result.txt` is historical evidence for the compatibility key, not an official-key test. Each official release attaches its own install-upgrade.log.

An emulator installation test is not a physical MECOOL KM2 Plus Deluxe test or a test of its Google Play Protect/file manager. Minimum SDK remains 26 and target SDK 33 to avoid unrelated runtime behavior changes. The original attribution and licence remain intact. No other repository is involved.

### Updates inside OUSWIS TV

The launch screen's Updates button and Home Settings > Updates open the same foreground screen. It checks this repository's latest official release, downloads `OUSWIS-TV-1.0.apk`, verifies size/digest, package, build number and the pinned permanent certificate, then offers Android's installation confirmation. The first install from this source requires Android's Allow from this source setting; after returning, press Install again. No automatic confirmation, uninstall or data clear is used. Builds compare `versionCode` from the official release tag because the visible version remains 1.0. The weekly background check uses the same source and validation.

The Android 14 release gate also installs an earlier-build fixture of the updater, uses the visible Updates control to fetch the currently published official APK over HTTPS, checks the source-permission screen, accepts Android's update confirmation on the disposable emulator, and verifies the downloaded build and private-data sentinel. It then installs and verifies the new final APK, retaining the original app identity/data throughout. The release includes `inapp-update.log` and the install-confirmation screenshot. The older 10040 APK needs one manual update to gain these corrected updater settings; subsequent official builds can be downloaded from the app.

### Live TV picture and entry point

The original Live TV tile in Inputs opens the existing Xtream account/player directly, using the same saved account, including on devices without a hardware tuner. Live TV is absent from the drawer's Shortcuts group to avoid duplication. During playback the complete video frame is fitted and centered on a black fullscreen surface; the channel label overlays it briefly rather than reserving screen height. DOWN selects the next row in the channel list and UP selects the previous one. The first/last channels do not wrap to the other end. BACK restores selection to the currently watched row. LEFT/RIGHT decrease/increase the picture area in 5% steps between 80% and 100% to compensate for a TV's overscan; the choice persists across channels/restarts. OK pauses/resumes.

`test-live-playback.py` serves three synthetic H.264 transport streams on the test runner, reverses a loopback port into the disposable emulator, verifies the drawer has only one Live TV entry, and opens that Inputs tile. It checks all four green frame borders in decoded screenshots for 16:9, 4:3 and a 95% picture area. Three channels with distinct colors/aspect ratios distinguish forward and backward navigation: DOWN follows 1→2→3, UP follows 3→2→1, with no wrap at either boundary. Its demo account exists only in emulator preferences, never in the APK. This verifies real decoding/layout with test content, not a user's subscription or physical TV overscan.

## Visible features and remote navigation

The launch/settings screen has native, focusable Xtream Codes and Matches buttons. LEFT moves from the main settings list to Xtream; DOWN selects Matches; RIGHT or Back returns focus to the settings list. Both features also appear in drawer shortcuts; the home Live TV button launches Xtream and Matches is a default top button. Neither feature requires a hardware TV tuner. The home match preview stays visible with an honest empty state if fixtures are unavailable.

`tools/test-feature-entrypoints.py` verifies the visible controls with UIAutomator, opens both activities with remote key events, checks all four matches tabs and captures settings, Xtream, matches and home screenshots. Publication requires this test as well as the Android 14 installation/upgrade test. It does not enter provider credentials or claim successful subscription playback.
