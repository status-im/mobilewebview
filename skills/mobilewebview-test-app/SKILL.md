---
name: mobilewebview-test-app
description: >-
  Drive the MobileWebView test harness (test-app) from an agent via the
  mobilewebview-test-app MCP server — open feature screens, navigate, eval JS,
  invoke harness actions, read verdicts, screenshot. Use when reproducing or
  verifying MobileWebView behavior in test-app: navigation, find, zoom, cookies,
  localStorage, profile isolation, data clearing, downloads, scripts, or rendering.
---

# MobileWebView test-app (agent control)

Automate the macOS test harness without clicking. The app exposes a localhost
HTTP control API; the MCP server wraps it as tools.

## Prerequisites

1. **MCP server** — see [test-app/mcp/README.md](../../test-app/mcp/README.md).
   Example Cursor/`mcp.json` entry (repo-relative `--directory`):

```json
{
  "mcpServers": {
    "mobilewebview-test-app": {
      "command": "uv",
      "args": ["run", "--directory", "test-app/mcp", "python", "server.py"]
    }
  }
}
```

Or point any MCP host at `test-app/mcp/mcp.json`.

2. **App running** with control port up:

```bash
cd <repo-root>
./run_macos.sh   # or: make run TARGET_OS=macos
```

Status bar shows `agent :17321 · …`. Override with `MWV_AGENT_PORT` (`0` disables).

3. Confirm tools: `mwv_health` → `ok: true`.

## Workflow

```
mwv_health
mwv_list_screens          # ids: navigation, downloads, data-clearing, …
mwv_open_screen(id)
mwv_state                 # screenId, actions[], webView{}, screen{}
mwv_navigate / mwv_invoke / mwv_eval_js / mwv_set_prop
mwv_wait_loaded
mwv_screenshot → Read the PNG
mwv_go_home or mwv_press_back
```

Prefer **MCP tools** over Accessibility / mouse injection. Prefer `mwv_invoke`
and `mwv_eval_js` over guessing UI coordinates.

## Screen ids

| id | Screen |
|----|--------|
| `navigation` | Navigation & History |
| `find` | Find in page |
| `zoom` | Zoom |
| `cookies` | Cookies |
| `localstorage` | localStorage |
| `profile-isolation` | Profile isolation |
| `data-clearing` | Data clearing |
| `origin` | Origin |
| `scripts` | Scripts & isolation |
| `rendering` | Snapshot & Freeze |
| `downloads` | Downloads |

## Downloads harness (example)

```
mwv_open_screen("downloads")
mwv_invoke("accept_mode_auto")
mwv_invoke("url_small")          # also: page, inline, cancel, pause, retry, profile_cancel
# poll mwv_state until screen.verdicts.urlSmall is pass|fail|skip
mwv_screenshot
```

`mwv_state().screen` includes `verdicts`, `details`, and live `downloads[]`.

## Adding agent hooks on a screen

In the screen QML:

```qml
readonly property var agentActions: ({
    "my_action": function() { /* … */ }
})

function agentState() {
    return { /* JSON-serializable snapshot for mwv_state */ }
}
```

Then `mwv_invoke("my_action")` works without MCP changes.

## Control API (without MCP)

`http://127.0.0.1:17321` — `GET /health`, `GET /state`,
`POST /rpc` with `{"method":"…","params":{…}}`.
Methods mirror tool names without the `mwv_` prefix (`open_screen`, `eval_js`, …).

## Gotchas

- App must be running; MCP does not launch it.
- Only one listener per port; quit a previous test-app before relaunch.
- Screens without `agentActions` still support navigate / eval_js when they have a webView.
- `mwv_press_back` tears down the screen webView (same as Menu ←).
- Hot reload / rebuild replaces the process — re-check `mwv_health`.
