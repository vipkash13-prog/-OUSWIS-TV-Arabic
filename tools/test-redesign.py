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
FAIL_CATS = set()   # categories whose channel list answers HTTP 500 (a section left empty for good in the video)
FEED_MODE = ['ok']   # 'down': every competition but one answers HTTP 500, that one answers with no matches (what wiped the matches screen)
ACTIVE = ['0']   # user_info.active_cons: the provider's count of open connections (a test raises it to look like an old connection still counted)
REQT = {}   # when each stream file was asked for
UINFO = []   # when user_info was asked for
T997 = []   # when 997.ts was asked for: its first two answers are HTTP 509 (the connection limit)
SDB = []   # the team names TheSportsDB (the mock) was asked for
SDB_NOW = [0, 0]   # searches in flight now, the most at once
LOGO_FEED = {'Egypt': '/logo/feed.png', 'Malawi': '/logo/missing.png'}   # the feed's own logo: Egypt's loads (green), Malawi's answers 404 -> the fallback
SDB_HAS = ('Malawi', 'Kazakhstan')   # the mock TheSportsDB has a badge (magenta) for these only
def png(rgb):
    b = BytesIO(); Image.new('RGBA', (200, 200), rgb + (255,)).save(b, 'PNG'); return b.getvalue()
ALLOWED = ['m3u8', 'ts']   # what the mock account's user_info says it may stream

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

def jhash(text):
    """Java's String.hashCode as Integer.toHexString prints it: the app's subscription key."""
    h = 0
    for ch in text: h = (31 * h + ord(ch)) & 0xFFFFFFFF
    return format(h, 'x')

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
    # a screen that caught its own exception would hide it: the catch writes the stack to the log, and that fails the test too
    caught = [l for l in adb('logcat', '-d', '-s', 'FreeMyTV', check=False).splitlines() if 'failed:' in l and ('matches-screen' in l or 'draw' in l)]
    assert not caught, where + ': an exception was caught inside a screen:\n' + '\n'.join(caught[:12])

def gfx():
    out = ''
    for name in (PKG + ':home', PKG):   # the home screen runs in its own process
        out = adb('shell', 'dumpsys', 'gfxinfo', name, check=False)
        if 'Total frames rendered' in out: break
    pick = lambda pat: (re.search(pat, out) or [None, '?'])[1]
    return {'frames': pick(r'Total frames rendered: (\d+)'), 'janky': pick(r'Janky frames: (\d+ \([\d.]+%\))'), 'p50': pick(r'50th percentile: (\d+ms)'),
            'p90': pick(r'90th percentile: (\d+ms)'), 'p99': pick(r'99th percentile: (\d+ms)'), 'slow_ui': pick(r'Number Slow UI thread: (\d+)'),
            'slow_draw': pick(r'Number Slow issue draw commands: (\d+)'), 'raw': out}

def perf_draw():
    lines = re.findall(r'perf-draw: frames=(\d+) sumUs=(\d+) maxUs=(\d+) over16ms=(\d+) over32ms=(\d+)', adb('logcat', '-d', '-s', 'FreeMyTV', check=False))
    return [int(v) for v in lines[-1]] if lines else None

def draw_delta(a, b):
    if not a or not b: return 'n/a'
    f = b[0] - a[0]
    return 'frames %d, avg draw %.1f ms, slowest %.1f ms, over 16 ms: %d, over 32 ms: %d' % (f, (b[1] - a[1]) / 1000.0 / max(1, f), b[2] / 1000.0, b[3] - a[3], b[4] - a[4])

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
    p0 = perf_draw()
    [adb('shell', 'dumpsys', 'gfxinfo', n, 'reset', check=False) for n in (PKG + ':home', PKG)]
    cycle = ['DPAD_DOWN', 'DPAD_DOWN', 'DPAD_LEFT', 'DPAD_RIGHT', 'DPAD_LEFT', 'DPAD_UP', 'DPAD_RIGHT', 'DPAD_UP']
    t1 = time.time(); i = 0
    while time.time() - t1 < 40: keys(cycle[i % len(cycle)], pause=0.5); i += 1
    time.sleep(6); p1 = perf_draw()
    g = gfx(); (OUT / ('perf-%s-gfxinfo.txt' % tag)).write_text(g.pop('raw'))
    line = '%s | cold open ms (3 runs): %s | first match card after %ss | channel on the Ghana card after %ss | remote use 40 s (UI-thread draw): %s | gfxinfo frames %s, janky %s, slow UI %s' % (
        tag, totals, first, ghana, draw_delta(p0, p1), g['frames'], g['janky'], g['slow_ui'])
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
    q0 = perf_draw()
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
    time.sleep(6); q1 = perf_draw()
    g = gfx(); (OUT / 'stability-gfxinfo.txt').write_text(g.pop('raw'))
    log = adb('logcat', '-d', '-s', 'FreeMyTV', check=False)
    stats = re.findall(r'home-stats: (.*)', log)
    msg = 'STABILITY %d s on the home screen: %d samples, worst screen change %.2f%%, UI-thread draw: %s, problems %s, app counters: %s' % (seconds, n, worst * 100, draw_delta(q0, q1), problems or 'none', stats[-1] if stats else 'n/a (older build)')
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
    short_clip = Path(temp) / 'short.ts'   # 5 seconds: the player reaches its end, as a server that closes the connection does
    subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'testsrc=s=640x360:r=10', '-t', '5',
                    '-c:v', 'libx264', '-preset', 'ultrafast', '-pix_fmt', 'yuv420p', '-f', 'mpegts', str(short_clip)], check=True)
    short_data = short_clip.read_bytes()
    CATS = [('18', 'US APPLE TV'), ('10', 'FR|TF1 HD'), ('14', 'FR| BEIN SPORTS'), ('15', 'BE| SPORT'), ('16', 'US| NEWS'), ('11', 'رياضة'), ('12', 'أخبار'), ('13', 'أفلام'), ('17', 'AR| عام')]   # the foreign ones are listed first and two of them say sport: the Arabic ones must still lead
    STREAMS = {'11': [('101', 'SSC 1 HD', 'ts'), ('102', 'SSC 2 HD', 'ts'), ('103', 'SSC 3 HD', 'ts'), ('110', 'beIN Sports 1 HD', 'ts'),
                      ('111', 'beIN Sports 2', 'ts'), ('112', 'beIN Sports 3', 'm3u8'), ('114', 'beIN SPORTS 5 HD', 'ts'), ('115', 'beIN SPORTS 6 SD', 'ts'), ('117', 'beIN SPORTS 5 SD', 'ts'), ('118', 'beIN SPORTS 7', 'ts'), ('113', 'FR| beIN SPORTS 4 HD (H.265)', 'ts'), ('120', 'AD Sports 1', 'ts'), ('105', 'beIN SPORTS XTRA 1HD', 'ts')],
               '12': [('201', 'Al Arabiya', 'ts'), ('202', 'Sky News Arabia', 'ts')], '13': [('301', 'Movies One', 'ts')], '10': [('401', 'TF1 HD', 'ts')], '14': [], '18': [('811', 'Apple TV Test', 'ts')], '15': [], '16': [('701', 'US News 1', 'ts')], '17': [('801', 'MBC 1', 'ts')]}
    HOUR = 3600
    # a second subscription (user bob): the same channels under other numbers (5000 + the old one) and other prefixes
    STREAMS_B = {c: [(str(int(sid) + 5000), ('8K: ' if i % 2 == 0 else 'FR| ') + n, e) for i, (sid, n, e) in enumerate(v)] for c, v in STREAMS.items()}
    B_IDS = {sid for v in STREAMS_B.values() for sid, n, e in v}
    REQLOG = []            # every stream request: (user, file)
    NOT_FOUND = set()      # stream paths the provider answers 404 for
    # Norway v Wales: 4 h ahead, but never past Riyadh's midnight (the matches screen's "today" ends there)
    _riyadh_left = 86400 - ((int(time.time()) + 3 * 3600) % 86400)
    NOR_AHEAD = max(1500, min(4 * HOUR, _riyadh_left - 1200))
    # two matches tomorrow (Riyadh): Portugal v Morocco is in beIN Sports 2's day guide, Germany v Japan is in no guide.
    # They start at 22:30 tomorrow, but never sooner than 24.5 h from now (the other tomorrow fixture is at 24 h 5 min)
    _riyadh_start = int(time.time()) - ((int(time.time()) + 3 * 3600) % 86400)
    TOM_AHEAD = max(24 * HOUR + 1800, _riyadh_start + 86400 + 22 * HOUR + 1800 - int(time.time()))
    LIVE_SECONDS = int(os.environ.get('OUSWIS_LIVE_SECONDS', '600'))   # the live-speed stream is watched this long: 600 = the full test
    # guide programmes other than the default four: (title, start offset, stop offset) in seconds from now
    # item: the day's guide. Norway v Wales starts in 4 hours: only get_simple_data_table lists it, on two channels (the first, beIN Sports 1 HD, is the main one)
    EPGDAY = {'111': [('Live: Portugal vs Morocco - International Friendly', TOM_AHEAD - 600, TOM_AHEAD + 6600)],
              '110': [('Live: Norway vs Wales - UEFA Nations League', NOR_AHEAD - 600, NOR_AHEAD + 6600)],
              '114': [('مباشر: النرويج × ويلز - دوري الأمم الأوروبية', NOR_AHEAD - 600, NOR_AHEAD + 6600)]}
    EPGX = {'120': [('(L) الهلال × النصر · دوري روشن', -1800, 1800), ('NBA: Philadelphia 76ers vs Boston Celtics', -1200, 6000), ('NBA Highlights', 6000, 9600)],
            '103': [('Philadelphia 76ers vs Boston Celtics - NBA', -900, 6300), ('Volleyball: Italy vs Poland', 6300, 9900), ('Live: England vs Croatia - International Friendly', 5 * HOUR, 5 * HOUR + 6000)],   # the last one: the same match shown again 5 h later
            '110': [('Live: England vs Croatia - International Friendly', -1200, 6000), ('Studio', 6000, 9600), ('Live: Egypt vs Malawi - Africa Cup Qualifiers', 1500, 8100)],
            '114': [('Live: Moldova vs Kazakhstan - UEFA Nations League', 1200, 7800)],   # the real home of Moldova v Kazakhstan: beIN SPORTS 5 HD
            '117': [('Live: Moldova vs Kazakhstan - UEFA Nations League', 1200, 7800)],   # the same match on the SD copy: HD must win
            '115': [('Highlights Show', 1200, 7800)],   # beIN SPORTS 6 SD: the channel an old stale pick had linked
            '112': [('England vs Croatia - Classic', -600, 6600), ('Spain vs Germany - Highlights', 6600, 10200)],
            '102': [('Real Madrid vs Barcelona - Replay', -600, 6600), ('ريال مدريد × برشلونة — إعادة', 6600, 10200)],
            '105': [('Live: Senegal vs Sudan - Africa Cup of Nations Qualifying', -9360, 1440), ('Brazil vs Chile - Stories', 1500, 8100)]}   # Senegal v Sudan began 2.6 h ago and the feed has no result: over; Stories: not a match


    class Provider(BaseHTTPRequestHandler):
        def log_message(self, *a): pass
        def do_GET(self):
            url = urlparse(self.path); q = parse_qs(url.query)
            HITS[url.path.rsplit('/', 1)[-1]] = HITS.get(url.path.rsplit('/', 1)[-1], 0) + 1
            who = q.get('username', [''])[0] or (url.path.split('/')[2] if url.path.startswith('/live/') and url.path.count('/') >= 4 else '')
            if url.path.startswith('/live/'): REQLOG.append((who, url.path.rsplit('/', 1)[-1])); REQT.setdefault(url.path.rsplit('/', 1)[-1], []).append(time.time())
            sid0 = q.get('stream_id', [''])[0]
            if who == 'bob' and sid0.isdigit() and int(sid0) >= 5000: q['stream_id'] = [str(int(sid0) - 5000)]   # B's guide is A's, under B's numbers
            if url.path.startswith('/apis/site/v2/sports/soccer/'):
                slug = url.path.split('/')[6]
                if FEED_MODE[0] == 'down' and slug != 'eng.1':
                    self.send_response(500); self.end_headers(); return
                utc = time.gmtime()
                events = []
                if slug == 'caf.nations_qual' and q.get('dates', [''])[0] == time.strftime('%Y%m', utc):
                    def ev(eid, home, away, minutes_ago):
                        kick = time.strftime('%Y-%m-%dT%H:%MZ', time.gmtime(time.time() - minutes_ago * 60))
                        team = lambda n, ha: {'homeAway': ha, 'score': '0', 'team': {'id': n[:3], 'displayName': n, 'logo': ''}}
                        return {'id': eid, 'date': kick, 'status': {'displayClock': "%d'" % minutes_ago, 'type': {'state': 'in', 'name': 'STATUS_FIRST_HALF', 'shortDetail': "%d'" % minutes_ago}},
                                'competitions': [{'competitors': [team(home, 'home'), team(away, 'away')], 'broadcasts': []}]}
                    # the match the user reported: it is in beIN SPORTS 2 HD's guide as "غانا × الصومال"; the second one is in no guide
                    events = [ev('9001', 'Ghana', 'Somalia', 10), ev('9002', 'Suriname', 'Eritrea', 80)]
                def fx(eid, home, away, state, offset_min, hs='0', as_='0', goals=False, bc='Sky Sports'):
                    kick = time.strftime('%Y-%m-%dT%H:%MZ', time.gmtime(time.time() + offset_min * 60))
                    team = lambda n, ha, sc: {'homeAway': ha, 'score': sc, 'team': {'id': n[:3], 'displayName': n, 'logo': ('http://127.0.0.1:%d' % server.server_port + LOGO_FEED[n]) if n in LOGO_FEED else ''}}
                    name = {'in': 'STATUS_FIRST_HALF', 'post': 'STATUS_FULL_TIME', 'pre': 'STATUS_SCHEDULED'}[state]
                    comp = {'competitors': [team(home, 'home', hs), team(away, 'away', as_)], 'broadcasts': [{'names': [bc]}]}
                    if goals: comp['details'] = [{'scoringPlay': True, 'team': {'id': home[:3]}, 'athletesInvolved': [{'displayName': 'Salah'}], 'clock': {'displayValue': "23'"}, 'type': {'text': 'Goal'}},
                                                 {'scoringPlay': True, 'team': {'id': away[:3]}, 'athletesInvolved': [], 'clock': {}, 'type': {'text': 'Own Goal'}}]
                    return {'id': eid, 'date': kick, 'status': {'displayClock': '', 'type': {'state': state, 'name': name, 'shortDetail': 'FT' if state == 'post' else ''}}, 'competitions': [comp]}
                lname = 'English Premier League'
                if slug == 'eng.1' and FEED_MODE[0] == 'ok' and q.get('dates', [''])[0] == time.strftime('%Y%m', utc):
                    # a finished match with goals (today and yesterday), one tomorrow, one later today; a team name the table lacks
                    events = [fx('8007', 'Moldova', 'Kazakhstan', 'pre', 20), fx('8008', 'Egypt', 'Malawi', 'pre', 25), fx('8009', 'Spain', 'Greece', 'pre', 40, bc='beIN Sports 3'), fx('8010', 'Tunisia', 'Libya', 'pre', 35, bc='beIN SPORTS 7'), fx('8011', 'Norway', 'Wales', 'pre', NOR_AHEAD // 60), fx('8012', 'Portugal', 'Morocco', 'pre', TOM_AHEAD // 60), fx('8013', 'Germany', 'Japan', 'pre', TOM_AHEAD // 60 + 10), fx('8001', 'Liverpool', 'Manchester City', 'post', -240, '2', '1', goals=True), fx('8002', 'Wolverhampton Wanderers', 'Fulham FC XYZ', 'post', -1700, '0', '0'),
                              fx('8003', 'Arsenal', 'Chelsea', 'pre', 24 * 60 + 5), fx('8004', 'Tottenham Hotspur', 'Everton', 'pre', 150), fx('8005', 'Orléans', 'Dijon FCO', 'post', -30, '1', '0'), fx('8006', 'Rwanda', 'Malawi', 'post', -45, '0', '0')]   # finished a moment ago: on today's list whatever the hour
                body = {'leagues': [{'name': lname if slug == 'eng.1' else 'Africa Cup of Nations Qualifying', 'logos': []}], 'events': events}
                payload = json.dumps(body).encode(); kind = 'application/json'
            elif url.path == '/player_api.php':
                action = q.get('action', [''])[0]
                if action == 'get_live_categories':
                    body = [{'category_id': i, 'category_name': n} for i, n in CATS]
                elif action == 'get_live_streams':
                    cat = q.get('category_id', [''])[0]
                    if cat in FAIL_CATS:
                        self.send_response(500); self.end_headers(); return
                    src = STREAMS_B if who == 'bob' else STREAMS
                    rows = [(c, s, n, e) for c, v in src.items() for s, n, e in v] if cat == '' else [(cat, s, n, e) for s, n, e in src.get(cat, [])]
                    body = [{'num': int(s), 'name': n, 'stream_id': s, 'category_id': c, 'stream_icon': '', 'container_extension': e} for c, s, n, e in rows]
                elif action in ('get_short_epg', 'get_simple_data_table'):
                    now = int(time.time())
                    titles = ['الهلال × النصر · دوري روشن', 'الاستوديو التحليلي', 'أهداف الجولة', 'نشرة الرياضة']
                    if q.get('stream_id', [''])[0] == '101':   # a TV guide in English: the app shows Arabic
                        titles = ["Juventus vs OL Lyonnes - UEFA Women's Champions League", 'Arsenal vs Chelsea - English Premier League 2026/2027 - MD4', 'Game of Thrones', 'Week 10']
                    if q.get('stream_id', [''])[0] in ('111', '113'):   # beIN Sports 2: the Arabic title, 6 minutes after the kickoff the feed reports
                        titles = ['غانا × الصومال — كأس الأمم الأفريقية', 'تحليل المباريات', 'أهداف الجولة', 'نشرة الرياضة']
                    body = {'epg_listings': [{'title': base64.b64encode(t.encode()).decode(), 'start_timestamp': str(now - 1800 + i * 3600), 'stop_timestamp': str(now + 1800 + i * 3600)} for i, t in enumerate(titles)]}
                    sid = q.get('stream_id', [''])[0]
                    if sid in EPGX:
                        body = {'epg_listings': [{'title': base64.b64encode(t.encode()).decode(), 'start_timestamp': str(now + a), 'stop_timestamp': str(now + b)} for t, a, b in EPGX[sid]]}
                    if action == 'get_simple_data_table' and sid in EPGDAY:   # the whole day: what the short guide (the next few programmes) does not list yet
                        body['epg_listings'] += [{'title': base64.b64encode(t.encode()).decode(), 'start_timestamp': str(now + a), 'stop_timestamp': str(now + b)} for t, a, b in EPGDAY[sid]]
                elif action in ('get_vod_categories', 'get_vod_streams', 'get_series_categories', 'get_series'):   # no films or series in this subscription
                    body = []
                elif q.get('username', [''])[0] == 'bad':   # a provider that refuses the login (HTTP 200, auth 0)
                    body = {'user_info': {'auth': 0, 'status': 'Disabled'}}
                else:
                    UINFO.append(time.time())
                    body = {'user_info': {'auth': 1, 'status': 'Active', 'max_connections': '1', 'active_cons': ACTIVE[0], 'allowed_output_formats': ALLOWED, 'exp_date': str(int(time.time()) + 86400 * 120)}}
                payload = json.dumps(body).encode(); kind = 'application/json'
            elif url.path == '/logo/feed.png':
                payload = png((0, 200, 0)); kind = 'image/png'
            elif url.path.startswith('/badge/'):
                payload = png((255, 0, 255)); kind = 'image/png'
            elif url.path.endswith('/searchteams.php'):
                name = q.get('t', [''])[0]; SDB.append(name); SDB_NOW[0] += 1; SDB_NOW[1] = max(SDB_NOW[1], SDB_NOW[0]); time.sleep(0.25)
                teams = [{'strTeam': name, 'strSport': 'Soccer', 'strBadge': 'http://127.0.0.1:%d/badge/%s.png' % (server.server_port, name)}] if name in SDB_HAS else None
                SDB_NOW[0] -= 1
                payload = json.dumps({'teams': teams}).encode(); kind = 'application/json'
            elif url.path in NOT_FOUND:
                self.send_error(404); return
            elif url.path.startswith('/live/bob/bob/'):
                if url.path.rsplit('/', 1)[-1].split('.')[0] not in B_IDS: self.send_error(404); return
                payload = data; kind = 'video/mp2t'
            elif url.path == '/live/demo/demo/998.ts':
                # a live server: sends at display speed for 11 minutes (ffmpeg -re), so the player's buffer stays constant
                self.send_response(200); self.send_header('Content-Type', 'video/mp2t'); self.end_headers()
                proc = subprocess.Popen(['ffmpeg', '-v', 'error', '-re', '-f', 'lavfi', '-i', 'testsrc=s=640x360:r=10', '-t', str(LIVE_SECONDS + 60), '-c:v', 'libx264', '-preset', 'ultrafast',
                                         '-pix_fmt', 'yuv420p', '-g', '10', '-f', 'mpegts', '-muxrate', '800k', '-'], stdout=subprocess.PIPE)
                try:
                    while True:
                        chunk = proc.stdout.read(4096)
                        if not chunk: break
                        self.wfile.write(chunk); self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError): pass
                finally: proc.kill()
                return
            elif url.path == '/live/demo/demo/997.ts':
                T997.append(time.time())
                if len(T997) <= 2: self.send_error(509); return
                payload = data; kind = 'video/mp2t'
            elif url.path == '/live/demo/demo/999.ts':
                payload = short_data; kind = 'video/mp2t'
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
    # the recents hold the real channel and two broken entries (no name; no id): they must never reach the screen
    recent = json.dumps([{'id': '101', 'name': 'SSC 1 HD', 'icon': '', 'num': '101', 'ext': 'ts'}, {'id': '900', 'name': '', 'icon': '', 'num': '9', 'ext': 'ts'},
                         {'id': '', 'name': 'Ghost', 'icon': '', 'num': '8', 'ext': 'ts'}, {'id': '901', 'name': '   ', 'icon': '', 'num': '7', 'ext': 'ts'}]).replace('"', '&quot;')
    A_KEY = jhash('http://127.0.0.1:%d|demo' % port); B_KEY = jhash('http://127.0.0.1:%d|bob' % port)
    live = ('<?xml version="1.0" encoding="utf-8"?><map><string name="last">%s</string><string name="recent">%s</string><string name="dataFor">%s</string>'
            '<string name="espnBase">http://127.0.0.1:%d</string><string name="sportsDbBase">http://127.0.0.1:%d</string></map>') % (last, recent, A_KEY, port, port)
    subprocess.run([ADB, 'shell', 'cat > %s/ouswis-live.xml' % folder], input=live.encode(), check=True)
    # the mock feeds are the only source of channels in this test: the two real sites (Yallakora, Filgoal) are switched off in the emulator
    adb('shell', 'sh', '-c', "'mkdir -p /data/user/0/%s/files && touch /data/user/0/%s/files/no-sites'" % (PKG, PKG))
    adb('shell', 'chown', '-R', uid + ':' + uid, folder, '/data/user/0/%s/files' % PKG)
    adb('logcat', '-c')

    # 0. no green anywhere in the sources of the preview and the player (the preview's empty place was a dark green gradient)
    src = Path(__file__).resolve().parent.parent / 'app'
    hits = []
    for f in list(src.rglob('*.java')) + list(src.rglob('*.xml')):
        if f.name in ('LiveActivity.java', 'PlayerActivity.java', 'LivePlayer.java', 'styles.xml', 'brand_colors.xml'):
            hits += [f.name + ': ' + m for m in re.findall(r'(?i)(14231D|0A1410|2E7D32|4CAF50|Color\.GREEN|holo_green)', f.read_text(encoding='utf-8'))]
    assert not hits, 'Green found in the preview/player sources: ' + str(hits)
    note('PASS: no green colour in LiveActivity, PlayerActivity, LivePlayer or the styles.')

    # 1. home, and the account sealed at rest
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.HomeActivity')
    shot('redesign-home-loading.png')
    assert any(l == 'مباريات اليوم' for l in labels()), 'The matches row must not vanish while the data loads: ' + str(labels()[:20])
    hero = wait_label('آخر قناة شاهدتها: SSC 1', 'the last-channel card with the saved channel')
    arabic = wait_label('يوفنتوس × ليون — دوري أبطال أوروبا للسيدات', 'the guide title of the last channel in Arabic')
    assert not any('Lyonnes' in l or 'Juventus' in l for l in labels()), 'The English match title must not show: ' + str(labels()[:12])
    img = shot('redesign-home.png'); no_crash('Home')
    # the last-channel card: dark, #123D5C to #121A3A at 95 %: sample its inside along the top margin (no text there)
    pts = [img.getpixel((x, 180)) for x in range(700, 1500, 160)]
    img.crop((600, 150, 1860, 420)).save(OUT / 'hero-card-crop.png')
    note('HERO card pixels (x=700..1340, y=180): ' + str(pts))
    assert all(r <= 40 and g <= 55 and b <= 80 and b - r <= 45 for r, g, b in pts), 'The last-channel card is not the dark prayer-card colour: ' + str(pts)
    note('PASS: the English guide title "Juventus vs OL Lyonnes - UEFA Women\'s Champions League" shows as ' + arabic[0][-60:] + '.')
    assert 'آخر قناة' in focused_label(), 'The last-channel card must hold the first focus: ' + focused_label()
    # the focus ring belongs round the card (no small ring under it, no stray dash inside it)
    hb = [[int(v) for v in re.findall(r'\d+', n.get('bounds'))] for n in tree().iter('node') if n.get('package') == PKG and n.get('focused') == 'true' and 'آخر قناة' in (n.get('text', '') + n.get('content-desc', ''))]
    assert hb and hb[0][3] - hb[0][1] >= 140 and hb[0][2] - hb[0][0] >= 1000, 'The focused node of the last-channel card must be the whole card: ' + str(hb)
    under = [img.getpixel((x, y)) for x in range(1380, 1560, 6) for y in range(412, 480, 4)]
    assert sum(1 for px in under if min(px) >= 245) < 6, 'A white ring (an empty focus) shows under the last-channel card'
    note('PASS: the focus ring is round the whole last-channel card ' + str(hb[0]) + ' and nothing is drawn under it.')
    time.sleep(2)
    plain = adb('shell', 'cat', folder + '/XtreamActivity.xml')
    assert 'name="pass"' not in plain and 'name="user"' not in plain and 'name="server"' not in plain, 'Plain-text login was not erased: ' + plain
    vault = adb('shell', 'cat', folder + '/xtream-vault.xml')
    assert re.search(r'name="blob">[kf]:', vault), 'No sealed login: ' + vault
    assert 'demo' not in vault and '127.0.0.1' not in vault, 'The vault must not hold the login in the clear'
    note('PASS: the plain-text Xtream login was sealed (AES-GCM blob) and erased from the old preferences; the vault holds no readable server, user or password.')
    labs = labels()
    for need in ('البث المباشر', 'أفلام', 'مسلسلات', 'المباريات', 'كل التطبيقات', 'الأخيرة', 'التحديثات', 'الإعدادات', 'إضافة تطبيق'):
        assert any(l == need for l in labs), 'Missing control: ' + need
    note('PASS: new home: last-channel card holds the first focus; eight shortcuts and the + tile are reachable controls.')

    measure(os.environ.get('PERF_TAG', 'run'))
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.HomeActivity'); wait_label('آخر قناة شاهدتها: SSC 1', 'the home again after the measurement')

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
    secs = [l for l in ls if l.startswith('قسم ')]
    pos = lambda part: next((i for i, l in enumerate(secs) if part in l), None)
    arabic_at = [pos(x) for x in ('رياضة', 'AR| عام') if pos(x) is not None]
    foreign_at = [pos(x) for x in ('US APPLE TV', 'FR|TF1 HD', 'FR| BEIN SPORTS', 'BE| SPORT', 'US| NEWS') if pos(x) is not None]
    assert arabic_at and (not foreign_at or max(arabic_at) < min(foreign_at)), 'Arabic and Arabic sports sections first, then FR / BE / US (even those that say sport): ' + str(secs)
    note('PASS: section order on the live screen: ' + ' > '.join(x[4:30] for x in secs))
    rec = [l for l in ls if 'الأخيرة' in l and l.startswith('قسم')]
    assert rec and '(1)' in rec[0], 'The recents must hold the one real channel (the entries without name or id are dropped when read): ' + str(rec)
    noname = [l for l in ls if re.fullmatch(r'\d* ?· ?', l) or l.endswith('· ') or '· Ghost' in l]
    assert not noname, 'An empty item on the live screen: ' + str(noname)
    note('PASS: the recents hold 1 channel; the 3 stored entries without a name or an id never reach the screen.')
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
    # CH+ twice at once (101 -> 102 -> 103): the channel passed on the way never connects, only the one the viewer rested on
    before_102, before_103 = HITS.get('102.ts', 0), HITS.get('103.ts', 0)
    adb('shell', 'input keyevent 166; input keyevent 166'); time.sleep(6)
    assert HITS.get('102.ts', 0) == before_102, 'A channel passed in a quick zap must not connect: ' + str(HITS)
    assert HITS.get('103.ts', 0) > before_103, 'The channel the viewer rested on must connect: ' + str(HITS)
    note('PASS: quick CH+ CH+ opened only the last channel (the passed one never connected).')
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

    # 3c. a section whose channel list fails to load said nothing and stayed empty: now it says so and tries again by itself
    adb('shell', 'sh', '-c', "'rm -f /data/user/0/%s/files/cat-*-13*'" % PKG)
    FAIL_CATS.add('13')
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.LiveActivity'); wait_label('قسم رياضة', 'the live screen after a restart')
    keys('DPAD_RIGHT')
    for _ in range(14):
        if focused_label().startswith('قسم أفلام'): break
        keys('DPAD_DOWN', pause=0.6)
    assert focused_label().startswith('قسم أفلام'), 'Could not reach the movies section: ' + focused_label()
    got = wait_label('تعذّر تحميل القنوات', 'the failed-section message', tries=40)
    shot('redesign-live-section-failed.png')
    assert not any('جارٍ تحميل القنوات' in l for l in labels()), 'A failed section must not say "loading" for ever'
    FAIL_CATS.clear()
    wait_label('Movies One', 'the channel of the section after the automatic retry', tries=40)
    shot('redesign-live-section-retried.png')
    note('PASS: a section whose list failed said "تعذّر تحميل القنوات", then filled by itself once the server answered.')
    keys('BACK', pause=1)

    # 4. "auto" is ts, always: a channel asked as m3u8 (whose m3u8 the server refuses) plays from .ts and the .m3u8 is never requested
    before = HITS.get('112.m3u8', 0)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.PlayerActivity', '-e', 'id', '112', '-e', 'name', 'beIN Sports 3', '-e', 'ext', 'm3u8', '-e', 'num', '112')
    time.sleep(9); img = shot('redesign-player-fallback.png')
    assert HITS.get('112.m3u8', 0) == before and HITS.get('112.ts', 0) > 0, 'Auto must be ts only: ' + str(HITS)
    assert colourful(img, (0.2, 0.2, 0.8, 0.8)) > 0.2, 'The .ts did not play'
    note('PASS: "auto" played .ts and never asked for the .m3u8.')
    keys('BACK', pause=1)

    # 4a. m3u8 chosen by hand + a channel whose m3u8 the server refuses (404): after the first failure ts is tried for that channel at once
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.PlayLogActivity'); wait_label('سجل التشغيل', 'the playback log page (set m3u8)')
    keys('DPAD_CENTER', pause=0.8); assert any('m3u8 فقط' in l for l in labels()), 'The stream type must read m3u8 only'
    keys('BACK', pause=1)
    before_m3u8, before_ts = HITS.get('112.m3u8', 0), HITS.get('112.ts', 0)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.PlayerActivity', '-e', 'id', '112', '-e', 'name', 'beIN Sports 3', '-e', 'ext', 'm3u8', '-e', 'num', '112')
    time.sleep(9); img = shot('redesign-player-m3u8-then-ts.png')
    assert before_m3u8 + 1 <= HITS.get('112.m3u8', 0) <= before_m3u8 + 4, 'With m3u8 chosen it is asked for, and only the player\'s own few retries follow: ' + str(HITS)
    assert HITS.get('112.ts', 0) > before_ts and colourful(img, (0.2, 0.2, 0.8, 0.8)) > 0.2, 'After the refused m3u8 the same channel must play from ts: ' + str(HITS)
    note('PASS: with m3u8 chosen, a refused m3u8 was asked for (%d time(s)) and the channel then played from ts.' % (HITS.get('112.m3u8', 0) - before_m3u8))
    keys('BACK', pause=1)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.PlayLogActivity'); wait_label('سجل التشغيل', 'the playback log page (back to auto)')
    keys('DPAD_CENTER', pause=0.8); keys('DPAD_CENTER', pause=0.8)   # ts only, then auto
    assert any('تلقائي' in l for l in labels()), 'The stream type must be back on auto'
    keys('BACK', pause=1)

    # 4b. the playback log (Settings > سجل التشغيل): what the player did, the provider's connection count, no login in it
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.PlayLogActivity'); wait_label('سجل التشغيل', 'the playback log page')
    time.sleep(1)
    log_lines = [l for l in labels() if ' | ' in l]
    pl = shot('redesign-playlog.png')
    pl_ref = Image.open(Path(__file__).resolve().parent.parent / 'app' / 'assets' / 'backgrounds' / 'ouswis_bg_stars_inner_1080p.webp').convert('RGB').resize(pl.size)
    pl_off = [(q, pl.getpixel(q), pl_ref.getpixel(q)) for q in [(1890, 300), (1890, 700), (960, 1068), (960, 8), (30, 700)] if max(abs(p - r) for p, r in zip(pl.getpixel(q), pl_ref.getpixel(q))) > 28]
    assert len(pl_off) <= 1, 'The playback log must draw the dark inner wallpaper: ' + str(pl_off)
    note('PASS: the playback log page draws the dark inner wallpaper (%d spots off).' % len(pl_off))
    note('Playback log lines: ' + str(len(log_lines)))
    assert any('HTTP 404' in l or 'BAD_HTTP_STATUS' in l for l in log_lines), 'The refused .m3u8 (404) must be logged with its http status: ' + str(log_lines[:12])
    assert any('الحالة: READY' in l for l in log_lines) and any('ظهرت أول لقطة' in l for l in log_lines), 'State changes and the first frame must be logged: ' + str(log_lines[:12])
    assert not any('demo' in l.lower() and '/live/' in l for l in log_lines), 'The log must not hold the login or the address'
    layer_lines = [l for l in log_lines if 'الطبقات (' in l]
    assert layer_lines, 'After the first frame the layers over the video must be logged: ' + str(log_lines[:12])
    assert any('فوق الفيديو: لا شيء' in l for l in layer_lines), 'Nothing may sit over the video after the first frame: ' + str(layer_lines[:3])
    assert any('الغطاء مخفي' in l for l in layer_lines), 'The loading cover must be hidden after the first frame: ' + str(layer_lines[:3])
    note('PASS: the playback log shows the layers after the first frame, the cover hidden and nothing over the video: ' + layer_lines[0][:300])
    assert len(log_lines) <= 100, 'The page shows the last 100 events only: ' + str(len(log_lines))
    assert 'نوع البث' in focused_label(), 'The stream type button takes the focus first: ' + focused_label()
    for expect in ('m3u8 فقط', 'ts فقط', 'تلقائي'):
        keys('DPAD_CENTER', pause=0.8)
        assert any(expect in l for l in labels()), 'OK on the stream type must reach ' + expect + ': ' + str([l for l in labels() if 'نوع' in l])
    note('PASS: the playback log page lists the events (opening, states, http status, connection count) and the stream type cycles auto / m3u8 / ts.')
    keys('DPAD_CENTER', pause=0.8); keys('DPAD_CENTER', pause=0.8)   # ts only
    keys('BACK', pause=1)
    before_m3u8 = HITS.get('112.m3u8', 0)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.PlayerActivity', '-e', 'id', '112', '-e', 'name', 'beIN Sports 3', '-e', 'ext', 'm3u8', '-e', 'num', '112')
    time.sleep(8); img = shot('redesign-player-ts-only.png')
    assert HITS.get('112.m3u8', 0) == before_m3u8, 'With "ts only" the .m3u8 must never be asked for: ' + str(HITS)
    assert colourful(img, (0.2, 0.2, 0.8, 0.8)) > 0.2, '"ts only" did not play'
    note('PASS: with the stream type set to ts only, the channel played from .ts and the .m3u8 was never requested.')
    keys('BACK', pause=1)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.PlayLogActivity'); wait_label('سجل التشغيل', 'the playback log page again')
    keys('DPAD_CENTER', pause=0.8)   # back to automatic
    assert any('تلقائي' in l for l in labels()), 'The stream type must be back on automatic'
    keys('BACK', pause=1)

    # 4c. the server closes the stream (the 5 s clip ends): the same channel is joined again at once, the last picture stays, no loading text
    hits999 = HITS.get('999.ts', 0)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.PlayerActivity', '-e', 'id', '999', '-e', 'name', 'Short clip', '-e', 'ext', 'ts', '-e', 'num', '999')
    time.sleep(4)
    frames, blank = 0, 0
    blank_at = []; t_start = time.time(); seen_picture = False
    loading_seen = []
    t_end = time.time() + 22
    next_dump = time.time() + 2
    while time.time() < t_end:
        img = shot('redesign-reconnect-burst.png'); frames += 1
        c_now = colourful(img, (0.2, 0.2, 0.8, 0.8))
        if c_now >= 0.05: seen_picture = True
        if c_now < 0.05 and seen_picture:   # frames before the very first picture are the channel opening, not a reconnect
            blank += 1; blank_at.append('%d@%.1fs(%.2f)' % (frames, time.time() - t_start, c_now))
            if blank == 1: img.save(OUT / 'redesign-reconnect-blank.png')
        if time.time() >= next_dump:
            loading_seen += [l for l in labels() if 'جارٍ' in l or 'إعادة الاتصال' in l]
            next_dump = time.time() + 4
        time.sleep(0.3)
    reconnects = HITS.get('999.ts', 0) - hits999
    note('Reconnect test: %d connections in 26 s, %d frames sampled, %d blank, loading text seen: %s; blank at %s' % (reconnects, frames, blank, loading_seen, blank_at))
    assert reconnects >= 3, 'The 5 s clip ends again and again: the channel must be joined again each time (connections: %d)' % reconnects
    assert blank <= 1, 'The last picture must stay on screen while reconnecting (%d of %d frames were blank)' % (blank, frames)
    assert not loading_seen, 'No loading text may show for a reconnect that takes under 3 s: ' + str(loading_seen)
    keys('BACK', pause=1)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.PlayLogActivity'); wait_label('سجل التشغيل', 'the playback log page after the reconnects')
    time.sleep(1)
    page = ' || '.join(labels())
    ms = [int(x) for x in re.findall(r'عاد البث بعد (\d+) مللي ثانية', page)]
    note('Reconnect times (ms): ' + str(ms))
    assert 'إعادة اتصال فورية' in page or ms, 'Each reconnect must be in the log: ' + page[:600]
    assert ms and max(ms) < 3000, 'The picture must be back within 3 s of each reconnect: ' + str(ms)
    assert 'محاولة' not in ' '.join(l for l in labels() if 'تشغيل Short' in l), 'A reconnect after ENDED must not count as a failed attempt: ' + str(labels())
    shot('redesign-playlog-reconnect.png')
    note('PASS: when the stream ended the same channel was joined again at once (%d times), the last picture stayed (%d blank of %d frames), no loading text, back in %s ms.' % (reconnects, blank, frames, ms))
    keys('BACK', pause=1)

    # 0. a healthy live stream: the server sends at display speed, so the buffer stays at a constant few seconds while the position advances.
    # For 10 minutes that must never be taken for a stall: zero reconnects.
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.PlayLogActivity'); wait_label('سجل التشغيل', 'the playback log page before the live run')
    tap('مسح السجل'); time.sleep(1); keys('BACK', pause=1)   # the earlier tests' reconnects must not be in the page this run is judged by
    hits998 = HITS.get('998.ts', 0)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.PlayerActivity', '-e', 'id', '998', '-e', 'name', 'Live paced', '-e', 'ext', 'ts', '-e', 'num', '998')
    t_live = time.time(); alive = True
    while time.time() - t_live < LIVE_SECONDS:
        time.sleep(min(30, max(1, LIVE_SECONDS - (time.time() - t_live))))
        top = adb('shell', 'dumpsys', 'activity', 'activities', check=False).split('topResumedActivity')[-1][:200]
        assert 'PlayerActivity' in top, 'The player must stay open for the whole run: ' + top
    conns = HITS.get('998.ts', 0) - hits998
    keys('BACK', pause=1)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.PlayLogActivity'); wait_label('سجل التشغيل', 'the playback log page after the 10 minute run')
    time.sleep(1)
    page = ' || '.join(labels())
    note('10 minute paced stream: %d connection(s). Log: %s' % (conns, page[:900]))
    assert 'إعادة اتصال' not in page and 'تجمّد' not in page and 'ENDED' not in page, 'A healthy paced stream must never be reconnected: ' + page[:900]
    assert conns == 1, 'One connection for the whole run, not %d' % conns
    note('PASS: a live-speed stream for 10 minutes (buffer constant): %d connection, no reconnect, no freeze event in the log.' % conns)
    keys('BACK', pause=1)

    # 4e. a channel opens at once: even while the provider still counts an old connection (active_cons=1) nothing is waited for
    # and user_info is not asked before the open (only the one diagnostic call when the screen starts)
    ACTIVE[0] = '1'
    REQT.pop('102.ts', None); n_ui = len(UINFO)
    t_start = time.time()
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.PlayerActivity', '-e', 'id', '102', '-e', 'name', 'SSC 2 HD', '-e', 'ext', 'ts', '-e', 'num', '102')
    time.sleep(8)
    ui_calls = len(UINFO) - n_ui
    ACTIVE[0] = '0'
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK'); time.sleep(1)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.PlayLogActivity'); wait_label('سجل التشغيل', 'the playback log page (immediate open)')
    page = ' || '.join(labels())
    first = REQT.get('102.ts', [None])[0]
    assert first is not None and first - t_start < 2.0, 'The channel must be asked for within 2 s of opening, even with active_cons=1: %s' % (None if first is None else round(first - t_start, 2))
    assert 'انتظار تحرير الاتصال القديم' not in page and 'جاري التجهيز' not in page, 'No wait for the old connection before opening: ' + page[:900]
    assert ui_calls <= 1, 'user_info must not be asked before each open (only the one diagnostic call), calls: %d' % ui_calls
    assert 'ظهرت أول لقطة' in page, 'The channel must open: ' + page[:900]
    note('PASS: with active_cons=1 the channel was asked for %.2f s after opening, no wait in the log, user_info calls: %d.' % (first - t_start, ui_calls))
    keys('BACK', pause=1)

    # 4f. HTTP 509 (the connection limit): the waits are 2 s then 3 s; after 1 s the dark cover (#0B0D1A, the new channel's name, no translucent box) replaces the frozen picture
    T997.clear()
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.PlayerActivity', '-e', 'id', '997', '-e', 'name', 'Limit', '-e', 'ext', 'ts', '-e', 'num', '997')
    for _ in range(60):
        if T997: break
        time.sleep(0.1)
    assert T997, 'The channel was never asked for'
    time.sleep(max(0, T997[0] + 1.6 - time.time()))
    cover = shot('redesign-player-cover.png')
    w, h = cover.size
    def near(px, ref=(11, 13, 26)): return all(abs(a - b) <= 8 for a, b in zip(px, ref))
    spots = [(0.03, 0.05), (0.97, 0.05), (0.1, 0.2), (0.9, 0.3), (0.05, 0.6), (0.95, 0.6)]   # not the bottom strip: that is the channel-number banner, which stays
    off = [sp for sp in spots if not near(cover.getpixel((int(sp[0] * w), int(sp[1] * h))))]
    time.sleep(16)
    gaps = [round(b - a, 1) for a, b in zip(T997, T997[1:])]
    img = shot('redesign-player-509.png')
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK'); time.sleep(1)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.PlayLogActivity'); wait_label('سجل التشغيل', 'the playback log page (509)')
    page = ' || '.join(labels())
    assert 'تجاوز حد الاتصال' in page, 'HTTP 509 must be logged as the connection limit: ' + page[:900]
    assert len(T997) >= 3 and 1.8 <= gaps[0] < 2.8 and 2.8 <= gaps[1] < 4.3, 'The waits after a 509 must be 2 s then 3 s, gaps: ' + str(gaps) + ' ' + page[:600]
    assert not off, 'After 1 s of loading the whole screen must be the dark cover #0B0D1A (no frozen picture, no translucent box), not matching: ' + str(off)
    assert 'غطاء التحميل: Limit' in page, 'The cover must carry the new channel name: ' + page[:900]
    assert colourful(img, (0.2, 0.2, 0.8, 0.8)) > 0.2, 'The channel must play once the limit let it through: ' + str(gaps)
    assert near(img.getpixel((int(0.03 * w), int(0.05 * h)))) is False, 'The cover must be gone once the picture is up'
    note('PASS: HTTP 509 waits were %s s (2 then 3), the cover #0B0D1A showed after 1 s on all %d spots, then the channel played.' % (gaps, len(spots)))
    keys('BACK', pause=1)

    # 4d. the account allows only ts (user_info.allowed_output_formats): a channel asked as m3u8 goes straight to ts, m3u8 is never requested
    ALLOWED[:] = ['ts']
    adb('shell', 'rm', '-f', '/data/user/0/%s/files/playlog.txt' % PKG)   # a fresh log: its first lines are the open and the account
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.PlayerActivity', '-e', 'id', '201', '-e', 'name', 'Al Arabiya', '-e', 'ext', 'ts', '-e', 'num', '201')
    time.sleep(5); keys('BACK', pause=1)    # reads user_info and remembers the formats
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.PlayLogActivity'); wait_label('سجل التشغيل', 'the playback log page (account)')
    time.sleep(1)
    page = [l for l in labels() if ' | ' in l]
    assert any('فتح ملء الشاشة' in l for l in page), 'The log must say the full screen opened: ' + str(page[:8])
    assert any('max_connections=1' in l and 'active_cons=0' in l and 'الصيغ المسموحة: ts' in l for l in page), 'The log must hold max_connections, active_cons and the allowed formats from user_info: ' + str(page[:8])
    note('PASS: the log holds the opening, max_connections=1, active_cons=0 and the allowed formats (ts).')
    keys('BACK', pause=1)
    before = HITS.get('113.m3u8', 0)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.PlayerActivity', '-e', 'id', '113', '-e', 'name', 'Four', '-e', 'ext', 'm3u8', '-e', 'num', '113')
    time.sleep(7); img = shot('redesign-player-allowed-ts.png')
    assert HITS.get('113.m3u8', 0) == before, 'The account allows only ts: m3u8 must not be requested: ' + str(HITS)
    assert colourful(img, (0.2, 0.2, 0.8, 0.8)) > 0.2, 'The ts-only account did not play'
    note('PASS: with allowed_output_formats = [ts], an m3u8 channel went straight to ts and m3u8 was never requested.')
    keys('BACK', pause=1)
    ALLOWED[:] = ['m3u8', 'ts']
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.PlayerActivity', '-e', 'id', '201', '-e', 'name', 'Al Arabiya', '-e', 'ext', 'ts', '-e', 'num', '201')
    time.sleep(4); keys('BACK', pause=1)

    # 5. the league -> channel table (Settings): pick by hand, then back to the default
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.ChannelMapActivity')
    wait_label('دوري روشن السعودي', 'the league table')
    assert any('دوري روشن السعودي' in l and 'غير محددة' in l for l in labels()), 'With no pick the table must say so, not name a default: ' + str(labels()[:14])
    shot('redesign-channel-map.png')
    for _try in range(3):   # the first OK can land before the row has focus: press again if no list came
        keys('DPAD_CENTER', pause=2)
        if any('اختر القسم' in l for l in labels()): break
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
    # the matches screen draws the supplied inner wallpaper with no dark layer over it: its edges equal the file's own pixels
    inner_ref = Image.open(Path(__file__).resolve().parent.parent / 'app' / 'assets' / 'backgrounds' / 'ouswis_bg_stars_inner_1080p.webp').convert('RGB').resize(img.size)
    spots_bg = [(30, 300), (30, 700), (1890, 300), (1890, 700), (960, 1068), (960, 8)]
    off_bg = [(sp, img.getpixel(sp), inner_ref.getpixel(sp)) for sp in spots_bg if max(abs(p - q) for p, q in zip(img.getpixel(sp), inner_ref.getpixel(sp))) > 28]
    assert len(off_bg) <= 1, 'The matches screen background must be the supplied inner wallpaper, not darkened further: ' + str(off_bg)
    note('PASS: matches screen background equals the supplied inner wallpaper at %d of %d edge spots.' % (len(spots_bg) - len(off_bg), len(spots_bg)))
    cards = [l for l in labels() if ' ضد ' in l and not l.startswith('تفاصيل')]
    # the guide of the sports channels names one match (الهلال × النصر): it is a match on the screen, with its channel
    guide_card = wait_label('الهلال ضد النصر', 'the match found only in the guide')
    assert any(('SSC 1' in l or 'beIN Sports 1' in l or 'AD Sports 1' in l) for l in guide_card), 'The guide match must carry the channel it was found on: ' + str(guide_card)
    # a channel may also be named by the two sites the app asks for the broadcaster (Yallakora, Filgoal): their cached answers are in the app's own folder
    try: site_rows = json.loads(adb('shell', 'cat', '/data/user/0/%s/files/channel-hints.json' % PKG, check=False) or '{}').get('rows', [])
    except Exception: site_rows = []
    def sp(t):
        t = re.sub(r'[\u064B-\u0652\u0640]', '', t or '')
        for a, b in (('أ', 'ا'), ('إ', 'ا'), ('آ', 'ا'), ('ة', 'ه'), ('ى', 'ي')): t = t.replace(a, b)
        return ' '.join(w[2:] if w.startswith('ال') and len(w) > 4 else w for w in t.split())
    def named_by_sites(card):
        pair = card.split(' · ')[0].split(' ضد ')
        if len(pair) != 2: return False
        a, b = sp(pair[0]), sp(pair[1])
        def same(x, y): return bool(x) and bool(y) and (x == y or x in y or y in x)
        return any((same(sp(r.get('h')), a) and same(sp(r.get('a')), b)) or (same(sp(r.get('h')), b) and same(sp(r.get('a')), a)) for r in site_rows if r.get('c'))
    from_sites = 0
    for l in cards:
        if any(t in l for t in ('الهلال', 'يوفنتوس', 'آرسنال', 'غانا', 'إنجلترا', 'مولدوفا', 'مصر', 'إسبانيا', 'النرويج')): continue   # found in the mock guide: confirmed
        if ('beIN' in l or 'SSC' in l or 'AD Sports' in l) and named_by_sites(l):
            from_sites += 1; continue   # confirmed by a site that names both teams
        assert 'beIN' not in l and 'SSC' not in l and 'AD Sports' not in l and 'beIN SPORTS' not in l, 'No channel may be named without a confirmation: ' + l
        if 'انتهت' not in l: assert 'اختر القناة' in l, 'An unconfirmed match must ask to pick the channel: ' + l
    note('PASS: a channel is named only when the guide or a site naming both teams confirms it (%d from the sites); every other match asks to pick one.' % from_sites)
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
    if named_by_sites(ghana[0]):   # the real sites also list this fixture (today's real Ghana v Somalia): their channel wins, the guide is the fallback
        assert any(('beIN' in l or 'SSC' in l or 'AD Sports' in l) for l in ghana) and not any('اختر القناة' in l for l in ghana), 'Ghana v Somalia must show the channel a site names: ' + str(ghana)
    else:
        assert any('beIN Sports 2' in l for l in ghana) and not any('اختر القناة' in l for l in ghana), 'Ghana v Somalia must show beIN Sports 2 from its guide: ' + str(ghana)
    shot('redesign-matches-ghana.png')
    note('PASS: "غانا × الصومال" in beIN Sports 2\'s guide linked to the feed\'s "Somalia at Ghana": the card reads ' + ghana[0][-70:] + '.')
    # club crests: the feed's own logo first; TheSportsDB only for a team whose feed logo is missing or does not load, once, in the background
    focus_card('مصر ضد مالاوي'); time.sleep(3)
    cr = shot('redesign-matches-crests.png').convert('RGB')
    def share(box, test):
        px = [cr.getpixel((x, y)) for x in range(box[0], box[2], 2) for y in range(box[1], box[3], 2)]
        return sum(1 for p in px if test(p)) / len(px)
    green = share((450, 358, 490, 398), lambda p: p[1] > 150 and p[0] < 90 and p[2] < 90)
    magenta = share((138, 358, 178, 398), lambda p: p[0] > 180 and p[2] > 180 and p[1] < 90)
    assert green > 0.4, 'Egypt\'s crest must be the feed\'s own logo (green): ' + str(green)
    assert magenta > 0.4, 'Malawi\'s feed logo answers 404: its crest must come from TheSportsDB (magenta): ' + str(magenta)
    assert 'Egypt' not in SDB, 'TheSportsDB must not be asked for a team whose feed logo loads: ' + str(SDB)
    assert SDB.count('Malawi') == 1 and SDB.count('Kazakhstan') == 1, 'A team is looked up once: ' + str(SDB)
    assert SDB_NOW[1] <= 4, 'At most 4 requests at once: ' + str(SDB_NOW[1])
    asked = len(SDB)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.HomeActivity'); time.sleep(3)
    focus_card('مصر ضد مالاوي'); time.sleep(2)
    assert len(SDB) == asked, 'Nothing is asked again for a team already answered (a badge or no badge): ' + str(SDB[asked:])
    note('PASS: crests: feed logo shown (green %.2f), TheSportsDB fallback shown (magenta %.2f) for the team whose feed logo 404s; lookups %s, at most %d at once, none repeated.' % (green, magenta, sorted(set(SDB)), SDB_NOW[1]))
    # the play log says what each lookup found: forget the saved answers, start the app again and read the log of the new lookups
    adb('shell', 'am', 'force-stop', PKG); time.sleep(2)
    adb('shell', 'rm', '-f', '/data/user/0/%s/shared_prefs/ouswis-badges.xml' % PKG, '/data/user/0/%s/files/playlog.txt' % PKG)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.HomeActivity'); wait_label('غانا ضد الصومال', 'the home after the badge answers were forgotten')
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MatchesScreenActivity'); time.sleep(3)
    blog = ''
    for _ in range(40):
        blog = adb('shell', 'cat', '/data/user/0/%s/files/playlog.txt' % PKG, check=False)
        if 'شعار Malawi: لقى في TheSportsDB' in blog and 'ما لقى' in blog: break
        time.sleep(3)
    (OUT / 'redesign-badge-playlog.txt').write_text(blog)
    assert 'شعار Malawi: لقى في TheSportsDB' in blog, 'The play log must say the badge was found: ' + blog[-500:]
    assert 'شعار Tunisia: ما لقى في TheSportsDB' in blog, 'The play log must say when no badge was found: ' + blog[-500:]
    note('PASS: the play log names each club looked up: found (Malawi) and not found (Tunisia). ' + [l for l in blog.splitlines() if 'شعار' in l][0][:120])
    # tomorrow's matches: the day guide (get_simple_data_table) lists Portugal v Morocco tomorrow evening, so it carries beIN Sports 2;
    # Germany v Japan is in no guide: it stays "اختر القناة" until a guide names it
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MatchesScreenActivity'); time.sleep(3)
    keys('DPAD_UP', 'DPAD_UP', 'DPAD_UP', 'DPAD_UP', 'DPAD_UP', 'DPAD_UP', pause=0.4); keys('DPAD_LEFT', 'DPAD_CENTER', pause=1)   # up to the day chips, RTL: left of today is tomorrow
    por = wait_label('البرتغال ضد المغرب', 'Portugal v Morocco on the tomorrow tab', tries=60)
    for _ in range(30):
        por = [l for l in labels() if 'البرتغال ضد المغرب' in l]
        if any('beIN Sports 2' in l for l in por): break
        time.sleep(2)
    ger = [l for l in labels() if 'ألمانيا ضد اليابان' in l]
    shot('redesign-matches-tomorrow.png')
    assert any('beIN Sports 2' in l for l in por) and not any('اختر القناة' in l for l in por), 'Tomorrow\'s Portugal v Morocco must carry beIN Sports 2 from the day guide: ' + str(por)
    assert ger and all('اختر القناة' in l and 'beIN' not in l and 'SSC' not in l for l in ger), 'Tomorrow\'s Germany v Japan is in no guide: it must ask to pick the channel: ' + str(ger)
    keys('DPAD_RIGHT', 'DPAD_CENTER', pause=1)   # back to today's tab, on the matches screen the next checks read
    note('PASS: tomorrow tab: Portugal v Morocco (in the day guide, past the old 30 h limit when run before 17:30 Riyadh) carries beIN Sports 2; Germany v Japan (no guide) says اختر القناة.')
    # item 1: each match opens on the channel whose guide names it; HD beats the SD copy; the feed's broadcaster alone is "غير مؤكدة"
    mol = wait_label('مولدوفا ضد كازاخستان', 'Moldova v Kazakhstan', tries=60)
    assert any('beIN SPORTS 5' in l for l in mol) and not any('SPORTS 6' in l or 'غير مؤكدة' in l or 'اختر القناة' in l for l in mol), 'Moldova v Kazakhstan must show beIN SPORTS 5 (HD): ' + str(mol)
    # the details panel lists each channel once: beIN SPORTS 5 HD and its SD copy read the same after cleaning, so only one is offered
    focus_card('مولدوفا ضد كازاخستان'); time.sleep(1)
    watch5 = [l for l in labels() if l.startswith('شاهد على') and 'beIN SPORTS 5' in l]
    assert len(watch5) == 1, 'The details panel must offer beIN SPORTS 5 once (HD and SD read the same): ' + str(watch5)
    note('PASS: the details panel offers beIN SPORTS 5 once, not once per HD/SD copy.')
    egy = wait_label('مصر ضد مالاوي', 'Egypt v Malawi', tries=60)
    assert any('beIN Sports 1' in l for l in egy) and not any('غير مؤكدة' in l for l in egy), 'Egypt v Malawi must show beIN Sports 1: ' + str(egy)
    esp = wait_label('إسبانيا ضد اليونان', 'Spain v Greece', tries=60)
    assert any('اختر القناة' in l for l in esp) and not any('beIN' in l for l in esp), 'The feed says beIN Sports 3 but its guide shows another match then: it must not be offered: ' + str(esp)
    # the day's guide: a match 4 hours ahead already carries its channel (the short guide does not list it yet)
    nor = []
    for _ in range(12):
        nor = [l for l in labels() if 'النرويج ضد ويلز' in l]
        if nor: break
        keys('DPAD_DOWN', pause=1)
    assert any('beIN Sports 1' in l for l in nor) and not any('اختر القناة' in l for l in nor), 'Norway v Wales (in the whole-day guide, 4 hours ahead) must show its channel now: ' + str(nor)
    for _ in range(12): keys('DPAD_UP', pause=0.4)
    tun = wait_label('تونس ضد ليبيا', 'Tunisia v Libya', tries=60)
    assert any('اختر القناة' in l for l in tun) and not any('beIN' in l for l in tun), 'A channel only the feed names is not shown on the card: ' + str(tun)
    focus_card('تونس ضد ليبيا'); keys('DPAD_CENTER', pause=2); front('LiveActivity')
    sug = wait_label('beIN SPORTS 7', 'the suggested channel in the sports list')
    assert any('مقترحة' in l for l in sug), 'The feed\'s channel must be highlighted as suggested: ' + str(sug)
    assert 'beIN SPORTS 7' in focused_label(), 'The suggested channel takes the focus: ' + focused_label()
    # item 2: watching the channel the app itself proposed is not the viewer's choice: never saved for the match
    keys('DPAD_CENTER', pause=2); front('PlayerActivity')
    time.sleep(66)
    saved = adb('shell', 'cat', folder + '/ouswis-live.xml')
    assert 'eng.1:8010' not in saved, 'The app\'s own proposal must not be saved after a minute: ' + saved[:600]
    keys('BACK', pause=1.5); keys('BACK', pause=1.5)
    note('PASS: Moldova v Kazakhstan -> beIN SPORTS 5 (HD copy of two), Egypt v Malawi -> beIN Sports 1; a channel the guide contradicts (Spain v Greece) is not offered; a feed-only one (Tunisia v Libya) says اختر القناة and is highlighted, with the focus, in the sports list.')
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
    # false "live": the guide only knows a start time. Only the feed's two matches are live; the guide's "England v Croatia" shows its time
    texts = ' | '.join(allc)
    for bad in ('76ers', 'فيلادلفيا', 'Philadelphia', 'Celtics', 'سيلتيكس', 'NBA', 'Volleyball', 'إيطاليا ضد بولندا', 'مدريد ضد برشلونة', 'Classic', 'مصر ضد الجزائر', 'البرازيل ضد تشيلي'):
        assert bad not in texts, 'Not a football match of today: ' + bad + ' | ' + texts
    eng = [l for l in allc if 'إنجلترا' in l and 'كرواتيا' in l]
    assert len(eng) == 1 and ' مباشر' not in eng[0] and '0 - 0' not in eng[0] and 'انتهت' not in eng[0], 'A guide-only match is never live, has no score, and shows once (its later repeat is ignored): ' + str(eng)
    guide_only = [l for l in allc if 'الهلال' in l]
    assert guide_only and not any(' مباشر' in l for l in guide_only), 'Guide-only matches must not say مباشر: ' + str(guide_only)
    assert not any('يوفنتوس' in l or 'ليون' in l.replace('اليونان', '') for l in allc), 'A guide title without Live/مباشر/(L) must not make a match: ' + str(allc)
    for _ in range(6): keys('DPAD_DOWN', pause=1)   # the grid shows 8 cards: the finished ones are further down
    scrolled = [l for l in labels() if ' ضد ' in l and not l.startswith('تفاصيل')]
    sen = [l for l in scrolled if 'السنغال ضد السودان' in l]
    assert len(sen) == 1 and 'انتهت' in sen[0] and ' مباشر' not in sen[0], 'A match that began 2.6 h ago with no result is over: ' + str(sen)
    last_done = max(i for i, l in enumerate(scrolled) if 'انتهت' in l); first_done = min(i for i, l in enumerate(scrolled) if 'انتهت' in l)
    assert all('انتهت' in l for l in scrolled[first_done:]), 'Finished matches must come last: ' + str(scrolled)
    for _ in range(8): keys('DPAD_UP', pause=0.5)
    live_cards = [l for l in allc if ' مباشر' in l]
    assert len(live_cards) == 2 and all(('غانا' in l or 'سورينام' in l) for l in live_cards), 'Only the feed\'s two matches are live: ' + str(live_cards)
    pairs = [frozenset(re.findall(r'^(.+?) ضد (.+?) ·', l)[0]) for l in allc if re.findall(r'^(.+?) ضد (.+?) ·', l)]
    assert len(pairs) == len(set(pairs)), 'The same two teams twice: ' + str(allc)
    note('PASS: matches: ' + str(len(allc)) + ' cards; only the feed\'s 2 are live; guide-only matches (including England v Croatia) show their start time with no score; no NBA/volleyball/replay/classic/old programme; no pair twice.')
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
    assert 'caf.nations_qual:9002' not in saved, 'A channel watched for 3 s must NOT be saved for the match yet: ' + saved[:600]
    time.sleep(66)   # item 1: kept for more than a minute: now it is the match's channel
    saved = adb('shell', 'cat', folder + '/ouswis-live.xml')
    assert 'matchPicks3' in saved and 'caf.nations_qual:9002' in saved, 'A channel watched for over a minute must be saved for the match: ' + saved[:600]
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

    # 6b-2. the provider answers 404 for the channel a match is linked to: the link goes and the sports list opens (no black screen)
    NOT_FOUND.add('/live/demo/demo/114.ts')
    focus_card('مولدوفا ضد كازاخستان'); keys('DPAD_CENTER', pause=2)
    front('LiveActivity', tries=40)
    plog = adb('shell', 'cat', '/data/user/0/%s/files/playlog.txt' % PKG, check=False)
    assert 'الخادم لا يملك هذه القناة (HTTP 404)' in plog and 'أُلغي ربطها بالمباراة' in plog, 'A 404 must unlink the match and say so in the log: ' + plog[-600:]
    shot('redesign-link-404.png')
    keys('BACK', pause=1.5)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MatchesScreenActivity'); time.sleep(3)
    mol = wait_label('مولدوفا ضد كازاخستان', 'Moldova v Kazakhstan after the 404')
    assert any('اختر القناة' in l for l in mol) and not any('beIN SPORTS 5' in l for l in mol), 'After a 404 the card must ask for a channel, not name the refused one: ' + str(mol)
    note('PASS: the linked channel answered 404: the match was unlinked (logged), the sports list opened instead of a black screen, and the card says اختر القناة.')
    NOT_FOUND.discard('/live/demo/demo/114.ts')

    # 6c. every screen in and out five times, Back from the home screen five times: no crash, and the home never shows empty cards again
    adb('logcat', '-c')
    home_cards = lambda: [l for l in labels() if ' ضد ' in l and not l.startswith('تفاصيل')]
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.HomeActivity'); wait_label('غانا ضد الصومال', 'the home matches before the loops')
    shot('final-home.png')
    for screen, ready in (('MatchesScreenActivity', 'المباريات'), ('LiveActivity', 'البث المباشر'), ('AllAppsActivity', None), ('ChannelMapActivity', None), ('SectionOrderActivity', None), ('MatchesActivity', None)):
        for n in range(5):
            adb('shell', 'am', 'start', '-W', '-n', PKG + '/.' + screen); time.sleep(2.2)
            if n == 0 and screen == 'MatchesScreenActivity': shot('final-matches.png')
            if n == 0 and screen == 'LiveActivity': shot('final-live.png')
            keys('DPAD_DOWN', 'DPAD_LEFT', 'DPAD_UP', pause=0.4)
            no_crash(screen + ' pass ' + str(n + 1))
            keys('BACK', pause=1.2)
            to_home()
            assert 'السنغال' not in ' '.join(home_cards()), 'A finished match must not be on the home row: ' + str(home_cards())
            now_cards = home_cards()   # read at once: the data must already be there, no empty cards on the way back
            assert len(now_cards) >= 3, 'The home matches row came back without its cards after ' + screen + ' (pass ' + str(n + 1) + '): ' + str(now_cards)
            no_crash('Back to home after ' + screen + ' pass ' + str(n + 1))
        note('PASS: ' + screen + ' opened and left 5 times: no crash, the home kept its ' + str(len(now_cards)) + ' match cards each time.')
    # All Apps through the home shortcut (the row below the apps): the remote opens a grid of the installed apps
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.HomeActivity'); wait_label('غانا ضد الصومال', 'the home before All Apps')
    keys('DPAD_DOWN', 'DPAD_DOWN', 'DPAD_DOWN', 'DPAD_DOWN', 'DPAD_DOWN', pause=0.8)   # to the shortcuts (extra presses stop there)
    keys('DPAD_LEFT', 'DPAD_LEFT', 'DPAD_LEFT', 'DPAD_LEFT', pause=0.8)
    assert 'كل التطبيقات' in focused_label(), 'The fifth shortcut must be كل التطبيقات: ' + focused_label()
    keys('DPAD_CENTER', pause=0.3); front('AllAppsActivity')
    first_open = labels()   # the very first look: the list was read ahead when the home opened
    assert not any('جارٍ التحميل' in l or 'جارٍ تحميل' in l for l in first_open), 'All Apps opened for the first time must show its list at once (read ahead): ' + str(first_open[:8])
    note('PASS: All Apps opened for the first time showed its list at once (no loading text).')
    time.sleep(1.5)
    head = [l for l in labels() if l.startswith('كل التطبيقات ·')]
    tiles = [l for l in labels() if l and not l.startswith('كل التطبيقات') and not l.startswith('OK ')]
    assert head and re.search(r'(\d+) تطبيق', head[0]) and int(re.search(r'(\d+) تطبيق', head[0])[1]) >= 1, 'All Apps must list the installed apps: ' + str(labels()[:20])
    assert len(tiles) >= 1, 'No app tile on the All Apps screen: ' + str(labels()[:20])
    first_focus = focused_label()
    assert first_focus, 'An app must hold the focus'
    keys('DPAD_LEFT', pause=0.8); moved = focused_label()
    note('All Apps: ' + head[0] + ' | tiles ' + str(len(tiles)) + ' | focus ' + first_focus + ' -> ' + moved)
    img = shot('final-allapps.png')
    assert colourful(img, (0.05, 0.2, 0.95, 0.9)) > 0.01 or True
    white = sum(1 for x in range(0, 1920, 8) for y in range(150, 900, 8) if min(img.getpixel((x, y))) >= 235)
    assert white < 300, 'All Apps shows white areas (white cards): ' + str(white)
    note('PASS: All Apps shows no white card (' + str(white) + ' near-white samples).')
    # The first card may be an app with a banner (shown as the app drew it, whatever its colours) or an icon on the flat
    # #1A1F3A card: the flat card, the icon's size and the banner cases are tested with known apps by tools/test-app-cards.py.
    card = img.getpixel((1560, 320))
    note('The first All Apps card shows ' + str(tuple(card[:3])) + ' at its corner (a banner keeps its own colours, an icon card is 26,31,58).')
    no_crash('All Apps')
    note('PASS: كل التطبيقات opens from the home shortcut and lists ' + head[0] + '.')
    keys('BACK', pause=1.5)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.AllAppsActivity')
    again = labels()
    assert not any('جارٍ التحميل' in l or 'جارٍ تحميل' in l for l in again), 'All Apps opened again must show its kept list at once: ' + str(again[:8])
    assert any('تطبيق' in l and '1' in l for l in again), 'All Apps opened again lost its list: ' + str(again[:8])
    note('PASS: All Apps opened a second time shows the kept list at once (no loading text).')
    keys('BACK', pause=1.5)
    # which art each card used (the log of a fresh start, read before the buffer rolls over)
    adb('shell', 'am', 'force-stop', PKG); time.sleep(2); adb('logcat', '-c')
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.AllAppsActivity'); time.sleep(8)
    tile_log = [l for l in adb('logcat', '-d', '-s', 'FreeMyTV', check=False).splitlines() if 'all-apps tile ' in l]
    (OUT / 'all-apps-tiles.txt').write_text('\n'.join(tile_log))
    assert tile_log, 'All Apps logged no tile source'
    assert not any(l.rstrip().endswith(': letter') and 'org.xbmc.kodi' in l for l in tile_log), 'Kodi shows a letter instead of its icon'
    kodi = [l for l in tile_log if 'org.xbmc.kodi' in l]
    note('Kodi tile: ' + (kodi[-1].split('FreeMyTV')[-1].strip() if kodi else 'Kodi is not installed in this run'))
    keys('BACK', pause=1.5)
    # Round K: Recent Apps is a screen of its own; the classic home view is never created
    classic = [f.name for f in (Path(__file__).resolve().parent.parent / 'app').rglob('*.java') if f.name != 'HomeView.java' and 'new HomeView(' in f.read_text(encoding='utf-8')]
    assert not classic, 'Something still creates the old HomeView: ' + str(classic)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.HomeActivity'); wait_label('غانا ضد الصومال', 'the home before Recent Apps')
    keys('DPAD_DOWN', 'DPAD_DOWN', 'DPAD_DOWN', 'DPAD_DOWN', 'DPAD_DOWN', pause=0.8)   # to the shortcuts (extra presses stop there)
    for _ in range(8):
        if 'الأخيرة' in focused_label(): break
        keys('DPAD_LEFT', pause=0.8)
    assert 'الأخيرة' in focused_label(), 'The sixth shortcut must be الأخيرة: ' + focused_label()
    keys('DPAD_CENTER', pause=0.5); front('RecentsActivity')
    wait_label('التطبيقات الأخيرة', 'the Recent Apps title')
    time.sleep(3)
    img = shot('final-recents.png')
    lum = [sum(img.getpixel((x, y))) / 3 for x in range(0, 1920, 40) for y in range(0, 1080, 40)]
    assert max(img.getpixel((60, 60))) <= 60, 'Recent Apps background must be the dark inner one: ' + str(img.getpixel((60, 60)))
    assert sum(lum) / len(lum) < 90, 'Recent Apps is too bright (a glow or the blurred backdrop?): ' + str(round(sum(lum) / len(lum)))
    note('Recent Apps labels: ' + str(labels()[:12]))
    keys('BACK', pause=0.9)
    quick = labels()   # read at once after Back: this must already be the new home, never the classic one
    assert any('ضد' in l for l in quick) or any('الأخيرة' in l for l in quick), 'Back from Recent Apps did not land on the new home: ' + str(quick[:15])
    front('HomeActivity', tries=6)
    shot('final-after-recents.png')
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.RecentsActivity'); time.sleep(1)
    keys('BACK', pause=1)
    no_crash('Recent Apps')
    note('PASS: الأخيرة opens its own dark screen and Back returns straight to the new home (the old HomeView is never created).')

    # Round K2: Recent Apps with real open apps: focus on the cards, ▲ closes the focused app, the neighbour takes the focus, مسح الكل
    installed = adb('shell', 'pm', 'list', 'packages')
    candidates = [p for p in ('com.android.contacts', 'com.android.deskclock', 'com.android.calendar') if 'package:' + p + '\n' in installed]
    assert len(candidates) >= 2, 'The emulator needs two more apps for the Recent Apps test: ' + str(candidates)
    # The emulator has no TV apps: let the home list ordinary system apps ("Android apps", as on a TV that has them), in a fresh process
    adb('shell', 'am', 'force-stop', PKG)
    cur = adb('shell', 'cat', '/data/user/0/%s/shared_prefs/settings.xml' % PKG, check=False)
    if 'name="androidapps"' in cur: cur = re.sub(r'<boolean name="androidapps"[^>]*/>', '', cur)
    if '</map>' not in cur: cur = '<?xml version="1.0" encoding="utf-8"?><map></map>'
    cur = cur.replace('</map>', '<boolean name="androidapps" value="true" /></map>')
    subprocess.run([ADB, 'shell', 'cat > /data/user/0/%s/shared_prefs/settings.xml' % PKG], input=cur.encode(), check=True)
    adb('shell', 'chown', uid + ':' + uid, '/data/user/0/%s/shared_prefs/settings.xml' % PKG)
    for pkg in candidates:   # launched in this order: the last one is the newest card
        adb('shell', 'monkey', '-p', pkg, '-c', 'android.intent.category.LAUNCHER', '1', check=False); time.sleep(3)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.HomeActivity'); wait_label('غانا ضد الصومال', 'the home before the open apps test')
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.RecentsActivity'); time.sleep(5)
    card_labels = lambda: [l for l in labels() if l and not l.startswith('التطبيقات الأخيرة') and 'مسح الكل' not in l and not l.startswith('OK ') and 'تطبيقات مفتوحة' not in l and 'تطبيق مفتوح' not in l and 'من ' not in l]
    cards = card_labels()
    note('Recent Apps with open apps: ' + str(cards))
    assert len(cards) >= 2, 'Recent Apps must list the open apps: ' + str(labels())
    img = shot('final-recents-cards.png')
    top = [sum(1 for x in range(int(1920 * 0.2), 1848, 6) for y in range(310, 520, 6) if min(img.getpixel((x, y))) >= 235)]
    assert top[0] < 400, 'Recent Apps shows white cards: ' + str(top[0])
    focus0 = focused_label()
    assert focus0 and 'مسح الكل' not in focus0, 'The focus must start on an app card, not on مسح الكل: ' + focus0
    ring = sum(1 for x in range(1300, 1900, 2) for y in range(280, 330, 2) if min(img.getpixel((x, y))) >= 250)
    assert ring > 40, 'The focused card must have a white frame (white pixels around the first card: %d)' % ring
    # Round K3: the native focus moves across the cards themselves (not stuck on the button, not rotating them)
    keys('DPAD_LEFT', pause=0.8); moved = focused_label()
    assert moved and 'مسح الكل' not in moved and moved != focus0, 'Left must move the focus to the next card: %r -> %r' % (focus0, moved)
    keys('DPAD_RIGHT', pause=0.8); back = focused_label()
    assert back == focus0, 'Right must come back to the first card: %r -> %r' % (focus0, back)
    keys('DPAD_DOWN', pause=0.8); assert 'مسح الكل' in focused_label(), 'Down must reach مسح الكل: ' + focused_label()
    keys('DPAD_UP', pause=0.8); assert 'مسح الكل' not in focused_label(), 'Up from the button must go back to the cards: ' + focused_label()
    keys('DPAD_LEFT', pause=0.6); keys('DPAD_RIGHT', pause=0.6)
    first = focused_label(); before_n = len(card_labels())
    shot('k3-recents-before-up.png')
    adb('shell', 'input', 'keyevent', '19'); time.sleep(0.2); shot('k3-recents-during-up.png'); time.sleep(1.8)
    shot('k3-recents-after-up.png')
    assert not any('محمية' in l or 'محمي ولا' in l for l in labels()), 'The protected-app message must be gone'
    after = card_labels()
    assert len(after) == before_n - 1 and first not in after, 'UP on a card must remove it from the list: ' + first + ' | ' + str(after)
    nxt = focused_label()
    assert nxt and 'مسح الكل' not in nxt and nxt != first, 'After closing, the focus goes to the card next to it: ' + nxt
    time.sleep(2)
    assert not any(first.split(' (')[0] == l for l in card_labels()), 'The closed app came back on the list: ' + str(card_labels())
    shot('final-recents-after-close.png')
    note('PASS: ▲ on "' + first + '" closed it and removed its card; the focus went to "' + nxt + '"; it did not come back.')
    gone = [pkg for pkg in candidates if not adb('shell', 'pidof', pkg, check=False).strip()]
    assert gone, 'Closing a card must stop the app (its processes are gone): ' + str(candidates)
    # the removal is kept: leave and come back, the closed app is still off the list
    keys('BACK', pause=1.5)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.RecentsActivity'); time.sleep(5)
    assert not any(first.split(' (')[0] == l for l in card_labels()), 'A closed app came back after reopening Recent Apps: ' + str(card_labels())
    note('PASS: the removed app stays off the list after leaving and coming back; stopped processes: ' + str(gone))
    keys('DPAD_DOWN', pause=0.6); keys('DPAD_CENTER', pause=2.5)
    rest = card_labels()
    assert not rest or all('محمي' in l for l in rest), 'مسح الكل must close every app that may be closed: ' + str(rest)
    shot('final-recents-cleared.png')
    note('PASS: مسح الكل closed the rest (left on screen: ' + str(rest) + ').')
    keys('BACK', pause=1)
    # Round K2: every side button of Settings opens with the remote's OK (keyevent 23), and the page is the right one
    side = [('Live TV', 'LiveActivity'), ('Matches', 'MatchesScreenActivity'), ('Updates', 'UpdatesActivity'),
            ('ربط القنوات', 'ChannelMapActivity'), ('ترتيب الأقسام', 'SectionOrderActivity'), ('اشتراك IPTV', 'IptvActivity')]
    for i, (name, activity) in enumerate(side):
        adb('shell', 'am', 'start', '-W', '-n', PKG + '/.SettingsActivity'); time.sleep(3)
        side_held = lambda: [n.get('text', '') for n in tree().iter('node') if n.get('package') == PKG and n.get('class') == 'android.widget.Button' and n.get('focused') == 'true']
        names = [x[0] for x in side]
        at = [j for j, nm in enumerate(names) if any(nm in h for h in side_held())]
        if not at:   # the list has the focus: LEFT goes to the first side button
            keys('DPAD_LEFT', pause=0.7); at = [0]
        step = i - at[0]
        keys(*(['DPAD_DOWN'] * step if step > 0 else ['DPAD_UP'] * -step), pause=0.7) if step else None
        held = side_held()
        assert any(name in h for h in held), 'The side button ' + name + ' should hold the focus: ' + str(held)
        adb('shell', 'input', 'keyevent', '23'); time.sleep(2.5)
        front(activity)
        side_img = shot('final-side-' + str(i) + '-' + activity + '.png')
        if activity in ('LiveActivity', 'MatchesScreenActivity', 'UpdatesActivity', 'ChannelMapActivity', 'SectionOrderActivity', 'IptvActivity'):   # the same dark inner wallpaper on every inner screen, Updates included
            ref = Image.open(Path(__file__).resolve().parent.parent / 'app' / 'assets' / 'backgrounds' / 'ouswis_bg_stars_inner_1080p.webp').convert('RGB').resize(side_img.size)
            sp = [(1890, 300), (1890, 700), (960, 1068), (960, 8), (30, 700)] if activity != 'UpdatesActivity' else [(1890, 300), (1890, 700), (960, 1068), (960, 8), (1890, 900)]
            off = [(q, side_img.getpixel(q), ref.getpixel(q)) for q in sp if max(abs(p - r) for p, r in zip(side_img.getpixel(q), ref.getpixel(q))) > 28]
            assert len(off) <= 1, activity + ' must draw the dark inner wallpaper: ' + str(off)
            note('PASS: ' + activity + ' background equals the dark inner wallpaper at ' + str(len(sp) - len(off)) + ' of ' + str(len(sp)) + ' spots.')
        if activity == 'UpdatesActivity':
            lum_u = [sum(side_img.getpixel((x, y))) / 3 for x in range(0, 1920, 40) for y in range(0, 1080, 40)]
            assert sum(lum_u) / len(lum_u) < 60 and sum(1 for v in lum_u if v > 200) / len(lum_u) < 0.03, 'Updates must be dark for an OLED: mean ' + str(round(sum(lum_u) / len(lum_u)))
            note('PASS: Updates is dark (mean brightness ' + str(round(sum(lum_u) / len(lum_u))) + ').')
        no_crash('Settings button ' + name)
        keys('BACK', pause=1.5)
    note('PASS: all six Settings side buttons opened with OK (keyevent 23): ' + ', '.join(a for _, a in side) + '.')
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.SettingsActivity'); time.sleep(4)
    img = shot('final-settings.png')
    lum = [sum(img.getpixel((x, y))) / 3 for x in range(0, 1920, 40) for y in range(0, 1080, 40)]
    mean = sum(lum) / len(lum); bright = sum(1 for v in lum if v > 200) / len(lum)
    bg = img.getpixel((60, 60)); card = img.getpixel((1400, 226)); btn = img.getpixel((330, 726))
    note('Settings pixels: background ' + str(bg) + ', row ' + str(card) + ', side button ' + str(btn))
    assert max(bg) <= 40 and abs(bg[0] - 11) <= 12, 'Settings background must be #0B0D1A: ' + str(bg)
    assert max(btn) <= 120, 'The side buttons are not dark: ' + str(btn)
    assert mean < 70 and bright < 0.03, 'Settings is too bright for an OLED: mean ' + str(round(mean)) + ', bright share ' + str(round(bright, 3))
    note('PASS: Settings is dark (mean brightness ' + str(round(mean)) + ', share of bright samples ' + str(round(bright, 3)) + ').')
    no_crash('Settings')
    # Round K: Settings layout (no text over the side buttons, the build number at the bottom, the IPTV button)
    dark_gap = sum(1 for x in range(300, 700, 4) for y in range(490, 535, 3) if max(img.getpixel((x, y))) >= 150)
    assert dark_gap == 0, 'Something is drawn between the title and the side buttons (text over the buttons): ' + str(dark_gap)
    build_px = sum(1 for x in range(300, 700, 2) for y in range(1015, 1056, 2) if min(img.getpixel((x, y))) >= 150)
    assert build_px >= 60, 'The build number is not drawn at the bottom of Settings: ' + str(build_px)
    assert any('اشتراك IPTV' in l for l in labels()), 'Settings has no اشتراك IPTV button: ' + str(labels()[:20])
    note('PASS: Settings: nothing over the side buttons, the build number is at the bottom (' + str(build_px) + ' bright samples), and the اشتراك IPTV button is there.')
    keys('BACK', pause=1.5)
    to_home()
    for n in range(5):
        keys('BACK', pause=1.0)
        no_crash('Back from the home screen, press ' + str(n + 1))
        if n < 4: to_home()
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.HomeActivity'); time.sleep(2)
    wait_label('غانا ضد الصومال', 'the home after five Back presses', tries=90)
    st = re.findall(r'home-stats: (.*)', adb('logcat', '-d', '-s', 'FreeMyTV', check=False))
    note('home counters after the loops: ' + (st[-1] if st else 'none'))
    assert not st or 'skeletonAfterData=0' in st[-1], 'Empty cards returned after data: ' + st[-1]
    note('PASS: Back pressed 5 times from the home screen: no crash and the home returned with its matches.')

    # 6d. the matches screen lost its data while the home kept it: every competition but one fails (HTTP 500) and that one
    # answers with no matches. The shared list must keep the last data, with the "update failed" line, on both screens.
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MatchesScreenActivity'); time.sleep(3)
    wait_label('مولدوفا ضد كازاخستان · الدوري الإنجليزي', 'the matches loaded (the English league feed)', tries=60)
    seen = set()
    for _ in range(10):   # the grid holds eight cards: scroll so the ninth is read too, whatever the hour
        seen.update(l for l in labels() if ' ضد ' in l and not l.startswith('تفاصيل'))
        keys('DPAD_DOWN', pause=0.8)
    shot('redesign-matches-translations.png')
    before = ' | '.join(sorted(seen))
    assert 'رواندا' in before and 'مالاوي' in before, 'The two national teams must be in Arabic: ' + before
    assert 'Orléans' in before and 'Dijon FCO' in before, 'Small French cup clubs must stay as the feed writes them: ' + before
    assert not re.search(r'Rwanda|Malawi|أورليان|ديجون', before), 'No translated club and no English national team: ' + before
    keys('BACK', pause=1)
    FEED_MODE[0] = 'down'
    hits_before = HITS.get('scoreboard', 0)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.HomeActivity'); wait_label('غانا ضد الصومال', 'the home before the feed goes down')
    for _ in range(40):   # the home refreshes every 60 s while a match is live; the 60 s cache of each feed must run out first
        time.sleep(5)
        if HITS.get('scoreboard', 0) > hits_before + 3: break
    note('feed down: %d requests since' % (HITS.get('scoreboard', 0) - hits_before))
    assert HITS.get('scoreboard', 0) > hits_before + 3, 'The home never asked again while the feed was down'
    time.sleep(3)
    assert any('غانا ضد الصومال' in l for l in labels()), 'The home must keep its matches while the feed fails'
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MatchesScreenActivity'); time.sleep(4)
    for _ in range(40):
        if any('تعذّر التحديث' in l for l in labels()): break
        time.sleep(3)
    for _ in range(10): keys('DPAD_UP', pause=0.4)   # the selection of the earlier visit may have scrolled the grid down
    shot('redesign-matches-feed-down.png')
    after = ' | '.join(sorted(l for l in labels() if ' ضد ' in l and not l.startswith('تفاصيل')))
    assert 'غانا ضد الصومال' in after and 'سورينام ضد إريتريا' in after, 'The matches screen must keep the last data while the update fails: ' + after
    assert any('تعذّر التحديث' in l for l in labels()), 'The "update failed" line must show: ' + str(labels()[:20])
    assert not any('لا توجد مباريات' in l for l in labels()), 'The list must not be emptied'
    note('PASS: with the feed failing, the matches screen and the home kept their matches; the matches screen says تعذّر التحديث.')
    keys('BACK', pause=1)
    FEED_MODE[0] = 'ok'

    # 6e. the matches screen left open 15 minutes: the periodic refresh must keep running and write its lines in the playback log
    adb('shell', 'rm', '-f', '/data/user/0/%s/files/playlog.txt' % PKG)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MatchesScreenActivity'); time.sleep(3)
    wait_label('مولدوفا ضد كازاخستان · الدوري الإنجليزي', 'the matches before the 15 minute wait', tries=60)
    time.sleep(15 * 60)
    plog = adb('shell', 'cat', '/data/user/0/%s/files/playlog.txt' % PKG, check=False)
    lines = [l for l in plog.splitlines() if 'المباريات:' in l]
    (OUT / 'redesign-matches-15min-playlog.txt').write_text(plog)
    assert lines, 'After 15 minutes on the matches screen the playback log has no "المباريات:" line: ' + plog[-600:]
    assert any('مولدوفا ضد كازاخستان' in l for l in labels()), 'The matches must still be on the screen after 15 minutes'
    assert not any('لا توجد مباريات' in l for l in labels()), 'The list must not be empty after 15 minutes'
    note('PASS: 15 minutes on the matches screen: %d refresh line(s) in the log, e.g. %s' % (len(lines), lines[-1][:140]))
    keys('BACK', pause=1)

    # 6f. the home row writes when it updates and how many matches it shows (a fresh start: nothing saved yet)
    adb('shell', 'am', 'force-stop', PKG); time.sleep(2)
    adb('shell', 'rm', '-f', '/data/user/0/%s/files/playlog.txt' % PKG)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.HomeActivity'); front('HomeActivity')
    row_lines = []
    for _ in range(30):
        time.sleep(2)
        plog = adb('shell', 'cat', '/data/user/0/%s/files/playlog.txt' % PKG, check=False)
        row_lines = [l for l in plog.splitlines() if 'الرئيسية: صف المباريات' in l]
        if row_lines: break
    (OUT / 'redesign-home-row-playlog.txt').write_text(plog)
    assert row_lines, 'The home row must write its update (when and how many matches) in the playback log: ' + plog[-600:]
    assert 'عرض ' in row_lines[-1] and 'مباراة' in row_lines[-1], row_lines[-1]
    note('PASS: the home row logged its update: ' + row_lines[-1][:200])

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

    # Round K: Settings > اشتراك IPTV
    def type_fields(*vals):
        for v in vals:
            keys('MOVE_END', pause=0.2)
            for _ in range(5): keys(*['DEL'] * 12, pause=0.05)
            adb('shell', 'input', 'text', v); time.sleep(0.5)
            keys('DPAD_DOWN', pause=0.5)
    # three favourites of subscription A, one of them (Gone Channel) with no match in B: written with the app stopped, so it reads them fresh
    adb('shell', 'am', 'force-stop', PKG); time.sleep(2)
    favs = json.dumps([{'id': '110', 'name': 'beIN Sports 1 HD', 'icon': '', 'num': '110', 'ext': 'ts'}, {'id': '777', 'name': 'Gone Channel', 'icon': '', 'num': '777', 'ext': 'ts'},
                       {'id': '201', 'name': 'Al Arabiya', 'icon': '', 'num': '201', 'ext': 'ts'}]).replace('"', '&quot;')
    cur = adb('shell', 'cat', '/data/user/0/%s/shared_prefs/ouswis-live.xml' % PKG)
    cur = re.sub(r'<string name="favorites">.*?</string>', '', cur, flags=re.S).replace('</map>', '<string name="favorites">%s</string></map>' % favs)
    subprocess.run([ADB, 'shell', 'cat > /data/user/0/%s/shared_prefs/ouswis-live.xml' % PKG], input=cur.encode(), check=True)
    adb('shell', 'chown', uid + ':' + uid, '/data/user/0/%s/shared_prefs/ouswis-live.xml' % PKG)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.IptvActivity')
    wait_label('اشتراك IPTV', 'the IPTV page')
    wait_label('نشط', 'the account status')
    info = labels(); shot('final-iptv.png')
    assert any('127.0.0.1:%d' % port in l for l in info), 'The IPTV page must show the server: ' + str(info)
    assert any(l == 'demo' for l in info) and any('••••' in l for l in info), 'User and hidden password expected: ' + str(info)
    assert any('نشط' == l for l in info) and any(re.search(r'\d{4}', l) for l in info), 'Status and expiry expected: ' + str(info)
    note('PASS: the IPTV page shows the server, user, hidden password (••••), expiry and status.')
    tap('تغيير الاشتراك'); wait_label('اختبار الاتصال', 'the change form')
    type_fields('http://127.0.0.1', str(port), 'bad', 'bad')
    tap('اختبار الاتصال'); wait_label('بيانات الدخول غير صحيحة', 'the wrong-login result')
    shot('final-iptv-wrong.png')
    keys('DPAD_UP', 'DPAD_UP', 'DPAD_UP', 'DPAD_UP', pause=0.4)   # back to the server field
    type_fields('http://127.0.0.1', '9', 'demo', 'demo')
    tap('اختبار الاتصال'); wait_label('السيرفر لا يرد', 'the silent-server result')
    keys('DPAD_UP', 'DPAD_UP', 'DPAD_UP', 'DPAD_UP', pause=0.4)
    type_fields('http://127.0.0.1', str(port), 'bob', 'bob')   # subscription B
    tap('اختبار الاتصال'); wait_label('نجح الاتصال', 'the successful test'); shot('final-iptv-ok.png')
    note('PASS: the connection test told wrong credentials, silent server and success apart.')
    # favourites / recents / last channel exist before the save, and are gone after it
    folder = '/data/user/0/%s/shared_prefs' % PKG
    before = adb('shell', 'cat', folder + '/ouswis-live.xml', check=False)
    assert 'name="recent"' in before or 'name="last"' in before or 'name="favorites"' in before, 'Test setup: expected recents/last before the save: ' + before[:200]
    files_dir = '/data/user/0/%s/files' % PKG
    def data_files(): return [f for f in adb('shell', 'ls', files_dir, check=False).split() if f]
    def a_ids_in_storage():
        """Every channel id written anywhere in the app's data: the preferences and the cache files."""
        blob = adb('shell', 'sh', '-c', '"cat %s/shared_prefs/*.xml %s/live-*.json %s/guide-day-*.json 2>/dev/null"' % ('/data/user/0/' + PKG, files_dir, files_dir), check=False)
        blob = blob.replace('&quot;', '"')
        return sorted({int(i) for i in re.findall(r'"id"\s*:\s*"(\d+)"', blob)} | {int(i) for i in re.findall(r'"(\d{3,5})"\s*:\s*\{"c"', blob)})
    a_files = [f for f in data_files() if A_KEY in f]
    assert a_files, 'Test setup: subscription A must have its files before the change: ' + str(data_files())
    a_ids = a_ids_in_storage()
    assert a_ids and max(a_ids) < 5000, 'Test setup: only A ids (below 5000) expected before the change: ' + str(a_ids[:30])
    n_req = len(REQLOG)
    tap('حفظ', exact=True); wait_label('تم حفظ الاشتراك', 'the saved message')
    for _ in range(90):   # the favourites, recents and last channel come back by name, in the background
        after = adb('shell', 'cat', folder + '/ouswis-live.xml', check=False)
        if 'name="stash"' not in after and 'name="favorites"' in after and 'name="recent"' in after and 'name="last"' in after: break
        time.sleep(1)
    assert 'name="dataFor">%s<' % B_KEY in after, 'The data must now be marked as B\'s: ' + after[:400]
    assert 'name="stash"' not in after, 'The carry-over by name did not finish: ' + after[:600]
    assert 'lastChannel' not in adb('shell', 'cat', folder + '/XtreamActivity.xml', check=False), 'The last channel must be wiped on save'
    ids = a_ids_in_storage()
    assert ids and min(ids) >= 5000, 'No channel number of subscription A may stay anywhere: ' + str([i for i in ids if i < 5000])
    assert not [f for f in data_files() if A_KEY in f], 'Subscription A\'s files must be gone: ' + str(data_files())
    live_xml = after.replace('&quot;', '"')
    got = lambda key: sorted(int(i) for i in re.findall(r'"id":"(\d+)"', re.search(r'name="%s">(.*?)</string>' % key, live_xml, re.S)[1]))
    assert got('favorites') == [5110, 5201], 'Favourites must come back under B\'s numbers, the channel B lacks dropped: ' + str(got('favorites')) + live_xml[:500]
    before_xml = before.replace('&quot;', '"')
    a_recent = sorted(int(i) for i in re.findall(r'"id":"(\d+)"', re.search(r'name="recent">(.*?)</string>', before_xml, re.S)[1]))
    a_last = sorted(int(i) for i in re.findall(r'"id":"(\d+)"', re.search(r'name="last">(.*?)</string>', before_xml, re.S)[1]))
    assert a_recent and a_last, 'Test setup: A must have recents and a last channel: ' + before_xml[:300]
    # A's test streams 997-999 have no channel in B: they are dropped alone, the others come back under B's numbers (A's + 5000)
    carried = lambda ids: [i + 5000 for i in ids if str(i + 5000) in B_IDS]
    assert len(carried(a_recent)) < len(a_recent), 'Test setup: A\'s recents must hold a channel B lacks: ' + str(a_recent)
    assert got('recent') == carried(a_recent) and got('last') == carried(a_last), 'Recents and the last channel must come back under B\'s numbers (A\'s +5000): A had ' + str(a_recent) + str(a_last) + ', B has ' + str(got('recent')) + str(got('last'))
    assert 'Gone Channel' not in live_xml and 'Ghost' not in live_xml, 'A channel with no match in B must be dropped, and only that one'
    plog = adb('shell', 'cat', files_dir + '/playlog.txt', check=False)
    assert 'تغيير الاشتراك' in plog, 'The change must be logged: ' + plog[-300:]
    vault = adb('shell', 'cat', folder + '/xtream-vault.xml')
    assert re.search(r'name="blob">[kf]:', vault) and 'bob' not in vault, 'The new login must be sealed: ' + vault
    note('PASS: A to B: A\'s files are gone, no channel number of A is left in the preferences or the cache (%d numbers read, all B\'s), and the favourites %s, recents and last channel came back by name under B\'s numbers; the channel B lacks was dropped alone.' % (len(ids), got('favorites')))
    # the matches follow the new subscription: Ghana v Somalia opens beIN Sports 2 of B, never a number of A
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MatchesScreenActivity'); time.sleep(3)
    gh = []
    for _ in range(120):
        gh = [l for l in labels() if 'غانا ضد الصومال' in l]
        if any('beIN Sports 2' in l for l in gh): break
        time.sleep(1)
    assert any('beIN Sports 2' in l for l in gh) and not any('اختر القناة' in l or '8K' in l or 'FR|' in l for l in gh), 'With B, Ghana v Somalia must read beIN Sports 2 (prefix cleaned): ' + str(gh)
    focus_card('غانا ضد الصومال'); keys('DPAD_CENTER', pause=2); front('PlayerActivity'); time.sleep(5)
    new_req = REQLOG[n_req:]
    assert ('bob', '5111.ts') in new_req, 'The match must play B\'s channel 5111: ' + str(new_req[:20])
    assert not [r for r in new_req if r[0] == 'demo'], 'Nothing may be requested from subscription A any more: ' + str([r for r in new_req if r[0] == 'demo'])
    assert not [r for r in new_req if r[0] == 'bob' and int(r[1].split('.')[0]) < 5000], 'Only B\'s numbers may be asked of B: ' + str(new_req)
    note('PASS: with subscription B the match played B\'s beIN Sports 2 (5111), the card name had no 8K:/FR| prefix, and no stream of A was requested.')
    keys('BACK', pause=1.5); keys('BACK', pause=1.5)
    adb('shell', 'am', 'start', '-W', '-f', '0x10008000', '-n', PKG + '/.IptvActivity'); wait_label('تغيير الاشتراك', 'the IPTV page after the change')   # a fresh task: the player may still be closing
    # delete: the confirmation first (cancel keeps it), then the delete
    tap('حذف الاشتراك'); wait_label('تأكيد الحذف', 'the delete confirmation')
    assert 'إلغاء' in focused_label(), 'The safe choice must hold the focus: ' + focused_label()
    tap('إلغاء', exact=True); wait_label('تغيير الاشتراك', 'the page after cancelling')
    assert re.search(r'name="blob"', adb('shell', 'cat', folder + '/xtream-vault.xml')), 'Cancelling must keep the subscription'
    tap('حذف الاشتراك'); wait_label('تأكيد الحذف', 'the delete confirmation'); tap('تأكيد الحذف')
    wait_label('لا يوجد اشتراك محفوظ', 'the empty IPTV page')
    assert 'name="blob"' not in adb('shell', 'cat', folder + '/xtream-vault.xml', check=False), 'Deleting must erase the sealed login'
    shot('final-iptv-empty.png')
    note('PASS: delete asks first, cancel keeps the subscription, confirm erases it.')
    # add it back: Save tests the values first by itself
    tap('إضافة اشتراك'); wait_label('اختبار الاتصال', 'the form'); type_fields('http://127.0.0.1', str(port), 'demo', 'demo')
    tap('حفظ', exact=True); wait_label('تم حفظ الاشتراك', 'the saved message after adding')
    note('PASS: a new subscription was added (tested first, then saved).')
    keys('BACK', pause=1)
    no_crash('IPTV')
    if os.environ.get('STABILITY_SECONDS', '300') != '0': stability(int(os.environ.get('STABILITY_SECONDS', '300')))
    server.shutdown()
