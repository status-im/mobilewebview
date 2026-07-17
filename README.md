# MobileWebView

[![CMake Tests](https://github.com/status-im/mobilewebview/actions/workflows/ci.yml/badge.svg)](https://github.com/status-im/mobilewebview/actions/workflows/ci.yml)

Cross-platform native WebView for Qt applications — Android, iOS, macOS.

The repository contains:

- `mobilewebview/` — reusable Qt library (`MobileWebView`)
- `test-app/` — standalone test application that exercises the library
- `test-app/mcp/` — MCP server so a Cursor agent can drive the test-app
- `Makefile` — single entry point for building and running on all platforms

## Quick start

```bash
make run TARGET_OS=macos
```

Set the required environment variables for your target platform (see below), then run:

```bash
make run TARGET_OS=ios-simulator
make run TARGET_OS=ios
make run TARGET_OS=android
make run TARGET_OS=android-emulator
```

To build without launching the app:

```bash
make build TARGET_OS=ios-simulator
```

To clean the build directory for a target:

```bash
make clean TARGET_OS=ios-simulator
```

## Required environment variables

| TARGET_OS | Variable | Example |
|---|---|---|
| `macos` | `QTDIR` | `~/Qt/6.9.2/macos` |
| `ios-simulator` | `QTDIR` | `~/Qt/6.9.2/ios` |
| | `QT_HOST_PATH` | `~/Qt/6.9.2/macos` |
| `ios` | `QTDIR` | `~/Qt/6.9.2/ios` |
| | `QT_HOST_PATH` | `~/Qt/6.9.2/macos` |
| | `DEVELOPMENT_TEAM` | `YOUR_APPLE_TEAM_ID` |
| `android` | `QTDIR` | `~/Qt/6.9.2/android_arm64_v8a` |
| | `QT_HOST_PATH` | `~/Qt/6.9.2/macos` |
| | `ANDROID_SDK_ROOT` | `~/Library/Android/sdk` |
| | `ANDROID_NDK_ROOT` | `~/Library/Android/sdk/ndk/27.2.12479018` |
| | `JAVA_HOME` | `/usr/libexec/java_home -v 17` |
| `android-emulator` | same as `android` | (use `x86_64` Qt kit) |

You can export these in your shell profile or pass them inline:

```bash
QTDIR=~/Qt/6.9.2/ios \
QT_HOST_PATH=~/Qt/6.9.2/macos \
DEVELOPMENT_TEAM=YOUR_APPLE_TEAM_ID \
make run TARGET_OS=ios
```

See [BUILD.md](BUILD.md) for details on building `MobileWebView` as a static or dynamic library.

## Platform requirements

| Platform | Minimum version | Notes |
|---|---|---|
| iOS | 17 | Persistent storage uses per-account `WKWebsiteDataStore` identifiers |
| macOS | 14 | Same storage model as iOS |
| Android | WebView 113+ | Requires `androidx.webkit` MULTI_PROFILE for per-account isolation and incognito profiles |

## Storage profiles (standard / incognito)

`MobileWebViewBackend` exposes `offTheRecord` and `storageName` properties. Standard mode
persists cookies, HTTP cache, and DOM storage per `storageName` partition. Incognito mode uses
an ephemeral store (in-memory on Apple; a deletable Android profile that is removed on
teardown and swept after crashes).

Changing either property on a live webview recreates the native view and reloads the current URL.

## Agent control (test-app MCP)

On macOS the test-app listens on `http://127.0.0.1:17321` (status bar shows `agent :17321`).
A Cursor agent can open screens, navigate, run JS, invoke harness actions, and screenshot
via the MCP server in `test-app/mcp/`.

```bash
./run_macos.sh          # starts app + control API
# enable MCP server "mobilewebview-test-app" (see test-app/mcp/mcp.json)
```

| | |
|---|---|
| Control API | `GET /health`, `GET /state`, `POST /rpc` |
| Port | `MWV_AGENT_PORT` (default `17321`; `0` disables) |
| MCP setup | [test-app/mcp/README.md](test-app/mcp/README.md) |
| Agent skill | [skills/mobilewebview-test-app/](skills/mobilewebview-test-app/) |

Smoke without MCP:

```bash
curl -s http://127.0.0.1:17321/health
curl -s http://127.0.0.1:17321/rpc \
  -H 'content-type: application/json' \
  -d '{"method":"list_screens","params":{}}'
```
