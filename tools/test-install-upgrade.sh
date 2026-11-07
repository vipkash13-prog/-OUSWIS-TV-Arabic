#!/usr/bin/env bash
# Test only a disposable Android 14 emulator; never uninstall or clear an app.
# Usage: tools/test-install-upgrade.sh <previous.apk> <update.apk> [evidence-dir]
set -euo pipefail
previous="${1:?previous APK required}"
update="${2:?update APK required}"
evidence="${3:-install-evidence}"
adb_bin="${ADB:-adb}"
mkdir -p "$evidence"
exec > >(tee "$evidence/install-upgrade.log") 2>&1

[ "$("$adb_bin" shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || {
  echo "Refusing: this test must run on a disposable emulator."; exit 1;
}
[ "$("$adb_bin" shell getprop ro.build.version.sdk | tr -d '\r')" = 34 ] || {
  echo "Android 14 / API 34 is required."; exit 1;
}
"$adb_bin" shell getprop ro.build.fingerprint
if "$adb_bin" shell pm path io.ouswis.tv.standalone | tr -d '\r' | grep -q '^package:'; then
  echo "Refusing: emulator already contains io.ouswis.tv.standalone. Use a fresh emulator, without uninstalling it."
  exit 1
fi

if [ -n "${LEGACY_APK:-}" ]; then
  if "$adb_bin" shell pm path io.freemytv | tr -d '\r' | grep -q '^package:'; then
    echo "Refusing: legacy app already exists on this emulator; it will not be replaced."; exit 1
  fi
  "$adb_bin" install --no-streaming "$LEGACY_APK"
  "$adb_bin" root
  "$adb_bin" wait-for-device
  python3 tools/test-coexistence.py prepare "$evidence"
fi
"$adb_bin" install --no-streaming "$previous"
for rejected in "${REJECTED_APK:-}" "${DOWNGRADE_APK:-}"; do
  [ -n "$rejected" ] || continue
  if result="$("$adb_bin" install --no-streaming -r "$rejected" 2>&1)"; then
    echo "Unexpected success for the known-incompatible APK: $rejected"; exit 1
  fi
  printf '%s\n' "$result"
  printf '%s\n' "$result" | grep -Eq 'INSTALL_FAILED_(CONFLICTING_PROVIDER|VERSION_DOWNGRADE|UPDATE_INCOMPATIBLE)'
done
"$adb_bin" root
"$adb_bin" wait-for-device
[ "$("$adb_bin" shell id -u | tr -d '\r')" = 0 ] || {
  echo "A rootable AOSP emulator is required to verify private data preservation."; exit 1;
}
"$adb_bin" shell dumpsys package io.ouswis.tv.standalone > "$evidence/before.txt"
uid_before="$(sed -nE 's/^ *(userId|appId)=([0-9]+).*$/\2/p' "$evidence/before.txt" | head -1)"
first_before="$(sed -n 's/^ *firstInstallTime=//p' "$evidence/before.txt" | head -1)"
[ -n "$uid_before" ]
[ -n "$first_before" ]
"$adb_bin" shell "mkdir -p /data/user/0/io.ouswis.tv.standalone/files; echo ouswis-preserved > /data/user/0/io.ouswis.tv.standalone/files/upgrade-probe; chown $uid_before:$uid_before /data/user/0/io.ouswis.tv.standalone/files /data/user/0/io.ouswis.tv.standalone/files/upgrade-probe"

if [ -n "${INAPP_RELEASE_CODE:-}" ]; then
  python3 tools/test-inapp-update.py "$evidence"
fi
"$adb_bin" install --no-streaming -r "$update"
"$adb_bin" shell dumpsys package io.ouswis.tv.standalone > "$evidence/after.txt"
uid_after="$(sed -nE 's/^ *(userId|appId)=([0-9]+).*$/\2/p' "$evidence/after.txt" | head -1)"
first_after="$(sed -n 's/^ *firstInstallTime=//p' "$evidence/after.txt" | head -1)"
[ -n "$uid_after" ]
[ "$uid_before" = "$uid_after" ]
[ "$first_before" = "$first_after" ]
[ "$("$adb_bin" shell cat /data/user/0/io.ouswis.tv.standalone/files/upgrade-probe | tr -d '\r')" = ouswis-preserved ]
"$adb_bin" shell pm path io.ouswis.tv.standalone
"$adb_bin" logcat -c
"$adb_bin" shell am start -W -n io.ouswis.tv.standalone/io.ouswis.tv.standalone.SettingsActivity | tee "$evidence/launch.txt"
grep -q 'Status: ok' "$evidence/launch.txt"
echo "PASS: Android 14 installed the update; UID, first-install time and private app data survived. No uninstall or data clear was used."
