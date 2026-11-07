#!/usr/bin/env python3
"""Exercise the official download and Android consent UI on a disposable emulator."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

OUT = Path(sys.argv[1])
EXPECTED = int(os.environ['INAPP_RELEASE_CODE'])
ADB = os.environ.get('ADB', 'adb')

def adb(*args):
    return subprocess.check_output([ADB, *args], timeout=60).decode('utf-8', 'replace').replace('\r', '')

def note(message):
    print(message, flush=True)
    with (OUT / 'inapp-update.log').open('a') as f:
        f.write(message + '\n')

def ui():
    for attempt in range(3):
        try:
            adb('shell', 'uiautomator', 'dump', '/data/local/tmp/update-ui.xml')
            return ET.fromstring(adb('shell', 'cat', '/data/local/tmp/update-ui.xml'))
        except (subprocess.CalledProcessError, ET.ParseError):
            if attempt == 2:
                raise
            time.sleep(2)

def click(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

def shot(name):
    (OUT / name).write_bytes(subprocess.check_output([ADB, 'exec-out', 'screencap', '-p'], timeout=60))

assert adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1'
adb('shell', 'am', 'start', '-W', '-n', 'io.ouswis.tv.standalone/io.ouswis.tv.standalone.SettingsActivity')
for key in ('RIGHT', 'LEFT', 'DOWN', 'DOWN', 'CENTER'):
    adb('shell', 'input', 'keyevent', 'KEYCODE_DPAD_' + key)
for _ in range(90):
    try:
        state = json.loads(adb('shell', 'cat', '/data/user/0/io.ouswis.tv.standalone/files/update.json'))
    except (subprocess.CalledProcessError, ValueError):
        state = {}
    if state.get('state') == 'ready':
        break
    if state.get('state') == 'failed' and os.environ.get('INAPP_EXPECT_LEGACY_REJECTION') == '1':
        assert state.get('refused'), state
        assert state.get('error') == 'other', state
        assert int(state['version']) == EXPECTED, state
        assert not '.apk' in adb('shell','ls','/data/user/0/io.ouswis.tv.standalone/no_backup/update')
        shot('inapp-legacy-rejected.png')
        note('PASS: standalone updater rejected the published legacy io.freemytv APK despite its matching signing certificate.')
        note('Bootstrap: there is not yet a published standalone APK to download. Standalone installation/update is tested separately; re-run after publication to test the full in-app download/install flow.')
        sys.exit(0)
    if state.get('state') in ('failed', 'build', 'current'):
        raise AssertionError('Expected a newer signed download: ' + str(state))
    time.sleep(2)
else:
    raise AssertionError('Update download timed out: ' + str(state))
assert int(state['version']) == EXPECTED, state
note('PASS: the visible Updates button fetched the official GitHub APK and verified its build/signature.')
shot('inapp-download-ready.png')
nodes = list(ui().iter('node'))
button = next(n for n in nodes if n.get('class') == 'android.widget.Button' and n.get('enabled') == 'true')
click(button)
time.sleep(2)
foreground = adb('shell', 'dumpsys', 'activity', 'activities')
assert any('com.android.settings' in line and ('mResumedActivity' in line or 'topResumedActivity' in line)
           for line in foreground.splitlines()), 'Expected Android allow-install-from-this-source screen'
shot('inapp-source-permission.png')
note('PASS: Android asks to allow OUSWIS TV as an installation source; the app does not bypass consent.')
# Simulate granting that one permission on the disposable emulator, then return to the app.
adb('shell', 'appops', 'set', 'io.ouswis.tv.standalone', 'REQUEST_INSTALL_PACKAGES', 'allow')
adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
time.sleep(2)
click(next(n for n in ui().iter('node') if n.get('class') == 'android.widget.Button' and n.get('enabled') == 'true'))
for _ in range(20):
    nodes = list(ui().iter('node'))
    install = next((n for n in nodes if 'packageinstaller' in n.get('package', '')
                    and n.get('text', '').lower() == 'update' and n.get('enabled') == 'true'), None)
    if install is not None:
        break
    time.sleep(1)
else:
    shot('inapp-installer-failure.png')
    (OUT / 'inapp-installer-logcat.txt').write_text(adb('logcat', '-d', '-t', '600'))
    raise AssertionError('Android update confirmation did not appear')
shot('inapp-install-confirmation.png')
note('PASS: the app opened Android\'s update confirmation with the downloaded APK.')
click(install)
for _ in range(60):
    package = adb('shell', 'dumpsys', 'package', 'io.ouswis.tv.standalone')
    if re.search(r'versionCode=' + str(EXPECTED) + r'\b', package):
        break
    time.sleep(1)
else:
    raise AssertionError('The in-app update was not installed')
assert adb('shell', 'cat', '/data/user/0/io.ouswis.tv.standalone/files/upgrade-probe').strip() == 'ouswis-preserved'
note('PASS: Android installed the downloaded official build ' + str(EXPECTED) + ' and retained private app data.')
