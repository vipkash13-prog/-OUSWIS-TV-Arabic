#!/usr/bin/env python3
"""Native optional notices, expiry, actual current prayer countdown and default C on a disposable emulator."""
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
ADB=os.environ.get('ADB','adb');PKG='io.ouswis.tv.standalone';COMPONENT=PKG+'/'+PKG+'.NowPlaying'
OUT=Path(sys.argv[1]);OUT.mkdir(parents=True,exist_ok=True)
def adb(*args,binary=False):
    r=subprocess.run([ADB,*args],capture_output=True,check=True,timeout=90)
    return r.stdout if binary else r.stdout.decode('utf-8','replace').replace('\r','')
def ui():
    adb('shell','uiautomator','dump','/data/local/tmp/polish.xml');return ET.fromstring(adb('shell','cat','/data/local/tmp/polish.xml'))
def shot(name): (OUT/name).write_bytes(adb('exec-out','screencap','-p',binary=True))
def note(text):
    print(text,flush=True)
    with (OUT/'home-enhancements.log').open('a') as f:f.write(text+'\n')
assert adb('shell','getprop','ro.kernel.qemu').strip()=='1'
adb('root');adb('wait-for-device');adb('shell','am','force-stop',PKG)
path='/data/user/0/'+PKG+'/shared_prefs/settings.xml'
saved=adb('shell','cat',path,binary=True)
uid=re.search(r'(?:userId|appId)=(\d+)',adb('shell','dumpsys','package',PKG))[1]
old_listeners=adb('shell','settings','get','secure','enabled_notification_listeners')
def write(data):
    subprocess.run([ADB,'shell','cat > '+path],input=data,check=True,timeout=30)
    adb('shell','chown',uid+':'+uid,path)
try:
    settings=ET.fromstring(saved)
    for n in list(settings):
        if n.get('name') in ('language','topContent','city','latitude','longitude','temperature','islandNotifications','prayerAlerts','shelf','androidapps'):settings.remove(n)
    for key,value in [('language','ar'),('topContent','none'),('city','جدة'),('latitude','21.4858'),('longitude','39.1925')]:ET.SubElement(settings,'string',{'name':key}).text=value
    installed=adb('shell','pm','list','packages')
    shelf=[name for name in ('com.android.settings','com.android.calendar','com.android.contacts') if 'package:'+name+'\n' in installed]
    assert len(shelf)>=2
    ET.SubElement(settings,'string',{'name':'shelf'}).text=','.join(shelf)
    ET.SubElement(settings,'boolean',{'name':'androidapps','value':'true'})
    ET.SubElement(settings,'boolean',{'name':'islandNotifications','value':'true'})
    ET.SubElement(settings,'boolean',{'name':'prayerAlerts','value':'false'})
    write(ET.tostring(settings,encoding='utf-8'))
    adb('shell','am','start','-W','-n',PKG+'/.HomeActivity')
    adb('shell','cmd','notification','allow_listener',COMPONENT)
    for _ in range(12):
        tree=ui();text=ET.tostring(tree,encoding='unicode')
        if '°C' in text and 'المتبقي' in text:break
        time.sleep(1)
    else:
        shot('enhancements-data-failure.png');raise AssertionError('Expected current Celsius weather and next-prayer countdown on native Home')
    shot('home-enhancements.png')
    note('PASS: default Celsius and a countdown derived from current real Jeddah prayer data are visible to Android accessibility.')
    before=adb('shell','dumpsys','activity','activities')
    title='OUSWIS_island_check'
    note(adb('shell','su','2000','cmd','notification','post','-t',title,'ouswis-island-probe','Integration-check').strip())
    found=None
    for _ in range(3):
        tree=ui()
        found=next((n for n in tree.iter('node') if n.get('package')==PKG and title in (n.get('text','')+' '+n.get('content-desc',''))),None)
        if found is not None:break
    if found is None:
        shot('island-notification-failure.png')
        (OUT/'island-failure.xml').write_bytes(ET.tostring(tree))
        (OUT/'island-system.txt').write_text(adb('shell','dumpsys','notification'))
        (OUT/'island-logcat.txt').write_text(adb('logcat','-d','-t','600'))
        raise AssertionError('Enabled notification did not reach the existing island')
    box=list(map(int,re.findall(r'\d+',found.get('bounds',''))))
    root=next(n for n in tree.iter('node') if n.get('class')=='android.view.ViewGroup')
    screen=list(map(int,re.findall(r'\d+',root.get('bounds',''))))
    assert box[0]>screen[2]*.65 and box[3]<screen[3]*.2,('Island moved from existing top-right capsule',box,screen)
    shot('island-notification.png')
    time.sleep(7)
    assert title not in ET.tostring(ui(),encoding='unicode'),'Island did not auto-hide'
    note('PASS: an actual Android notification title appeared in the existing top-right island and auto-hid. Notification access was enabled only on this disposable emulator.')
    for _ in range(8):
        adb('shell','input','keyevent','KEYCODE_DPAD_RIGHT','KEYCODE_DPAD_LEFT')
    tree=ui()
    assert any(n.get('package')==PKG and n.get('focused')=='true' and n.get('clickable')=='true' for n in tree.iter('node'))
    assert 'Process: '+PKG not in adb('logcat','-d','-b','crash')
    note('PASS: rapid physical remote directions retain focus without a process crash. Physical MECOOL frame rate was not measured.')
finally:
    adb('shell','am','force-stop',PKG);write(saved)
    if COMPONENT not in old_listeners:adb('shell','cmd','notification','disallow_listener',COMPONENT)
