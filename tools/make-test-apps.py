#!/usr/bin/env python3
"""Builds four tiny code-less test apps for the All Apps card test, into the folder given:
  card.lightbanner  a 16:9 banner that is almost white (like Netflix's)
  card.squarebanner a square banner with a magenta frame (must be shown whole, not cropped)
  card.whiteicon    an adaptive icon: white background, orange foreground, no banner
  card.wideicon     a plain (legacy) icon twice as wide as it is high, no banner
Each has a LEANBACK_LAUNCHER activity that nothing ever starts. Needs ANDROID_HOME (build-tools 34.0.0, android-34) and PIL."""
import os
import shutil
import subprocess
import sys
from pathlib import Path
from PIL import Image, ImageDraw

SDK = Path(os.environ['ANDROID_HOME'])
BT = SDK / 'build-tools' / '34.0.0'
JAR = SDK / 'platforms' / 'android-34' / 'android.jar'
out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
work = out / 'work'; shutil.rmtree(work, ignore_errors=True)
ks = work / 'test.keystore'; work.mkdir(parents=True)
subprocess.run(['keytool', '-genkeypair', '-keystore', str(ks), '-storepass', 'android', '-keypass', 'android', '-alias', 'k', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650', '-dname', 'CN=test'], check=True, capture_output=True)

def png(path, size, draw):
    img = Image.new('RGBA', size, (0, 0, 0, 0)); draw(ImageDraw.Draw(img), size); path.parent.mkdir(parents=True, exist_ok=True); img.save(path)

def app(name, label, banner=None, icon=None, adaptive=False):
    d = work / name; res = d / 'res'
    attrs = ' android:label="%s"' % label
    if banner:
        png(res / 'drawable-xhdpi' / 'banner.png', banner[0], banner[1]); attrs += ' android:banner="@drawable/banner"'
    if adaptive:
        png(res / 'drawable-xxhdpi' / 'fg.png', (324, 324), lambda g, s: g.ellipse((60, 60, 264, 264), fill=(255, 128, 0, 255)))
        (res / 'values').mkdir(parents=True, exist_ok=True)
        (res / 'values' / 'c.xml').write_text('<resources><color name="white">#FFFFFFFF</color></resources>')
        (res / 'mipmap-anydpi-v26').mkdir(parents=True)
        (res / 'mipmap-anydpi-v26' / 'ic.xml').write_text('<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@color/white"/><foreground android:drawable="@drawable/fg"/></adaptive-icon>')
        attrs += ' android:icon="@mipmap/ic"'
    elif icon:
        png(res / 'drawable-xhdpi' / 'ic.png', icon[0], icon[1]); attrs += ' android:icon="@drawable/ic"'
    (d / 'AndroidManifest.xml').write_text(
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="%s"><application android:hasCode="false"%s>'
        '<activity android:name=".Nothing" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/>'
        '<category android:name="android.intent.category.LEANBACK_LAUNCHER"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity>'
        '</application></manifest>' % (name, attrs))
    subprocess.run([str(BT / 'aapt2'), 'compile', '--dir', str(res), '-o', str(d / 'res.zip')], check=True) if res.exists() else None
    link = [str(BT / 'aapt2'), 'link', '-o', str(d / 'u.apk'), '-I', str(JAR), '--manifest', str(d / 'AndroidManifest.xml'), '--min-sdk-version', '26', '--target-sdk-version', '33']
    if res.exists(): link.append(str(d / 'res.zip'))
    subprocess.run(link, check=True)
    subprocess.run([str(BT / 'zipalign'), '-f', '4', str(d / 'u.apk'), str(d / 'a.apk')], check=True)
    subprocess.run([str(BT / 'apksigner'), 'sign', '--ks', str(ks), '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', str(out / (name + '.apk')), str(d / 'a.apk')], check=True)

app('card.lightbanner', '0A Light banner', banner=((640, 360), lambda g, s: (g.rectangle((0, 0, s[0], s[1]), fill=(250, 250, 250, 255)), g.rectangle((250, 130, 390, 230), fill=(220, 0, 0, 255)))))
app('card.squarebanner', '0B Square banner', banner=((360, 360), lambda g, s: (g.rectangle((0, 0, 360, 360), fill=(255, 0, 255, 255)), g.rectangle((24, 24, 336, 336), fill=(0, 160, 0, 255)))))
app('card.whiteicon', '0C White icon', adaptive=True)
app('card.wideicon', '0D Wide icon', icon=((256, 128), lambda g, s: g.rectangle((0, 0, 255, 127), fill=(0, 120, 255, 255))))
shutil.rmtree(work)
print('built', sorted(p.name for p in out.glob('*.apk')))
