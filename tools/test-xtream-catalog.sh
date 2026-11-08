#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
jar="${OUSWIS_JSON_JAR:-out/test-deps/json-20240303.jar}"
if [ ! -f "$jar" ]; then
  mkdir -p "$(dirname "$jar")"
  curl --fail --location --silent --show-error https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar --output "$jar"
fi
echo "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed  $jar" | sha256sum --check --status
mkdir -p out/catalog-tests
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -cp "$jar" -d out/catalog-tests \
  app/src/io/ouswis/tv/standalone/XtreamCatalog.java tests/XtreamCatalogTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "out/catalog-tests:$jar" io.ouswis.tv.standalone.XtreamCatalogTest
