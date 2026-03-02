# MobileWebView

[![CMake Tests](https://github.com/status-im/mobilewebview/actions/workflows/ci.yml/badge.svg)](https://github.com/status-im/mobilewebview/actions/workflows/ci.yml)

Cross-platform native WebView for Qt applications — Android, iOS, macOS.

The repository contains:

- `mobilewebview/` — reusable Qt library (`MobileWebView`)
- `test-app/` — standalone test application that exercises the library
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

### Recommended ~/.zshrc setup

Uncomment the block for the platform you are targeting:

```zsh
# macOS
export QTDIR="$HOME/Qt/6.9.2/macos"

# iOS / iOS Simulator
# export QTDIR="$HOME/Qt/6.9.2/ios"
# export QT_HOST_PATH="$HOME/Qt/6.9.2/macos"
# export DEVELOPMENT_TEAM=YOUR_APPLE_TEAM_ID

# Android
# export QTDIR="$HOME/Qt/6.9.2/android_arm64_v8a"
# export QT_HOST_PATH="$HOME/Qt/6.9.2/macos"
# export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
# export ANDROID_NDK_ROOT="$ANDROID_SDK_ROOT/ndk/27.2.12479018"
# export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
```

## Optional variables

| Variable | Default | Description |
|---|---|---|
| `CONFIGURATION` | `Release` | `Release` or `Debug` |
| `ANDROID_PLATFORM` | `35` | Android API level |
| `ARCH` | platform default | Override CPU architecture |
| `DEVICE_ID` | auto-detected | iOS device UDID or Android serial |

## Prerequisites

| Tool | Required for |
|---|---|
| CMake 3.21+ | all targets |
| Qt 6 (`qt-cmake`) | all targets |
| Xcode + command-line tools | macOS / iOS |
| `androiddeployqt`, `adb` | Android |
| JDK 17 | Android |

## Project structure

```
mobilewebview/          Qt library — platform-native WebView backed by WKWebView / Android WebView
  include/              Public API header
  src/common/           Platform-independent C++ (pimpl, WebChannel transport)
  src/darwin/           macOS + iOS implementation (Objective-C++, WKWebView)
  src/android/          Android implementation (JNI)
  src/js/               JavaScript bridge scripts
  android/              Android Java source (MobileWebView.java)

test-app/               Test application
  CMakeLists.txt        App CMake project (consumes mobilewebview via add_subdirectory)
  main.cpp              Entry point
  qml/                  QML UI
  js/                   JavaScript helpers
  web/                  HTML test page

Makefile                Build/run entry point
README.md               This file
BUILD.md                Static vs dynamic linking guide
```

See [BUILD.md](BUILD.md) for details on building `MobileWebView` as a static or dynamic library.
