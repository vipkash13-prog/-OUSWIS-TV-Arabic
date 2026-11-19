#!/usr/bin/env bash
# Offline: the channel hints' page parsing and team-name matching (the app classes from ./build.sh are needed).
set -euo pipefail
cd "$(dirname "$0")/.."
jar="${OUSWIS_JSON_JAR:-out/test-deps/json-20240303.jar}"
if [ ! -f "$jar" ]; then
  mkdir -p "$(dirname "$jar")"
  curl --fail --location --silent --show-error https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar --output "$jar"
fi
mkdir -p out/channel-hints-tests
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 \
  -cp "out/classes:$jar:${ANDROID_HOME:?Android SDK required}/platforms/android-34/android.jar" \
  -d out/channel-hints-tests tests/ChannelHintsTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "out/channel-hints-tests:out/classes:$jar" io.ouswis.tv.standalone.ChannelHintsTest
echo "PASS: channel hints (pages, spellings, channel names)"
