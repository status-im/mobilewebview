#!/usr/bin/env bash
# Compile and run JVM unit tests for mobilewebview Android helpers (pure Java, no android.jar).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/java-tests"
ANDROID_SRC="$ROOT/mobilewebview/android/src/org/mobilewebview"
ANDROID_TEST="$ROOT/mobilewebview/android/tests/org/mobilewebview"

mkdir -p "$OUT"

SOURCES=(
  "$ANDROID_SRC/OriginUtils.java"
  "$ANDROID_SRC/BridgeScriptBuilder.java"
  "$ANDROID_SRC/PendingActionQueue.java"
  "$ANDROID_SRC/WebViewUrlPolicy.java"
  "$ANDROID_TEST/OriginUtilsTest.java"
  "$ANDROID_TEST/BridgeScriptBuilderTest.java"
  "$ANDROID_TEST/MobileWebViewPendingActionsTest.java"
  "$ANDROID_TEST/WebViewUrlPolicyTest.java"
)

echo "Compiling Java tests..."
javac -d "$OUT" "${SOURCES[@]}"

TESTS=(
  org.mobilewebview.OriginUtilsTest
  org.mobilewebview.BridgeScriptBuilderTest
  org.mobilewebview.MobileWebViewPendingActionsTest
  org.mobilewebview.WebViewUrlPolicyTest
)

for test in "${TESTS[@]}"; do
  echo "Running $test..."
  java -cp "$OUT" "$test"
done

echo "All Java tests passed."
