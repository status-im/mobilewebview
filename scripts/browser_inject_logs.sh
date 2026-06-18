#!/usr/bin/env bash
# Capture Android WebView bridge + Ethereum provider injection diagnostics.
# Works when testing mobilewebview via status-desktop (FETCHCONTENT_SOURCE_DIR_MOBILEWEBVIEW).
#
# Usage:
#   ./scripts/browser_inject_logs.sh
#   ./scripts/browser_inject_logs.sh -c
#
# Reproduce in Status app: Browser → opensea.io → wait ~15s → try connect wallet → Ctrl+C.

set -euo pipefail

CLEAR=false
OUT="${TMPDIR:-/tmp}/mobilewebview-browser-inject-$(date +%Y%m%d-%H%M%S).log"

while [[ $# -gt 0 ]]; do
	case "$1" in
	-c | --clear) CLEAR=true; shift ;;
	-o | --output)
		OUT="$2"
		shift 2
		;;
	-h | --help)
		echo "Usage: $0 [-c] [-o FILE]"
		exit 0
		;;
	*) echo "unknown arg: $1" >&2; exit 1 ;;
	esac
done

if ! command -v adb >/dev/null 2>&1; then
	echo "adb not found" >&2
	exit 1
fi

FILTER='inject-diag|StatusInject|Ethereum Wrapper|Ethereum Injector|EIP-6963|QtBridge|MobileWebView'

echo "mobilewebview injection log capture"
echo "Output: $OUT"
echo "Filter: $FILTER"
echo "Build tip: export FETCHCONTENT_SOURCE_DIR_MOBILEWEBVIEW=$HOME/status/mobilewebview"
echo "Press Ctrl+C when done."
echo ""

if [[ "$CLEAR" == true ]]; then
	adb logcat -c
fi

{
	echo "# mobilewebview-browser-inject $(date -Iseconds)"
	echo "# repo: $(cd "$(dirname "$0")/.." && pwd)"
	echo "# filter: $FILTER"
	echo ""
	adb logcat -v time MobileWebView:* chromium:W 2>&1 | grep --line-buffered -E -- "$FILTER"
} | tee "$OUT"
