#!/usr/bin/env python3
"""Decode synthetic channels and verify all four video borders on a disposable emulator."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import BytesIO
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import threading
import time
from urllib.parse import parse_qs, urlparse
from PIL import Image

OUT = Path(sys.argv[1])
ADB = os.environ.get('ADB', 'adb')

def adb(*args):
    return subprocess.check_output([ADB, *args], timeout=60).decode().replace('\r', '')

def note(message):
    print(message, flush=True)
    with (OUT / 'live-playback.log').open('a') as f:
        f.write(message + '\n')

def keys(*names):
    for name in names:
        adb('shell', 'input', 'keyevent', 'KEYCODE_' + name)

assert adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1'
with tempfile.TemporaryDirectory() as temp:
    clips = []
    for index, (size, color) in enumerate((('640x360', 'red'), ('640x480', 'blue'))):
        path = Path(temp) / (str(index) + '.ts')
        subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'color=c='+color+':s='+size+':r=10',
                        '-vf', 'drawbox=x=0:y=0:w=iw:h=ih:color=lime:t=12', '-t', '20',
                        '-c:v', 'libx264', '-preset', 'ultrafast', '-pix_fmt', 'yuv420p', '-f', 'mpegts', str(path)], check=True)
        clips.append(path.read_bytes())

    class Provider(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass  # Synthetic credentials stay out of HTTP logs too.

        def do_GET(self):
            url = urlparse(self.path)
            if url.path == '/player_api.php':
                query = parse_qs(url.query)
                if query.get('action') == ['get_live_streams']:
                    body = [{'name': 'Test 16:9', 'stream_id': '1'}, {'name': 'Test 4:3', 'stream_id': '2'}]
                else:
                    body = {'user_info': {'status': 'Active'}}
                data = json.dumps(body).encode()
                kind = 'application/json'
            elif url.path in ('/live/demo/demo/1.ts', '/live/demo/demo/2.ts'):
                data = clips[0 if url.path.endswith('/1.ts') else 1]
                kind = 'video/mp2t'
            else:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header('Content-Type', kind)
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            try:
                self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError):
                pass

    server = ThreadingHTTPServer(('127.0.0.1', 0), Provider)
    port = server.server_port
    threading.Thread(target=server.serve_forever, daemon=True).start()
    adb('reverse', 'tcp:'+str(port), 'tcp:'+str(port))
    # Only the synthetic account on this disposable emulator; never use real subscription credentials.
    adb('shell', 'am', 'force-stop', 'io.freemytv')
    package = adb('shell', 'dumpsys', 'package', 'io.freemytv')
    uid = re.search(r'^\s*(?:userId|appId)=(\d+)', package, re.M)[1]
    folder = '/data/user/0/io.freemytv/shared_prefs'
    pref = folder + '/XtreamActivity.xml'
    adb('shell', 'mkdir', '-p', folder)
    content = ('<?xml version="1.0" encoding="utf-8"?><map><string name="server">http://127.0.0.1:'+str(port)+
               '</string><string name="user">demo</string><string name="pass">demo</string></map>')
    subprocess.run([ADB, 'shell', 'cat > '+pref], input=content.encode(), check=True)
    adb('shell', 'chown', uid+':'+uid, folder, pref)
    adb('shell', 'am', 'start', '-W', '-n', 'io.freemytv/io.freemytv.SettingsActivity')
    keys('DPAD_RIGHT', 'DPAD_LEFT', 'DPAD_CENTER')
    time.sleep(3)
    keys('DPAD_CENTER')

    def complete_frame(vw, vh, filename, percent=100):
        for _ in range(25):
            time.sleep(1)
            png = subprocess.check_output([ADB, 'exec-out', 'screencap', '-p'], timeout=60)
            image = Image.open(BytesIO(png)).convert('RGB')
            width, height = image.size
            available_width = width - 2*int(width*(100-percent)/200+0.5)
            available_height = height - 2*int(height*(100-percent)/200+0.5)
            ratio = min(available_width / vw, available_height / vh)
            fw, fh = int(vw*ratio), int(vh*ratio)
            left, top = (width-fw)//2, (height-fh)//2
            points = [(left+2, height//2), (left+fw-3, height//2),
                      (width//2, top+2), (width//2, top+fh-3)]
            def green(x, y):
                r,g,b = image.getpixel((x,y))
                return g > 160 and r < 100 and b < 100
            if all(green(x,y) for x,y in points):
                (OUT / filename).write_bytes(png)
                note('PASS: decoded '+str(vw)+'x'+str(vh)+' channel retains all four green frame borders, centered without crop.')
                return
        (OUT / 'live-playback-failure.png').write_bytes(png)
        (OUT / 'live-playback-logcat.txt').write_text(adb('logcat', '-d', '-t', '700'))
        raise AssertionError('Full video frame missing: '+str(points))

    complete_frame(640, 360, 'live-fit-16x9.png')
    keys('DPAD_LEFT')
    complete_frame(640, 360, 'live-safe-95.png', 95)
    note('PASS: LEFT reduces picture size to 95% without cropping, for TV overscan.')
    keys('DPAD_RIGHT')
    complete_frame(640, 360, 'live-fit-16x9.png')
    keys('DPAD_DOWN')
    complete_frame(640, 480, 'live-fit-4x3.png')
    note('PASS: DOWN switches to the next channel during playback.')
    keys('DPAD_UP')
    complete_frame(640, 360, 'live-fit-16x9.png')
    note('PASS: UP switches back to the previous channel during playback.')
    keys('BACK')
    server.shutdown()
    adb('reverse', '--remove', 'tcp:'+str(port))
note('Synthetic local streams only; no real subscription or provider availability was tested.')
