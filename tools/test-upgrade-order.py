#!/usr/bin/env python3
"""Installs the official build, then each preview over the one before it with `adb install -r` (never -d, never an
uninstall), and checks the versionCode on the device grows each time. Then tries the official build again: Android
must refuse it as a downgrade, which is what keeps the order honest."""
import re
import subprocess
import sys
from pathlib import Path

PKG = 'io.ouswis.tv.standalone'
LOG = Path('upgrade-order.log')
lines = []


def note(m):
    print(m, flush=True)
    lines.append(m)
    LOG.write_text('\n'.join(lines) + '\n')


def adb(*a):
    r = subprocess.run(['adb', *a], capture_output=True, text=True)
    return r.returncode, (r.stdout + r.stderr).strip()


def code():
    _, out = adb('shell', 'dumpsys', 'package', PKG)
    m = re.search(r'versionCode=(\d+)', out)
    return int(m[1]) if m else None


apks = sorted(Path(sys.argv[1]).glob('*.apk'), key=lambda p: int(p.name.split('-')[0]))
assert len(apks) >= 2, 'need the official build and at least one preview'
prev = None
for p in apks:
    rc, out = adb('install', '-r', str(p))
    assert rc == 0 and 'Success' in out, 'Installing ' + p.name + ' over the previous build failed: ' + out
    now = code()
    note('PASS: %s installed over %s, versionCode now %s' % (p.name, 'nothing' if prev is None else prev, now))
    assert prev is None or now > prev, 'versionCode did not grow: %s -> %s' % (prev, now)
    prev = now
rc, out = adb('install', '-r', str(apks[0]))
assert rc != 0 and 'DOWNGRADE' in out.upper(), 'The older official build must be refused as a downgrade: ' + out
assert code() == prev, 'A refused downgrade changed the installed build'
note('PASS: the older official build is refused as a downgrade (%s), the installed build stays %s.' % (out.splitlines()[-1][:120], prev))
