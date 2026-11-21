#!/usr/bin/env bash
# Downloads the runtime jars declared in pom.xml (Guice and its dependencies) into lib/.
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -q -B dependency:copy-dependencies -DoutputDirectory=lib -DincludeScope=runtime
echo "jars in lib/: $(ls lib | tr '\n' ' ')"
