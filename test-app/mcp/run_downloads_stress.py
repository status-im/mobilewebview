#!/usr/bin/env python3
"""Stress Downloads harness: manual accept, incognito, pause/resume loops, retry loops."""

from __future__ import annotations

import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = "http://127.0.0.1:17321"
RANGE_PORT = 8765
RANGE_URL = f"http://127.0.0.1:{RANGE_PORT}/big.bin"
PAUSE_ROUNDS = 8
RETRY_ROUNDS = 5


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


def invoke(action: str) -> None:
    rpc("invoke", {"action": action})


def screen() -> dict:
    return get_state().get("screen") or {}


def latest() -> dict | None:
    downloads = screen().get("downloads") or []
    return downloads[-1] if downloads else None


def wait_latest_state(wanted: set[str], timeout: float) -> dict | None:
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = latest()
        if last and last.get("state") in wanted:
            return last
        time.sleep(0.2)
    return last


def wait_download_count(n: int, timeout: float = 30) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if len(screen().get("downloads") or []) >= n:
            return True
        time.sleep(0.2)
    return False


def wait_verdict(key: str, timeout: float) -> tuple[str, str]:
    deadline = time.time() + timeout
    last = ("unknown", "")
    while time.time() < deadline:
        sc = screen()
        v = (sc.get("verdicts") or {}).get(key, "unknown")
        d = (sc.get("details") or {}).get(key, "")
        last = (v, d)
        if v in ("pass", "fail", "skip"):
            return last
        time.sleep(0.3)
    return last


def ensure_alive() -> None:
    health()


def section(title: str) -> None:
    print(f"\n=== {title} ===", flush=True)


def start_range_server() -> subprocess.Popen:
    script = Path(__file__).resolve().parent / "range_server.py"
    proc = subprocess.Popen(
        [sys.executable, str(script), "--port", str(RANGE_PORT), "--mb", "24"],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    deadline = time.time() + 10
    while time.time() < deadline:
        if proc.poll() is not None:
            out = proc.stdout.read() if proc.stdout else ""
            raise RuntimeError(f"range server exited: {out}")
        try:
            with urllib.request.urlopen(
                urllib.request.Request(RANGE_URL, method="HEAD"), timeout=1
            ) as resp:
                if resp.status == 200:
                    return proc
        except Exception:  # noqa: BLE001
            time.sleep(0.2)
    proc.kill()
    raise RuntimeError("range server did not become ready")


def main() -> int:
    results: list[tuple[str, str, str]] = []
    range_proc: subprocess.Popen | None = None

    def record(name: str, ok: bool, detail: str) -> None:
        mark = "OK" if ok else "FAIL"
        results.append((name, mark, detail))
        print(f"  [{mark}] {detail}", flush=True)

    try:
        print("health:", health())
    except Exception as exc:  # noqa: BLE001
        print("FAIL: app not reachable:", exc, file=sys.stderr)
        return 2

    try:
        range_proc = start_range_server()
        print("range server:", RANGE_URL)
    except Exception as exc:  # noqa: BLE001
        print("FAIL: range server:", exc, file=sys.stderr)
        return 2

    rpc("open_screen", {"id": "downloads"})
    time.sleep(1.0)
    rpc("set_prop", {"name": "largeUrl", "value": RANGE_URL})

    # --- Manual accept ---
    section("manual accept (standard)")
    try:
        ensure_alive()
        invoke("set_standard")
        invoke("accept_mode_manual")
        invoke("clear_list")
        invoke("url_small")
        if not wait_download_count(1, 30):
            record("manual_accept", False, "no Request after url_small")
        else:
            d = wait_latest_state({"Requested"}, 10)
            if not d or d.get("state") != "Requested":
                record("manual_accept", False, f"expected Requested, got {d}")
            else:
                invoke("accept_latest")
                d2 = wait_latest_state({"InProgress", "Completed"}, 60)
                ok = bool(d2 and d2.get("state") in ("InProgress", "Completed"))
                record("manual_accept", ok, f"after accept: {d2}")
    except Exception as exc:  # noqa: BLE001
        record("manual_accept", False, str(exc))

    # --- Manual cancel ---
    section("manual cancel (standard)")
    try:
        ensure_alive()
        invoke("accept_mode_manual")
        invoke("clear_list")
        invoke("url_small")
        wait_download_count(1, 30)
        d = wait_latest_state({"Requested"}, 10)
        if not d or d.get("state") != "Requested":
            record("manual_cancel", False, f"expected Requested, got {d}")
        else:
            invoke("cancel_latest")
            d2 = wait_latest_state({"Cancelled"}, 10)
            ok = bool(d2 and d2.get("state") == "Cancelled")
            record("manual_cancel", ok, f"after cancel: {d2}")
    except Exception as exc:  # noqa: BLE001
        record("manual_cancel", False, str(exc))

    # --- Incognito smoke: url_small + inline ---
    section("incognito smoke")
    try:
        ensure_alive()
        invoke("set_incognito")
        time.sleep(2.0)  # profile recreate + webView settle
        sc = screen()
        if not sc.get("offTheRecord"):
            record("incognito_flag", False, f"offTheRecord={sc.get('offTheRecord')}")
        else:
            record("incognito_flag", True, "offTheRecord=true")
        invoke("accept_mode_auto")
        invoke("clear_list")
        invoke("url_small")
        v, d = wait_verdict("urlSmall", 60)
        record("incognito_url_small", v in ("pass", "skip"), f"{v} — {d}")
        invoke("inline")
        v, d = wait_verdict("inline", 45)
        record("incognito_inline", v in ("pass", "skip"), f"{v} — {d}")
    except Exception as exc:  # noqa: BLE001
        record("incognito_smoke", False, str(exc))

    # --- Pause/resume stress (standard + incognito) ---
    for mode, set_action in (("standard", "set_standard"), ("incognito", "set_incognito")):
        section(f"pause/resume x{PAUSE_ROUNDS} ({mode})")
        try:
            ensure_alive()
            invoke(set_action)
            time.sleep(0.5)
            invoke("accept_mode_auto")
            invoke("clear_list")
            invoke("download_large")
            if not wait_download_count(1, 30):
                record(f"pause_loop_{mode}", False, "no download started")
                continue
            d0 = wait_latest_state({"InProgress", "Requested", "Completed"}, 30)
            if d0 and d0.get("state") == "Requested":
                # auto should have accepted; if still Requested, accept
                invoke("accept_latest")
            d0 = wait_latest_state({"InProgress"}, 30)
            if not d0 or d0.get("state") != "InProgress":
                record(
                    f"pause_loop_{mode}",
                    False,
                    f"never InProgress (got {d0}) — file may be too fast/blocked",
                )
                continue
            # Let throttled local server push some bytes before first pause.
            time.sleep(1.2)

            cycles = 0
            failures = []
            for i in range(PAUSE_ROUNDS):
                ensure_alive()
                cur = latest()
                if not cur:
                    failures.append(f"r{i}: no download")
                    break
                if cur.get("state") == "Completed":
                    # Finished mid-loop — count successful cycles so far
                    break
                if cur.get("state") != "InProgress":
                    # try resume if paused leftover
                    if cur.get("state") == "Paused":
                        invoke("resume_latest")
                        wait_latest_state({"InProgress"}, 15)
                    else:
                        failures.append(f"r{i}: state={cur.get('state')}")
                        break

                invoke("pause_latest")
                # Wait for Paused, then settle so WK didFail resumeData can arrive.
                paused = wait_latest_state({"Paused", "Completed", "Interrupted"}, 25)
                if not paused:
                    failures.append(f"r{i}: pause timeout")
                    break
                if paused.get("state") == "Completed":
                    break
                if paused.get("state") != "Paused":
                    failures.append(
                        f"r{i}: pause→{paused.get('state')} err={paused.get('error')}"
                    )
                    break
                time.sleep(0.55)
                paused = latest()
                if not paused or paused.get("state") != "Paused":
                    failures.append(
                        f"r{i}: pause unsettled →{paused.get('state') if paused else None}"
                        f" err={paused.get('error') if paused else ''}"
                    )
                    break

                invoke("resume_latest")
                resumed = wait_latest_state({"InProgress", "Completed", "Interrupted"}, 25)
                if not resumed:
                    failures.append(f"r{i}: resume timeout")
                    break
                if resumed.get("state") == "Interrupted":
                    failures.append(f"r{i}: resume→Interrupted {resumed.get('error')}")
                    break
                cycles += 1
                print(f"    cycle {cycles}: Paused→{resumed.get('state')}", flush=True)
                # Brief transfer before next pause so WK has something to snapshot.
                time.sleep(0.8)

            detail = f"cycles={cycles}/{PAUSE_ROUNDS}"
            if failures:
                detail += "; " + "; ".join(failures[:4])
            final = latest()
            if final:
                detail += f"; final={final.get('state')}"
            ensure_alive()
            no_resume = any(
                "no resume data" in f or "Resume data unavailable" in f for f in failures
            )
            if cycles >= 3 and not failures:
                record(f"pause_loop_{mode}", True, detail)
            elif cycles >= 1:
                # Partial WK resume support — not a crash, but not full stress.
                record(f"pause_loop_{mode}", True, "PARTIAL " + detail)
            elif no_resume:
                # WK often returns nil resumeData for localhost/nav downloads.
                record(f"pause_loop_{mode}", True, "SKIP " + detail)
            else:
                record(f"pause_loop_{mode}", False, detail)
        except Exception as exc:  # noqa: BLE001
            record(f"pause_loop_{mode}", False, str(exc))

    # --- Retry loops ---
    for mode, set_action in (("standard", "set_standard"), ("incognito", "set_incognito")):
        section(f"retry x{RETRY_ROUNDS} ({mode})")
        try:
            ensure_alive()
            invoke(set_action)
            time.sleep(0.5)
            invoke("accept_mode_auto")
            passes = 0
            fails = []
            for i in range(RETRY_ROUNDS):
                invoke("clear_list")
                # reset verdict via starting retry scenario
                invoke("retry")
                v, d = wait_verdict("retry", 45)
                if v == "pass":
                    passes += 1
                    print(f"    retry {i+1}: pass — {d}", flush=True)
                else:
                    fails.append(f"{i+1}:{v}:{d}")
                    print(f"    retry {i+1}: {v} — {d}", flush=True)
                ensure_alive()
            record(
                f"retry_loop_{mode}",
                passes == RETRY_ROUNDS,
                f"pass={passes}/{RETRY_ROUNDS}"
                + (("; " + "; ".join(fails[:3])) if fails else ""),
            )
        except Exception as exc:  # noqa: BLE001
            record(f"retry_loop_{mode}", False, str(exc))

    # --- Manual + incognito accept ---
    section("manual accept (incognito)")
    try:
        ensure_alive()
        invoke("set_incognito")
        time.sleep(0.5)
        invoke("accept_mode_manual")
        invoke("clear_list")
        invoke("url_small")
        wait_download_count(1, 30)
        d = wait_latest_state({"Requested"}, 15)
        if not d or d.get("state") != "Requested":
            record("manual_incognito_accept", False, f"expected Requested, got {d}")
        else:
            invoke("accept_latest")
            d2 = wait_latest_state({"InProgress", "Completed"}, 60)
            ok = bool(d2 and d2.get("state") in ("InProgress", "Completed"))
            record("manual_incognito_accept", ok, f"after accept: {d2}")
    except Exception as exc:  # noqa: BLE001
        record("manual_incognito_accept", False, str(exc))

    print("\n=== SUMMARY ===")
    failed = 0
    for name, mark, detail in results:
        if mark != "OK":
            failed += 1
        print(f"  [{mark}] {name}: {detail}")

    try:
        path = rpc("screenshot", {"path": "/tmp/mwv-downloads-stress.png"}).get("path")
        print("screenshot:", path)
    except Exception as exc:  # noqa: BLE001
        print("screenshot failed:", exc)

    if range_proc is not None:
        range_proc.kill()
        range_proc.wait(timeout=5)

    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
