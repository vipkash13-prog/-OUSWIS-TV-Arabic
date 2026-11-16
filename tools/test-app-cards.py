#!/usr/bin/env python3
"""All Apps card test on an emulator: the app and four code-less test apps (tools/make-test-apps.py) are installed,
All Apps is opened and each card is checked on the screenshot; Kodi too when the run could download it.
usage: test-app-cards.py FreeMyTV.apk testapps-dir [kodi.apk]"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from io import BytesIO
from pathlib import Path
from PIL import Image

PKG = 'io.ouswis.tv.standalone'
APK, APPS = sys.argv[1], Path(sys.argv[2])
KODI = Path(sys.argv[3]) if len(sys.argv) > 3 and Path(sys.argv[3]).exists() else None
OUT = Path('app-cards-evidence'); OUT.mkdir(exist_ok=True)
CARD = (26, 31, 58)
lines = []

def note(m):
    print(m, flush=True); lines.append(m); (OUT / 'app-cards.log').write_text('\n'.join(lines) + '\n')

def adb(*a, check=True):
    r = subprocess.run(['adb', *a], capture_output=True, timeout=180)
    out = (r.stdout + r.stderr).decode('utf-8', 'replace')
    if check and r.returncode != 0: raise AssertionError('adb %s failed: %s' % (' '.join(a), out[-400:]))
    return out

def install(p):
    out = adb('install', '-r', str(p), check=False)
    return 'Success' in out, out.strip()[-200:]

def shot():
    data = subprocess.check_output(['adb', 'exec-out', 'screencap', '-p'], timeout=60)
    (OUT / 'all-apps.png').write_bytes(data)
    return Image.open(BytesIO(data)).convert('RGB')

def nodes():
    for _ in range(8):
        try:
            adb('shell', 'rm', '-f', '/data/local/tmp/ac.xml'); adb('shell', 'uiautomator', 'dump', '/data/local/tmp/ac.xml')
            root = ET.fromstring(adb('shell', 'cat', '/data/local/tmp/ac.xml').replace('\r', ''))
            found = {}
            for n in root.iter('node'):
                if n.get('package') != PKG: continue
                t = (n.get('text', '') or n.get('content-desc', '')).strip()
                m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.get('bounds', ''))
                if t and m: found[t] = tuple(map(int, m.groups()))
            return found
        except (AssertionError, ET.ParseError, subprocess.SubprocessError):
            time.sleep(2)
    raise AssertionError('uiautomator never answered')

def near(p, want, tol=14): return all(abs(p[i] - want[i]) <= tol for i in range(3))

ok, out = install(APK); assert ok, 'the app did not install: ' + out
for apk in sorted(APPS.glob('*.apk')):
    ok, out = install(apk); assert ok, apk.name + ' did not install: ' + out
note('PASS: the app and %d test apps installed.' % len(list(APPS.glob('*.apk'))))
kodi_installed = False
if KODI:
    kodi_installed, out = install(KODI)
    note('Kodi: ' + ('installed' if kodi_installed else 'did not install: ' + out))
else:
    note('Kodi: not downloaded in this run')
subprocess.run(['adb', 'root'], capture_output=True, timeout=60); subprocess.run(['adb', 'wait-for-device'], timeout=60); time.sleep(3)   # AllAppsActivity is not exported
adb('shell', 'appops', 'set', PKG, 'SYSTEM_ALERT_WINDOW', 'allow', check=False)
adb('logcat', '-c')
adb('shell', 'am', 'start', '-W', '-n', PKG + '/.AllAppsActivity')
time.sleep(10)
n = nodes()
img = shot()
W, H = img.size
note('Screenshot %dx%d; cards seen: %s' % (W, H, sorted(k for k in n if k[:1] == '0' or 'Kodi' in k)))
log = adb('logcat', '-d', '-s', 'FreeMyTV', check=False)
(OUT / 'all-apps-tiles.txt').write_text('\n'.join(l for l in log.splitlines() if 'all-apps tile ' in l))

def at(name, fx, fy):
    x1, y1, x2, y2 = n[name]
    return img.getpixel((min(W - 1, int(x1 + (x2 - x1) * fx)), min(H - 1, int(y1 + (y2 - y1) * fy))))

def tile_log(pkg):
    m = re.findall(r'all-apps tile ' + re.escape(pkg) + r': (.*)', log)
    return m[-1].strip() if m else None

for pkg, name in (('card.lightbanner', '0A Light banner'), ('card.squarebanner', '0B Square banner'), ('card.whiteicon', '0C White icon'), ('card.wideicon', '0D Wide icon')):
    assert name in n, name + ' is not on screen: ' + str(sorted(n)[:30])
    note('Log: %s -> %s' % (pkg, tile_log(pkg)))
# a near-white banner is shown as it is: filled, never swapped for an icon
assert tile_log('card.lightbanner') == 'loadBanner', 'The light banner must come from loadBanner: ' + str(tile_log('card.lightbanner'))
for fx, fy in ((0.1, 0.1), (0.9, 0.1), (0.1, 0.9), (0.9, 0.9)):
    p = at('0A Light banner', fx, fy); assert min(p) >= 225, 'The light banner must fill the card, got %s at %s,%s' % (p, fx, fy)
p = at('0A Light banner', 0.5, 0.5); assert p[0] > 170 and p[1] < 90, 'The banner centre must show: ' + str(p)
note('PASS: a near-white banner fills the whole card (loadBanner), not swapped for an icon.')
# a square banner is shown whole and centred: its frame is not cropped, the card shows at its sides
assert tile_log('card.squarebanner') == 'loadBanner', str(tile_log('card.squarebanner'))
assert near(at('0B Square banner', 0.08, 0.5), CARD), 'The sides of a square banner must show the flat card: ' + str(at('0B Square banner', 0.08, 0.5))
top = at('0B Square banner', 0.5, 0.025); assert top[0] > 200 and top[1] < 70 and top[2] > 200, 'The square banner lost its top frame (cropped): ' + str(top)
mid = at('0B Square banner', 0.5, 0.5); assert mid[1] > 120 and mid[0] < 70, 'The square banner centre: ' + str(mid)
note('PASS: a square banner is shown whole and centred (frame intact, flat card at its sides).')
# an adaptive icon: white background and orange foreground together, centred, on the flat card
assert tile_log('card.whiteicon') and 'icon' in tile_log('card.whiteicon') and 'letter' not in tile_log('card.whiteicon'), str(tile_log('card.whiteicon'))
assert near(at('0C White icon', 0.08, 0.5), CARD), 'Outside the icon the card must be flat: ' + str(at('0C White icon', 0.08, 0.5))
assert min(at('0C White icon', 0.36, 0.25)) >= 225, 'The adaptive icon must keep its white background: ' + str(at('0C White icon', 0.36, 0.25))
o = at('0C White icon', 0.5, 0.5); assert o[0] > 200 and 90 < o[1] < 170 and o[2] < 70, 'The orange foreground must show in the centre: ' + str(o)
note('PASS: a white adaptive icon is drawn whole (background and foreground) in the centre of the flat card.')
# a wide legacy icon keeps its proportions: not stretched to a square
assert near(at('0D Wide icon', 0.08, 0.5), CARD)
c = at('0D Wide icon', 0.5, 0.5); assert c[2] > 200 and c[0] < 70, 'The wide icon centre: ' + str(c)
assert near(at('0D Wide icon', 0.5, 0.12), CARD), 'A wide icon must not be stretched to a square: ' + str(at('0D Wide icon', 0.5, 0.12))
note('PASS: a wide icon keeps its proportions, centred.')
if kodi_installed:
    k = [t for t in n if 'Kodi' in t]
    note('Kodi log: ' + str(tile_log('org.xbmc.kodi')))
    assert tile_log('org.xbmc.kodi') and 'letter' not in tile_log('org.xbmc.kodi'), 'Kodi shows a letter instead of its icon'
    if k:
        x1, y1, x2, y2 = n[k[0]]; img.crop((x1, y1, x2, y2)).save(OUT / 'kodi-tile.png')
        note('PASS: Kodi has its icon on the card (' + tile_log('org.xbmc.kodi') + '); crop saved as kodi-tile.png')
    else:
        note('Kodi is installed but its card is not on the first screen of All Apps.')
else:
    note('NOT TESTED: Kodi (no download in this run).')
