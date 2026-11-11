#!/usr/bin/env python3
"""Repeat real DPAD input and retain emulator-only frame/memory evidence; no physical-TV claims."""
import os, re, subprocess, sys, time
from pathlib import Path
import xml.etree.ElementTree as ET
ADB=os.environ.get('ADB','adb');PKG='io.ouswis.tv.standalone';OUT=Path(sys.argv[1]);LABEL=sys.argv[2];OUT.mkdir(parents=True,exist_ok=True)
def adb(*args):return subprocess.check_output([ADB,*args],timeout=90).decode('utf-8','replace').replace('\r','')
def tree():
    for attempt in range(4):
        try:
            adb('shell','rm','-f','/data/local/tmp/remote-perf.xml');adb('shell','uiautomator','dump','/data/local/tmp/remote-perf.xml')
            return ET.fromstring(adb('shell','cat','/data/local/tmp/remote-perf.xml'))
        except (subprocess.CalledProcessError,ET.ParseError):
            if attempt==3:raise
            time.sleep(1)
def focused():
    return next(n.get('bounds') for n in tree().iter('node') if n.get('package')==PKG and n.get('focused')=='true' and n.get('clickable')=='true')
assert adb('shell','getprop','ro.kernel.qemu').strip()=='1'
first=focused();adb('shell','dumpsys','gfxinfo',PKG,'reset')
for _ in range(20):adb('shell','input','keyevent','KEYCODE_DPAD_RIGHT','KEYCODE_DPAD_LEFT')
assert focused()==first,'Rapid shelf navigation lost focus'
adb('shell','input','keyevent','KEYCODE_DPAD_DOWN')
menu=focused();assert menu!=first,'DOWN did not enter the existing section row'
for _ in range(20):adb('shell','input','keyevent','KEYCODE_DPAD_RIGHT','KEYCODE_DPAD_LEFT')
assert focused()==menu,'Rapid section navigation lost focus'
adb('shell','input','keyevent','KEYCODE_DPAD_UP')
assert focused()==first,'UP did not return to the shelf'
# The first seeded shelf app is the actual installed Android Settings app.
adb('shell','input','keyevent','KEYCODE_DPAD_CENTER')
for attempt in range(12):
    state=adb('shell','dumpsys','activity','activities')
    if any('com.android.settings/' in line and ('mResumedActivity' in line or 'topResumedActivity' in line) for line in state.splitlines()):break
    time.sleep(.5)
else:raise AssertionError('OK did not launch the real installed shelf app')
adb('shell','input','keyevent','KEYCODE_BACK')
for attempt in range(12):
    state=adb('shell','dumpsys','activity','activities')
    if any(PKG+'/.HomeActivity' in line and ('mResumedActivity' in line or 'topResumedActivity' in line) for line in state.splitlines()):break
    time.sleep(.5)
else:raise AssertionError('Back did not return from the real installed app')
assert focused()==first,'Return from the app lost Home focus'
gfx=adb('shell','dumpsys','gfxinfo',PKG,'framestats');memory=adb('shell','dumpsys','meminfo',PKG)
(OUT/(LABEL+'-remote-frames.txt')).write_text(gfx);(OUT/(LABEL+'-remote-memory.txt')).write_text(memory)
assert 'Process: '+PKG not in adb('logcat','-d','-b','crash')
pss=re.findall(r'TOTAL PSS:\s*(\d+)',memory)
assert not pss or sum(map(int,pss))<512*1024,'Unexpected excessive emulator PSS'
with (OUT/'remote-performance.log').open('a') as f:
    f.write('PASS: '+LABEL+': 80 physical LEFT/RIGHT inputs, DOWN/UP, OK opens the real installed app, Back returns, stable focus, no app crash; PSS KiB '+str(pss)+'. Raw frame/memory evidence attached. Software emulator data, not a MECOOL frame-rate measurement.\n')
print((OUT/'remote-performance.log').read_text(),flush=True)
