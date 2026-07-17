#!/usr/bin/env python3
"""MCP server that drives the MobileWebView test-app via its localhost control API."""

from __future__ import annotations

import json
import os
from typing import Any

import httpx
from mcp.server.fastmcp import FastMCP

DEFAULT_PORT = int(os.environ.get("MWV_AGENT_PORT", "17321"))
BASE_URL = os.environ.get("MWV_AGENT_URL", f"http://127.0.0.1:{DEFAULT_PORT}")

mcp = FastMCP(
    "mobilewebview-test-app",
    instructions=(
        "Control the MobileWebView test harness (test-app). "
        "Start the app first (./run_macos.sh); it listens on 127.0.0.1:17321. "
        "Typical flow: mwv_health → mwv_list_screens → mwv_open_screen → "
        "mwv_navigate / mwv_invoke / mwv_eval_js → mwv_state → mwv_screenshot."
    ),
)


class AgentError(RuntimeError):
    pass


def _client() -> httpx.Client:
    return httpx.Client(base_url=BASE_URL, timeout=30.0)


def _rpc(method: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
    with _client() as client:
        try:
            response = client.post("/rpc", json={"method": method, "params": params or {}})
        except httpx.ConnectError as exc:
            raise AgentError(
                f"test-app not reachable at {BASE_URL}. "
                "Build/run with ./run_macos.sh (control port 17321)."
            ) from exc
        response.raise_for_status()
        data = response.json()
    if not data.get("ok", False):
        raise AgentError(data.get("error") or json.dumps(data))
    return data.get("result") or {}


def _dump(value: Any) -> str:
    return json.dumps(value, indent=2, ensure_ascii=False)


@mcp.tool()
def mwv_health() -> str:
    """Check that the test-app agent control server is up."""
    with _client() as client:
        try:
            response = client.get("/health")
        except httpx.ConnectError as exc:
            raise AgentError(
                f"test-app not reachable at {BASE_URL}. Run ./run_macos.sh first."
            ) from exc
        response.raise_for_status()
        return _dump(response.json())


@mcp.tool()
def mwv_state() -> str:
    """Return current screen, webView state, actions, and per-screen agentState()."""
    with _client() as client:
        try:
            response = client.get("/state")
        except httpx.ConnectError as exc:
            raise AgentError(
                f"test-app not reachable at {BASE_URL}. Run ./run_macos.sh first."
            ) from exc
        response.raise_for_status()
        data = response.json()
    if not data.get("ok", False):
        raise AgentError(data.get("error") or json.dumps(data))
    return _dump(data.get("result") or data)


@mcp.tool()
def mwv_list_screens() -> str:
    """List feature screens (id, title, subtitle) available from the home menu."""
    return _dump(_rpc("list_screens"))


@mcp.tool()
def mwv_open_screen(screen_id: str) -> str:
    """Open a feature screen by id (e.g. navigation, downloads, data-clearing)."""
    return _dump(_rpc("open_screen", {"id": screen_id}))


@mcp.tool()
def mwv_go_home() -> str:
    """Pop back to the home screen."""
    return _dump(_rpc("go_home"))


@mcp.tool()
def mwv_press_back() -> str:
    """Press the screen's Menu back (teardown webView + pop)."""
    return _dump(_rpc("press_back"))


@mcp.tool()
def mwv_navigate(url: str) -> str:
    """Load a URL in the current screen's webView."""
    return _dump(_rpc("navigate", {"url": url}))


@mcp.tool()
def mwv_load_html(html: str, base_url: str = "about:blank") -> str:
    """loadHtml into the current webView."""
    return _dump(_rpc("load_html", {"html": html, "baseUrl": base_url}))


@mcp.tool()
def mwv_go_back() -> str:
    """WebView history back."""
    return _dump(_rpc("go_back"))


@mcp.tool()
def mwv_go_forward() -> str:
    """WebView history forward."""
    return _dump(_rpc("go_forward"))


@mcp.tool()
def mwv_reload(bypass_cache: bool = False) -> str:
    """Reload the current page (optionally bypassing HTTP cache)."""
    return _dump(_rpc("reload", {"bypassCache": bypass_cache}))


@mcp.tool()
def mwv_stop() -> str:
    """Stop the in-flight load."""
    return _dump(_rpc("stop"))


@mcp.tool()
def mwv_eval_js(script: str, timeout_ms: int = 10000) -> str:
    """Run JavaScript in the current webView and return the result."""
    return _dump(_rpc("eval_js", {"script": script, "timeoutMs": timeout_ms}))


@mcp.tool()
def mwv_find_text(text: str, flags: int = 0, timeout_ms: int = 5000) -> str:
    """Find text in page. flags: 0=forward, 1=backwards, 2=case-sensitive."""
    return _dump(_rpc("find_text", {"text": text, "flags": flags, "timeoutMs": timeout_ms}))


@mcp.tool()
def mwv_stop_find() -> str:
    """Clear find-in-page highlights."""
    return _dump(_rpc("stop_find"))


@mcp.tool()
def mwv_set_zoom(factor: float) -> str:
    """Set webView zoomFactor."""
    return _dump(_rpc("set_zoom", {"factor": factor}))


@mcp.tool()
def mwv_invoke(action: str) -> str:
    """Invoke a named agentActions entry on the current screen (see mwv_state().actions)."""
    return _dump(_rpc("invoke", {"action": action}))


@mcp.tool()
def mwv_set_prop(name: str, value: Any) -> str:
    """Set a QML property on the current screen (e.g. acceptMode, offTheRecord)."""
    return _dump(_rpc("set_prop", {"name": name, "value": value}))


@mcp.tool()
def mwv_wait_loaded(timeout_ms: int = 15000) -> str:
    """Block until the current webView reports loaded && !loading."""
    return _dump(_rpc("wait_loaded", {"timeoutMs": timeout_ms}))


@mcp.tool()
def mwv_screenshot(path: str = "") -> str:
    """Grab the test-app window to a PNG. Empty path → temp file. Then Read the image."""
    params: dict[str, Any] = {}
    if path:
        params["path"] = path
    return _dump(_rpc("screenshot", params))


def main() -> None:
    mcp.run(transport="stdio")


if __name__ == "__main__":
    main()
