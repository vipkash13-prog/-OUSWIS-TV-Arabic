#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p out/date-tests
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -d out/date-tests \
  app/src/io/ouswis/tv/standalone/SaudiDates.java tests/SaudiDatesTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/date-tests io.ouswis.tv.standalone.SaudiDatesTest
