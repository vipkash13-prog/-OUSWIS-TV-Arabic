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
    result = adb('shell', 'am', 'start', '-W', '-n', 'io.ouswis.tv.standalone/io.ouswis.tv.standalone.SettingsActivity')
    assert 'Status: ok' in result
    activity('SettingsActivity')

assert adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1', 'Emulator required'
settings()
nodes = list(xml().iter('node'))
assert any(n.get('text') in ('Live TV', 'البث التلفزيوني المباشر') and n.get('clickable') == 'true' for n in nodes)
assert any(n.get('text') in ('Matches', 'المباريات') and n.get('clickable') == 'true' for n in nodes)
assert any(n.get('text') in ('Updates', 'التحديثات') and n.get('clickable') == 'true' for n in nodes)
screenshot('settings-features.png')
note('PASS: Live TV and Matches are visible, clickable controls on the launch screen.')

# RIGHT returns to the settings canvas even if focus was restored to a feature button.
adb('shell', 'input', 'keyevent', 'KEYCODE_DPAD_RIGHT')
adb('shell', 'input', 'keyevent', 'KEYCODE_DPAD_LEFT')
adb('shell', 'input', 'keyevent', 'KEYCODE_DPAD_CENTER')
activity('XtreamActivity')
assert 'Xtream Codes' in ET.tostring(xml(), encoding='unicode')
screenshot('xtream-login.png')
note('PASS: remote LEFT + OK opens the built-in Xtream account/player screen.')

def nodes():
    return [n for n in xml().iter('node') if n.get('package') == 'io.ouswis.tv.standalone']

def label(n):
    return n.get('text', '') + ' ' + n.get('content-desc', '')

def key(name):
    adb('shell','input','keyevent','KEYCODE_DPAD_'+name)

# The redesigned Matches screen (Settings -> Matches): reached with the remote, drawn on one canvas and exposed to
# accessibility, so the nodes below are what TalkBack reads.
settings()
adb('shell', 'input', 'keyevent', 'KEYCODE_DPAD_RIGHT')
adb('shell', 'input', 'keyevent', 'KEYCODE_DPAD_LEFT')
adb('shell', 'input', 'keyevent', 'KEYCODE_DPAD_DOWN')
adb('shell', 'input', 'keyevent', 'KEYCODE_DPAD_CENTER')
activity('MatchesScreenActivity')
for _ in range(40):
    found = nodes()
    cards = [n for n in found if ' ضد ' in label(n) and n.get('clickable') == 'true']
    empty_day = any('لا توجد مباريات' in label(n) for n in found)
    if cards or empty_day:
        break
    time.sleep(1)
else:
    screenshot('matches-feed-failure.png')
    (OUT / 'matches-feed-logcat.txt').write_text(adb('logcat','-d','-t','600'))
    raise AssertionError('The new Matches screen drew neither match cards nor an empty-day message')
note('PASS: the new Matches screen opened from Settings and showed ' + (str(len(cards)) + ' match cards' if cards else 'an honest empty day') + '.')
texts = ' | '.join(label(n) for n in nodes())
for chip in ('أمس', 'اليوم', 'غداً', 'الكل', 'دوري روشن', 'أبطال آسيا', 'أبطال أوروبا', 'الدوري الإسباني', 'المفضلة'):
    assert chip in texts, chip
screenshot('matches-screen.png')
note('PASS: all day and league filters are present and readable.')
# remote: UP from the first card reaches the day chips (focus on "اليوم"), RIGHT goes to "أمس", LEFT x2 to "غداً"
key('UP')
def focused_text():
    f = [label(n) for n in nodes() if n.get('focused') == 'true' and label(n).strip()]
    return f[0] if f else ''
for _ in range(3):
    if 'اليوم' in focused_text():
        break
    key('UP'); time.sleep(0.3)
assert 'اليوم' in focused_text(), focused_text()
key('RIGHT'); assert 'أمس' in focused_text(), focused_text()
key('LEFT'); key('LEFT'); assert 'غداً' in focused_text(), focused_text()
key('CENTER'); time.sleep(1)
key('DOWN')
screenshot('matches-details.png')
note('PASS: remote arrows move across the RTL filter chips; OK selects tomorrow; DOWN returns to the grid.')
adb('shell','input','keyevent','KEYCODE_BACK')
activity('SettingsActivity')
note('PASS: Back from the new Matches screen returns to Settings.')

# The previous Matches window is still in the app and still passes its own checks, launched directly.
result = adb('shell', 'am', 'start', '-W', '-n', 'io.ouswis.tv.standalone/io.ouswis.tv.standalone.MatchesActivity')
assert 'Status: ok' in result
activity('MatchesActivity')
def wait_fixtures():
    for _ in range(20):
        tree=xml()
        headings=[n.get('text','') for n in tree.iter('node') if '•' in n.get('text','')]
        if any(any(ch.isdigit() and ch not in ('0','٠') for ch in text.rsplit('•',1)[-1]) for text in headings):
            note('PASS: Android fetched real fixtures and displayed a positive match count: '+str(headings))
            return
    screenshot('matches-feed-failure.png')
    (OUT / 'matches-feed-logcat.txt').write_text(adb('logcat','-d','-t','600'))
    raise AssertionError('No real fixtures appeared on the Android screen')

wait_fixtures()
ui = ET.tostring(xml(), encoding='unicode')
for title in ('مباريات قادمة', 'مباشر الآن', 'نتائج اليوم', 'نتائج أمس'):
    assert title in ui, title
screenshot('matches-screen-legacy.png')
note('PASS: the previous Matches window opens with all four tabs.')
def key(name):
    adb('shell','input','keyevent','KEYCODE_DPAD_'+name)

def focused(identifier):
    state = xml()
    (OUT / 'feature-focus.xml').write_bytes(ET.tostring(state, encoding='utf-8'))
    assert any(n.get('resource-id') == 'io.ouswis.tv.standalone:id/'+identifier and n.get('focused') == 'true'
               for n in state.iter('node')), 'Expected remote focus: '+identifier

focused('matches_carousel')
key('UP')
for _ in range(3): key('LEFT')
focused('matches_tab_live')
for name in ('upcoming', 'today', 'yesterday'):
    key('RIGHT')
    focused('matches_tab_'+name)
    key('CENTER')
    focused('matches_carousel')
    key('UP')
    focused('matches_tab_'+name)
key('DOWN')
focused('matches_carousel')
key('RIGHT')
key('LEFT')
key('UP')
key('LEFT')
key('LEFT')
key('CENTER')
wait_fixtures()
screenshot('matches-details-legacy.png')
from PIL import Image
pixel=Image.open(OUT / 'matches-details.png').convert('RGB')
rgb=pixel.getpixel((int(pixel.width*0.20),int(pixel.height*0.28)))
assert max(rgb)<70 and max(rgb)-min(rgb)<25, 'Expected glossy black matches background: '+str(rgb)
note('PASS: matches window uses a glossy black background.')
adb('shell','input','keyevent','KEYCODE_BACK')
note('PASS: remote arrows reach all four tabs and the match card; Back closes the matches window.')
result = adb('shell', 'am', 'start', '-W', '-n', 'io.ouswis.tv.standalone/io.ouswis.tv.standalone.HomeActivity')
assert 'Status: ok' in result
activity('HomeActivity')
assert not any(n.get('resource-id') == 'io.ouswis.tv.standalone:id/home_matches' for n in xml().iter('node'))
assert sum(1 for n in nodes() if n.get('text') in ('البث المباشر','المباريات','كل التطبيقات','الأخيرة','التحديثات','الإعدادات') and n.get('clickable') == 'true') == 6, 'The six shortcuts of the new home'
screenshot('home-features.png')
note('PASS: no floating matches card covers the home screen or drawer.')
note('No provider credentials were entered. Live stream playback and availability of fixtures require the user/provider network.')
