#!/usr/bin/env bash
# Compiles the service, tests and benchmarks with plain javac (JDK 21+, no build tool needed).
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out
find src/main -name '*.java' > out.sources
javac --release 21 -d out/main @out.sources
javac --release 21 -cp out/main -d out/test src/test/java/dev/pointledger/TestMain.java
javac --release 21 -cp out/main -d out/bench src/bench/java/dev/pointledger/Bench.java
rm out.sources
echo "built into out/"
