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
    '601': ('Desert Storm', '2', '7.1', 2), '602': ('Night Run', '2', '5.9', 4), '603': ('Last Train', '2', '8.0', 6), '604': ('Two Voices', '2', '6.2', 8),
    '901': ('Adult Film', '3', '5.0', 0),
}
def stream(i, m):
    name, cat, rate, ago = m
    return {'num': int(i), 'name': name, 'stream_type': 'movie', 'stream_id': int(i), 'stream_icon': 'http://127.0.0.1:%d/poster/%s.png' % (PORT[0], i),
            'rating': rate, 'rating_5based': float(rate) / 2, 'added': str(NOW - ago * 86400 - int(i)), 'category_id': cat, 'container_extension': 'mkv' if i == '604' else 'mp4', 'custom_sid': None, 'direct_source': ''}
PORT = [0]
SERIES = [('701', 'الوادي البعيد', '21', '8.6'), ('702', 'Night Shift', '21', '7.0'), ('901', 'Adult Show', '22', '5.0')]
EPISODES = [('1001', 1, 1), ('1002', 1, 2), ('1003', 1, 3), ('1004', 2, 1), ('1005', 2, 2), ('1006', 2, 3)]
CLIP = [b'']
MKV = [b'']

class Provider(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def send_bytes(self, payload, kind):
        rng = self.headers.get('Range')
        if rng and kind.startswith('video/'):
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
        if url.path.startswith('/apis/site/v2/sports/soccer/'):   # four matches being played now, so the home shows its matches row
            events = []
            if url.path.split('/')[6] == 'eng.1':
                def ev(eid, home, away, minutes_ago):
                    kick = time.strftime('%Y-%m-%dT%H:%MZ', time.gmtime(time.time() - minutes_ago * 60))
                    team = lambda n, ha: {'homeAway': ha, 'score': '1', 'team': {'id': n[:3], 'displayName': n, 'logo': ''}}
                    return {'id': eid, 'date': kick, 'status': {'displayClock': "%d'" % minutes_ago, 'type': {'state': 'in', 'name': 'STATUS_FIRST_HALF', 'shortDetail': "%d'" % minutes_ago}},
                            'competitions': [{'competitors': [team(home, 'home'), team(away, 'away')], 'broadcasts': []}]}
                events = [ev('9101', 'Liverpool', 'Arsenal', 10), ev('9102', 'Chelsea', 'Everton', 20), ev('9103', 'Fulham', 'Brentford', 30), ev('9104', 'Leeds', 'Burnley', 40)]
            return self.send_bytes(json.dumps({'leagues': [{'name': 'English Premier League', 'logos': []}], 'events': events}).encode(), 'application/json')
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
            elif action == 'get_series_categories': body = [{'category_id': '21', 'category_name': 'AR| مسلسلات عربية', 'parent_id': 0}, {'category_id': '22', 'category_name': 'XXX Adult', 'parent_id': 0}]
            elif action == 'get_series':
                cat = q.get('category_id', [''])[0]
                body = [{'num': n + 1, 'name': name, 'series_id': int(sid), 'cover': 'http://127.0.0.1:%d/poster/%s.png' % (PORT[0], sid), 'plot': '', 'rating': rate,
                         'last_modified': str(NOW - n * 3600), 'category_id': c} for n, (sid, name, c, rate) in enumerate(SERIES) if cat in ('', c)]
            elif action == 'get_series_info':
                sid = q.get('series_id', [''])[0]
                body = {'seasons': [{'season_number': 1, 'name': 'Season 1', 'episode_count': 3}, {'season_number': 2, 'name': 'Season 2', 'episode_count': 3}],
                        'info': {'name': 'الوادي البعيد', 'plot': 'قصة المسلسل من بيانات الاشتراك.', 'cast': 'ممثل', 'director': 'مخرج', 'releaseDate': '2023-05-01', 'rating': '8.6', 'category_id': '21'},
                        'episodes': {str(season): [{'id': eid, 'episode_num': num, 'title': 'الوادي البعيد S0%dE0%d' % (se, num), 'container_extension': 'mkv' if eid == '1006' else 'mp4', 'season': se,
                                                'info': {'plot': 'ملخص الحلقة %d' % num, 'duration_secs': 120}} for eid, se, num in EPISODES if se == season] for season in (1, 2)} if sid == '701' else {}}
            elif action in ('get_live_categories',): body = [{'category_id': '11', 'category_name': 'رياضة'}]
            elif action in ('get_live_streams',): body = [{'num': 101, 'name': 'SSC 1 HD', 'stream_id': '101', 'category_id': '11', 'stream_icon': '', 'container_extension': 'ts'}]
            elif action in ('get_short_epg', 'get_simple_data_table'): body = {'epg_listings': []}
            else: body = {'user_info': {'auth': 1, 'status': 'Active', 'max_connections': '1', 'active_cons': '0', 'allowed_output_formats': ['m3u8', 'ts'], 'exp_date': str(NOW + 86400 * 90)}}
            return self.send_bytes(json.dumps(body).encode(), 'application/json')
        m = re.fullmatch(r'/poster/(\d+)\.png', url.path)
        if m:
            b = BytesIO(); Image.new('RGB', (300, 450), COLOURS[int(m[1]) % len(COLOURS)]).save(b, 'PNG')
            return self.send_bytes(b.getvalue(), 'image/png')
        if url.path == '/series/demo/demo/1006.mkv':
            return self.send_bytes(MKV[0], 'video/x-matroska')
        if re.fullmatch(r'/series/demo/demo/10\d\d\.mp4', url.path):
            return self.send_bytes(CLIP[0], 'video/mp4')
        if url.path == '/movie/demo/demo/604.mkv':
            return self.send_bytes(MKV[0], 'video/x-matroska')
        m = re.fullmatch(r'/movie/demo/demo/(\d+)\.mp4', url.path)
        if m:
            assert m[1] != '901'
            return self.send_bytes(CLIP[0], 'video/mp4')
        if url.path.startswith('/live/'): return self.send_bytes(b'', 'video/mp2t')
        self.send_error(404)

with tempfile.TemporaryDirectory() as temp:
    clip = Path(temp) / 'film.mp4'
    # one sound track, no subtitles
    subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'testsrc=s=640x360:r=10', '-f', 'lavfi', '-i', 'sine=f=440', '-t', '120', '-map', '0:v', '-map', '1:a',
                    '-c:v', 'libx264', '-preset', 'ultrafast', '-g', '10', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-movflags', '+faststart', str(clip)], check=True)
    CLIP[0] = clip.read_bytes()
    # English and Arabic sound (English first and default), English and Arabic subtitles
    (Path(temp) / 'en.srt').write_text('1\n00:00:00,000 --> 00:02:00,000\nWelcome\n', encoding='utf-8')
    (Path(temp) / 'ar.srt').write_text('1\n00:00:00,000 --> 00:02:00,000\nمرحبا بكم\n', encoding='utf-8')
    mkv = Path(temp) / 'two.mkv'
    subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'testsrc=s=640x360:r=10', '-f', 'lavfi', '-i', 'sine=f=440', '-f', 'lavfi', '-i', 'sine=f=880',
                    '-i', str(Path(temp) / 'en.srt'), '-i', str(Path(temp) / 'ar.srt'), '-t', '120',
                    '-map', '0:v', '-map', '1:a', '-map', '2:a', '-map', '3:s', '-map', '4:s', '-c:v', 'libx264', '-preset', 'ultrafast', '-g', '10', '-pix_fmt', 'yuv420p',
                    '-c:a', 'aac', '-c:s', 'srt', '-metadata:s:a:0', 'language=eng', '-metadata:s:a:1', 'language=ara', '-metadata:s:s:0', 'language=eng',
                    '-metadata:s:s:1', 'language=ara', '-disposition:a:0', 'default', '-disposition:a:1', '0', '-disposition:s:0', '0', '-disposition:s:1', '0', str(mkv)], check=True)
    MKV[0] = mkv.read_bytes()
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
    for need in ('البث المباشر', 'أفلام', 'مسلسلات', 'المباريات', 'كل التطبيقات', 'الأخيرة', 'التحديثات', 'الإعدادات'):
        assert any(l == need for l in labs), 'Missing shortcut: ' + need + ' | ' + str(labs[:40])
    keys(*['DPAD_DOWN'] * 7, pause=0.4); keys(*['DPAD_RIGHT'] * 9, pause=0.25)
    assert 'البث المباشر' in focused_label(), 'The first shortcut must be البث المباشر: ' + focused_label()
    keys('DPAD_LEFT')
    assert 'أفلام' in focused_label(), 'The second shortcut, beside Live TV, must be أفلام: ' + focused_label()
    shot('vod-home-shortcut.png')
    note('PASS: the home has eight shortcuts: أفلام beside البث المباشر, then مسلسلات.')
    keys('DPAD_CENTER', pause=1.5); front('MoviesActivity')

    # 2. the films screen: newest first, the adult section and its films never shown
    head = wait_label('أحدث الإضافات · ', 'the newest additions with the catalogue count')
    assert '12 فيلم' in head[0], 'The count must leave out the adult section (12 films): ' + head[0]
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
    assert len(grid) == 12, 'Two rows of six show the 12 films: ' + str(grid)
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

    # 6. sound and subtitles (▼): by language, Arabic sound by default, a picked subtitle kept for later
    def menu_labels():
        return [l for l in labels() if l.startswith('الصوت: ') or l.startswith('الترجمة: ') or 'مسار صوت واحد' in l or 'لا توجد ترجمات' in l]
    def play(mid, name, ext):
        adb('shell', 'am', 'start', '-W', '-n', PKG + '/.VodPlayerActivity', '--es', 'id', mid, '--es', 'name', "'" + name + "'", '--es', 'ext', ext, '--es', 'cat', '2')
        front('VodPlayerActivity'); time.sleep(6)
    adb('shell', 'am', 'force-stop', PKG); time.sleep(1)
    play('604', 'Two Voices', 'mkv')
    keys('DPAD_DOWN', pause=1.5)
    ml = menu_labels()
    for need in ('الصوت: عربي — مختار', 'الصوت: إنجليزي', 'الترجمة: بدون ترجمة — مختار', 'الترجمة: عربي', 'الترجمة: إنجليزي'):
        assert need in ml, 'Sound/subtitles menu lacks ' + need + ': ' + str(ml)
    shot('vod-tracks-menu.png')
    note('PASS: ▼ opens the sound and subtitles menu: ' + ' | '.join(ml) + ' (Arabic sound chosen by itself, no subtitles with Arabic sound).')
    for _ in range(6):
        if focused_label().startswith('الترجمة: عربي'): break
        keys('DPAD_DOWN', pause=0.5)
    keys('DPAD_CENTER', pause=2)
    assert 'الترجمة: عربي — مختار' in menu_labels(), 'The Arabic subtitles were not chosen: ' + str(menu_labels())
    keys('BACK', pause=1)
    front('VodPlayerActivity')
    wait_label('ترجمة: مرحبا بكم', 'the Arabic subtitle on screen', tries=15)
    shot('vod-subtitles.png')
    kept = adb('shell', 'cat', folder + '/ouswis-tracks.xml')
    assert 'name="text">ar<' in kept, 'The Arabic subtitle choice must be kept: ' + kept
    note('PASS: the Arabic subtitles show on screen ("مرحبا بكم") and the choice is kept for the next films.')
    keys('BACK', pause=1.5)
    adb('shell', 'am', 'force-stop', PKG); time.sleep(1)
    play('604', 'Two Voices', 'mkv')
    wait_label('ترجمة: مرحبا بكم', 'the kept Arabic subtitles on the next play', tries=15)
    keys('DPAD_DOWN', pause=1.5)
    ml = menu_labels()
    assert 'الصوت: عربي — مختار' in ml and 'الترجمة: عربي — مختار' in ml, 'The kept choice on a new play: ' + str(ml)
    keys('BACK', pause=1); keys('BACK', pause=1.5)
    note('PASS: played again, the file opens with Arabic sound and the kept Arabic subtitles.')
    play('601', 'Desert Storm', 'mp4')
    keys('DPAD_DOWN', pause=1.5)
    ml = menu_labels()
    assert any('مسار صوت واحد فقط' in l for l in ml) and any('لا توجد ترجمات' in l for l in ml), 'A one-track file must say there is nothing else: ' + str(ml)
    shot('vod-tracks-single.png')
    keys('BACK', pause=1); keys('BACK', pause=1.5)
    note('PASS: a file with one sound track and no subtitles says so in the menu.')
    no_crash('Sound and subtitles')

    # 7. series: the shortcut, the grid, the page (seasons, episodes, watched, continue) and the same sound/subtitle choice
    adb('shell', 'am', 'force-stop', PKG); time.sleep(1)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.HomeActivity'); front('HomeActivity'); time.sleep(2)
    keys(*['DPAD_DOWN'] * 7, pause=0.4); keys(*['DPAD_RIGHT'] * 9, pause=0.25); keys('DPAD_LEFT', 'DPAD_LEFT')
    assert 'مسلسلات' in focused_label(), 'The third shortcut must be مسلسلات: ' + focused_label()
    keys('DPAD_CENTER', pause=1.5); front('MoviesActivity')
    head = wait_label('المسلسلات · أحدث الإضافات · ', 'the series screen with its count')
    assert '2 مسلسل' in head[0], 'Two series (the adult one left out): ' + head[0]
    wait_label('الوادي البعيد', 'the newest series')
    assert not any('Adult' in l for l in labels()), 'An adult series shows'
    shot('series-grid.png')
    note('PASS: مسلسلات opens the series screen: ' + head[0] + ', no adult section or series.')
    keys('DPAD_CENTER', pause=1.5); front('SeriesActivity')
    wait_label('الحلقة 1', 'the episodes of season 1')
    labs = labels()
    for need in ('الموسم 1 — محدد', 'الموسم 2', '▶ تشغيل: م1 · ح1', 'موسمان', '2023', 'التقييم 8.6', 'الحلقة 3'):
        assert any(need in l for l in labs), 'The series page lacks ' + need + ': ' + str(labs)
    shot('series-page.png')
    note('PASS: series page: seasons as tabs, season 1\'s episodes, rating, seasons count, year, "▶ تشغيل: م1 · ح1".')
    # season 2, episode 1, watched to the end
    keys('DPAD_LEFT', pause=1)
    assert any(l.startswith('الموسم 2 — محدد') for l in labels()), 'LEFT must move to season 2: ' + str(labels()[:12])
    keys('DPAD_CENTER', pause=4); front('VodPlayerActivity')
    assert ('/series/demo/demo/1004.mp4', '') in HITS, 'The episode was not asked for at /series/USER/PASS/ID.ext: ' + str(HITS[-4:])
    keys(*['DPAD_RIGHT'] * 13, pause=0.3)
    for _ in range(20):
        if 'SeriesActivity' in adb('shell', 'dumpsys', 'activity', 'activities').split('topResumedActivity')[-1][:300]: break
        time.sleep(1)
    front('SeriesActivity'); time.sleep(1)
    labs = labels()
    assert any(l.startswith('الحلقة 1 · شوهدت') for l in labs), 'Episode 1 must show as watched: ' + str(labs)
    assert any(l.startswith('▶ كمّل: م2 · ح2') for l in labs), 'Continue must move on to the next episode: ' + str(labs)
    shot('series-watched.png')
    note('PASS: an episode played to the end shows "✓ شوهدت", and continue moves on to "م2 · ح2".')
    # continue: plays S2E2; stop part way
    keys('DPAD_RIGHT', 'DPAD_RIGHT', pause=0.8)
    assert focused_label().startswith('▶ كمّل: م2 · ح2'), 'RIGHT past season 1 reaches continue: ' + focused_label()
    keys('DPAD_CENTER', pause=4); front('VodPlayerActivity')
    assert ('/series/demo/demo/1005.mp4', '') in HITS, 'Continue must play S2E2: ' + str(HITS[-4:])
    keys(*['DPAD_RIGHT'] * 4, pause=0.3); time.sleep(2)
    keys('BACK', pause=2); front('SeriesActivity')
    keys('DPAD_LEFT', 'DPAD_LEFT', pause=0.8)   # off continue, to season 2
    labs = labels()
    assert any(l.startswith('الحلقة 2 · متوقفة عند ') for l in labs), 'Episode 2 must show where it stopped: ' + str(labs)
    assert any(l.startswith('▶ كمّل: م2 · ح2') for l in labs), 'Continue stays on the started episode: ' + str(labs)
    shot('series-progress.png')
    note('PASS: an episode stopped part way shows its progress, and continue resumes it.')
    keys('BACK', pause=1.5); front('MoviesActivity')
    keys('DPAD_RIGHT', pause=0.5); keys('DPAD_UP', 'DPAD_UP', pause=0.8)
    assert focused_label().startswith('قسم ▶ تابع المشاهدة'), focused_label()
    time.sleep(1)
    assert any(l.startswith('الوادي البعيد') for l in labels()), 'Continue watching holds the series: ' + str(labels())
    note('PASS: تابع المشاهدة in the series screen holds الوادي البعيد.')
    # the sound and subtitle choice made on a film carries over to an episode
    adb('shell', 'am', 'force-stop', PKG); time.sleep(1)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.VodPlayerActivity', '--es', 'id', '1006', '--es', 'name', "'ep3'", '--es', 'ext', 'mkv', '--es', 'show', '701', '--es', 'showName', "'الوادي البعيد'", '--es', 'showIcon', 'http://127.0.0.1:%d/poster/701.png' % PORT[0], '--es', 'showCat', '21', '--ei', 'season', '2', '--ei', 'num', '3')
    front('VodPlayerActivity'); time.sleep(6)
    assert ('/series/demo/demo/1006.mkv', '') in HITS
    keys('DPAD_DOWN', pause=1.5)
    ml = menu_labels()
    assert 'الصوت: عربي — مختار' in ml and 'الترجمة: عربي — مختار' in ml, 'An episode must open with the kept choice: ' + str(ml)
    shot('series-tracks.png')
    keys('BACK', pause=1); keys('BACK', pause=1.5)
    note('PASS: an episode opens with the same kept sound and subtitle choice (Arabic sound, Arabic subtitles), and ▼ opens the same menu.')
    no_crash('Series')

    # 8. the home's films and series row (home_vod.png): started and unfinished first with their progress, then the newest
    adb('shell', 'am', 'force-stop', PKG); time.sleep(1)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.VodPlayerActivity', '--es', 'id', '602', '--es', 'name', "'Night Run'", '--es', 'icon', 'http://127.0.0.1:%d/poster/602.png' % PORT[0], '--es', 'ext', 'mp4', '--es', 'cat', '2', '--el', 'from', '50000')
    front('VodPlayerActivity'); time.sleep(5); keys('BACK', pause=2)   # a film stopped part way (about 55 s of 2 min)
    adb('shell', 'am', 'force-stop', PKG); time.sleep(1)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.HomeActivity'); front('HomeActivity')
    wait_label('أفلام ومسلسلات', 'the films and series heading on the home', tries=20)
    def row_labels():
        return [l for l in labels() if re.search(r' · (فيلم|مسلسل)', l)]
    for _ in range(20):
        if len(row_labels()) >= 5: break
        time.sleep(1)
    rl = row_labels()
    note('Home row (right to left): ' + ' | '.join(rl[:7]))
    assert rl and rl[0].startswith('Night Run · فيلم · تابع المشاهدة '), 'The last film started comes first, with its progress: ' + str(rl[:4])
    assert len(rl) > 1 and rl[1].startswith('الوادي البعيد · مسلسل · ح3 · تابع المشاهدة '), 'Then the series left part way, with its episode: ' + str(rl[:4])
    assert [l.split(' · ')[0] for l in rl[2:5]] == ['المدينة الأخيرة', 'Night Shift', 'ظل الصحراء'], 'Then the newest films and series, newest first: ' + str(rl[2:6])
    assert all('تابع المشاهدة' not in l for l in rl[2:]), 'Only what is being watched carries a progress bar: ' + str(rl)
    assert not any('Adult' in l for l in rl), 'No adult film or series on the home: ' + str(rl)
    for _ in range(40):   # the matches row too, so every row is on screen
        if sum(1 for l in labels() if ' ضد ' in l) >= 3: break
        time.sleep(1)
    time.sleep(2)
    img = shot('vod-home-row.png')
    first = [list(map(int, re.findall(r'\d+', n.get('bounds')))) for n in tree().iter('node') if n.get('package') == PKG and (n.get('text', '') or n.get('content-desc', '')).startswith('Night Run · ')]
    assert first, 'The first poster has no node'
    x1, y1, x2, y2 = first[0]
    W, H = img.size
    inner = ((x1 + 12) / W, (y1 + 40) / H, (x2 - 12) / W, (y1 + (y2 - y1) * 0.5) / H)   # under the tag chip, above the fade
    assert colourful(img, inner) > 0.3, 'The first poster is not drawn from the provider: ' + str(first[0])
    note('PASS: the home row: ' + rl[0] + ' and ' + rl[1] + ' first, then the newest (المدينة الأخيرة, Night Shift, ظل الصحراء), each tagged فيلم or مسلسل.')
    # every row whole on the screen, in order, nothing overlapping, the side margins as before
    def group(test):
        bs = [list(map(int, re.findall(r'\d+', n.get('bounds')))) for n in tree().iter('node') if n.get('package') == PKG and test((n.get('text', '') or n.get('content-desc', '')).strip())]
        return [b for b in bs if len(b) == 4]
    groups = [('the last-channel and prayer cards', group(lambda l: l.startswith('آخر قناة') or l.startswith('ابدأ المشاهدة') or l.startswith('مواقيت الصلاة'))),
              ('the matches', group(lambda l: ' ضد ' in l and not l.startswith('تفاصيل'))),
              ('the films and series', group(lambda l: bool(re.search(r' · (فيلم|مسلسل)', l)))),
              ('the apps', group(lambda l: l == 'إضافة تطبيق')),
              ('the shortcuts', group(lambda l: l in ('البث المباشر', 'أفلام', 'مسلسلات', 'المباريات', 'كل التطبيقات', 'الأخيرة', 'التحديثات', 'الإعدادات')))]
    spans = []
    for name, bs in groups:
        assert bs, 'Nothing found for ' + name + ': ' + str(labels()[:40])
        top, bottom, left, right = min(b[1] for b in bs), max(b[3] for b in bs), min(b[0] for b in bs), max(b[2] for b in bs)
        assert top >= 0 and bottom <= 1000 and left >= 40 and right <= 1880, name + ' is not whole inside the screen and its margins: ' + str((left, top, right, bottom))   # (a focused card grows 1.8 %)
        spans.append((name, top, bottom))
    for (a, at, ab), (b, bt, bb) in zip(spans, spans[1:]):
        assert ab < bt, a + ' (to ' + str(ab) + ') overlaps ' + b + ' (from ' + str(bt) + ')'
    note('PASS: every row whole on the 1920x1080 screen, in order, without overlapping: ' + ', '.join('%s %d–%d' % s for s in spans) + '.')
    # the remote: down to the row, OK opens the film's page, the next poster the series' page, "عرض الكل" the films screen
    for _ in range(8):
        if focused_label().startswith('Night Run'): break
        keys('DPAD_DOWN', pause=0.6)
    assert focused_label().startswith('Night Run'), 'DOWN reaches the row and its first poster: ' + focused_label()
    keys('DPAD_CENTER', pause=2); front('MovieActivity')
    wait_label('Night Run', 'the film page from the home row')
    assert any(l.startswith('▶ كمّل من ') for l in labels()), 'The film page offers to continue: ' + str(labels()[:20])
    keys('BACK', pause=2); front('HomeActivity')
    assert focused_label().startswith('Night Run'), 'Back on the home, the focus stays on the poster: ' + focused_label()
    keys('DPAD_LEFT', pause=0.6)
    assert focused_label().startswith('الوادي البعيد'), focused_label()
    keys('DPAD_CENTER', pause=2); front('SeriesActivity')
    wait_label('▶ كمّل: ', 'the series page from the home row')
    keys('BACK', pause=2); front('HomeActivity')
    for _ in range(30):
        if focused_label().startswith('عرض الكل'): break
        keys('DPAD_LEFT', pause=0.3)
    assert focused_label().startswith('عرض الكل'), 'LEFT past the last poster reaches عرض الكل: ' + focused_label()
    shot('vod-home-viewall.png')
    keys('DPAD_CENTER', pause=2); front('MoviesActivity')
    wait_label('الأفلام · ', 'the films screen from عرض الكل')
    keys('BACK', pause=2); front('HomeActivity')
    note('PASS: on the home row, OK opens the film page (with "كمّل من"), the series page, and عرض الكل the films screen; the focus stays on the poster after Back.')
    no_crash('Home row')

    # 9. Live TV still opens from its shortcut, with the account
    keys('BACK', pause=2)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.HomeActivity'); front('HomeActivity'); time.sleep(2)
    keys(*['DPAD_DOWN'] * 7, pause=0.4); keys(*['DPAD_RIGHT'] * 9, pause=0.25)
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
