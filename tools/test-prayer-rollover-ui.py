#!/usr/bin/env python3
"""Real AlAdhan evening/midnight regression on a disposable Android 14 emulator only."""
import datetime as dt
import json, math, os, subprocess, sys, time, urllib.request
from pathlib import Path
import xml.etree.ElementTree as ET
ADB=os.environ.get('ADB','adb');PKG='io.ouswis.tv.standalone';OUT=Path(sys.argv[1]);OUT.mkdir(parents=True,exist_ok=True)
ROOT='/data/user/0/'+PKG+'/shared_prefs/'
def adb(*args,binary=False):
    r=subprocess.run([ADB,*args],capture_output=True,check=True,timeout=90)
    return r.stdout if binary else r.stdout.decode('utf-8','replace').replace('\r','')
def ui():
    for i in range(4):
        try:
            adb('shell','rm','-f','/data/local/tmp/prayer-rollover.xml');adb('shell','uiautomator','dump','/data/local/tmp/prayer-rollover.xml')
            return ET.fromstring(adb('shell','cat','/data/local/tmp/prayer-rollover.xml'))
        except (subprocess.CalledProcessError,ET.ParseError):
            if i==3:raise
            time.sleep(1)
def note(t):
    print(t,flush=True)
    with (OUT/'prayer-rollover.log').open('a') as f:f.write(t+'\n')
assert adb('shell','getprop','ro.kernel.qemu').strip()=='1'
assert adb('shell','getprop','ro.build.version.sdk').strip()=='34'
adb('root');adb('wait-for-device')
saved={n:adb('shell','cat',ROOT+n,binary=True) for n in ('settings.xml','prayer-times.xml')}
old_auto=adb('shell','settings','get','global','auto_time').strip()
old_auto_zone=adb('shell','settings','get','global','auto_time_zone').strip()
old_zone=adb('shell','getprop','persist.sys.timezone').strip()
uid=adb('shell','stat','-c','%u',ROOT+'settings.xml').strip()
def write(name,raw):
    subprocess.run([ADB,'shell','cat > '+ROOT+name],input=raw,check=True,timeout=30)
    adb('shell','chown',uid+':'+uid,ROOT+name)
def set_clock(at):
    adb('shell','am','force-stop',PKG)
    adb('shell','date','-u','@'+str(int(at.timestamp())))
    assert abs(int(adb('shell','date','+%s').strip())-at.timestamp())<10,'Emulator clock was not set'
source=[]
for day in ('10-11-2026','11-11-2026'):
    address='https://api.aladhan.com/v1/timings/'+day+'?latitude=21.4858&longitude=39.1925&method=4&school=0&timezonestring=Asia%2FRiyadh'
    raw=urllib.request.urlopen(address,timeout=45).read().decode();data=json.loads(raw)['data']
    assert data['meta']['timezone']=='Asia/Riyadh' and data['meta']['method']['id']==4 and data['date']['gregorian']['date']==day
    source.append(raw)
try:
    adb('shell','settings','put','global','auto_time','0');adb('shell','settings','put','global','auto_time_zone','0')
    adb('shell','setprop','persist.sys.timezone','UTC') # deliberately different from Jeddah
    prefs=ET.fromstring(saved['settings.xml'])
    for n in list(prefs):
        if n.get('name') in ('language','topContent','clock'):prefs.remove(n)
    for key,value in {'language':'ar','topContent':'none','clock':'12'}.items():ET.SubElement(prefs,'string',{'name':key}).text=value
    adb('shell','am','force-stop',PKG);write('settings.xml',ET.tostring(prefs,encoding='utf-8'))
    cache=ET.Element('map')
    for key,raw in zip(('today','tomorrow'),source):ET.SubElement(cache,'string',{'name':key}).text=raw
    write('prayer-times.xml',ET.tostring(cache,encoding='utf-8'))
    zone=dt.timezone(dt.timedelta(hours=3));next_fajr=json.loads(source[1])['data']['timings']['Fajr'][:5]
    target=dt.datetime.fromisoformat('2026-11-11T'+next_fajr+':00').replace(tzinfo=zone)
    scenarios=[('2026-11-10T19:00:00','العشاء',False),('2026-11-10T19:41:00','الفجر',True),('2026-11-10T23:59:00','الفجر',True),('2026-11-11T00:00:00','الفجر',False)]
    for i,(stamp,prayer,tomorrow) in enumerate(scenarios):
        at=dt.datetime.fromisoformat(stamp).replace(tzinfo=zone);set_clock(at)
        adb('shell','am','start','-W','-n',PKG+'/.HomeActivity')
        if i==0:
            current=json.loads(source[0])['data']['timings']['Isha'][:5]
            prayer_at=dt.datetime.fromisoformat('2026-11-10T'+current+':00').replace(tzinfo=zone)
        else:prayer_at=target
        minutes=math.ceil((prayer_at-at).total_seconds()/60)
        remaining=str(minutes)+' دقيقة' if minutes<60 else str(minutes//60)+' س '+str(minutes%60)+' د'
        expected='الصلاة القادمة: '+prayer+(' · غدًا' if tomorrow else '')
        for attempt in range(8):
            tree=ui();text=ET.tostring(tree,encoding='unicode')
            if expected in text and 'المتبقي '+remaining in text:break
            time.sleep(1)
        else:raise AssertionError((stamp,expected,remaining,text))
        if not tomorrow:assert expected+' · غدًا' not in text
        time.sleep(1)
        name='prayer-jeddah-'+stamp[11:16].replace(':','')
        (OUT/(name+'.png')).write_bytes(adb('exec-out','screencap','-p',binary=True))
        (OUT/(name+'.xml')).write_bytes(ET.tostring(tree))
        note('PASS: Jeddah '+stamp+' -> '+prayer+(' tomorrow' if tomorrow else ' today')+', '+str(minutes)+' min / '+remaining+'. Device timezone UTC; real AlAdhan method 4, next-day Fajr '+next_fajr+'.')
        if stamp.endswith('19:41:00'):
            # Stay inside the same HomeActivity: the separate widget layer must still update on the minute tick.
            adb('shell','date','-u','@'+str(int(at.timestamp())+60))
            live_minutes=minutes-1
            live_text=str(live_minutes//60)+' س '+str(live_minutes%60)+' د'
            for attempt in range(8):
                live_tree=ui()
                if 'المتبقي '+live_text in ET.tostring(live_tree,encoding='unicode'):break
                time.sleep(1)
            else:raise AssertionError('The cached live widget did not update without recreating HomeActivity')
            (OUT/'prayer-jeddah-1942-live.png').write_bytes(adb('exec-out','screencap','-p',binary=True))
            note('PASS: the same running HomeActivity updated its countdown at 19:42 without recreation; live data is not frozen by the independent widget layer.')

    adb('shell','input','keyevent','KEYCODE_DPAD_RIGHT','KEYCODE_DPAD_LEFT','KEYCODE_DPAD_DOWN','KEYCODE_DPAD_UP')
    assert any(n.get('focused')=='true' and n.get('clickable')=='true' and n.get('package')==PKG for n in ui().iter('node'))
    note('PASS: physical remote arrows remain responsive after evening and Saudi midnight transitions.')
finally:
    adb('shell','am','force-stop',PKG)
    for name,raw in saved.items():write(name,raw)
    adb('shell','date','-u','@'+str(int(time.time())))
    adb('shell','setprop','persist.sys.timezone',old_zone)
    for key,value in (('auto_time',old_auto),('auto_time_zone',old_auto_zone)):
        if value=='null':adb('shell','settings','delete','global',key)
        else:adb('shell','settings','put','global',key,value)
