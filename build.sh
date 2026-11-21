#!/usr/bin/env bash
# Compiles the service, tests and benchmarks with javac (JDK 21+). The only third-party
# dependency is Guice (see pom.xml); its jars live in lib/ and are fetched once with Maven.
set -euo pipefail
cd "$(dirname "$0")"
[ -n "$(ls lib/*.jar 2>/dev/null)" ] || ./scripts/fetch-deps.sh
CP="lib/*"
rm -rf out
find src/main -name '*.java' > out.sources
javac --release 21 -cp "$CP" -d out/main @out.sources
javac --release 21 -cp "out/main:$CP" -d out/test src/test/java/dev/pointledger/TestMain.java
javac --release 21 -cp "out/main:$CP" -d out/bench src/bench/java/dev/pointledger/Bench.java
rm out.sources
echo "built into out/"
