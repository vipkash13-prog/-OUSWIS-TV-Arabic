#!/usr/bin/env python3
"""The redesigned native home on a disposable emulator: Arabic, RTL, the six shortcuts, the prayer card, the app row and its
"+ إضافة" picker, physical remote movement and the shortcut launches. Emulator only; a real TV is a separate test."""
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
    for attempt in range(6):
        try:
            adb('shell','rm','-f','/data/local/tmp/gold.xml')
            adb('shell','uiautomator','dump','/data/local/tmp/gold.xml')
            return ET.fromstring(adb('shell','cat','/data/local/tmp/gold.xml'))
        except (subprocess.CalledProcessError, ET.ParseError):
            if attempt==5:raise
            time.sleep(1)
def bounds(n):return list(map(int,re.findall(r'\d+',n.get('bounds',''))))
def label(n):return n.get('text','')+' '+n.get('content-desc','')
def mine(tree):return [n for n in tree.iter('node') if n.get('package')==PKG]
def key(k,pause=0.5):adb('shell','input','keyevent','KEYCODE_'+k);time.sleep(pause)
def launch():
    adb('shell','am','start','-W','-n',PKG+'/.HomeActivity');time.sleep(2)
def front(name):
    for _ in range(20):
        state=adb('shell','dumpsys','activity','activities')
        if any(name in x and ('mResumedActivity' in x or 'topResumedActivity' in x) for x in state.splitlines()):return
        time.sleep(0.5)
    raise AssertionError('Expected foreground '+name)
def wait_for(predicate,what,tries=25):
    for _ in range(tries):
        tree=ui()
        found=predicate(tree)
        if found:return tree
        time.sleep(1)
    raise AssertionError(what)
def focused(tree):
    return next((n for n in mine(tree) if n.get('focused')=='true' and n.get('clickable')=='true'),None)

assert adb('shell','getprop','ro.kernel.qemu').strip()=='1','Disposable emulator required'
adb('root');adb('wait-for-device');adb('shell','am','force-stop',PKG)
prefs='/data/user/0/'+PKG+'/shared_prefs/settings.xml'
try:saved=adb('shell','cat',prefs,binary=True)
except subprocess.CalledProcessError:saved=b'<map/>'
display_override='Physical size: 3840x2160' not in adb('shell','wm','size')
if display_override:adb('shell','wm','size','1920x1080')
uid=re.search(r'(?:userId|appId)=(\d+)',adb('shell','dumpsys','package',PKG)).group(1)
def write(data):
    path=OUT/'gold-settings.tmp';path.write_bytes(data)
    try:
        adb('shell','mkdir','-p',prefs.rsplit('/',1)[0]);adb('shell','chown',uid+':'+uid,prefs.rsplit('/',1)[0])
        adb('push',str(path),'/data/local/tmp/gold-settings.xml');adb('shell','cp','/data/local/tmp/gold-settings.xml',prefs)
        adb('shell','chown',uid+':'+uid,prefs);adb('shell','chmod','660',prefs)
    finally:path.unlink(missing_ok=True)
try:
    # Real installed Android apps exercise the row; no APK or key is invented.
    available=adb('shell','pm','list','packages')
    candidates=['com.android.settings','com.android.calendar','com.android.deskclock','com.android.calculator2','com.android.contacts','com.android.gallery3d','com.android.documentsui','com.android.music']
    shelf=[p for p in candidates if 'package:'+p+'\n' in available][:2]
    assert len(shelf)==2,'Need two installed shelf candidates'
    settings=ET.fromstring(saved)
    values={'language':('string','ar'),'topContent':('string','none'),'city':('string','جدة'),'latitude':('string','21.4858'),'longitude':('string','39.1925'),'shelf':('string',','.join(shelf)),'androidapps':('boolean','true'),'weather':('boolean','true'),'prayers':('boolean','true')}
    for n in list(settings):
        if n.get('name') in values:settings.remove(n)
    for name,(kind,value) in values.items():
        n=ET.SubElement(settings,kind,{'name':name})
        if kind=='string':n.text=value
        else:n.set('value',value)
    write(ET.tostring(settings,encoding='utf-8'));launch()

    tree=wait_for(lambda t:any('مواقيت الصلاة' in label(n) and 'المتبقي' in label(n) for n in mine(t)),'The prayer card with real data never appeared')
    png=adb('exec-out','screencap','-p',binary=True);im=Image.open(BytesIO(png)).convert('RGB');w,h=im.size;k=w/1920
    suffix=str(w)+'x'+str(h)
    (OUT/('gold-home-ar-'+suffix+'.png')).write_bytes(png)
    (OUT/('gold-home-ar-'+suffix+'.xml')).write_bytes(ET.tostring(tree))
    nodes=mine(tree)
    # night background (SPEC #0B0D1A) away from the wallpaper's top third
    r,g,b=im.getpixel((int(960*k),int(1000*k)));assert max(r,g,b)<60,('Background must be the dark night colour',(r,g,b))
    # the six shortcuts exist, once each, and run right to left in the SPEC order
    order=['البث المباشر','المباريات','كل التطبيقات','الأخيرة','التحديثات','الإعدادات']
    xs=[]
    for name in order:
        hits=[n for n in nodes if n.get('text')==name and n.get('clickable')=='true' and bounds(n)[1]>h*0.7]
        assert len(hits)==1,(name,len(hits))
        xs.append(bounds(hits[0])[0])
    assert xs==sorted(xs,reverse=True),('Shortcuts must read right to left',xs)
    # the old big weather panel is gone: weather is a chip on the top bar
    chips=[n for n in nodes if label(n).startswith('الطقس')]
    assert chips and bounds(chips[0])[3]<h*0.2,'Weather must be a chip in the top bar'
    assert not any('الرطوبة' in label(n) for n in nodes),'The big weather panel must be gone'
    # the supplied wallpaper under everything: its moon (about 1533,127 of 1920x1080) is a light pixel, which the old gold wallpaper never had there
    mr,mg,mb=im.getpixel((int(1533*k),int(127*k)));assert max(mr,mg,mb)>150,('The supplied wallpaper must be drawn (moon)',(mr,mg,mb))
    # the last-channel card is 153 dp (229.5 reference px) tall and stays above the matches heading
    hero=[n for n in nodes if label(n).startswith('آخر قناة') or label(n).startswith('ابدأ المشاهدة')]
    assert hero,'The last-channel card must be a control'
    hb=bounds(hero[0]);assert (hb[3]-hb[1])<=(229.5+16)*k+2,('The hero card must be 153 dp tall',hb)
    heading=[n for n in nodes if n.get('text')=='مباريات اليوم']
    if heading:assert hb[3]<=bounds(heading[0])[1]+2,('The hero card must not reach the matches row',hb,bounds(heading[0]))
    # the first focus is the last-channel card
    first=focused(tree);assert first is not None and ('آخر قناة' in label(first) or 'ابدأ المشاهدة' in label(first)),label(first) if first is not None else None
    # the row of apps: what we put there, then the "+ add" tile
    apps_y=[bounds(n)[1] for n in nodes if n.get('text')=='إضافة تطبيق']
    assert len(apps_y)==1,'The + إضافة tile must close the apps row'
    # remote: LEFT moves along the row (RTL: the prayer card is on the left of the last-channel card), DOWN goes lower, UP returns
    key('DPAD_LEFT');second=focused(ui());assert second is not None and 'مواقيت الصلاة' in label(second),label(second) if second is not None else None
    key('DPAD_DOWN');lower=focused(ui());assert lower is not None and bounds(lower)[1]>bounds(second)[1],(lower,second)
    key('DPAD_UP');again=focused(ui());assert again is not None and bounds(again)[1]<bounds(lower)[1]
    # "+ إضافة": opens the list of installed apps (not the settings), and a pick joins the row
    launch()
    for _ in range(4):key('DPAD_DOWN')
    key('DPAD_UP')   # shortcuts row -> up to the apps row
    tree=ui()
    on_row=focused(tree)
    guard=0
    while on_row is not None and on_row.get('text')!='إضافة تطبيق' and guard<12:
        key('DPAD_LEFT');on_row=focused(ui());guard+=1
    assert on_row is not None and on_row.get('text')=='إضافة تطبيق','Could not reach the + tile with the remote'
    before=[n.get('text') for n in mine(ui()) if n.get('clickable')=='true' and bounds(n)[1]>h*0.55 and bounds(n)[3]<h*0.78]
    key('DPAD_CENTER',pause=1.5)
    dialog=wait_for(lambda t:any('اختر تطبيقًا لإضافته' in label(n) for n in t.iter('node')),'The installed-apps list did not open')
    (OUT/'gold-add-app-dialog.png').write_bytes(adb('exec-out','screencap','-p',binary=True))
    key('DPAD_CENTER',pause=2)   # the first app in the list
    stored=adb('shell','cat',prefs)
    after_shelf=re.search(r'name="shelf">([^<]*)<',stored)
    assert after_shelf and len([p for p in after_shelf.group(1).split(',') if p])==3,('The picked app did not join the shelf',stored[-400:])
    launch()
    after=[n.get('text') for n in mine(wait_for(lambda t:len([n for n in mine(t) if n.get('text')=='إضافة تطبيق'])==1,'row'))
           if n.get('clickable')=='true' and bounds(n)[1]>h*0.55 and bounds(n)[3]<h*0.78]
    assert len(after)==len(before)+1,(before,after)
    # shortcuts open their screens with the remote's keys
    for index,name in [(4,'UpdatesActivity'),(1,'MatchesScreenActivity'),(0,'XtreamActivity')]:   # Live TV asks to sign in when nobody has
        launch()
        for _ in range(5):key('DPAD_DOWN',0.3)
        for _ in range(8):key('DPAD_RIGHT',0.2)
        for _ in range(index):key('DPAD_LEFT',0.3)
        key('DPAD_CENTER',pause=1.5);front(name)
        adb('shell','input','keyevent','KEYCODE_BACK');time.sleep(1)
    crash=adb('logcat','-d','-b','crash')
    assert 'Process: '+PKG not in crash,crash
    with (OUT/'gold-home.log').open('a') as f:f.write('PASS: redesigned home at '+suffix+': Arabic RTL, six shortcuts in order, weather as a top chip, prayer card with real data, apps row with the + picker (installed apps list, pick joins the shelf), physical LEFT/DOWN/UP and OK to Updates, Matches and Live TV. Physical TV performance not measured.\n')
    print((OUT/'gold-home.log').read_text(),flush=True)
finally:
    adb('shell','am','force-stop',PKG);write(saved)
    if display_override:adb('shell','wm','size','reset')
