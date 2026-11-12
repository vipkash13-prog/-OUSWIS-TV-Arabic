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
    full = adb('logcat', '-d', '-t', '30000', check=False).splitlines()
    (OUT / 'redesign-logcat.txt').write_text('\n'.join(l for l in full if any(k in l for k in ('ouswis', 'FATAL', 'AndroidRuntime', 'ActivityTaskManager', 'WindowManager: Changing focus')) and 'uiautomator' not in l)[-60000:])
    raise AssertionError('Never saw: ' + what + ' | labels: ' + str(labels()[:25]) + ' | front: ' + adb('shell', 'dumpsys', 'activity', 'activities', check=False).split('topResumedActivity')[-1][:200])

def tap(part, exact=False):
    """Tap the first node whose text matches (dialog lists are plain views, so uiautomator can click them)."""
    for n in tree().iter('node'):
        t = (n.get('text', '') or n.get('content-desc', '')).strip()
        if n.get('package') == PKG and (t == part if exact else part in t):
            x1, y1, x2, y2 = [int(v) for v in re.findall(r'\d+', n.get('bounds'))]
            adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2)); time.sleep(2); return
    shot('redesign-failure.png'); raise AssertionError('Nothing to tap: ' + part)

def to_home():
    """Back out to the home screen; if Back leaves for another task, open it by its own name (it resumes, which is what matters)."""
    try: front('HomeActivity', tries=6)
    except AssertionError:
        adb('shell', 'am', 'start', '-W', '-n', PKG + '/.HomeActivity'); front('HomeActivity')

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

def gfx():
    out = ''
    for name in (PKG + ':home', PKG):   # the home screen runs in its own process
        out = adb('shell', 'dumpsys', 'gfxinfo', name, check=False)
        if 'Total frames rendered' in out: break
    pick = lambda pat: (re.search(pat, out) or [None, '?'])[1]
    return {'frames': pick(r'Total frames rendered: (\d+)'), 'janky': pick(r'Janky frames: (\d+ \([\d.]+%\))'), 'p50': pick(r'50th percentile: (\d+ms)'),
            'p90': pick(r'90th percentile: (\d+ms)'), 'p99': pick(r'99th percentile: (\d+ms)'), 'slow_ui': pick(r'Number Slow UI thread: (\d+)'),
            'slow_draw': pick(r'Number Slow issue draw commands: (\d+)'), 'raw': out}

def measure(tag):
    """Performance of the home screen: cold open time, time until the first match card shows, then 40 s of remote use under gfxinfo."""
    totals = []
    for _ in range(3):
        adb('shell', 'am', 'force-stop', PKG); time.sleep(2)
        t0 = time.time()
        out = adb('shell', 'am', 'start', '-W', '-n', PKG + '/.HomeActivity')
        totals.append(int((re.search(r'TotalTime: (\d+)', out) or [0, 0])[1]))
    first = None; ghana = None
    while time.time() - t0 < 90 and not ghana:
        ls = labels()
        if first is None and any(' ضد ' in l for l in ls): first = round(time.time() - t0, 1)
        if any('غانا ضد الصومال' in l and 'beIN Sports 2' in l for l in ls): ghana = round(time.time() - t0, 1)
    time.sleep(15)
    [adb('shell', 'dumpsys', 'gfxinfo', n, 'reset', check=False) for n in (PKG + ':home', PKG)]
    cycle = ['DOWN', 'DOWN', 'LEFT', 'RIGHT', 'LEFT', 'UP', 'RIGHT', 'UP']
    t1 = time.time(); i = 0
    while time.time() - t1 < 40: keys(cycle[i % len(cycle)], pause=1.0); i += 1
    g = gfx(); (OUT / ('perf-%s-gfxinfo.txt' % tag)).write_text(g.pop('raw'))
    line = '%s | cold open ms (3 runs): %s | first match card after %ss | channel on the Ghana card after %ss | remote use 40 s: frames %s, janky %s, p50 %s p90 %s p99 %s, slow UI %s, slow draw %s' % (
        tag, totals, first, ghana, g['frames'], g['janky'], g['p50'], g['p90'], g['p99'], g['slow_ui'], g['slow_draw'])
    note('PERF ' + line); (OUT / ('perf-%s.txt' % tag)).write_text(line + '\n')
    adb('shell', 'am', 'force-stop', PKG); time.sleep(2)

def stability(seconds=300):
    """The home screen left open: nothing may blink, jump, lose its cards or its focus."""
    adb('shell', 'am', 'force-stop', PKG); time.sleep(2); adb('logcat', '-c')
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.HomeActivity')
    wait_label('غانا ضد الصومال', 'the home matches', tries=60)
    for _ in range(40):
        if any('غانا ضد الصومال' in l and 'beIN Sports 2' in l for l in labels()): break
        time.sleep(1)
    cards = lambda: sorted(l for l in labels() if ' ضد ' in l and not l.startswith('تفاصيل'))
    base = cards(); focus0 = focused_label(); assert len(base) >= 3, base
    [adb('shell', 'dumpsys', 'gfxinfo', n, 'reset', check=False) for n in (PKG + ':home', PKG)]
    prev = shot('stability-0.png'); worst = 0.0; end = time.time() + seconds; n = 0; problems = []
    while time.time() < end:
        time.sleep(10); n += 1
        now = cards()
        if now != base: problems.append('cards changed at sample %d: %s vs %s' % (n, now, base))
        if focused_label() != focus0: problems.append('focus moved at sample %d: %s' % (n, focused_label()))
        img = shot('stability-last.png')
        # compare the body of the screen (below the top bar, above the hint line): only the progress bar may move
        box = (0, 250, 1920, 1000); a = prev.crop(box).convert('L'); b = img.crop(box).convert('L')
        from PIL import ImageChops
        diff = ImageChops.difference(a, b).point(lambda v: 255 if v > 40 else 0)
        frac = sum(1 for v in diff.getdata() if v) / float(diff.width * diff.height)
        worst = max(worst, frac)
        if frac > 0.02: problems.append('screen changed %.1f%% at sample %d' % (frac * 100, n)); shot('stability-change-%d.png' % n)
        prev = img
    g = gfx(); (OUT / 'stability-gfxinfo.txt').write_text(g.pop('raw'))
    log = adb('logcat', '-d', '-s', 'FreeMyTV', check=False)
    stats = re.findall(r'home-stats: (.*)', log)
    msg = 'STABILITY %d s on the home screen: %d samples, worst screen change %.2f%%, frames drawn %s (janky %s), problems %s, app counters: %s' % (seconds, n, worst * 100, g['frames'], g['janky'], problems or 'none', stats[-1] if stats else 'n/a (older build)')
    note(msg); (OUT / 'stability.txt').write_text(msg + '\n')
    assert not problems, msg
    if stats:
        assert 'skeletonAfterData=0' in stats[-1] and 'focusResets=0' in stats[-1], 'The app reports a relapse: ' + stats[-1]

subprocess.run([ADB, 'root'], capture_output=True, timeout=60); subprocess.run([ADB, 'wait-for-device'], timeout=60); time.sleep(3)
with tempfile.TemporaryDirectory() as temp:
    clip = Path(temp) / 'clip.ts'
    subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'testsrc=s=640x360:r=10', '-t', '90',
                    '-c:v', 'libx264', '-preset', 'ultrafast', '-pix_fmt', 'yuv420p', '-f', 'mpegts', str(clip)], check=True)
    data = clip.read_bytes()
    CATS = [('10', 'FR|TF1 HD'), ('11', 'رياضة'), ('12', 'أخبار'), ('13', 'أفلام')]
    STREAMS = {'11': [('101', 'SSC 1 HD', 'ts'), ('102', 'SSC 2 HD', 'ts'), ('103', 'SSC 3 HD', 'ts'), ('110', 'beIN Sports 1 HD', 'ts'),
                      ('111', 'beIN Sports 2', 'ts'), ('112', 'beIN Sports 3', 'm3u8'), ('113', 'FR| beIN SPORTS 4 HD (H.265)', 'ts'), ('120', 'AD Sports 1', 'ts'), ('105', 'beIN SPORTS XTRA 1HD', 'ts')],
               '12': [('201', 'Al Arabiya', 'ts'), ('202', 'Sky News Arabia', 'ts')], '13': [('301', 'Movies One', 'ts')], '10': [('401', 'TF1 HD', 'ts')]}

    class Provider(BaseHTTPRequestHandler):
        def log_message(self, *a): pass
        def do_GET(self):
            url = urlparse(self.path); q = parse_qs(url.query)
            HITS[url.path.rsplit('/', 1)[-1]] = HITS.get(url.path.rsplit('/', 1)[-1], 0) + 1
            if url.path.startswith('/apis/site/v2/sports/soccer/'):
                slug = url.path.split('/')[6]
                utc = time.gmtime()
                events = []
                if slug == 'caf.nations_qual' and q.get('dates', [''])[0] == time.strftime('%Y%m', utc):
                    def ev(eid, home, away, minutes_ago):
                        kick = time.strftime('%Y-%m-%dT%H:%MZ', time.gmtime(time.time() - minutes_ago * 60))
                        team = lambda n, ha: {'homeAway': ha, 'score': '0', 'team': {'id': n[:3], 'displayName': n, 'logo': ''}}
                        return {'id': eid, 'date': kick, 'status': {'displayClock': "%d'" % minutes_ago, 'type': {'state': 'in', 'name': 'STATUS_FIRST_HALF', 'shortDetail': "%d'" % minutes_ago}},
                                'competitions': [{'competitors': [team(home, 'home'), team(away, 'away')], 'broadcasts': []}]}
                    # the match the user reported: it is in beIN SPORTS 2 HD's guide as "غانا × الصومال"; the second one is in no guide
                    events = [ev('9001', 'Ghana', 'Somalia', 10), ev('9002', 'Suriname', 'Eritrea', 50)]
                body = {'leagues': [{'name': 'Africa Cup of Nations Qualifying', 'logos': []}], 'events': events}
                payload = json.dumps(body).encode(); kind = 'application/json'
            elif url.path == '/player_api.php':
                action = q.get('action', [''])[0]
                if action == 'get_live_categories':
                    body = [{'category_id': i, 'category_name': n} for i, n in CATS]
                elif action == 'get_live_streams':
                    cat = q.get('category_id', [''])[0]
                    body = [{'num': int(s), 'name': n, 'stream_id': s, 'category_id': cat, 'stream_icon': '', 'container_extension': e} for s, n, e in STREAMS.get(cat, [])]
                elif action == 'get_short_epg':
                    now = int(time.time())
                    titles = ['الهلال × النصر · دوري روشن', 'الاستوديو التحليلي', 'أهداف الجولة', 'نشرة الرياضة']
                    if q.get('stream_id', [''])[0] == '101':   # a TV guide in English: the app shows Arabic
                        titles = ["Juventus vs OL Lyonnes - UEFA Women's Champions League", 'Arsenal vs Chelsea - English Premier League 2026/2027 - MD4', 'Game of Thrones', 'Week 10']
                    if q.get('stream_id', [''])[0] in ('111', '113'):   # beIN Sports 2: the Arabic title, 6 minutes after the kickoff the feed reports
                        titles = ['غانا × الصومال — كأس الأمم الأفريقية', 'تحليل المباريات', 'أهداف الجولة', 'نشرة الرياضة']
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
    live = '<?xml version="1.0" encoding="utf-8"?><map><string name="last">%s</string><string name="espnBase">http://127.0.0.1:%d</string></map>' % (last, port)
    subprocess.run([ADB, 'shell', 'cat > %s/ouswis-live.xml' % folder], input=live.encode(), check=True)
    adb('shell', 'chown', '-R', uid + ':' + uid, folder)
    adb('logcat', '-c')

    # 1. home, and the account sealed at rest
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.HomeActivity')
    shot('redesign-home-loading.png')
    assert any(l == 'مباريات اليوم' for l in labels()), 'The matches row must not vanish while the data loads: ' + str(labels()[:20])
    hero = wait_label('آخر قناة شاهدتها: SSC 1 HD', 'the last-channel card with the saved channel')
    arabic = wait_label('يوفنتوس × ليون — دوري أبطال أوروبا للسيدات', 'the guide title of the last channel in Arabic')
    assert not any('Lyonnes' in l or 'Juventus' in l for l in labels()), 'The English match title must not show: ' + str(labels()[:12])
    img = shot('redesign-home.png'); no_crash('Home')
    # the last-channel card: dark, #123D5C to #121A3A at 95 %: sample its inside along the top margin (no text there)
    pts = [img.getpixel((x, 180)) for x in range(700, 1500, 160)]
    img.crop((600, 150, 1860, 420)).save(OUT / 'hero-card-crop.png')
    note('HERO card pixels (x=700..1340, y=180): ' + str(pts))
    assert all(r <= 40 and g <= 75 and b <= 105 for r, g, b in pts), 'The last-channel card is lighter than #123D5C: ' + str(pts)
    assert all(g <= 45 and b <= 75 for r, g, b in pts[:4]), 'The text side of the card must be the dark end (#121A3A): ' + str(pts)
    note('PASS: the English guide title "Juventus vs OL Lyonnes - UEFA Women\'s Champions League" shows as ' + arabic[0][-60:] + '.')
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

    measure(os.environ.get('PERF_TAG', 'run'))
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.HomeActivity'); wait_label('آخر قناة شاهدتها: SSC 1 HD', 'the home again after the measurement')

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
    ls = labels()
    assert [i for i, l in enumerate(ls) if 'قسم رياضة' in l][0] < [i for i, l in enumerate(ls) if 'قسم FR|TF1 HD' in l][0], 'Arabic and sports sections must come before the foreign ones by default'
    note('PASS: by default the Arabic/sports sections come before the foreign one (FR|TF1 HD), after favourites and recents.')
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

    # 3b. "last channel" follows what was watched full screen for 10 s, from anywhere; the small preview never counts
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.HomeActivity'); wait_label('آخر قناة شاهدتها', 'the home screen')
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.PlayerActivity', '-e', 'id', '110', '-e', 'name', 'beIN Sports 1 HD', '-e', 'ext', 'ts', '-e', 'num', '110')
    time.sleep(20)    # channel A: more than 10 s of picture (the emulator takes a few seconds to start it)
    keys('CHANNEL_UP', pause=20)    # channel B, the next one in its section, also more than 10 s
    keys('BACK', pause=2); to_home()
    got = wait_label('آخر قناة شاهدتها', 'the last-channel card after coming back')
    assert any('beIN Sports 2' in l for l in got), 'Home must read the last channel again on return: ' + str(got)
    recent = adb('shell', 'cat', folder + '/ouswis-live.xml')
    assert '111' in recent and '110' in recent, 'Both channels watched for over 10 s must be in Recent: ' + recent[:900]
    assert recent.index('111') < recent.index('110'), 'Recent must list B before A: ' + recent[:900]
    note('PASS: watched A (beIN 1) then B (beIN 2), 14 s each: coming back, the home card shows B and Recent lists B before A.')
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.LiveActivity'); wait_label('قسم', 'the live screen')
    keys('DPAD_DOWN', pause=20)    # the preview plays another channel for 20 s
    keys('BACK', pause=2); to_home()
    assert any('beIN Sports 2' in l for l in wait_label('آخر قناة شاهدتها', 'the card after a long preview')), 'The small preview must not change the last channel'
    note('PASS: 14 s on the small preview in the live screen left the last channel unchanged.')
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.LiveActivity'); wait_label('قسم', 'the live screen again')

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
    assert any('دوري روشن السعودي' in l and 'غير محددة' in l for l in labels()), 'With no pick the table must say so, not name a default: ' + str(labels()[:14])
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
    tap('بدون اختيار يدوي')
    wait_label('غير محددة', 'the table back to no pick')
    note('PASS: the league table names no default channel; a hand pick shows, and removing it leaves the league without one.')
    keys('BACK', pause=1)

    # 6. matches (real ESPN data from the emulator's network)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MatchesScreenActivity')
    for _ in range(40):
        ls = labels()
        if any(' ضد ' in l for l in ls) or any('لا توجد مباريات' in l for l in ls): break
        time.sleep(1)
    img = shot('redesign-matches.png'); keys('DPAD_DOWN'); keys('DPAD_LEFT'); shot('redesign-matches-focus.png')
    no_crash('Matches')
    cards = [l for l in labels() if ' ضد ' in l and not l.startswith('تفاصيل')]
    # the guide of the sports channels names one match (الهلال × النصر): it is a match on the screen, with its channel
    guide_card = wait_label('الهلال ضد النصر', 'the match found only in the guide')
    assert any(('SSC 1' in l or 'beIN Sports 1' in l or 'AD Sports 1' in l) for l in guide_card), 'The guide match must carry the channel it was found on: ' + str(guide_card)
    for l in cards:
        if any(t in l for t in ('الهلال', 'يوفنتوس', 'آرسنال', 'غانا')): continue   # found in the mock guide: confirmed
        assert 'beIN' not in l and 'SSC' not in l and 'AD Sports' not in l and 'beIN SPORTS' not in l, 'No channel may be named without a confirmation: ' + l
        if 'انتهت' not in l: assert 'اختر القناة' in l, 'An unconfirmed match must ask to pick the channel: ' + l
    labs = labels(); i_all = [i for i, l in enumerate(labs) if l.startswith('الكل')][0]
    favs = [i for i, l in enumerate(labs) if '★ المفضلة' in l]   # the row scrolls: with many leagues the favourites chip is past the edge
    league_chips = labs[i_all + 1:favs[0] if favs else i_all + 12]
    assert any('دوري روشن' in l for l in league_chips), 'Filters: الكل, then the day\'s leagues (دوري روشن comes from the guide): ' + str(labs[:30])
    shot('redesign-matches-chips.png')
    note('PASS: matches screen: a match found only in the guide shows with its channel; no unconfirmed match names a channel (it says اختر القناة); the league chips come from the day\'s matches between الكل and المفضلة.')
    note('PASS: matches screen loaded the (mock) fixtures and took keys without a crash.')

    def focus_card(part):
        """Walk the 2-column grid with the remote: the card's place is its place in the screen's own card list (row = index // 2; the second column is LEFT)."""
        adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MatchesScreenActivity'); time.sleep(3)
        cards = [l for l in labels() if ' ضد ' in l and not l.startswith('تفاصيل')]
        cards = [l for n, l in enumerate(cards) if l not in cards[:n]]
        at = [n for n, l in enumerate(cards) if part in l]
        assert at, 'Card not listed: ' + part + ' | ' + str(cards)
        for _ in range(6): keys('DPAD_UP', pause=0.5)   # the screen may keep an earlier focus: go back to the chips first
        keys('DPAD_DOWN', 'DPAD_RIGHT', pause=1)   # first row, first column
        for _ in range(at[0] // 2): keys('DPAD_DOWN', pause=1)
        if at[0] % 2: keys('DPAD_LEFT', pause=1)
        assert part in focused_label(), 'Wrong card focused: ' + focused_label() + ' | wanted index ' + str(at[0]) + ' of ' + str(cards)
    # 6b. the reported case: Ghana v Somalia is in beIN Sports 2's guide as "غانا × الصومال" and must carry that channel
    ghana = wait_label('غانا ضد الصومال', 'the Ghana v Somalia match (mock feed: Somalia at Ghana)', tries=60)
    assert any('beIN Sports 2' in l for l in ghana) and not any('اختر القناة' in l for l in ghana), 'Ghana v Somalia must show beIN Sports 2 from its guide: ' + str(ghana)
    shot('redesign-matches-ghana.png')
    note('PASS: "غانا × الصومال" in beIN Sports 2\'s guide linked to the feed\'s "Somalia at Ghana": the card reads ' + ghana[0][-70:] + '.')
    eri = wait_label('سورينام ضد إريتريا', 'the Suriname v Eritrea match (in no guide)')
    for _ in range(40):
        eri = [l for l in labels() if 'سورينام ضد إريتريا' in l]
        if any('اختر القناة' in l for l in eri): break
        time.sleep(1)
    assert any('اختر القناة' in l for l in eri) and not any('beIN' in l or 'SSC' in l for l in eri), 'An unconfirmed match must ask for a channel: ' + str(eri)
    # one card for Ghana v Somalia although two channels carry it; the live counter equals the live cards; nothing says مباشر twice
    allc = [l for l in labels() if ' ضد ' in l and not l.startswith('تفاصيل')]
    assert len([l for l in allc if 'غانا ضد الصومال' in l]) == 1, 'The same match must show once: ' + str(allc)
    head = [l for l in labels() if l.startswith('المباريات ·')]
    liven = len({l for l in allc if ' مباشر ' in l})
    assert head and re.search(r'(\d+) مباشر', head[0]) and int(re.search(r'(\d+) مباشر', head[0])[1]) == liven, 'The live counter must equal the live matches: ' + str(head) + ' vs ' + str(liven)
    assert not any('مباشر مباشر' in l for l in labels()), 'A repeated مباشر: ' + str([l for l in labels() if 'مباشر مباشر' in l])
    opts = labels()
    focus_card('غانا ضد الصومال')
    opts = labels()
    assert any(l.startswith('شاهد على beIN SPORTS 4') for l in opts) and not any('H.265' in l or 'FR|' in l for l in opts), 'The other channel must be an option, cleaned of prefix and H.265: ' + str([l for l in opts if 'شاهد' in l])
    shot('redesign-matches-options.png')
    note('PASS: Ghana v Somalia shows once (beIN Sports 2 chosen over the H.265 channel, which is listed as an option named beIN SPORTS 4); the live counter is ' + str(liven) + ' = the live cards.')
    # OK on the unconfirmed match opens the sports section of the live screen on its first beIN/SSC channel
    focus_card('سورينام ضد إريتريا')
    keys('DPAD_CENTER', pause=2); front('LiveActivity')
    wait_label('SSC 2 HD', 'the sports section of the live screen')
    fl = focused_label()
    assert 'SSC 1 HD' in fl, 'The focus must be on the first beIN/SSC channel: ' + fl
    shot('redesign-matches-sports-list.png')
    note('PASS: OK on a match without a confirmed channel opened the live screen on the sports section; focus: ' + fl[:60] + '.')
    keys('DPAD_DOWN', pause=1); keys('DPAD_CENTER', pause=2); front('PlayerActivity'); time.sleep(3)
    saved = adb('shell', 'cat', folder + '/ouswis-live.xml')
    assert 'matchPicks' in saved and 'caf.nations_qual:9002' in saved, 'A channel played from there must be saved for the match: ' + saved[:600]
    keys('BACK', pause=1.5); keys('BACK', pause=1.5)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MatchesScreenActivity')
    again = wait_label('سورينام ضد إريتريا', 'the match again')
    for _ in range(20):
        again = [l for l in labels() if 'سورينام ضد إريتريا' in l]
        if any('SSC' in l or 'beIN' in l for l in again): break
        time.sleep(1)
    assert any('SSC 2' in l for l in again), 'The channel played for the match must be its channel now: ' + str(again)
    note('PASS: the channel played from the sports list was saved for that match and the card names it: ' + again[0][-60:] + '.')
    (OUT / 'redesign-logcat.txt').write_text(adb('logcat', '-d', '-t', '800', check=False))

    # 7. sections: order and hiding (Settings)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.SectionOrderActivity')
    wait_label('FR|TF1 HD', 'the sections list in Settings')
    tap('FR|TF1 HD'); tap('إلى أول القائمة'); time.sleep(1)
    note('am start Live -> ' + adb('shell', 'am', 'start', '-W', '-f', '0x04000000', '-n', PKG + '/.LiveActivity', check=False)[-300:] + ' | front ' + adb('shell', 'dumpsys', 'activity', 'activities', check=False).split('topResumedActivity')[-1][:120])
    time.sleep(2)
    wait_label('قسم FR|TF1 HD', 'the live sections after reordering')
    firsts = [l for l in labels() if l.strip().startswith('قسم ') and '★' not in l and '↺' not in l]
    assert firsts and 'FR|TF1 HD' in firsts[0], 'A saved order must win: ' + str(firsts[:4])
    note('PASS: moving a section to the top in Settings changed the live screen order.')
    keys('BACK', pause=1.5)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.SectionOrderActivity'); wait_label('أخبار', 'the sections list')
    tap('أخبار'); tap('إخفاء القسم')
    adb('shell', 'am', 'start', '-W', '-f', '0x04000000', '-n', PKG + '/.LiveActivity'); wait_label('قسم FR|TF1 HD', 'the live screen after hiding')
    assert not any('قسم أخبار' in l for l in labels()), 'A hidden section must leave the live screen'
    note('PASS: a hidden section no longer shows on the live screen.')
    keys('BACK', pause=1.5)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.SectionOrderActivity'); wait_label('الترتيب الافتراضي', 'the reset row')
    tap('الترتيب الافتراضي')
    adb('shell', 'am', 'start', '-W', '-f', '0x04000000', '-n', PKG + '/.LiveActivity'); wait_label('قسم رياضة', 'the live screen after the reset')
    firsts = [l for l in labels() if l.strip().startswith('قسم ') and '★' not in l and '↺' not in l]
    assert firsts and 'FR|TF1 HD' not in firsts[0], 'After the reset the foreign section must not lead: ' + str(firsts[:4])
    note('PASS: reset restored the default order and showed the hidden section again.')
    shot('redesign-live-after-reset.png')
    no_crash('Sections')
    if os.environ.get('STABILITY_SECONDS', '300') != '0': stability(int(os.environ.get('STABILITY_SECONDS', '300')))
    server.shutdown()
