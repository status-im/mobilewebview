#!/usr/bin/env bash
# Run all mobilewebview test suites: JS units + native C++/Qt (ctest) + Android JVM.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
export QTDIR="${QTDIR:-$HOME/Qt/6.11.0/macos}"

echo "==> JS unit tests (inline_download_interceptor)"
(
  cd "$ROOT/mobilewebview/tests/js"
  npm ci
  npm test
)

echo
echo "==> Native C++/Qt tests (macOS, ctest)"
make -C "$ROOT" test TARGET_OS=macos

echo
echo "==> Android JVM unit tests"
"$ROOT/scripts/run_java_android_tests.sh"

echo
echo "All test suites passed."
