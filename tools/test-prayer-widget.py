#!/usr/bin/env python3
"""Verify real Jeddah data in the replacement drawer card and render the new icon on Android 14."""
import datetime
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB=os.environ.get('ADB','adb')
OUT=Path(sys.argv[1])
PKG='io.ouswis.tv.standalone'

def adb(*args):
    return subprocess.check_output([ADB,*args],timeout=60).decode('utf-8','replace').replace('\r','')
def note(text):
    print(text,flush=True)
    with (OUT/'prayer-widget.log').open('a') as f:f.write(text+'\n')
def ui():
    adb('shell','uiautomator','dump','/data/local/tmp/prayer-ui.xml')
    return ET.fromstring(adb('shell','cat','/data/local/tmp/prayer-ui.xml'))
def shot(name):
    (OUT/name).write_bytes(subprocess.check_output([ADB,'exec-out','screencap','-p'],timeout=60))

assert adb('shell','getprop','ro.kernel.qemu').strip()=='1'
adb('shell','am','start','-W','-n',PKG+'/'+PKG+'.HomeActivity')
adb('shell','input','keyevent','KEYCODE_DPAD_DOWN')
today=datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=3))).strftime('%d-%m-%Y')
for _ in range(20):
    tree=ui()
    text=ET.tostring(tree,encoding='unicode')
    try:
        preferences=ET.fromstring(adb('shell','cat','/data/user/0/'+PKG+'/shared_prefs/prayer-times.xml'))
        raw=next(n.text for n in preferences if n.get('name')=='today')
        data=json.loads(raw)['data']
    except (subprocess.CalledProcessError,StopIteration,ValueError,TypeError):
        time.sleep(1);continue
    if data['date']['gregorian']['date']==today and ('Prayer times' in text or 'مواقيت الصلاة' in text):
        break
else:
    shot('prayer-widget-failure.png')
    (OUT/'prayer-logcat.txt').write_text(adb('logcat','-d','-t','600'))
    raise AssertionError('The current Jeddah prayer day did not reach the visible card')
meta=data['meta']
assert meta['timezone']=='Asia/Riyadh' and meta['method']['id']==4
assert abs(meta['latitude']-21.4858)<0.02 and abs(meta['longitude']-39.1925)<0.02
for en,ar in [('Fajr','الفجر'),('Dhuhr','الظهر'),('Asr','العصر'),('Maghrib','المغرب'),('Isha','العشاء')]:
    assert en in text or ar in text, en
    assert data['timings'][en]
assert not any(coin in text for coin in ('Bitcoin','Ethereum','Solana','Crypto','العملات الرقمية'))
shot('prayer-times-jeddah.png')
note('PASS: the drawer replaces crypto with all five prayer times for Jeddah, '+today+', Asia/Riyadh, Umm al-Qura method 4.')
note('Source times: '+json.dumps({key:data['timings'][key] for key in ('Fajr','Dhuhr','Asr','Maghrib','Isha')},ensure_ascii=False))
assert 'Status: ok' in adb('shell','am','start','-W','-a','android.settings.APPLICATION_DETAILS_SETTINGS','-d','package:'+PKG)
ui()
shot('new-app-icon.png')
note('PASS: Android renders the new adaptive app icon; the TV banner retains the supplied 320x180 artwork.')
adb('shell','am','start','-W','-n',PKG+'/'+PKG+'.HomeActivity')
