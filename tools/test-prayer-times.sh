#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
jar="${OUSWIS_JSON_JAR:-out/test-deps/json-20240303.jar}"
[ -f "$jar" ] || { echo 'Run tools/test-matches-feed.sh first to prepare the test-only JSON dependency.'; exit 1; }
echo "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed  $jar" | sha256sum --check --status
mkdir -p out/prayer-tests
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -cp "out/classes:$jar" -d out/prayer-tests tests/PrayerScheduleTest.java
python3 tools/run-java-network-test.py api.aladhan.com "out/classes:out/prayer-tests:$jar" io.ouswis.tv.standalone.PrayerScheduleTest "$@"
