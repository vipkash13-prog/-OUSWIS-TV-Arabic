#!/usr/bin/env python3
"""Films (Xtream VOD, round 1) on a disposable 1920x1080 emulator against a synthetic provider, driven with the remote's
keys: the "أفلام" shortcut beside Live TV, the films screen (sections, poster grid with ratings, newest, sort, search),
an adult section that never shows, a film's page (get_vod_info), playback from /movie/USER/PASS/ID.ext with seeking,
the saved position ("continue from…"), continue watching, favourites, and Live TV still opening. Synthetic account only.
Fails on any crash or broken expectation; saves screenshots as evidence."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json, os, re, subprocess, sys, tempfile, threading, time
import xml.etree.ElementTree as ET
from io import BytesIO
from pathlib import Path
from urllib.parse import parse_qs, urlparse
from PIL import Image

OUT = Path(sys.argv[1]); OUT.mkdir(parents=True, exist_ok=True)
ADB = os.environ.get('ADB', 'adb')
PKG = 'io.ouswis.tv.standalone'
HITS = []   # (action or path, query) for every request

def adb(*args, check=True):
    return subprocess.run([ADB, *args], capture_output=True, timeout=90, check=check).stdout.decode('utf-8', 'replace').replace('\r', '')

def note(message):
    print(message, flush=True)
    with (OUT / 'vod.log').open('a') as f: f.write(message + '\n')

def shot(name):
    data = subprocess.check_output([ADB, 'exec-out', 'screencap', '-p'], timeout=60)
    (OUT / name).write_bytes(data)
    return Image.open(BytesIO(data)).convert('RGB')

def keys(*names, pause=0.7):
    for n in names:
        adb('shell', 'input', 'keyevent', 'KEYCODE_' + n); time.sleep(pause)

def tree():
    for _ in range(6):
        try:
            adb('shell', 'rm', '-f', '/data/local/tmp/vod.xml')
            adb('shell', 'uiautomator', 'dump', '/data/local/tmp/vod.xml')
            return ET.fromstring(adb('shell', 'cat', '/data/local/tmp/vod.xml'))
        except (subprocess.CalledProcessError, ET.ParseError):
            time.sleep(1.5)
    raise RuntimeError('uiautomator never answered')

def labels():
    return [(n.get('text', '') or n.get('content-desc', '')).strip() for n in tree().iter('node') if n.get('package') == PKG]

def focused_label():
    for n in tree().iter('node'):
        if n.get('package') == PKG and n.get('focused') == 'true' and (n.get('text', '') + n.get('content-desc', '')).strip():
            return (n.get('text', '') + ' ' + n.get('content-desc', '')).strip()
    return ''

def front(name, tries=24):
    for _ in range(tries):
        state = adb('shell', 'dumpsys', 'activity', 'activities')
        if any(name in l and ('mResumedActivity' in l or 'topResumedActivity' in l) for l in state.splitlines()): return
        time.sleep(0.5)
    shot('vod-failure.png')
    raise AssertionError('Expected foreground: ' + name)

def wait_label(part, what, tries=40):
    for _ in range(tries):
        found = [l for l in labels() if part in l]
        if found: return found
        time.sleep(1)
    shot('vod-failure.png')
    raise AssertionError('Never saw: ' + what + ' | labels: ' + str(labels()[:30]))

def no_crash(where):
    log = adb('logcat', '-d', '-b', 'crash', check=False)
    assert not ('FATAL EXCEPTION' in log and PKG in log), where + ' crashed:\n' + log[-2500:]

def colourful(img, box):
    w, h = img.size
    x1, y1, x2, y2 = [int(v) for v in (box[0] * w, box[1] * h, box[2] * w, box[3] * h)]
    n = hit = 0
    for x in range(x1, x2, max(1, (x2 - x1) // 40)):
        for y in range(y1, y2, max(1, (y2 - y1) // 24)):
            r, g, b = img.getpixel((x, y)); n += 1
            if max(r, g, b) - min(r, g, b) > 70 and max(r, g, b) > 120: hit += 1
    return hit / max(1, n)

def jhash(text):
    h = 0
    for ch in text: h = (31 * h + ord(ch)) & 0xFFFFFFFF
    return format(h, 'x')

def seconds(text):
    m = re.search(r'(?:(\d+):)?(\d+):(\d\d)', text)
    if not m: return -1
    return int(m[1] or 0) * 3600 + int(m[2]) * 60 + int(m[3])

def bar_position():
    """The player's bar shows "now من total" for a few seconds after a key."""
    for _ in range(4):
        for l in labels():
            if ' من ' in l and re.search(r'\d+:\d\d', l): return seconds(l), l
        time.sleep(0.5)
    return -1, ''

assert adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1', 'Disposable emulator required'
subprocess.run([ADB, 'root'], capture_output=True, timeout=60); subprocess.run([ADB, 'wait-for-device'], timeout=60); time.sleep(3)
if 'Physical size: 1920x1080' not in adb('shell', 'wm', 'size'): adb('shell', 'wm', 'size', '1920x1080')

NOW = int(time.time())
CATS = [('1', 'AR| أفلام عربية'), ('2', 'EN| Action'), ('3', 'XXX Adult 18+')]
COLOURS = [(200, 40, 40), (40, 160, 60), (40, 70, 200), (200, 150, 30), (150, 40, 180), (30, 160, 170), (210, 90, 30), (90, 90, 210), (170, 170, 40), (200, 60, 120)]
MOVIES = {   # id: (name, category, rating, added days ago)
    '501': ('ظل الصحراء', '1', '7.5', 1), '502': ('المدينة الأخيرة', '1', '7.9', 0), '503': ('سرّ البحر', '1', '6.6', 3), '504': ('رحلة النجوم', '1', '8.5', 5),
    '505': ('الحارس', '1', '9.0', 7), '506': ('طريق الذهب', '1', '6.9', 9), '507': ('ليلة في جدة', '1', '6.5', 11), '508': ('الصقر', '1', '6.4', 13),
    '601': ('Desert Storm', '2', '7.1', 2), '602': ('Night Run', '2', '5.9', 4), '603': ('Last Train', '2', '8.0', 6),
    '901': ('Adult Film', '3', '5.0', 0),
}
def stream(i, m):
    name, cat, rate, ago = m
    return {'num': int(i), 'name': name, 'stream_type': 'movie', 'stream_id': int(i), 'stream_icon': 'http://127.0.0.1:%d/poster/%s.png' % (PORT[0], i),
            'rating': rate, 'rating_5based': float(rate) / 2, 'added': str(NOW - ago * 86400 - int(i)), 'category_id': cat, 'container_extension': 'mp4', 'custom_sid': None, 'direct_source': ''}
PORT = [0]
CLIP = [b'']

class Provider(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def send_bytes(self, payload, kind):
        rng = self.headers.get('Range')
        if rng and kind == 'video/mp4':
            a, b = re.match(r'bytes=(\d*)-(\d*)', rng).groups()
            start = int(a) if a else 0; end = int(b) if b else len(payload) - 1
            end = min(end, len(payload) - 1)
            self.send_response(206); self.send_header('Content-Range', 'bytes %d-%d/%d' % (start, end, len(payload)))
            payload = payload[start:end + 1]
        else:
            self.send_response(200)
        self.send_header('Content-Type', kind); self.send_header('Accept-Ranges', 'bytes'); self.send_header('Content-Length', str(len(payload))); self.end_headers()
        try: self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError): pass
    def do_GET(self):
        url = urlparse(self.path); q = parse_qs(url.query)
        action = q.get('action', [''])[0]
        HITS.append((action or url.path, url.query))
        if url.path == '/player_api.php':
            if action == 'get_vod_categories': body = [{'category_id': i, 'category_name': n, 'parent_id': 0} for i, n in CATS]
            elif action == 'get_vod_streams':
                cat = q.get('category_id', [''])[0]
                body = [stream(i, m) for i, m in MOVIES.items() if cat in ('', m[1])]
            elif action == 'get_vod_info':
                i = q.get('vod_id', [''])[0]; name, cat, rate, ago = MOVIES.get(i, ('?', '1', '0', 0))
                body = {'info': {'name': name, 'plot': 'بعد انقطاع الاتصال عن آخر مدينة على الساحل، ينطلق فريق صغير في رحلة خطيرة عبر الصحراء لإيصال رسالة قد تغيّر مصير الجميع.',
                                 'cast': 'ممثل أول، ممثلة ثانية، ممثل ثالث', 'director': 'مخرج الاختبار', 'genre': 'أكشن', 'releasedate': '2025-03-01',
                                 'duration_secs': 120, 'duration': '00:02:00', 'rating': rate, 'video': {'width': 1920, 'height': 1080}},
                        'movie_data': {'stream_id': int(i), 'name': name, 'category_id': cat, 'container_extension': 'mp4'}}
            elif action in ('get_live_categories',): body = [{'category_id': '11', 'category_name': 'رياضة'}]
            elif action in ('get_live_streams',): body = [{'num': 101, 'name': 'SSC 1 HD', 'stream_id': '101', 'category_id': '11', 'stream_icon': '', 'container_extension': 'ts'}]
            elif action in ('get_short_epg', 'get_simple_data_table'): body = {'epg_listings': []}
            else: body = {'user_info': {'auth': 1, 'status': 'Active', 'max_connections': '1', 'active_cons': '0', 'allowed_output_formats': ['m3u8', 'ts'], 'exp_date': str(NOW + 86400 * 90)}}
            return self.send_bytes(json.dumps(body).encode(), 'application/json')
        m = re.fullmatch(r'/poster/(\d+)\.png', url.path)
        if m:
            b = BytesIO(); Image.new('RGB', (300, 450), COLOURS[int(m[1]) % len(COLOURS)]).save(b, 'PNG')
            return self.send_bytes(b.getvalue(), 'image/png')
        m = re.fullmatch(r'/movie/demo/demo/(\d+)\.mp4', url.path)
        if m:
            assert m[1] != '901'
            return self.send_bytes(CLIP[0], 'video/mp4')
        if url.path.startswith('/live/'): return self.send_bytes(b'', 'video/mp2t')
        self.send_error(404)

with tempfile.TemporaryDirectory() as temp:
    clip = Path(temp) / 'film.mp4'
    subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'testsrc=s=640x360:r=10', '-t', '120', '-c:v', 'libx264', '-preset', 'ultrafast',
                    '-g', '10', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', str(clip)], check=True)
    CLIP[0] = clip.read_bytes()
server = ThreadingHTTPServer(('127.0.0.1', 0), Provider); PORT[0] = server.server_port
threading.Thread(target=server.serve_forever, daemon=True).start()
adb('reverse', 'tcp:%d' % PORT[0], 'tcp:%d' % PORT[0])

# the synthetic login, written as an older version's plain-text one (the app seals it on first read)
adb('shell', 'am', 'force-stop', PKG)
uid = re.search(r'^\s*(?:userId|appId)=(\d+)', adb('shell', 'dumpsys', 'package', PKG), re.M)[1]
folder = '/data/user/0/%s/shared_prefs' % PKG
adb('shell', 'mkdir', '-p', folder)
adb('shell', 'rm', '-f', folder + '/xtream-vault.xml', folder + '/ouswis-live.xml', folder + '/ouswis-vod.xml')
adb('shell', 'sh', '-c', "'rm -f /data/user/0/%s/files/live-*.json /data/user/0/%s/files/vod-*.json'" % (PKG, PKG), check=False)
xml = '<?xml version="1.0" encoding="utf-8"?><map><string name="server">http://127.0.0.1:%d</string><string name="user">demo</string><string name="pass">demo</string></map>' % PORT[0]
subprocess.run([ADB, 'shell', 'cat > %s/XtreamActivity.xml' % folder], input=xml.encode(), check=True)
live = '<?xml version="1.0" encoding="utf-8"?><map><string name="dataFor">%s</string><string name="espnBase">http://127.0.0.1:%d</string></map>' % (jhash('http://127.0.0.1:%d|demo' % PORT[0]), PORT[0])
subprocess.run([ADB, 'shell', 'cat > %s/ouswis-live.xml' % folder], input=live.encode(), check=True)
adb('shell', 'sh', '-c', "'mkdir -p /data/user/0/%s/files && touch /data/user/0/%s/files/no-sites'" % (PKG, PKG))
adb('shell', 'chown', '-R', uid + ':' + uid, folder, '/data/user/0/%s/files' % PKG)
adb('logcat', '-c')

try:
    # 1. the home: "أفلام" sits beside "البث المباشر", and the remote opens it
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.HomeActivity'); front('HomeActivity'); time.sleep(3)
    labs = labels()
    for need in ('البث المباشر', 'أفلام', 'المباريات', 'كل التطبيقات', 'الأخيرة', 'التحديثات', 'الإعدادات'):
        assert any(l == need for l in labs), 'Missing shortcut: ' + need + ' | ' + str(labs[:40])
    keys(*['DPAD_DOWN'] * 5, pause=0.4); keys(*['DPAD_RIGHT'] * 8, pause=0.25)
    assert 'البث المباشر' in focused_label(), 'The first shortcut must be البث المباشر: ' + focused_label()
    keys('DPAD_LEFT')
    assert 'أفلام' in focused_label(), 'The second shortcut, beside Live TV, must be أفلام: ' + focused_label()
    shot('vod-home-shortcut.png')
    note('PASS: the home has seven shortcuts and أفلام is the one beside البث المباشر.')
    keys('DPAD_CENTER', pause=1.5); front('MoviesActivity')

    # 2. the films screen: newest first, the adult section and its films never shown
    head = wait_label('أحدث الإضافات · ', 'the newest additions with the catalogue count')
    assert '11 فيلم' in head[0], 'The count must leave out the adult section (11 films): ' + head[0]
    wait_label('المدينة الأخيرة', 'the newest film in the grid')
    time.sleep(3)
    labs = labels(); img = shot('vod-movies.png')
    assert not any('Adult' in l or 'XXX' in l for l in labs), 'An adult section or film shows: ' + str(labs)
    for need in ('قسم ▶ تابع المشاهدة', 'قسم ★ المفضلة', 'قسم أحدث الإضافات'):
        assert any(l.startswith(need) for l in labs), 'Missing section: ' + need + ' | ' + str(labs)
    for need in ('أفلام عربية', 'Action'):
        assert any(l.startswith('قسم ') and need in l for l in labs), 'Missing section: ' + need + ' | ' + str(labs)
    grid = [l for l in labs if ' · ★ ' in l]
    assert grid and grid[0].startswith('المدينة الأخيرة'), 'Newest first (المدينة الأخيرة, added today): ' + str(grid)
    assert len(grid) == 11, 'Two rows of six would show 11 films (all of them): ' + str(grid)
    assert colourful(img, (0.62, 0.14, 0.79, 0.42)) > 0.3, 'The first poster is not drawn from the provider'
    assert 'المدينة الأخيرة' in focused_label(), 'The first film holds the focus: ' + focused_label()
    note('PASS: films screen: newest first, ' + head[0] + ', posters from the provider, sections on the right, no adult section or film.')

    # sort by rating: the 9.0 film first
    keys('DPAD_UP', 'DPAD_LEFT', 'DPAD_CENTER', pause=0.8)
    assert any(l.startswith('ترتيب: الاسم') for l in labels()), 'Sort chip must cycle to الاسم'
    keys('DPAD_CENTER', pause=1)
    grid = [l for l in labels() if ' · ★ ' in l]
    assert grid[0].startswith('الحارس'), 'Sorted by rating, الحارس (9.0) first: ' + str(grid[:3])
    shot('vod-sort-rating.png')
    keys('DPAD_CENTER', pause=0.8)   # back to newest
    note('PASS: the sort chip cycles newest → name → rating and reorders the grid.')

    # one section, loaded on its own (get_vod_streams with its category_id)
    keys('DPAD_DOWN', pause=0.6); keys(*['DPAD_RIGHT'] * 2, pause=0.6)   # grid → sections (from the first column)
    assert focused_label().startswith('قسم أحدث الإضافات'), focused_label()
    keys('DPAD_DOWN', 'DPAD_DOWN', pause=1.2)
    wait_label('Night Run', 'the Action section')
    assert ('get_vod_streams', 'username=demo&password=demo&action=get_vod_streams&category_id=2') in [(a, q) for a, q in HITS], 'The section was not asked for by its category: ' + str(HITS[-6:])
    assert not any('ظل الصحراء' in l for l in labels()), 'Another section\'s films show in Action'
    shot('vod-section.png')
    note('PASS: a section loads by itself (get_vod_streams&category_id=2) and shows only its films.')

    # search by name, across the catalogue
    keys('DPAD_LEFT', 'DPAD_UP', 'DPAD_RIGHT', 'DPAD_RIGHT', pause=0.6)
    assert 'بحث' in focused_label(), 'The search chip: ' + focused_label()
    keys('DPAD_CENTER', pause=1.5)
    for n in tree().iter('node'):
        if n.get('class', '').endswith('EditText'):
            x1, y1, x2, y2 = [int(v) for v in re.findall(r'\d+', n.get('bounds'))]
            adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2)); time.sleep(0.8); break
    adb('shell', 'input', 'text', 'desert'); time.sleep(0.8)
    tapped = False
    for n in tree().iter('node'):
        if (n.get('text') or '') == 'بحث' and n.get('class', '').endswith('Button'):
            x1, y1, x2, y2 = [int(v) for v in re.findall(r'\d+', n.get('bounds'))]
            adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2)); tapped = True; break
    assert tapped, 'The search dialog has no بحث button'
    wait_label('Desert Storm', 'the search result')
    labs = labels()
    assert any(l.startswith('قسم بحث: desert') for l in labs), 'The search shows as a section: ' + str(labs[:20])
    assert len([l for l in labs if ' · ★ ' in l]) == 1, 'Only Desert Storm matches desert: ' + str(labs)
    shot('vod-search.png')
    note('PASS: search by name over the whole catalogue (case-insensitive): "desert" finds Desert Storm only.')

    # 3. a film's page from get_vod_info
    keys('DPAD_CENTER', pause=1.5); front('MovieActivity')
    wait_label('بطولة: ممثل أول', 'the cast from get_vod_info')
    labs = labels()
    for need in ('Desert Storm', '2025', '2 د', 'FHD', 'إخراج: مخرج الاختبار', '▶ تشغيل', '☆ مفضلة'):
        assert any(need in l for l in labs), 'Film page lacks ' + need + ': ' + str(labs)
    assert any('بعد انقطاع الاتصال' in l for l in labs), 'The story is missing'
    assert any(l == 'التقييم 7.1' for l in labs), 'The rating chip is missing: ' + str(labs)
    assert any('مقترحة لك' == l for l in labs) and any(l.startswith('Night Run') or l.startswith('Last Train') for l in labs), 'Films of the same section must follow: ' + str(labs)
    assert ('get_vod_info', 'username=demo&password=demo&action=get_vod_info&vod_id=601') in HITS
    shot('vod-movie.png')
    note('PASS: film page: poster, name, year, length, quality, rating, story, cast, director, ▶ تشغيل, and films of the same section.')

    # 4. playback on the app's player, seeking with the remote, the position kept
    keys('DPAD_CENTER', pause=6); front('VodPlayerActivity')
    asked = [q for a, q in HITS if a.startswith('/movie/')]
    assert any(True for a, q in HITS if a == '/movie/demo/demo/601.mp4'), 'The film was not asked for at /movie/USER/PASS/ID.ext: ' + str(HITS[-5:])
    img = shot('vod-playing.png')
    assert colourful(img, (0.2, 0.2, 0.8, 0.6)) > 0.2, 'No picture from the film'
    keys('DPAD_UP', pause=0.3)   # shows the bar
    p0, l0 = bar_position()
    keys('DPAD_RIGHT', 'DPAD_RIGHT', 'DPAD_RIGHT', 'DPAD_RIGHT', pause=0.3); time.sleep(1.5)
    p1, l1 = bar_position()
    assert p0 >= 0 and p1 >= p0 + 35, 'Four ▶ presses must move forward about 40 s: %s → %s' % (l0, l1)
    img = shot('vod-seek-forward.png')
    # the bar fills from left to right: at about 40 % of the film, gold on the left part of the bar, grey on the right
    gold = lambda px: px[0] > 180 and px[1] > 140 and px[2] < 140
    assert gold(img.getpixel((200, 929))) and not gold(img.getpixel((1700, 929))), 'The bar must fill from left to right: %s %s' % (img.getpixel((200, 929)), img.getpixel((1700, 929)))
    keys('DPAD_LEFT', pause=0.3); time.sleep(1.5)
    p2, l2 = bar_position()
    assert p2 <= p1 - 5, '◀ must move back: %s → %s' % (l1, l2)
    shot('vod-seek.png')
    keys('DPAD_CENTER', pause=1.5)
    assert any('متوقف' in l for l in labels()), 'OK must pause'
    shot('vod-paused.png')
    keys('DPAD_CENTER', pause=4)
    note('PASS: the film plays on the app\'s player from /movie/demo/demo/601.mp4; ▶ moved forward %s → %s, ◀ back to %s, the bar fills from left to right; OK pauses and plays.' % (l0, l1, l2))
    keys('BACK', pause=2); front('MovieActivity')
    resume = wait_label('▶ كمّل من ', 'continue from… after watching')
    assert seconds(resume[0]) >= p2, 'The saved position must be where it stopped: ' + resume[0]
    assert any(l.startswith('باقي ') for l in labels()), 'The time left under the progress bar'
    assert any('↺ من البداية' in l for l in labels()), 'From the start'
    img = shot('vod-movie-resume.png')
    note('PASS: back on the page: "' + resume[0] + '" with the progress bar and "↺ من البداية".')
    keys('DPAD_CENTER', pause=5); front('VodPlayerActivity')
    p3, l3 = bar_position()
    if p3 < 0: keys('DPAD_UP', pause=0.3); p3, l3 = bar_position()
    assert p3 >= seconds(resume[0]) - 2, 'Continue must start where it stopped: ' + resume[0] + ' → ' + l3
    note('PASS: "continue from" starts at ' + l3 + '.')
    keys('BACK', pause=2); front('MovieActivity')

    # favourites from the page
    keys('DPAD_LEFT', 'DPAD_LEFT', 'DPAD_CENTER', pause=1)
    assert any('★ في المفضلة' in l for l in labels()), 'The favourite button: ' + str(labels())
    keys('BACK', pause=2); front('MoviesActivity')

    # 5. continue watching and favourites in the films screen
    keys('DPAD_RIGHT', pause=0.5)   # the search results (4th section) → the sections column
    assert focused_label().startswith('قسم بحث'), focused_label()
    keys('DPAD_UP', 'DPAD_UP', 'DPAD_UP', pause=0.6)
    assert focused_label().startswith('قسم ▶ تابع المشاهدة'), 'Continue watching is the first section: ' + focused_label()
    time.sleep(1)
    assert any(l.startswith('Desert Storm') for l in labels()), 'Continue watching holds the film: ' + str(labels())
    shot('vod-continue.png')
    keys('DPAD_DOWN', pause=1)
    assert any(l.startswith('Desert Storm') for l in labels()), 'Favourites hold the film: ' + str(labels())
    note('PASS: تابع المشاهدة and المفضلة hold the film.')
    no_crash('Films')

    # 6. Live TV still opens from its shortcut, with the account
    keys('BACK', pause=2)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.HomeActivity'); front('HomeActivity'); time.sleep(2)
    keys(*['DPAD_DOWN'] * 5, pause=0.4); keys(*['DPAD_RIGHT'] * 8, pause=0.25)
    keys('DPAD_CENTER', pause=2); front('LiveActivity')
    wait_label('SSC 1 HD', 'the live channel list')
    shot('vod-live-still.png')
    note('PASS: البث المباشر still opens the live list from its shortcut.')
    keys('BACK', pause=1.5)
    mem = adb('shell', 'dumpsys', 'meminfo', PKG + ':home', check=False)
    total = re.search(r'TOTAL PSS:\s+(\d+)', mem) or re.search(r'TOTAL\s+(\d+)', mem)
    note('Memory of the :home process after the films screens (emulator, x86_64): ' + (str(int(total[1]) // 1024) + ' MB PSS' if total else 'n/a'))
    no_crash('End')
    note('PASS: films round 1 on the emulator. Not tested here: the MECOOL KM2 Plus (32-bit) itself and the real subscription.')
finally:
    adb('logcat', '-d', '-s', 'FreeMyTV', check=False) and (OUT / 'vod-logcat.txt').write_text(adb('logcat', '-d', '-t', '20000', check=False)[-200000:])
    adb('shell', 'am', 'force-stop', PKG)
