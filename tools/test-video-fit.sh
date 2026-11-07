#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p out/video-fit-tests
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -d out/video-fit-tests \
  app/src/io/ouswis/tv/standalone/VideoFit.java tests/VideoFitTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/video-fit-tests io.ouswis.tv.standalone.VideoFitTest
