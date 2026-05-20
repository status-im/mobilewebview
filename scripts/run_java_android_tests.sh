#!/usr/bin/env bash
# Compile and run JVM unit tests for mobilewebview Android helpers (pure Java, no android.jar).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/java-tests"
ANDROID_SRC="$ROOT/mobilewebview/android/src/org/mobilewebview"
ANDROID_TEST="$ROOT/mobilewebview/android/tests/org/mobilewebview"
JVM_SHADOW="$ROOT/mobilewebview/android/tests/jvm"

mkdir -p "$OUT"

SOURCES=(
  "$JVM_SHADOW/android/content/Context.java"
  "$JVM_SHADOW/android/util/Log.java"
  "$JVM_SHADOW/android/webkit/ValueCallback.java"
  "$JVM_SHADOW/android/webkit/WebView.java"
  "$JVM_SHADOW/androidx/webkit/WebViewFeature.java"
  "$JVM_SHADOW/androidx/webkit/WebViewCompat.java"
  "$ANDROID_SRC/OriginUtils.java"
  "$ANDROID_SRC/BridgeScriptBuilder.java"
  "$ANDROID_SRC/PendingActionQueue.java"
  "$ANDROID_SRC/WebViewUrlPolicy.java"
  "$ANDROID_SRC/BridgeInjectorHost.java"
  "$ANDROID_SRC/BridgeState.java"
  "$ANDROID_SRC/ScriptInjectionPhase.java"
  "$ANDROID_SRC/BridgeScriptInjector.java"
  "$ANDROID_TEST/OriginUtilsTest.java"
  "$ANDROID_TEST/BridgeScriptBuilderTest.java"
  "$ANDROID_TEST/MobileWebViewPendingActionsTest.java"
  "$ANDROID_TEST/WebViewUrlPolicyTest.java"
  "$ANDROID_TEST/TestAssert.java"
  "$ANDROID_TEST/FakeBridgeInjectorHost.java"
  "$ANDROID_TEST/BridgeScriptInjectorTest.java"
)

echo "Compiling Java tests..."
javac -d "$OUT" "${SOURCES[@]}"

TESTS=(
  org.mobilewebview.OriginUtilsTest
  org.mobilewebview.BridgeScriptBuilderTest
  org.mobilewebview.MobileWebViewPendingActionsTest
  org.mobilewebview.WebViewUrlPolicyTest
  org.mobilewebview.BridgeScriptInjectorTest
)

for test in "${TESTS[@]}"; do
  echo "Running $test..."
  java -cp "$OUT" "$test"
done

echo "All Java tests passed."
