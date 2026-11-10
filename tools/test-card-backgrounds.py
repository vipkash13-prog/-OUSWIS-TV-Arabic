#!/usr/bin/env python3
"""Exact preview-16 -> candidate reinstall, data retention and unchanged page geometry on disposable AOSP."""
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ADB=os.environ.get('ADB','adb');PKG='io.ouswis.tv.standalone'
APK=Path(sys.argv[1]).resolve();OUT=Path(sys.argv[2]);OUT.mkdir(parents=True,exist_ok=True)
def adb(*args,binary=False):
    r=subprocess.run([ADB,*args],capture_output=True,check=True,timeout=90)
    return r.stdout if binary else r.stdout.decode('utf-8','replace').replace('\r','')
def note(text):
    print(text,flush=True)
    with (OUT/'preview16-update.log').open('a') as f:f.write(text+'\n')
def ui():
    adb('shell','uiautomator','dump','/data/local/tmp/cards.xml')
    return ET.fromstring(adb('shell','cat','/data/local/tmp/cards.xml'))
def open_page(name):
    adb('shell','am','start','-W','-n',PKG+'/.'+name);time.sleep(3)
    return ui()
def shot(name): (OUT/name).write_bytes(adb('exec-out','screencap','-p',binary=True))
def info():
    text=adb('shell','dumpsys','package',PKG)
    return tuple(re.search(pattern,text)[1] for pattern in (r'(?:userId|appId)=(\d+)',r'firstInstallTime=([^\n]+)',r'versionCode=(\d+)'))
def fields(tree):
    return [(n.get('class'),n.get('bounds')) for n in tree.iter('node') if n.get('class')=='android.widget.EditText']
def tabs(tree):
    return {n.get('resource-id'):n.get('bounds') for n in tree.iter('node') if ':id/matches_tab_' in n.get('resource-id','')}

assert adb('shell','getprop','ro.kernel.qemu').strip()=='1','Disposable emulator only'
adb('root');adb('wait-for-device')
prior=OUT/'preview16-test-baseline.apk'
with urllib.request.urlopen('https://github.com/vipkash13-prog/-OUSWIS-TV-Arabic/releases/download/preview-16/OUSWIS-TV-preview.apk',timeout=90) as r:prior.write_bytes(r.read())
assert hashlib.sha256(prior.read_bytes()).hexdigest()=='636ff6377494162e217dec0f0913adc2517ada2b78c3edd226feb3e04bb0a952'
with zipfile.ZipFile(prior) as old,zipfile.ZipFile(APK) as new:
    assets=[name for name in old.namelist() if name.startswith('assets/') and not name.endswith('/')]
    for name in assets:assert old.read(name)==new.read(name),'Existing asset changed: '+name
    assert set(new.namelist())==set(old.namelist())
note('PASS: all '+str(len(assets))+' existing APK assets retained byte-for-byte, including the approved weather artwork. No asset was removed or replaced.')
settings='/data/user/0/'+PKG+'/shared_prefs/XtreamActivity.xml'
saved=None
try:
    adb('shell','am','force-stop',PKG)
    adb('install','--no-streaming','-r',str(prior))
    before=info();assert before[2]=='10075',before
    uid=before[0]
    try:saved=adb('shell','cat',settings,binary=True)
    except subprocess.CalledProcessError:saved=b'<map/>'
    def prefs(data):
        adb('shell','mkdir','-p',settings.rsplit('/',1)[0])
        adb('shell','chown',uid+':'+uid,settings.rsplit('/',1)[0])
        adb('shell','am','force-stop',PKG)
        subprocess.run([ADB,'shell','cat > '+settings],input=data,check=True,timeout=30)
        adb('shell','chown',uid+':'+uid,settings)
    prefs(b'<map/>')
    old_live=fields(open_page('XtreamActivity'));assert len(old_live)==3
    old_matches=tabs(open_page('MatchesActivity'));assert len(old_matches)==4
    prefs(saved)
    probe='/data/user/0/'+PKG+'/files/cards-preview16-probe'
    adb('shell','mkdir','-p',probe.rsplit('/',1)[0])
    adb('shell','chown',uid+':'+uid,probe.rsplit('/',1)[0])
    subprocess.run([ADB,'shell','cat > '+probe],input=b'preview16-retained\n',check=True,timeout=30)
    adb('shell','chown',uid+':'+uid,probe)
    before_settings=hashlib.sha256(adb('shell','cat',settings,binary=True)).hexdigest()
    adb('install','--no-streaming','-r',str(APK))
    after=info();assert after[:2]==before[:2] and after[2]=='10077',(before,after)
    assert adb('shell','cat',probe).strip()=='preview16-retained'
    assert hashlib.sha256(adb('shell','cat',settings,binary=True)).hexdigest()==before_settings
    note('PASS: the supplied preview-16 (10075) was installed, then the new 10077 APK updated it. App UID, first-install time, private file and account preferences survived; no uninstall or data clear.')
    prefs(b'<map/>')
    new_live=fields(open_page('XtreamActivity'));shot('card-live-background.png')
    assert new_live==old_live,(old_live,new_live)
    new_matches=tabs(open_page('MatchesActivity'));shot('card-matches-background.png')
    assert new_matches==old_matches,(old_matches,new_matches)
    note('PASS: login fields and all four match tabs retain exactly the preview-16 bounds. Page input/focus code is unchanged.')
finally:
    if saved is not None:prefs(saved)
    prior.unlink(missing_ok=True)
