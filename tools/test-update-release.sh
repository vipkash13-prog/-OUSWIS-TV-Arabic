#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p out/update-tests
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -d out/update-tests \
  app/src/io/ouswis/tv/standalone/UpdateRelease.java tests/UpdateReleaseTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/update-tests io.ouswis.tv.standalone.UpdateReleaseTest
