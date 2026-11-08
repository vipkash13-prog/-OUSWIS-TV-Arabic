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
import xml.etree.ElementTree as ET
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

def ui():
    # uiautomator briefly returns a null root while a window animates; retry instead of failing the run.
    for attempt in range(8):
        adb('shell', 'rm', '-f', '/data/local/tmp/live-ui.xml')
        subprocess.run([ADB, 'shell', 'uiautomator', 'dump', '/data/local/tmp/live-ui.xml'], timeout=60,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            text = adb('shell', 'cat', '/data/local/tmp/live-ui.xml')
            break
        except subprocess.CalledProcessError:
            time.sleep(2)
    else:
        raise RuntimeError('uiautomator never returned a window tree')
    (OUT / 'live-ui.xml').write_text(text)
    return ET.fromstring(text)

assert adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1'
with tempfile.TemporaryDirectory() as temp:
    clips = []
    for index, (size, color) in enumerate((('640x360', 'red'), ('640x480', 'blue'), ('640x360', 'yellow'))):
        path = Path(temp) / (str(index) + '.ts')
        subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'color=c='+color+':s='+size+':r=10',
                        '-vf', 'drawbox=x=0:y=0:w=iw:h=ih:color=lime:t=12', '-t', '20',
                        '-c:v', 'libx264', '-preset', 'ultrafast', '-pix_fmt', 'yuv420p', '-f', 'mpegts', str(path)], check=True)
        clips.append(path.read_bytes())

    subprocess.run(['ffmpeg', '-v', 'error', '-i', str(Path(temp) / '0.ts'), '-c', 'copy',
                    '-hls_time', '2', '-hls_list_size', '0', '-hls_segment_filename',
                    str(Path(temp) / 'recovery%03d.ts'), str(Path(temp) / 'recovery.m3u8')], check=True)
    faults = {'ts': False, 'all': False, 'ts_failures': 0, 'hls': 0, 'catalog': False, 'group_error': False, 'hold_groups': False, 'api_down': False}

    groups_started = threading.Event()
    groups_release = threading.Event()

    class Provider(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass  # Synthetic credentials stay out of HTTP logs too.

        def do_GET(self):
            url = urlparse(self.path)
            if url.path == '/player_api.php':
                if faults['api_down']:
                    self.send_error(503)
                    return
                query = parse_qs(url.query)
                action = query.get('action', [''])[0]
                if action == 'get_live_categories':
                    body = ([{'category_id':'10','category_name':'AR | OSN'}, {'category_id':'11','category_name':'Sports'}]
                            if faults['catalog'] else [{'category_id':'10','category_name':'General'}])
                elif action == 'get_live_streams':
                    if faults['catalog']:
                        group = query.get('category_id', [''])[0]
                        if group and faults['hold_groups']:
                            groups_started.set()
                            if not groups_release.wait(90):
                                self.send_error(503)
                                return
                        if group == '11' and faults['group_error']:
                            self.send_error(503)
                            return
                        body = [{'name':'OSN News','stream_id':'1','category_id':'10'},
                                {'name':'Ｏ.Ｓ.Ｎ Cinema','stream_id':'2','category_id':'10'}]
                        if group == '10':
                            body += [{'name':'Movies Action','stream_id':'3'}, {'name':'Movies Comedy','stream_id':'4'},
                                     {'name':'Movies Action','stream_id':'5'}]
                        elif group == '11':
                            body = [{'name':'Sports','stream_id':'6'}]
                    else:
                        body = [{'name': 'Test 16:9', 'stream_id': '1', 'category_id':'10'},
                                {'name': 'Test 4:3', 'stream_id': '2', 'category_id':'10'},
                                {'name': 'Test third', 'stream_id': '3', 'category_id':'10'}]
                else:
                    body = {'user_info': {'status': 'Active'}}
                data = json.dumps(body).encode()
                kind = 'application/json'
            elif url.path in ('/live/demo/demo/1.ts', '/live/demo/demo/2.ts', '/live/demo/demo/3.ts'):
                if url.path.endswith('/1.ts') and (faults['ts'] or faults['all']):
                    faults['ts_failures'] += 1
                    self.send_error(503)
                    return
                data = clips[int(url.path.rsplit('/',1)[1].split('.')[0])-1]
                kind = 'video/mp2t'
            elif url.path == '/live/demo/demo/1.m3u8':
                faults['hls'] += 1
                if faults['all']:
                    self.send_error(503)
                    return
                data = (Path(temp) / 'recovery.m3u8').read_bytes()
                kind = 'application/vnd.apple.mpegurl'
            elif re.fullmatch(r'/live/demo/demo/recovery[0-9]+\.ts', url.path):
                data = (Path(temp) / url.path.rsplit('/', 1)[1]).read_bytes()
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
    adb('shell', 'am', 'force-stop', 'io.ouswis.tv.standalone')
    package = adb('shell', 'dumpsys', 'package', 'io.ouswis.tv.standalone')
    uid = re.search(r'^\s*(?:userId|appId)=(\d+)', package, re.M)[1]
    folder = '/data/user/0/io.ouswis.tv.standalone/shared_prefs'
    pref = folder + '/XtreamActivity.xml'
    adb('shell', 'mkdir', '-p', folder)
    content = ('<?xml version="1.0" encoding="utf-8"?><map><string name="server">http://127.0.0.1:'+str(port)+
               '</string><string name="user">demo</string><string name="pass">demo</string></map>')
    subprocess.run([ADB, 'shell', 'cat > '+pref], input=content.encode(), check=True)
    adb('shell', 'chown', uid+':'+uid, folder, pref)
    adb('shell', 'am', 'start', '-W', '-n', 'io.ouswis.tv.standalone/io.ouswis.tv.standalone.HomeActivity')
    keys('DPAD_DOWN')
    nodes = list(ui().iter('node'))
    height = Image.open(BytesIO(subprocess.check_output([ADB, 'exec-out', 'screencap', '-p']))).height
    entries = [n for n in nodes if n.get('text') in ('Live TV', 'البث التلفزيوني المباشر')
               and int(re.findall(r'\d+', n.get('bounds'))[1]) > height//3]
    assert len(entries) == 1, 'Expected exactly one Live TV tile in the drawer, inside Inputs: '+str(len(entries))
    (OUT / 'live-input-single.png').write_bytes(subprocess.check_output([ADB, 'exec-out', 'screencap', '-p']))
    note('PASS: drawer has one Live TV tile in Inputs, with no duplicate shortcut.')
    x1,y1,x2,y2 = map(int,re.findall(r'\d+',entries[0].get('bounds')))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
    time.sleep(3)
    first = next(n for n in ui().iter('node') if n.get('text') == 'Test 16:9')
    x1,y1,x2,y2 = map(int,re.findall(r'\d+',first.get('bounds')))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))

    def complete_frame(vw, vh, filename, percent=100, color=None):
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
            r,g,b = image.getpixel((width//2,height//2))
            right_color = color is None or (color == 'yellow' and r > 180 and g > 180 and b < 100)
            if right_color and all(green(x,y) for x,y in points):
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
    keys('DPAD_UP')
    complete_frame(640, 480, 'live-fit-4x3.png')
    note('PASS: UP switches to the next channel during playback.')
    keys('DPAD_UP')
    complete_frame(640, 360, 'live-third-channel.png', color='yellow')
    note('PASS: UP from channel 2 opens channel 3, rather than the earlier channel 1.')
    keys('DPAD_UP')
    complete_frame(640, 360, 'live-third-channel.png', color='yellow')
    note('PASS: UP at the last channel stays there without jumping to the top.')
    keys('DPAD_DOWN')
    complete_frame(640, 480, 'live-fit-4x3.png')
    keys('DPAD_DOWN')
    complete_frame(640, 360, 'live-fit-16x9.png')
    note('PASS: DOWN switches back to the previous channel during playback.')
    keys('DPAD_DOWN')
    complete_frame(640, 360, 'live-fit-16x9.png')
    note('PASS: DOWN at the first channel stays there without jumping to the bottom.')
    keys('DPAD_UP')
    complete_frame(640, 480, 'live-fit-4x3.png')
    keys('BACK')
    assert any(n.get('text') == 'Test 4:3' and n.get('selected') == 'true' for n in ui().iter('node'))
    note('PASS: BACK restores the channel list selection to the watched second channel.')
    # Reproduce a provider that lists a channel but refuses its advertised TS endpoint.
    faults['ts'] = True
    first = next(n for n in ui().iter('node') if n.get('text') == 'Test 16:9')
    x1,y1,x2,y2 = map(int,re.findall(r'\d+', first.get('bounds')))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
    complete_frame(640, 360, 'live-hls-recovery.png')
    assert faults['ts_failures'] > 0 and faults['hls'] > 0, faults
    note('PASS: refused TS endpoint automatically falls back to HLS and Android decodes the complete frame.')
    keys('BACK')
    faults['all'] = True
    first = next(n for n in ui().iter('node') if n.get('text') == 'Test 16:9')
    x1,y1,x2,y2 = map(int,re.findall(r'\d+', first.get('bounds')))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
    for _ in range(20):
        if 'Playback error (' in ET.tostring(ui(), encoding='unicode'):
            break
        time.sleep(1)
    else:
        raise AssertionError('Both formats failed without bounded retries and visible error codes')
    count = faults['ts_failures'] + faults['hls']
    time.sleep(3)
    assert faults['ts_failures'] + faults['hls'] == count, 'Retries must stop after exhaustion'
    faults['all'] = False
    keys('DPAD_CENTER')
    complete_frame(640, 360, 'live-manual-recovery.png')
    note('PASS: both-format failures stop retrying, show numeric diagnostics, and OK retries successfully.')
    # Switching cancels any pending reconnect for the previous channel.
    keys('DPAD_UP')
    complete_frame(640, 480, 'live-fit-4x3.png')
    keys('BACK')
    def tap(node):
        x1,y1,x2,y2 = map(int,re.findall(r'\d+', node.get('bounds')))
        adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))

    def search_channels(query):
        tap(next(n for n in ui().iter('node') if n.get('content-desc') == 'مسح البحث / Clear search'))
        tap(next(n for n in ui().iter('node') if n.get('class') == 'android.widget.EditText'))
        adb('shell', 'input', 'text', query)
        keys('ENTER')

    search_channels('tEsT')
    texts = [n.get('text') for n in ui().iter('node')]
    assert all(name in texts for name in ('Test 16:9','Test 4:3','Test third')), texts
    search_channels('4:3')
    texts = [n.get('text') for n in ui().iter('node')]
    assert 'Test 4:3' in texts and 'Test 16:9' not in texts and 'Test third' not in texts, texts
    (OUT / 'live-channel-search.png').write_bytes(subprocess.check_output([ADB,'exec-out','screencap','-p']))
    keys('DPAD_CENTER')
    complete_frame(640, 480, 'live-search-selection.png')
    keys('BACK')
    nodes = list(ui().iter('node'))
    assert any(n.get('class') == 'android.widget.EditText' and n.get('text') == '4:3' for n in nodes)
    assert any(n.get('text') == 'Test 4:3' and n.get('selected') == 'true' for n in nodes)
    search_channels('zzzx')
    assert 'No matching channels' in ET.tostring(ui(), encoding='unicode')
    tap(next(n for n in ui().iter('node') if n.get('content-desc') == 'مسح البحث / Clear search'))
    texts = [n.get('text') for n in ui().iter('node')]
    assert all(name in texts for name in ('Test 16:9','Test 4:3','Test third')), texts
    search_channels('4:3')
    keys('DPAD_UP')
    assert any(n.get('class') == 'android.widget.EditText' and n.get('focused') == 'true' for n in ui().iter('node'))
    keys('DPAD_DOWN')
    assert any(n.get('class') == 'android.widget.ListView' and n.get('focused') == 'true' for n in ui().iter('node'))
    note('PASS: case-insensitive channel search, filtered playback of the correct original channel, BACK retention, empty results, clear and remote focus.')
    # The global provider response has only two rows. Category responses contain six distinct streams.
    faults['catalog'] = True
    faults['hold_groups'] = True
    adb('shell','am','force-stop','io.ouswis.tv.standalone')
    adb('shell','am','start','-W','-n','io.ouswis.tv.standalone/.XtreamActivity')

    def wait_count(count):
        for _ in range(12):
            tree = ui()
            if any('Live TV ('+count+')' in n.get('text','') for n in tree.iter('node')):
                return tree
            time.sleep(0.5)
        raise AssertionError('Missing catalogue/search count: '+count)

    wait_count('3/3')  # The saved list is usable before contacting the provider again.
    tap(next(n for n in ui().iter('node') if n.get('content-desc') == 'تحديث القنوات / Reload channels'))
    assert groups_started.wait(5), 'Expected deliberately blocked group requests'
    search_channels('osn')
    wait_count('2/3')
    (OUT / 'live-progressive-catalog.png').write_bytes(subprocess.check_output([ADB,'exec-out','screencap','-p']))
    # Open a channel while the groups are still blocked, then let the background refresh finish.
    second = next(n for n in ui().iter('node') if n.get('text') == 'Ｏ.Ｓ.Ｎ Cinema')
    tap(second)
    complete_frame(640,480,'live-during-group-load.png')
    faults['hold_groups'] = False
    groups_release.set()
    cache_file = '/data/user/0/io.ouswis.tv.standalone/files/xtream-catalog-cache.json'
    for _ in range(20):
        cached = json.loads(adb('shell','cat',cache_file))
        if cached.get('complete') and len(cached.get('channels',[])) == 6:
            break
        time.sleep(0.3)
    else:
        raise AssertionError('Final complete catalogue was not saved')
    complete_frame(640,480,'live-after-group-load.png')
    keys('BACK')
    wait_count('5/6')
    tap(next(n for n in ui().iter('node') if n.get('content-desc') == 'مسح البحث / Clear search'))
    tree = wait_count('6/6')
    field = next(n for n in tree.iter('node') if n.get('class') == 'android.widget.EditText')
    assert 'OSN' not in field.get('text',''), 'Search must not contain the old OSN example'
    search_channels('O.S.N')
    wait_count('5/6')
    (OUT / 'live-group-search.png').write_bytes(subprocess.check_output([ADB,'exec-out','screencap','-p']))
    search_channels('Movies%sAction')
    wait_count('2/6')  # Same name, different stream IDs: keep both entries.
    faults['group_error'] = True
    tap(next(n for n in ui().iter('node') if n.get('content-desc') == 'تحديث القنوات / Reload channels'))
    for _ in range(12):
        if 'Some groups failed to load' in ET.tostring(ui(),encoding='unicode'):
            break
        time.sleep(0.5)
    else:
        raise AssertionError('Missing group must be reported instead of a silent partial catalogue')
    faults['group_error'] = False
    tap(next(n for n in ui().iter('node') if n.get('content-desc') == 'تحديث القنوات / Reload channels'))
    wait_count('2/6')
    search_channels('osn')
    wait_count('5/6')
    faults['api_down'] = True
    adb('shell','am','force-stop','io.ouswis.tv.standalone')
    adb('shell','am','start','-W','-n','io.ouswis.tv.standalone/.XtreamActivity')
    wait_count('6/6')
    search_channels('osn')
    wait_count('5/6')
    note('PASS: saved catalogue opens while provider API is offline; first channels/search/playback remain available during blocked group loading; background completion does not interrupt the watched channel.')
    note('PASS: truncated global catalogue reconciled with all live groups; OSN finds five members instead of two name matches; decorated names, duplicate-name streams, no example text, partial-load warning and reload recovery verified.')
    server.shutdown()
    adb('reverse', '--remove', 'tcp:'+str(port))
note('Synthetic local streams only; no real subscription or provider availability was tested.')
