#!/usr/bin/env python3
"""Drive Downloads harness scenarios via the test-app control API."""

from __future__ import annotations

import json
import sys
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:17321"

SCENARIOS = [
    ("url_small", "urlSmall", 60),
    ("page", "page", 60),
    ("inline", "inline", 30),
    ("cancel", "cancel", 30),
    ("pause", "pause", 180),
    ("retry", "retry", 60),
    ("profile_cancel", "profile", 180),
]


def rpc(method: str, params: dict | None = None) -> dict:
    body = json.dumps({"method": method, "params": params or {}}).encode()
    req = urllib.request.Request(
        f"{BASE}/rpc",
        data=body,
        headers={"content-type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = json.loads(resp.read().decode())
    if not data.get("ok"):
        raise RuntimeError(data.get("error") or data)
    return data.get("result") or {}


def get_state() -> dict:
    with urllib.request.urlopen(f"{BASE}/state", timeout=30) as resp:
        data = json.loads(resp.read().decode())
    if not data.get("ok"):
        raise RuntimeError(data.get("error") or data)
    return data.get("result") or {}


def health() -> dict:
    with urllib.request.urlopen(f"{BASE}/health", timeout=5) as resp:
        return json.loads(resp.read().decode())


def wait_verdict(key: str, timeout: float) -> tuple[str, str]:
    deadline = time.time() + timeout
    last = ("unknown", "")
    while time.time() < deadline:
        try:
            st = get_state()
        except Exception as exc:  # noqa: BLE001
            raise RuntimeError(f"state failed (app crash?): {exc}") from exc
        screen = st.get("screen") or {}
        verdicts = screen.get("verdicts") or {}
        details = screen.get("details") or {}
        v = verdicts.get(key, "unknown")
        d = details.get(key, "")
        last = (v, d)
        if v in ("pass", "fail", "skip"):
            return last
        time.sleep(0.5)
    return last


def main() -> int:
    try:
        print("health:", health())
    except Exception as exc:  # noqa: BLE001
        print("FAIL: app not reachable:", exc, file=sys.stderr)
        return 2

    rpc("open_screen", {"id": "downloads"})
    time.sleep(1.0)
    rpc("invoke", {"action": "accept_mode_auto"})

    results: list[tuple[str, str, str]] = []
    for action, verdict_key, timeout in SCENARIOS:
        print(f"\n=== {action} ===", flush=True)
        try:
            rpc("invoke", {"action": "clear_list"})
            rpc("invoke", {"action": action})
            verdict, detail = wait_verdict(verdict_key, timeout)
            print(f"  {verdict_key}: {verdict} — {detail}", flush=True)
            results.append((action, verdict, detail))
            if verdict == "unknown":
                # dump state for debug
                print("  state:", json.dumps(get_state().get("screen"), indent=2)[:1500])
        except Exception as exc:  # noqa: BLE001
            print(f"  ERROR: {exc}", flush=True)
            results.append((action, "error", str(exc)))
            try:
                health()
            except Exception:
                print("  app appears dead", flush=True)
                break

    print("\n=== SUMMARY ===")
    failed = 0
    for action, verdict, detail in results:
        mark = "OK" if verdict in ("pass", "skip") else "FAIL"
        if mark == "FAIL":
            failed += 1
        print(f"  [{mark}] {action}: {verdict} — {detail}")

    try:
        path = rpc("screenshot", {"path": "/tmp/mwv-downloads-harness.png"}).get("path")
        print("screenshot:", path)
    except Exception as exc:  # noqa: BLE001
        print("screenshot failed:", exc)

    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
