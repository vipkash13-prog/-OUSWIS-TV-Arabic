#!/usr/bin/env python3
"""Verify APK metadata and the permanent repository signing identity."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess

CERT = "aa97cc826f59fa9ba8d37f4ba3978bd968bcc8f94a0f5aa9b458f55151a177b7"

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")))
    tools = sdk / "build-tools" / "34.0.0"
    badging = subprocess.check_output([str(tools / "aapt2"), "dump", "badging", str(args.apk)], text=True)
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    assert package, "Package metadata unavailable"
    assert package[1] == "io.freemytv", "Package must remain io.freemytv for an in-place update"
    assert int(package[2]) >= 10003, "versionCode must exceed the official-release baseline"
    assert package[3] == "1.0", "Display version must stay 1.0"
    assert "application-label:'OUSWIS TV'" in badging, "Application label must be OUSWIS TV"
    assert "launchable-activity: name='io.freemytv.SettingsActivity'" in badging
    signature = subprocess.check_output([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(args.apk)], text=True)
    assert "Signer #1 certificate SHA-256 digest: " + CERT in signature, "Wrong signing key: existing app would reject this update"
    print(package[0])
    print("Signing certificate SHA-256:", CERT)
    print("APK SHA-256:", hashlib.sha256(args.apk.read_bytes()).hexdigest())
    print("Release metadata and signature verified. Run test-install-upgrade.sh to verify actual installation.")

if __name__ == "__main__":
    main()
