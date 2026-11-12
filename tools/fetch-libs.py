#!/usr/bin/env python3
"""Fetch the few libraries the app links (Media3 ExoPlayer and what it needs) into libs/*.jar.

build.sh calls this. Every file is checked against the SHA-1 its repository publishes next to it, and what was fetched is
listed in libs/MANIFEST.txt. Pure Java only: no .so files, so nothing here depends on the CPU (armeabi-v7a, arm64, x86).
"""
import hashlib
import io
import sys
import urllib.request
import zipfile
from pathlib import Path

GOOGLE = "https://dl.google.com/dl/android/maven2/"
CENTRAL = "https://repo1.maven.org/maven2/"
MEDIA3 = "1.4.1"
LIBS = [
    (GOOGLE, "androidx.media3", "media3-common", MEDIA3, "aar"),
    (GOOGLE, "androidx.media3", "media3-container", MEDIA3, "aar"),
    (GOOGLE, "androidx.media3", "media3-database", MEDIA3, "aar"),
    (GOOGLE, "androidx.media3", "media3-datasource", MEDIA3, "aar"),
    (GOOGLE, "androidx.media3", "media3-decoder", MEDIA3, "aar"),
    (GOOGLE, "androidx.media3", "media3-extractor", MEDIA3, "aar"),
    (GOOGLE, "androidx.media3", "media3-exoplayer", MEDIA3, "aar"),
    (GOOGLE, "androidx.media3", "media3-exoplayer-hls", MEDIA3, "aar"),
    (GOOGLE, "androidx.annotation", "annotation-jvm", "1.8.0", "jar"),
    (CENTRAL, "com.google.guava", "guava", "33.0.0-android", "jar"),
    (CENTRAL, "com.google.guava", "failureaccess", "1.0.2", "jar"),
]

def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "ouswis-build"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()

def main():
    out = Path(__file__).resolve().parent.parent / "libs"
    out.mkdir(exist_ok=True)
    manifest = []
    for base, group, artifact, version, kind in LIBS:
        target = out / (artifact + ".jar")
        stem = f"{base}{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}"
        if target.exists() and target.stat().st_size > 0:
            manifest.append(f"{artifact}-{version} (cached)")
            continue
        data = get(stem + "." + kind)
        want = get(stem + "." + kind + ".sha1").decode().split()[0].strip()
        got = hashlib.sha1(data).hexdigest()
        if want != got:
            sys.exit(f"{artifact}: SHA-1 mismatch (repository {want}, downloaded {got})")
        if kind == "aar":
            with zipfile.ZipFile(io.BytesIO(data)) as z:
                names = z.namelist()
                if any(n.endswith(".so") for n in names):
                    sys.exit(f"{artifact}: contains native code; this build links pure Java only")
                data = z.read("classes.jar")
        target.write_bytes(data)
        manifest.append(f"{artifact}-{version} sha1={got}")
        print("fetched", artifact, version)
    (out / "MANIFEST.txt").write_text("\n".join(manifest) + "\n")

if __name__ == "__main__":
    main()
