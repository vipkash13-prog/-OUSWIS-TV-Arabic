#!/usr/bin/env python3
"""Exercise selected-language RTL and physical remote directions at 4K on an emulator."""
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get('ADB', 'adb')
PKG = 'io.ouswis.tv.standalone'
OUT = Path(sys.argv[1] if len(sys.argv) > 1 else 'install-evidence')
OUT.mkdir(parents=True, exist_ok=True)

def adb(*args, binary=False):
    r = subprocess.run([ADB, *args], capture_output=True, timeout=90, check=True)
    return r.stdout if binary else r.stdout.decode().replace('\r', '')

def tree():
    adb('shell', 'uiautomator', 'dump', '/data/local/tmp/rtl.xml')
    return ET.fromstring(adb('shell', 'cat', '/data/local/tmp/rtl.xml'))

def key(name):
    adb('shell', 'input', 'keyevent', 'KEYCODE_DPAD_' + name)

def tab(identifier):
    return next(n for n in tree().iter('node') if n.get('resource-id') == PKG + ':id/matches_tab_' + identifier)

def focused(identifier):
    assert tab(identifier).get('focused') == 'true', identifier

assert adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1', 'Disposable emulator only'
adb('root')
adb('wait-for-device')
adb('shell', 'am', 'force-stop', PKG)
prefs = '/data/user/0/' + PKG + '/shared_prefs/settings.xml'
# A fresh app can use only defaults and never have written settings.xml.
saved = adb('shell', 'if [ -f ' + prefs + ' ]; then cat ' + prefs
            + "; else echo '<map/>'; fi", binary=True)
uid = re.search(r'(?:userId|appId)=(\d+)', adb('shell', 'dumpsys', 'package', PKG)).group(1)
size = adb('shell', 'wm', 'size')
override = re.search(r'Override size: (\d+x\d+)', size)

def write_prefs(data):
    local = OUT / 'rtl-test-settings.tmp'
    local.write_bytes(data)
    try:
        adb('push', str(local), '/data/local/tmp/rtl-settings.xml')
        adb('shell', 'cp', '/data/local/tmp/rtl-settings.xml', prefs)
        adb('shell', 'chown', uid + ':' + uid, prefs)
        adb('shell', 'chmod', '660', prefs)
    finally:
        local.unlink(missing_ok=True)

try:
    settings = ET.fromstring(saved)
    for node in list(settings):
        if node.get('name') == 'language':
            settings.remove(node)
    ET.SubElement(settings, 'string', {'name': 'language'}).text = 'ar'
    write_prefs(ET.tostring(settings, encoding='utf-8'))
    adb('shell', 'wm', 'size', '3840x2160')
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.' + 'MatchesActivity')
    time.sleep(3)
    key('UP')
    # In Arabic the first tab is physically rightmost. LEFT must advance through the row.
    for _ in range(3): key('RIGHT')
    focused('live')
    live = [int(v) for v in re.findall(r'\d+', tab('live').get('bounds'))]
    upcoming = [int(v) for v in re.findall(r'\d+', tab('upcoming').get('bounds'))]
    assert live[0] > upcoming[0], (live, upcoming)
    for name in ('upcoming', 'today', 'yesterday'):
        key('LEFT')
        focused(name)
        key('CENTER')
        key('UP')
        focused(name)
    for name in ('today', 'upcoming', 'live'):
        key('RIGHT')
        focused(name)
    (OUT / 'rtl-matches-4k.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    from PIL import Image
    assert Image.open(OUT / 'rtl-matches-4k.png').size == (3840, 2160)
    (OUT / 'rtl-4k.log').write_text('PASS: Arabic app language with English system; RTL tab positions and physical LEFT/RIGHT focus, OK and UP tested at 3840x2160 on Android emulator.\nPhysical TV performance was not measured.\n')
    print((OUT / 'rtl-4k.log').read_text(), flush=True)
finally:
    adb('shell', 'am', 'force-stop', PKG)
    write_prefs(saved)
    adb('shell', 'wm', 'size', override.group(1) if override else 'reset')
