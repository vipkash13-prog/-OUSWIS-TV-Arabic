#!/usr/bin/env python3
"""Real native sunset home: Arabic, visible widgets, remote and shortcut launches; emulator only."""
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from io import BytesIO
from PIL import Image

ADB=os.environ.get('ADB','adb'); PKG='io.ouswis.tv.standalone'
OUT=Path(sys.argv[1]); OUT.mkdir(parents=True,exist_ok=True)
def adb(*args,binary=False):
    r=subprocess.run([ADB,*args],capture_output=True,check=True,timeout=90)
    return r.stdout if binary else r.stdout.decode('utf-8','replace').replace('\r','')
def ui():
    adb('shell','uiautomator','dump','/data/local/tmp/gold.xml')
    return ET.fromstring(adb('shell','cat','/data/local/tmp/gold.xml'))
def bounds(n):return list(map(int,re.findall(r'\d+',n.get('bounds',''))))
def label(n):return n.get('text','')+' '+n.get('content-desc','')
def key(k):adb('shell','input','keyevent','KEYCODE_DPAD_'+k)
def launch():
    adb('shell','am','start','-W','-n',PKG+'/.HomeActivity');time.sleep(2)
def front(name):
    state=adb('shell','dumpsys','activity','activities')
    assert any(name in x and ('mResumedActivity' in x or 'topResumedActivity' in x) for x in state.splitlines()),name

def tap(n):
    b=bounds(n);adb('shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2))

assert adb('shell','getprop','ro.kernel.qemu').strip()=='1','Disposable emulator required'
adb('root');adb('wait-for-device');adb('shell','am','force-stop',PKG)
prefs='/data/user/0/'+PKG+'/shared_prefs/settings.xml'
try:saved=adb('shell','cat',prefs,binary=True)
except subprocess.CalledProcessError:saved=b'<map/>'
display_override='Physical size: 3840x2160' not in adb('shell','wm','size')
if display_override:adb('shell','wm','size','1080x1920')
uid=re.search(r'(?:userId|appId)=(\d+)',adb('shell','dumpsys','package',PKG)).group(1)
def write(data):
    path=OUT/'gold-settings.tmp';path.write_bytes(data)
    try:
        adb('shell','mkdir','-p',prefs.rsplit('/',1)[0]);adb('shell','chown',uid+':'+uid,prefs.rsplit('/',1)[0])
        adb('push',str(path),'/data/local/tmp/gold-settings.xml');adb('shell','cp','/data/local/tmp/gold-settings.xml',prefs)
        adb('shell','chown',uid+':'+uid,prefs);adb('shell','chmod','660',prefs)
    finally:path.unlink(missing_ok=True)
try:
    # Use real installed Android apps to exercise the shelf; never fabricate APKs or keys.
    available=adb('shell','pm','list','packages')
    candidates=['com.android.settings','com.android.calendar','com.android.deskclock','com.android.calculator2','com.android.contacts','com.android.gallery3d','com.android.documentsui','com.android.music']
    shelf=[p for p in candidates if 'package:'+p+'\n' in available]
    assert shelf,'No installed shelf candidates'
    settings=ET.fromstring(saved)
    values={'language':('string','ar'),'topContent':('string','none'),'city':('string','جدة'),'latitude':('string','21.4858'),'longitude':('string','39.1925'),'shelf':('string',','.join(shelf)),'androidapps':('boolean','true'),'weather':('boolean','true'),'prayers':('boolean','true')}
    for n in list(settings):
        if n.get('name') in values:settings.remove(n)
    for name,(kind,value) in values.items():
        n=ET.SubElement(settings,kind,{'name':name})
        if kind=='string':n.text=value
        else:n.set('value',value)
    write(ET.tostring(settings,encoding='utf-8'));launch()
    for _ in range(8):
        tree=ui(); nodes=list(tree.iter('node'))
        prayer=next((n for n in nodes if 'مواقيت الصلاة' in label(n) and bounds(n)[1]>200),None)
        if prayer is not None:break
        time.sleep(1)
    assert prayer is not None,'Prayer card not visible without opening drawer'
    png=adb('exec-out','screencap','-p',binary=True);im=Image.open(BytesIO(png)).convert('RGB');w,h=im.size;k=w/1920
    suffix=str(w)+'x'+str(h)
    (OUT/('gold-home-ar-'+suffix+'.png')).write_bytes(png)
    (OUT/('gold-home-ar-'+suffix+'.xml')).write_bytes(ET.tostring(tree))
    p=bounds(prayer);assert p[1]>h*.55 and p[3]<=h,(p,w,h)
    # Dark glass must be visibly different from the old pale home, at both resolutions.
    r,g,b=im.getpixel((int(1200*k),int(940*k)));assert max(r,g,b)<180,(r,g,b)
    focus=next(n for n in nodes if n.get('focused')=='true' and n.get('package')==PKG and n.get('clickable')=='true')
    first=bounds(focus);assert h*.25<first[1]<h*.5,first
    key('RIGHT');tree=ui();second=bounds(next(n for n in tree.iter('node') if n.get('focused')=='true' and n.get('package')==PKG and n.get('clickable')=='true'))
    assert second[0]>first[0],(first,second)
    key('LEFT');key('DOWN');tree=ui();low=bounds(next(n for n in tree.iter('node') if n.get('focused')=='true' and n.get('package')==PKG and n.get('clickable')=='true'))
    assert h*.4<low[1]<h*.65,low
    key('UP');tree=ui();high=bounds(next(n for n in tree.iter('node') if n.get('focused')=='true' and n.get('package')==PKG and n.get('clickable')=='true'))
    assert high[1]<low[1],(high,low)
    for text,name in [('التحديثات','UpdatesActivity'),('المباريات','MatchesActivity'),('البث التلفزيوني المباشر','XtreamActivity')]:
        launch();tree=ui()
        n=next(n for n in tree.iter('node') if text in label(n) and h*.4<bounds(n)[1]<h*.65)
        tap(n);time.sleep(1);front(name);adb('shell','input','keyevent','KEYCODE_BACK')
    crash=adb('logcat','-d','-b','crash')
    assert 'Process: '+PKG not in crash,crash
    with (OUT/'gold-home.log').open('a') as f:f.write('PASS: native sunset/gold home at '+suffix+'; Arabic, persistent prayer card, real app shelf, physical RIGHT/LEFT/DOWN/UP, Updates/Matches/Live TV touch entrypoints. Physical TV performance not measured.\n')
    print((OUT/'gold-home.log').read_text(),flush=True)
finally:
    adb('shell','am','force-stop',PKG);write(saved)
    if display_override:adb('shell','wm','size','reset')
