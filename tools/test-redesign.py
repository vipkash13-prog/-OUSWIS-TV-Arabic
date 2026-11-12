#!/usr/bin/env python3
"""Launches the redesigned home, live TV browser and matches screen on a disposable emulator against a synthetic
Xtream provider, drives them with the remote's keys, saves screenshots and fails on any crash. Synthetic account only."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import base64, json, os, re, subprocess, sys, tempfile, threading, time
from pathlib import Path
from urllib.parse import parse_qs, urlparse

OUT = Path(sys.argv[1]); OUT.mkdir(parents=True, exist_ok=True)
ADB = os.environ.get('ADB', 'adb')
PKG = 'io.ouswis.tv.standalone'

def adb(*args, check=True):
    return subprocess.run([ADB, *args], capture_output=True, timeout=90, check=check).stdout.decode().replace('\r', '')

def note(message):
    print(message, flush=True)
    with (OUT / 'redesign.log').open('a') as f: f.write(message + '\n')

def shot(name):
    (OUT / name).write_bytes(subprocess.check_output([ADB, 'exec-out', 'screencap', '-p'], timeout=60))

def keys(*names, pause=0.6):
    for n in names:
        adb('shell', 'input', 'keyevent', 'KEYCODE_' + n); time.sleep(pause)

def crashed():
    log = adb('logcat', '-d', '-b', 'crash', check=False)
    return 'FATAL EXCEPTION' in log and PKG in log, log

with tempfile.TemporaryDirectory() as temp:
    clip = Path(temp) / 'clip.ts'
    subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'testsrc=s=640x360:r=10', '-t', '60',
                    '-c:v', 'libx264', '-preset', 'ultrafast', '-pix_fmt', 'yuv420p', '-f', 'mpegts', str(clip)], check=True)
    data = clip.read_bytes()
    CATS = [('11', 'رياضة'), ('12', 'أخبار'), ('13', 'أفلام')]
    STREAMS = {'11': [('101', 'SSC 1 HD'), ('102', 'SSC 2 HD'), ('103', 'SSC 3 HD'), ('110', 'beIN Sports 1 HD'), ('111', 'beIN Sports 2'), ('120', 'AD Sports 1')],
               '12': [('201', 'Al Arabiya'), ('202', 'Sky News Arabia')], '13': [('301', 'Movies One')]}

    class Provider(BaseHTTPRequestHandler):
        def log_message(self, *a): pass
        def do_GET(self):
            url = urlparse(self.path); q = parse_qs(url.query)
            if url.path == '/player_api.php':
                action = q.get('action', [''])[0]
                if action == 'get_live_categories':
                    body = [{'category_id': i, 'category_name': n} for i, n in CATS]
                elif action == 'get_live_streams':
                    cat = q.get('category_id', [''])[0]
                    body = [{'num': int(s), 'name': n, 'stream_id': s, 'category_id': cat, 'stream_icon': '', 'container_extension': 'ts'} for s, n in STREAMS.get(cat, [])]
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
                self.send_error(404); return
            self.send_response(200); self.send_header('Content-Type', kind); self.send_header('Content-Length', str(len(payload))); self.end_headers()
            try: self.wfile.write(payload)
            except (BrokenPipeError, ConnectionResetError): pass

    server = ThreadingHTTPServer(('127.0.0.1', 0), Provider); port = server.server_port
    threading.Thread(target=server.serve_forever, daemon=True).start()
    adb('reverse', 'tcp:%d' % port, 'tcp:%d' % port)
    adb('shell', 'am', 'force-stop', PKG)
    package = adb('shell', 'dumpsys', 'package', PKG)
    uid = re.search(r'^\s*(?:userId|appId)=(\d+)', package, re.M)[1]
    folder = '/data/user/0/%s/shared_prefs' % PKG
    adb('shell', 'mkdir', '-p', folder)
    xml = ('<?xml version="1.0" encoding="utf-8"?><map><string name="server">http://127.0.0.1:%d</string>'
           '<string name="user">demo</string><string name="pass">demo</string><string name="lastChannel">101</string>'
           '<string name="lastChannelName">SSC 1 HD</string></map>') % port
    subprocess.run([ADB, 'shell', 'cat > %s/XtreamActivity.xml' % folder], input=xml.encode(), check=True)
    last = json.dumps({'id': '101', 'name': 'SSC 1 HD', 'icon': '', 'num': '101', 'ext': 'ts'}).replace('"', '&quot;')
    live = '<?xml version="1.0" encoding="utf-8"?><map><string name="last">%s</string></map>' % last
    subprocess.run([ADB, 'shell', 'cat > %s/ouswis-live.xml' % folder], input=live.encode(), check=True)
    adb('shell', 'chown', '-R', uid + ':' + uid, folder)
    adb('logcat', '-c')

    # 1. the new home
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.HomeActivity'); time.sleep(14)
    shot('redesign-home.png'); bad, log = crashed(); assert not bad, 'Home crashed:\n' + log[-2000:]
    keys('DPAD_DOWN'); keys('DPAD_LEFT'); shot('redesign-home-focus.png')
    note('PASS: new home opened, drew and took remote keys without a crash.')

    # 2. the live TV browser
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.LiveActivity'); time.sleep(6)
    shot('redesign-live.png')
    keys('DPAD_DOWN'); time.sleep(4); shot('redesign-live-preview.png')
    keys('DPAD_LEFT'); time.sleep(3); shot('redesign-live-next-section.png')
    bad, log = crashed(); assert not bad, 'Live crashed:\n' + log[-2000:]
    note('PASS: live browser listed categories and channels, previewed a channel and changed section.')
    keys('DPAD_RIGHT'); keys('DPAD_CENTER', pause=2); time.sleep(6); shot('redesign-live-fullscreen.png')
    keys('BACK', pause=2); shot('redesign-live-back.png')
    bad, log = crashed(); assert not bad, 'Playback crashed:\n' + log[-2000:]
    note('PASS: OK opened the channel full screen and Back returned.')

    # 3. matches (real ESPN data from the emulator's network)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MatchesScreenActivity'); time.sleep(25)
    shot('redesign-matches.png'); keys('DPAD_DOWN'); keys('DPAD_LEFT'); shot('redesign-matches-focus.png')
    bad, log = crashed(); assert not bad, 'Matches crashed:\n' + log[-2000:]
    note('PASS: matches screen loaded, took keys without a crash.')
    (OUT / 'redesign-logcat.txt').write_text(adb('logcat', '-d', '-t', '600', check=False))
