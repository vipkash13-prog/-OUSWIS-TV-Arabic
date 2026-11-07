#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p out/video-fit-tests
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -d out/video-fit-tests \
  app/src/io/freemytv/VideoFit.java tests/VideoFitTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/video-fit-tests io.freemytv.VideoFitTest
