#!/usr/bin/env python3
"""Compare the actual official 10149 APK with its update on a disposable 4K Android 14 emulator."""
import hashlib, json, os, re, subprocess, sys, time, zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
from PIL import Image
from io import BytesIO

OLD=Path(sys.argv[1]).resolve();NEW=Path(sys.argv[2]).resolve();OUT=Path(sys.argv[3]);OUT.mkdir(parents=True,exist_ok=True)
ADB=os.environ.get('ADB','adb');PKG='io.ouswis.tv.standalone';ROOT='/data/user/0/'+PKG
PIN='2abd42e4c7b43fda8f2ed08d039eaae3946894506d18104cc5c62033eaeab779'
def adb(*args,binary=False):
    r=subprocess.run([ADB,*args],capture_output=True,check=True,timeout=90)
    return r.stdout if binary else r.stdout.decode('utf-8','replace').replace('\r','')
def note(t):
    print(t,flush=True)
    with (OUT/'official-visual.log').open('a') as f:f.write(t+'\n')
def ui():
    for attempt in range(4):
        try:
            adb('shell','rm','-f','/data/local/tmp/official-visual.xml')
            adb('shell','uiautomator','dump','/data/local/tmp/official-visual.xml')
            return ET.fromstring(adb('shell','cat','/data/local/tmp/official-visual.xml'))
        except (subprocess.CalledProcessError,ET.ParseError):
            if attempt==3:raise
            time.sleep(1)
def info():
    t=adb('shell','dumpsys','package',PKG)
    return [re.search(p,t)[1] for p in (r'(?:userId|appId)=(\d+)',r'firstInstallTime=([^\n]+)',r'versionCode=(\d+)')]
def capture(name,redesigned=False):
    adb('shell','am','start','-W','-n',PKG+'/.HomeActivity')
    for attempt in range(20):
        tree=ui();text=ET.tostring(tree,encoding='unicode')
        if 'المتبقي' in text and (('°' in text) if redesigned else ('°C' in text or '°F' in text)):break
        time.sleep(1)
    else:raise AssertionError('Expected real prayer/weather data before visual comparison')
    png=adb('exec-out','screencap','-p',binary=True)
    assert Image.open(BytesIO(png)).size==(3840,2160),'A real native 4K screenshot is required'
    (OUT/(name+'.png')).write_bytes(png);(OUT/(name+'.xml')).write_bytes(ET.tostring(tree))
    nodes=[(n.get('class'),n.get('text',''),n.get('content-desc',''),n.get('bounds')) for n in tree.iter('node') if n.get('package')==PKG and n.get('clickable')=='true']
    assert len(nodes)>=10,nodes
    if redesigned:
        labels=[(n[1] or n[2]).strip() for n in nodes]
        for shortcut in ('البث المباشر','المباريات','كل التطبيقات','الأخيرة','التحديثات','الإعدادات'):
            assert any(l.strip()==shortcut for l in labels),('Missing shortcut',shortcut)
        assert any('مواقيت الصلاة' in l for l in labels),'The prayer card must be a control on the new home'
        assert any(l.startswith('الطقس') for l in labels),'The weather chip must be a control on the new home'
        assert any('إضافة تطبيق' in l for l in labels),'The + tile must close the apps row'
        return {'controls':nodes}
    widgets=[n.get('bounds') for n in tree.iter('node') if n.get('package')==PKG and n.get('clickable')!='true' and ('مواقيت الصلاة' in (n.get('text','')+n.get('content-desc','')) or 'الرطوبة' in (n.get('text','')+n.get('content-desc','')))]
    assert len(widgets)==2,widgets
    return {'controls':nodes,'widgets':widgets}
sdk=Path(os.environ['ANDROID_HOME'])/'build-tools'/'34.0.0'
badging=subprocess.check_output([str(sdk/'aapt2'),'dump','badging',str(OLD)],text=True)
baseline_code=re.search(r"^package: name='io.ouswis.tv.standalone' versionCode='(\d+)' versionName='1.0'",badging,re.M)[1]
assert int(baseline_code)>=10149
if baseline_code=='10149':assert hashlib.sha256(OLD.read_bytes()).hexdigest()==PIN,'Baseline differs from official build #72'
subprocess.run([sys.executable,'tools/verify-release-apk.py',str(OLD)],check=True)
with zipfile.ZipFile(OLD) as old,zipfile.ZipFile(NEW) as new:
    names={n for n in old.namelist() if n.startswith('assets/')}
    # The owner replaced the wallpaper (Nov 12: ouswis_bg_1080p.webp, with a 4K copy) and asked for the old flag/gold wallpaper
    # and the gold frame to go; every other asset must stay byte-identical.
    ADDED={'assets/fonts/Tajawal-OFL.txt','assets/backgrounds/ouswis_bg_1080p.webp','assets/backgrounds/ouswis_bg_4k.webp','assets/backgrounds/ouswis_bg_inner_1080p.webp'}
    REMOVED={'assets/themes/gold-sunset.png','assets/themes/gold-frame.png'}
    assert (names-REMOVED)|ADDED=={n for n in new.namelist() if n.startswith('assets/')},sorted(names^{n for n in new.namelist() if n.startswith('assets/')})
    for n in names-REMOVED:assert old.read(n)==new.read(n),'Asset changed: '+n
    assert not any(n.startswith('lib/') and n.endswith('.so') for n in new.namelist()),'Recheck native ABI coverage'
note('PASS: all official assets, wallpaper and icons are byte-identical. No native .so/ABI restriction: Java/Dex APK supports armeabi-v7a without a separate native build.')
assert adb('shell','getprop','ro.kernel.qemu').strip()=='1'
assert adb('shell','getprop','ro.build.version.sdk').strip()=='34'
assert 'Physical size: 3840x2160' in adb('shell','wm','size')
adb('root');adb('wait-for-device');adb('install','--no-streaming',str(OLD))
before=info();assert before[2]==baseline_code,before
uid=before[0]
adb('shell','am','force-stop',PKG)
installed=adb('shell','pm','list','packages')
shelf=[p for p in ('com.android.settings','com.android.calendar','com.android.deskclock','com.android.contacts','com.android.gallery3d','com.android.documentsui') if 'package:'+p+'\n' in installed]
assert len(shelf)>=2
prefs=ET.Element('map')
for key,value in {'language':'ar','topContent':'none','city':'جدة','latitude':'21.4858','longitude':'39.1925','shelf':','.join(shelf),'temperature':'C'}.items():ET.SubElement(prefs,'string',{'name':key}).text=value
for key in ('androidapps','weather','prayers'):ET.SubElement(prefs,'boolean',{'name':key,'value':'true'})
adb('shell','mkdir','-p',ROOT+'/shared_prefs',ROOT+'/files')
for path,raw in ((ROOT+'/shared_prefs/settings.xml',ET.tostring(prefs,encoding='utf-8')),(ROOT+'/shared_prefs/XtreamActivity.xml',b'<map/>'),(ROOT+'/files/official-upgrade-probe',b'official-10149-data-preserved')):
    subprocess.run([ADB,'shell','cat > '+path],input=raw,check=True,timeout=30)
adb('shell','chown','-R',uid+':'+uid,ROOT+'/shared_prefs',ROOT+'/files')
original=capture('official-baseline-home-3840x2160')
adb('shell','am','force-stop',PKG)
settings_hash=hashlib.sha256(adb('shell','cat',ROOT+'/shared_prefs/settings.xml',binary=True)).hexdigest()
adb('install','--no-streaming','-r',str(NEW))
after=info();assert before[:2]==after[:2] and int(after[2])>int(baseline_code),(before,after)
assert hashlib.sha256(adb('shell','cat',ROOT+'/shared_prefs/settings.xml',binary=True)).hexdigest()==settings_hash
assert adb('shell','cat',ROOT+'/files/official-upgrade-probe')=='official-10149-data-preserved'
assert adb('shell','cat',ROOT+'/shared_prefs/XtreamActivity.xml').strip()=='<map/>'
note('PASS: actual published '+baseline_code+' -> '+after[2]+': UID, first install, Home settings, private probe and account preference file retained; no uninstall or data clear.')
updated=capture('official-polished-home-3840x2160',redesigned=True)
(OUT/'official-layout-comparison.json').write_text(json.dumps({'before':original,'after':updated},ensure_ascii=False,indent=2))
note('PASS: the redesign replaced the home layout on purpose; the update keeps the signer, UID, first-install time, settings and private data, and the new home exposes its six shortcuts, prayer card, weather chip and the apps row with its + tile at 3840x2160. Live clock/weather/prayer values are not frozen.')
adb('shell','am','force-stop',PKG)
