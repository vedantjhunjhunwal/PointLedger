#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./build.sh >/dev/null
[ -d dashboard/dist ] || (cd dashboard && npm ci && npm run build)
java -cp out/main dev.pointledger.App
