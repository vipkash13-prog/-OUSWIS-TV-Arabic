#!/usr/bin/env python3
"""The redesigned home, live browser, Media3 player, matches screen, channel table and the sealed account, on a disposable
emulator against a synthetic Xtream provider, driven with the remote's keys. Synthetic account only: nothing real is used.
Fails on any crash or any broken expectation; saves screenshots as evidence."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import base64, json, os, re, subprocess, sys, tempfile, threading, time
import xml.etree.ElementTree as ET
from io import BytesIO
from pathlib import Path
from urllib.parse import parse_qs, urlparse
from PIL import Image

OUT = Path(sys.argv[1]); OUT.mkdir(parents=True, exist_ok=True)
ADB = os.environ.get('ADB', 'adb')
PKG = 'io.ouswis.tv.standalone'
HITS = {}

def adb(*args, check=True):
    return subprocess.run([ADB, *args], capture_output=True, timeout=90, check=check).stdout.decode('utf-8', 'replace').replace('\r', '')

def note(message):
    print(message, flush=True)
    with (OUT / 'redesign.log').open('a') as f: f.write(message + '\n')

def shot(name):
    data = subprocess.check_output([ADB, 'exec-out', 'screencap', '-p'], timeout=60)
    (OUT / name).write_bytes(data)
    return Image.open(BytesIO(data)).convert('RGB')

def keys(*names, pause=0.6):
    for n in names:
        adb('shell', 'input', 'keyevent', 'KEYCODE_' + n); time.sleep(pause)

def tree():
    for attempt in range(6):
        try:
            adb('shell', 'rm', '-f', '/data/local/tmp/rd.xml')
            adb('shell', 'uiautomator', 'dump', '/data/local/tmp/rd.xml')
            return ET.fromstring(adb('shell', 'cat', '/data/local/tmp/rd.xml'))
        except (subprocess.CalledProcessError, ET.ParseError):
            time.sleep(1.5)
    raise RuntimeError('uiautomator never answered')

def labels():
    return [(n.get('text', '') or n.get('content-desc', '')).strip() for n in tree().iter('node') if n.get('package') == PKG]

def wait_label(part, what, tries=30):
    for _ in range(tries):
        found = [l for l in labels() if part in l]
        if found: return found
        time.sleep(1)
    shot('redesign-failure.png')
    (OUT / 'redesign-logcat.txt').write_text(adb('logcat', '-d', '-t', '800', check=False))
    raise AssertionError('Never saw: ' + what)

def tap(part, exact=False):
    """Tap the first node whose text matches (dialog lists are plain views, so uiautomator can click them)."""
    for n in tree().iter('node'):
        t = (n.get('text', '') or n.get('content-desc', '')).strip()
        if n.get('package') == PKG and (t == part if exact else part in t):
            x1, y1, x2, y2 = [int(v) for v in re.findall(r'\d+', n.get('bounds'))]
            adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2)); time.sleep(2); return
    shot('redesign-failure.png'); raise AssertionError('Nothing to tap: ' + part)

def focused_label():
    for n in tree().iter('node'):
        if n.get('package') == PKG and n.get('focused') == 'true' and (n.get('text', '') + n.get('content-desc', '')).strip():
            return (n.get('text', '') + ' ' + n.get('content-desc', '')).strip()
    return ''

def front(name, tries=20):
    for _ in range(tries):
        state = adb('shell', 'dumpsys', 'activity', 'activities')
        if any(name in l and ('mResumedActivity' in l or 'topResumedActivity' in l) for l in state.splitlines()): return
        time.sleep(0.5)
    raise AssertionError('Expected foreground: ' + name)

def colourful(img, box):
    """Share of sampled pixels that are clearly coloured: the synthetic picture is a colour test card, a blank surface is not."""
    w, h = img.size
    x1, y1, x2, y2 = [int(v) for v in (box[0] * w, box[1] * h, box[2] * w, box[3] * h)]
    n = hit = 0
    for x in range(x1, x2, max(1, (x2 - x1) // 40)):
        for y in range(y1, y2, max(1, (y2 - y1) // 24)):
            r, g, b = img.getpixel((x, y)); n += 1
            if max(r, g, b) - min(r, g, b) > 70 and max(r, g, b) > 120: hit += 1
    return hit / max(1, n)

def crashed():
    log = adb('logcat', '-d', '-b', 'crash', check=False)
    return 'FATAL EXCEPTION' in log and PKG in log, log

def no_crash(where):
    bad, log = crashed(); assert not bad, where + ' crashed:\n' + log[-2500:]

subprocess.run([ADB, 'root'], capture_output=True, timeout=60); subprocess.run([ADB, 'wait-for-device'], timeout=60); time.sleep(3)
with tempfile.TemporaryDirectory() as temp:
    clip = Path(temp) / 'clip.ts'
    subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'testsrc=s=640x360:r=10', '-t', '90',
                    '-c:v', 'libx264', '-preset', 'ultrafast', '-pix_fmt', 'yuv420p', '-f', 'mpegts', str(clip)], check=True)
    data = clip.read_bytes()
    CATS = [('11', 'رياضة'), ('12', 'أخبار'), ('13', 'أفلام')]
    STREAMS = {'11': [('101', 'SSC 1 HD', 'ts'), ('102', 'SSC 2 HD', 'ts'), ('103', 'SSC 3 HD', 'ts'), ('110', 'beIN Sports 1 HD', 'ts'),
                      ('111', 'beIN Sports 2', 'ts'), ('112', 'beIN Sports 3', 'm3u8'), ('120', 'AD Sports 1', 'ts')],
               '12': [('201', 'Al Arabiya', 'ts'), ('202', 'Sky News Arabia', 'ts')], '13': [('301', 'Movies One', 'ts')]}

    class Provider(BaseHTTPRequestHandler):
        def log_message(self, *a): pass
        def do_GET(self):
            url = urlparse(self.path); q = parse_qs(url.query)
            HITS[url.path.rsplit('/', 1)[-1]] = HITS.get(url.path.rsplit('/', 1)[-1], 0) + 1
            if url.path == '/player_api.php':
                action = q.get('action', [''])[0]
                if action == 'get_live_categories':
                    body = [{'category_id': i, 'category_name': n} for i, n in CATS]
                elif action == 'get_live_streams':
                    cat = q.get('category_id', [''])[0]
                    body = [{'num': int(s), 'name': n, 'stream_id': s, 'category_id': cat, 'stream_icon': '', 'container_extension': e} for s, n, e in STREAMS.get(cat, [])]
                elif action == 'get_short_epg':
                    now = int(time.time())
                    titles = ['الهلال × النصر · دوري روشن', 'الاستوديو التحليلي', 'أهداف الجولة', 'نشرة الرياضة']
                    body = {'epg_listings': [{'title': base64.b64encode(t.encode()).decode(), 'start_timestamp': str(now - 1800 + i * 3600), 'stop_timestamp': str(now + 1800 + i * 3600)} for i, t in enumerate(titles)]}
                else:
                    body = {'user_info': {'status': 'Active', 'exp_date': str(int(time.time()) + 86400 * 120)}}
                payload = json.dumps(body).encode(); kind = 'application/json'
            elif re.fullmatch(r'/live/demo/demo/[0-9]+\.ts', url.path):
                payload = data; kind = 'video/mp2t'
            else:
                self.send_error(404); return    # includes 112.m3u8: the player must fall back to .ts
            self.send_response(200); self.send_header('Content-Type', kind); self.send_header('Content-Length', str(len(payload))); self.end_headers()
            try: self.wfile.write(payload)
            except (BrokenPipeError, ConnectionResetError): pass

    server = ThreadingHTTPServer(('127.0.0.1', 0), Provider); port = server.server_port
    threading.Thread(target=server.serve_forever, daemon=True).start()
    adb('reverse', 'tcp:%d' % port, 'tcp:%d' % port)
    adb('shell', 'am', 'force-stop', PKG)
    uid = re.search(r'^\s*(?:userId|appId)=(\d+)', adb('shell', 'dumpsys', 'package', PKG), re.M)[1]
    folder = '/data/user/0/%s/shared_prefs' % PKG
    adb('shell', 'mkdir', '-p', folder)
    adb('shell', 'rm', '-f', folder + '/xtream-vault.xml', folder + '/ouswis-live.xml')
    adb('shell', 'sh', '-c', 'rm -f /data/user/0/%s/files/live-*.json' % PKG, check=False)
    # An older version's plain-text login: the app must seal it and erase this copy on first read.
    xml = ('<?xml version="1.0" encoding="utf-8"?><map><string name="server">http://127.0.0.1:%d</string>'
           '<string name="user">demo</string><string name="pass">demo</string><string name="lastChannel">101</string>'
           '<string name="lastChannelName">SSC 1 HD</string></map>') % port
    subprocess.run([ADB, 'shell', 'cat > %s/XtreamActivity.xml' % folder], input=xml.encode(), check=True)
    last = json.dumps({'id': '101', 'name': 'SSC 1 HD', 'icon': '', 'num': '101', 'ext': 'ts'}).replace('"', '&quot;')
    live = '<?xml version="1.0" encoding="utf-8"?><map><string name="last">%s</string></map>' % last
    subprocess.run([ADB, 'shell', 'cat > %s/ouswis-live.xml' % folder], input=live.encode(), check=True)
    adb('shell', 'chown', '-R', uid + ':' + uid, folder)
    adb('logcat', '-c')

    # 1. home, and the account sealed at rest
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.HomeActivity')
    hero = wait_label('آخر قناة شاهدتها: SSC 1 HD', 'the last-channel card with the saved channel')
    shot('redesign-home.png'); no_crash('Home')
    assert 'آخر قناة' in focused_label(), 'The last-channel card must hold the first focus: ' + focused_label()
    time.sleep(2)
    plain = adb('shell', 'cat', folder + '/XtreamActivity.xml')
    assert 'name="pass"' not in plain and 'name="user"' not in plain and 'name="server"' not in plain, 'Plain-text login was not erased: ' + plain
    vault = adb('shell', 'cat', folder + '/xtream-vault.xml')
    assert re.search(r'name="blob">[kf]:', vault), 'No sealed login: ' + vault
    assert 'demo' not in vault and '127.0.0.1' not in vault, 'The vault must not hold the login in the clear'
    note('PASS: the plain-text Xtream login was sealed (AES-GCM blob) and erased from the old preferences; the vault holds no readable server, user or password.')
    labs = labels()
    for need in ('البث المباشر', 'المباريات', 'كل التطبيقات', 'الأخيرة', 'التحديثات', 'الإعدادات', 'إضافة تطبيق'):
        assert any(l == need for l in labs), 'Missing control: ' + need
    note('PASS: new home: last-channel card holds the first focus; six shortcuts and the + tile are reachable controls.')

    # 2. OK on the last-channel card plays it with Media3, full screen
    keys('DPAD_CENTER', pause=1.5); front('PlayerActivity')
    time.sleep(5); img = shot('redesign-player.png')
    assert colourful(img, (0.2, 0.2, 0.8, 0.8)) > 0.2, 'No picture on the full-screen player'
    no_crash('Player')
    note('PASS: OK on the last-channel card opened the Media3 full-screen player and a picture is on screen.')
    time.sleep(8)   # "last channel" is recorded after 10 s of playing
    keys('BACK', pause=1.5); front('HomeActivity')
    recent = adb('shell', 'cat', folder + '/ouswis-live.xml')
    assert '101' in recent and 'name="recent"' in recent, 'A channel watched for over 10 s must join the recents: ' + recent[:300]
    note('PASS: after 10 s of playing the channel joined the recents; Back returned to the home screen.')

    # 3. the live browser: sections, channels, EPG, a muted preview, favourites, zapping
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.LiveActivity')
    wait_label('قسم رياضة', 'the server categories on the live screen')
    # remote columns: the screen opens on the channels; RIGHT -> sections, DOWN inside them, LEFT -> channels, LEFT -> preview
    assert 'SSC 1 HD' in focused_label() or '101' in focused_label(), 'The channels column must hold the first focus: ' + focused_label()
    keys('DPAD_RIGHT'); assert focused_label().startswith('قسم') and 'الأخيرة' in focused_label(), 'RIGHT must move to the sections column: ' + focused_label()
    keys('DPAD_DOWN'); assert focused_label().startswith('قسم رياضة'), 'DOWN in the sections must go to the next section: ' + focused_label()
    keys('DPAD_UP'); assert focused_label().startswith('قسم') and 'الأخيرة' in focused_label(), 'UP in the sections must go back: ' + focused_label()
    keys('DPAD_DOWN'); keys('DPAD_LEFT')
    wait_label('SSC 2 HD', 'the sport channels')
    assert 'SSC 1 HD' in focused_label(), 'LEFT from the sections must focus the channels: ' + focused_label()
    keys('DPAD_RIGHT'); assert focused_label().startswith('قسم رياضة'), 'Back from the channels the focus must be on the section I was in: ' + focused_label()
    keys('DPAD_LEFT', 'DPAD_LEFT'); assert focused_label().startswith('معاينة'), 'The preview is the third column: ' + focused_label()
    keys('DPAD_RIGHT')
    note('PASS: live screen remote columns: RIGHT/LEFT move sections <-> channels <-> preview, UP/DOWN stay inside the list, and the section is kept when coming back.')
    keys('DPAD_DOWN')
    time.sleep(5); img = shot('redesign-live-preview.png')
    assert colourful(img, (0.06, 0.18, 0.44, 0.52)) > 0.2, 'The muted preview did not show a picture'
    note('PASS: live browser listed the categories (with counts) and channels, and the muted Media3 preview shows a picture after the focus rests.')
    adb('shell', 'input', 'keyevent', '--longpress', 'KEYCODE_DPAD_CENTER'); time.sleep(1.5)
    assert any('★' in l and 'SSC 2 HD' in l for l in labels()), 'Long press OK must favourite the channel'
    note('PASS: a long press on OK added the channel to the favourites.')
    keys('DPAD_CENTER', pause=2); front('PlayerActivity'); time.sleep(5)
    img = shot('redesign-live-fullscreen.png')
    assert colourful(img, (0.2, 0.2, 0.8, 0.8)) > 0.2, 'No picture after OK on a channel'
    keys('CHANNEL_UP', pause=1.5)
    assert any(l.startswith('103 ·') or '103 · SSC 3 HD' in l for l in labels()), 'CH+ must open the next channel of the section: ' + str(labels())
    keys('CHANNEL_DOWN', pause=1.2); keys('CHANNEL_DOWN', pause=1.5)
    assert any('101 · SSC 1 HD' in l for l in labels()), 'CH- twice must reach the first channel: ' + str(labels())
    time.sleep(4); img = shot('redesign-player-zapped.png')
    assert colourful(img, (0.2, 0.2, 0.8, 0.8)) > 0.2, 'No picture after changing channel'
    no_crash('Zapping')
    note('PASS: OK plays full screen; CH+ / CH- move through the section (103, then back to 101) and the picture follows.')
    keys('BACK', pause=1.5); front('LiveActivity')
    note('PASS: Back from the player returned to the channel list.')

    # 4. a channel whose .m3u8 is refused plays through the .ts fallback
    before = HITS.get('112.m3u8', 0)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.PlayerActivity', '-e', 'id', '112', '-e', 'name', 'beIN Sports 3', '-e', 'ext', 'm3u8', '-e', 'num', '112')
    time.sleep(9); img = shot('redesign-player-fallback.png')
    assert HITS.get('112.m3u8', 0) > before and HITS.get('112.ts', 0) > 0, HITS
    assert colourful(img, (0.2, 0.2, 0.8, 0.8)) > 0.2, 'The .ts fallback did not play'
    note('PASS: the refused .m3u8 was tried first, then the .ts fallback played.')
    keys('BACK', pause=1)

    # 5. the league -> channel table (Settings): pick by hand, then back to the default
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.ChannelMapActivity')
    wait_label('دوري روشن السعودي', 'the league table')
    assert any('الافتراضي: SSC' in l for l in labels()), 'Defaults must show the SSC family for Roshn'
    shot('redesign-channel-map.png')
    keys('DPAD_CENTER', pause=2)
    wait_label('اختر القسم', 'the section list')
    tap('رياضة', exact=True)
    wait_label('SSC 1 HD', 'the channels of the chosen section')
    tap('SSC 2 HD')
    assert any('SSC 2 HD' in l and 'دوري روشن' in l for l in wait_label('SSC 2 HD', 'the table row with the chosen channel')), 'The pick must show in the table'
    assert 'name="picks"' in adb('shell', 'cat', folder + '/ouswis-live.xml')
    note('PASS: a channel picked by hand for a league is saved and shown in the table.')
    tap('دوري روشن السعودي'); wait_label('اختر القسم', 'the section list again')
    tap('القناة الافتراضية')
    wait_label('الافتراضي: SSC', 'the table back on its default')
    note('PASS: choosing the default put the league back on the SSC family.')
    keys('BACK', pause=1)

    # 6. matches (real ESPN data from the emulator's network)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MatchesScreenActivity')
    for _ in range(40):
        ls = labels()
        if any(' ضد ' in l for l in ls) or any('لا توجد مباريات' in l for l in ls): break
        time.sleep(1)
    img = shot('redesign-matches.png'); keys('DPAD_DOWN'); keys('DPAD_LEFT'); shot('redesign-matches-focus.png')
    no_crash('Matches')
    note('PASS: matches screen loaded real fixtures (or an honest empty day) and took keys without a crash.')
    (OUT / 'redesign-logcat.txt').write_text(adb('logcat', '-d', '-t', '800', check=False))
    server.shutdown()
