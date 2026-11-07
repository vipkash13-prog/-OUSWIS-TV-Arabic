#!/usr/bin/env python3
"""Open the visible Xtream/matches controls with the remote on a disposable emulator."""
import os
from pathlib import Path
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get('ADB', 'adb')
OUT = Path(sys.argv[1] if len(sys.argv) > 1 else 'install-evidence')
OUT.mkdir(parents=True, exist_ok=True)
log = []

def adb(*args, binary=False):
    result = subprocess.run([ADB, *args], capture_output=True, timeout=60)
    result.check_returncode()
    return result.stdout if binary else result.stdout.decode('utf-8', 'replace').replace('\r', '')

def note(text):
    print(text, flush=True)
    log.append(text)
    (OUT / 'feature-navigation.log').write_text('\n'.join(log) + '\n')

def xml():
    for attempt in range(3):
        try:
            adb('shell', 'uiautomator', 'dump', '/data/local/tmp/ouswis-features.xml')
            return ET.fromstring(adb('shell', 'cat', '/data/local/tmp/ouswis-features.xml'))
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ET.ParseError):
            if attempt == 2:
                screenshot('navigation-failure.png')
                (OUT / 'navigation-logcat.txt').write_text(adb('logcat', '-d', '-t', '300'))
                raise
            time.sleep(2)

def activity(name):
    for _ in range(20):
        state = adb('shell', 'dumpsys', 'activity', 'activities')
        if any(name in line and ('mResumedActivity' in line or 'topResumedActivity' in line) for line in state.splitlines()):
            note('Foreground: ' + name)
            return
        time.sleep(0.5)
    raise AssertionError('Expected foreground activity: ' + name)

def screenshot(name):
    (OUT / name).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))

def settings():
    result = adb('shell', 'am', 'start', '-W', '-n', 'io.freemytv/io.freemytv.SettingsActivity')
    assert 'Status: ok' in result
    activity('SettingsActivity')

assert adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1', 'Emulator required'
settings()
nodes = list(xml().iter('node'))
assert any(n.get('text') == 'Xtream Codes' and n.get('clickable') == 'true' for n in nodes)
assert any(n.get('text') in ('Matches', 'المباريات') and n.get('clickable') == 'true' for n in nodes)
screenshot('settings-features.png')
note('PASS: Xtream Codes and Matches are visible, clickable controls on the launch screen.')

# RIGHT returns to the settings canvas even if focus was restored to a feature button.
adb('shell', 'input', 'keyevent', 'KEYCODE_DPAD_RIGHT')
adb('shell', 'input', 'keyevent', 'KEYCODE_DPAD_LEFT')
adb('shell', 'input', 'keyevent', 'KEYCODE_DPAD_CENTER')
activity('XtreamActivity')
assert 'Xtream Codes' in ET.tostring(xml(), encoding='unicode')
screenshot('xtream-login.png')
note('PASS: remote LEFT + OK opens the built-in Xtream account/player screen.')

settings()
adb('shell', 'input', 'keyevent', 'KEYCODE_DPAD_RIGHT')
adb('shell', 'input', 'keyevent', 'KEYCODE_DPAD_LEFT')
adb('shell', 'input', 'keyevent', 'KEYCODE_DPAD_DOWN')
adb('shell', 'input', 'keyevent', 'KEYCODE_DPAD_CENTER')
activity('MatchesActivity')
ui = ET.tostring(xml(), encoding='unicode')
for title in ('مباريات قادمة', 'مباشر الآن', 'نتائج اليوم', 'نتائج أمس'):
    assert title in ui, title
screenshot('matches-screen.png')
note('PASS: remote LEFT + DOWN + OK opens Matches with all four tabs.')
result = adb('shell', 'am', 'start', '-W', '-n', 'io.freemytv/io.freemytv.HomeActivity')
assert 'Status: ok' in result
activity('HomeActivity')
assert 'مباريات قادمة' in ET.tostring(xml(), encoding='unicode')
screenshot('home-features.png')
note('PASS: the home screen displays its matches card even before a provider returns fixtures.')
note('No provider credentials were entered. Live stream playback and availability of fixtures require the user/provider network.')
