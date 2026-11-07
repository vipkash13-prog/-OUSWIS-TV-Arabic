#!/usr/bin/env python3
"""Prove the supplied legacy APK survives standalone installation on a disposable emulator."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ADB = os.environ.get('ADB', 'adb')
MODE = sys.argv[1]
OUT = Path(sys.argv[2])
OLD = 'io.freemytv'
NEW = 'io.ouswis.tv.standalone'

def raw(*args):
    return subprocess.check_output([ADB, *args], timeout=60)

def adb(*args):
    return raw(*args).decode('utf-8', 'replace').replace('\r', '')

def note(message):
    print(message, flush=True)
    with (OUT / 'coexistence.log').open('a') as f:
        f.write(message+'\n')

def snapshot(pkg):
    data = adb('shell', 'dumpsys', 'package', pkg)
    path = adb('shell', 'pm', 'path', pkg).strip().removeprefix('package:')
    return {'package': pkg, 'uid': re.search(r'^\s*(?:userId|appId)=(\d+)',data,re.M)[1],
            'code': re.search(r'versionCode=(\d+)',data)[1],
            'firstInstallTime': re.search(r'firstInstallTime=(.+)',data)[1], 'path': path,
            'apk_sha256': hashlib.sha256(raw('exec-out','cat',path)).hexdigest()}

assert adb('shell','getprop','ro.kernel.qemu').strip() == '1'
assert adb('shell','id','-u').strip() == '0'
marker = '/data/user/0/'+OLD+'/files/ouswis-coexistence-probe'
if MODE == 'prepare':
    old = snapshot(OLD)
    assert old['package'] != NEW
    adb('shell','mkdir','-p','/data/user/0/'+OLD+'/files')
    subprocess.run([ADB,'shell','cat > '+marker],input=b'legacy-retained\n',check=True)
    adb('shell','chown',old['uid']+':'+old['uid'],'/data/user/0/'+OLD+'/files',marker)
    (OUT / 'legacy-before.json').write_text(json.dumps(old,indent=2))
    note('Prepared legacy io.freemytv from the supplied preview APK; no replacement/uninstall/data-clear commands are used.')
elif MODE == 'verify':
    before = json.loads((OUT / 'legacy-before.json').read_text())
    after = snapshot(OLD)
    assert before == after, (before,after)
    assert adb('shell','cat',marker).strip() == 'legacy-retained'
    new = snapshot(NEW)
    assert before['uid'] != new['uid'], 'Apps must have separate Android app IDs'
    (OUT / 'legacy-after.json').write_text(json.dumps(after,indent=2))
    (OUT / 'standalone.json').write_text(json.dumps(new,indent=2))
    for pkg in (OLD,NEW):
        launch = adb('shell','am','start','-W','-n',pkg+'/'+pkg+'.SettingsActivity')
        assert 'Status: ok' in launch, launch
        note('PASS: '+pkg+' remains installed and launches its own SettingsActivity.')
    note('PASS: both packages coexist with different Android app IDs; legacy APK hash/path/version/UID/first-install time and private marker are unchanged.')
else:
    raise AssertionError('Unknown phase: '+MODE)
