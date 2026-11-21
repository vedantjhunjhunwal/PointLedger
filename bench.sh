#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./build.sh >/dev/null
java -Xmx3g -cp "out/main:out/bench:lib/*" dev.pointledger.Bench
