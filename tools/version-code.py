#!/usr/bin/env python3
"""One counter for every signed build, official or preview: each new build is above any build before it.

versionCode = 110253 + whole minutes since 2026-11-16 10:00 UTC. 110253 is the last official number published before
this scheme; a build made later always gets a higher number, whichever workflow makes it, so an update never needs an
uninstall. versionName stays 1.0. Prints the number; `--set app/AndroidManifest.xml` writes it there.
"""
import re
import sys
import time
from pathlib import Path

ANCHOR_CODE = 110253
ANCHOR_EPOCH = 1794823200  # 2026-11-16T10:00:00Z


def code(now=None):
    now = int(time.time()) if now is None else int(now)
    assert now >= ANCHOR_EPOCH, 'the clock is before the anchor'
    return ANCHOR_CODE + (now - ANCHOR_EPOCH) // 60


if __name__ == '__main__':
    c = code()
    if len(sys.argv) == 3 and sys.argv[1] == '--set':
        p = Path(sys.argv[2])
        s = p.read_text()
        assert re.search(r'android:versionCode="\d+"', s)
        p.write_text(re.sub(r'android:versionCode="\d+"', 'android:versionCode="%d"' % c, s, count=1))
    print(c)
