#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
jar="${OUSWIS_JSON_JAR:-out/test-deps/json-20240303.jar}"
mkdir -p out/enhancement-tests
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -cp "$jar" -d out/enhancement-tests \
  app/src/io/ouswis/tv/standalone/Spring.java app/src/io/ouswis/tv/standalone/PrayerSchedule.java \
  app/src/io/ouswis/tv/standalone/PrayerCountdown.java app/src/io/ouswis/tv/standalone/EnvironmentReading.java tests/HomeEnhancementsTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "out/enhancement-tests:$jar" io.ouswis.tv.standalone.HomeEnhancementsTest
