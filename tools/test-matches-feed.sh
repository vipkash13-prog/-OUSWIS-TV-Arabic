#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
jar="${OUSWIS_JSON_JAR:-out/test-deps/json-20240303.jar}"
if [ ! -f "$jar" ]; then
  mkdir -p "$(dirname "$jar")"
  curl --fail --location --silent --show-error https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar --output "$jar"
fi
echo "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed  $jar" | sha256sum --check --status
mkdir -p out/feed-tests
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 \
  -cp "out/classes:$jar:${ANDROID_HOME:?Android SDK required}/platforms/android-34/android.jar" \
  -d out/feed-tests tests/MatchesFeedTest.java
if ! "${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "out/classes:out/feed-tests:$jar" io.ouswis.tv.standalone.MatchesFeedTest "$@"; then
  if [ "${1:-}" = --live ]; then
    python3 - <<'PY'
import datetime,urllib.request,urllib.error,gzip
month=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m')
url='https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/scoreboard?dates='+month+'&limit=1000'
try:
    with urllib.request.urlopen(url,timeout=20) as r: print('HTTP diagnostic: urllib status',r.status)
except urllib.error.HTTPError as e:
    body=e.read(4096)
    if body[:2]==b'\x1f\x8b':
        try:body=gzip.decompress(body)
        except (OSError,EOFError):pass
    print('HTTP diagnostic:',e.code,'server=',e.headers.get('Server'),'proxy_reason=',e.headers.get('X-Mitmproxy-Blocked-Reason'))
    print(body.decode('utf-8','replace')[:300])
PY
  fi
  exit 1
fi
