# MobileWebView — unified build/run entry point
#
# Usage:
#   make run TARGET_OS=<target>
#
# Targets:
#   macos              macOS desktop app
#   ios-simulator      iOS Simulator (x86_64 by default on Apple Silicon)
#   ios                iOS physical device
#   android            Android device (arm64-v8a)
#   android-emulator   Android emulator (arm64-v8a on Apple Silicon, x86_64 otherwise)
#
# Required environment variables per target:
#
#   macos:
#     QTDIR              Qt macOS kit dir  (e.g. ~/Qt/6.11.0/macos)
#
#   ios-simulator / ios:
#     QTDIR              Qt iOS kit dir    (e.g. ~/Qt/6.11.0/ios)
#     QT_HOST_PATH       Qt macOS tools    (e.g. ~/Qt/6.11.0/macos)
#
#   ios only (physical device):
#     DEVELOPMENT_TEAM   Apple team ID     (e.g. YOUR_APPLE_TEAM_ID)
#
#   android / android-emulator:
#     QTDIR              Qt Android kit    (e.g. ~/Qt/6.11.0/android_arm64_v8a)
#     QT_HOST_PATH       Qt macOS tools    (e.g. ~/Qt/6.11.0/macos)
#     ANDROID_SDK_ROOT   Android SDK       (e.g. ~/Library/Android/sdk)
#     ANDROID_NDK_ROOT   Android NDK       (e.g. ~/Library/Android/sdk/ndk/27.2.12479018)
#     JAVA_HOME          JDK 17 path       (e.g. /usr/libexec/java_home -v 17)
#
# Optional:
#   CONFIGURATION      Release (default) or Debug
#   ANDROID_PLATFORM        Native / NDK API (default: 35; max for NDK 27.x CMake is often 35)
#   ANDROID_COMPILE_SDK     Gradle compileSdk for androiddeployqt (default: 36; install with
#                           sdkmanager "platforms;android-36" if missing)
#   ARCH               Override CPU architecture

TARGET_OS ?= macos
CONFIGURATION ?= Release
ANDROID_PLATFORM ?= 35
# Gradle / Java deps (e.g. androidx.core) may need a higher compileSdk than NDK CMAKE allows.
ANDROID_COMPILE_SDK ?= 36
SOURCE_DIR := $(shell cd "$(dir $(abspath $(lastword $(MAKEFILE_LIST))))" && pwd)
APP_SOURCE_DIR := $(SOURCE_DIR)/test-app
BUILD_BASE := $(SOURCE_DIR)/build

# Resolve qt-cmake: prefer QTDIR/bin/qt-cmake, fall back to QT_HOST_PATH/bin/qt-cmake, then PATH
ifneq ($(QTDIR),)
    QTCMAKE := $(QTDIR)/bin/qt-cmake
else ifneq ($(QT_HOST_PATH),)
    QTCMAKE := $(QT_HOST_PATH)/bin/qt-cmake
else
    QTCMAKE := qt-cmake
endif

JOBS := $(shell sysctl -n hw.ncpu 2>/dev/null || echo 8)

.PHONY: run build test clean clean-all help

run: build
	@$(MAKE) -f $(SOURCE_DIR)/Makefile _run_$(TARGET_OS)

build:
	@$(MAKE) -f $(SOURCE_DIR)/Makefile _build_$(TARGET_OS)

test:
	@$(MAKE) -f $(SOURCE_DIR)/Makefile _test_$(TARGET_OS)

clean:
	rm -rf "$(BUILD_BASE)/$(TARGET_OS)"

# Remove all platform build dirs (and tests-macos). Prefer ./clean.sh for a documented entry point.
clean-all:
	rm -rf "$(BUILD_BASE)"

include $(SOURCE_DIR)/make/macos.mk
include $(SOURCE_DIR)/make/ios.mk
include $(SOURCE_DIR)/make/android.mk
include $(SOURCE_DIR)/make/tests.mk

help:
	@echo ""
	@echo "Usage:  make run TARGET_OS=<target>"
	@echo ""
	@echo "Targets:"
	@echo "  macos              macOS desktop (default)"
	@echo "  ios-simulator      iOS Simulator"
	@echo "  ios                iOS physical device"
	@echo "  android            Android device (arm64-v8a)"
	@echo "  android-emulator   Android emulator (auto ABI by QTDIR/host)"
	@echo ""
	@echo "Other targets:"
	@echo "  build              Configure and build only (no run)"
	@echo "  test               Build and run unit tests"
	@echo "  clean              Remove build directory for TARGET_OS"
	@echo "  clean-all          Remove entire build/ (all platforms + tests)"
	@echo "  ./clean.sh         Same as clean-all; use ./clean.sh macos for one platform"
	@echo ""
	@echo "Required env vars by target — see README.md for details."
	@echo ""
