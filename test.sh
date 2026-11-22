#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./build.sh >/dev/null
java -cp "out/main:out/test:lib/*" dev.pointledger.TestMain
