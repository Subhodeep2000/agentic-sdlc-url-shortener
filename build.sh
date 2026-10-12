#!/usr/bin/env bash
# Compile everything and (optionally) run the test suite. No external dependencies required: JDK 21 only.
# Usage: ./build.sh [test]
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out && mkdir -p out
javac --release 21 -Xlint:all -d out $(find src/main src/test -name '*.java')
echo "Compiled OK -> out/"
if [[ "${1:-}" == "test" ]]; then
  java -cp out com.acme.testkit.TestRunner out
fi
