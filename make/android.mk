# Android targets

_ANDROID_ABI_DEVICE := $(if $(ARCH),$(ARCH),arm64-v8a)
_ANDROID_ABI_EMULATOR := $(if $(ARCH),$(ARCH),$(if $(findstring android_x86_64,$(QTDIR)),x86_64,$(if $(findstring android_arm64_v8a,$(QTDIR)),arm64-v8a,$(if $(filter arm64,$(shell uname -m 2>/dev/null)),arm64-v8a,x86_64))))
_ANDROID_ABI_ACTIVE := $(if $(filter android-emulator,$(TARGET_OS)),$(_ANDROID_ABI_EMULATOR),$(_ANDROID_ABI_DEVICE))

_check_android:
	@test -n "$(QTDIR)" || (echo "ERROR: QTDIR is not set (e.g. ~/Qt/6.9.2/android_arm64_v8a)"; exit 1)
	@test -n "$(QT_HOST_PATH)" || (echo "ERROR: QT_HOST_PATH is not set (e.g. ~/Qt/6.9.2/macos)"; exit 1)
	@test -n "$(ANDROID_SDK_ROOT)" || (echo "ERROR: ANDROID_SDK_ROOT is not set"; exit 1)
	@test -n "$(ANDROID_NDK_ROOT)" || (echo "ERROR: ANDROID_NDK_ROOT is not set"; exit 1)
	@test -n "$(JAVA_HOME)" || (echo "ERROR: JAVA_HOME is not set"; exit 1)
	@command -v "$(QTCMAKE)" >/dev/null 2>&1 || (echo "ERROR: qt-cmake not found: $(QTCMAKE)"; exit 1)
	@command -v androiddeployqt >/dev/null 2>&1 || (echo "ERROR: androiddeployqt not found in PATH"; exit 1)
	@test -x "$(ANDROID_SDK_ROOT)/platform-tools/adb" || (echo "ERROR: adb not found in ANDROID_SDK_ROOT/platform-tools"; exit 1)

_build_android: _check_android
	@mkdir -p "$(BUILD_BASE)/android"
	"$(QTCMAKE)" \
	    -S "$(APP_SOURCE_DIR)" \
	    -B "$(BUILD_BASE)/android" \
	    -DANDROID_ABI=$(_ANDROID_ABI_DEVICE) \
	    -DANDROID_PLATFORM=android-$(ANDROID_PLATFORM) \
	    -DBUILD_TESTING=OFF
	cmake --build "$(BUILD_BASE)/android" --parallel $(JOBS)
	@$(MAKE) -f $(SOURCE_DIR)/Makefile _android_deploy \
	    _ANDROID_BUILD_DIR="$(BUILD_BASE)/android" \
	    _ANDROID_ABI=$(_ANDROID_ABI_DEVICE)

_run_android: _android_install_run
	@true

_build_android-emulator: _check_android
	@mkdir -p "$(BUILD_BASE)/android-emulator"
	"$(QTCMAKE)" \
	    -S "$(APP_SOURCE_DIR)" \
	    -B "$(BUILD_BASE)/android-emulator" \
	    -DANDROID_ABI=$(_ANDROID_ABI_EMULATOR) \
	    -DANDROID_PLATFORM=android-$(ANDROID_PLATFORM) \
	    -DBUILD_TESTING=OFF
	cmake --build "$(BUILD_BASE)/android-emulator" --parallel $(JOBS)
	@$(MAKE) -f $(SOURCE_DIR)/Makefile _android_deploy \
	    _ANDROID_BUILD_DIR="$(BUILD_BASE)/android-emulator" \
	    _ANDROID_ABI=$(_ANDROID_ABI_EMULATOR)

_run_android-emulator: _android_install_run
	@true

_android_deploy:
	@DEPLOY_JSON="$(_ANDROID_BUILD_DIR)/android-MobileWebViewTest-deployment-settings.json"; \
	test -f "$$DEPLOY_JSON" || (echo "ERROR: deployment settings not found: $$DEPLOY_JSON"; exit 1); \
	androiddeployqt \
	    --input "$$DEPLOY_JSON" \
	    --output "$(_ANDROID_BUILD_DIR)/android-build" \
	    --android-platform "android-$(ANDROID_PLATFORM)" \
	    --gradle \
	    --no-gdbserver; \
	JAVA_SRC="$(APP_SOURCE_DIR)/../mobilewebview/android/src/org/mobilewebview/MobileWebView.java"; \
	JAVA_DST="$(_ANDROID_BUILD_DIR)/android-build/src/org/mobilewebview/MobileWebView.java"; \
	if [ -f "$$JAVA_SRC" ] && [ ! -f "$$JAVA_DST" ]; then \
	    echo "Copying MobileWebView Java bridge..."; \
	    mkdir -p "$$(dirname "$$JAVA_DST")"; \
	    cp "$$JAVA_SRC" "$$JAVA_DST"; \
	fi; \
	MANIFEST="$(_ANDROID_BUILD_DIR)/android-build/AndroidManifest.xml"; \
	if [ -f "$$MANIFEST" ] && ! grep -q "android.permission.INTERNET" "$$MANIFEST"; then \
	    echo "Adding INTERNET permission to AndroidManifest.xml"; \
	    awk '/<manifest/ && !inserted {print; print "    <uses-permission android:name=\"android.permission.INTERNET\"/>"; inserted=1; next} {print}' \
	        "$$MANIFEST" > "$$MANIFEST.tmp" && mv "$$MANIFEST.tmp" "$$MANIFEST"; \
	fi; \
	cd "$(_ANDROID_BUILD_DIR)/android-build" && ./gradlew assembleDebug --no-daemon

_ANDROID_BUILD_DIR_DEVICE := $(if $(filter android-emulator,$(TARGET_OS)),$(BUILD_BASE)/android-emulator,$(BUILD_BASE)/android)

_android_install_run:
	@ADB="$(ANDROID_SDK_ROOT)/platform-tools/adb"; \
	APK="$(_ANDROID_BUILD_DIR_DEVICE)/android-build/build/outputs/apk/debug/android-build-debug.apk"; \
	test -f "$$APK" || { echo "ERROR: APK not found: $$APK"; exit 1; }; \
	DEVICES_OUTPUT="$$("$$ADB" devices)"; \
	if [ -n "$(ANDROID_SERIAL)" ]; then \
	    SERIAL="$(ANDROID_SERIAL)"; \
	elif [ "$(TARGET_OS)" = "android-emulator" ]; then \
	    SERIAL="$$(printf '%s\n' "$$DEVICES_OUTPUT" | sed '1d' | awk '$$1 ~ /^emulator-/ && $$2=="device"{print $$1; exit}')"; \
	    if [ -z "$$SERIAL" ]; then \
	        EMULATOR_BIN="$(ANDROID_SDK_ROOT)/emulator/emulator"; \
	        if [ ! -x "$$EMULATOR_BIN" ]; then \
	            EMULATOR_BIN="$$(command -v emulator || true)"; \
	        fi; \
	        test -n "$$EMULATOR_BIN" || { \
	            echo "ERROR: Android emulator binary not found."; \
	            echo "Expected: $(ANDROID_SDK_ROOT)/emulator/emulator"; \
	            exit 1; \
	        }; \
	        AVD_NAME="$(ANDROID_AVD)"; \
	        if [ -z "$$AVD_NAME" ]; then \
	            AVD_NAME="$$("$$EMULATOR_BIN" -list-avds 2>/dev/null | awk 'NF {print; exit}')"; \
	        fi; \
	        test -n "$$AVD_NAME" || { \
	            echo "ERROR: no Android AVD found."; \
	            echo "Create one in Android Studio or set ANDROID_AVD=<name>."; \
	            exit 1; \
	        }; \
	        echo "Starting emulator AVD '$$AVD_NAME'..."; \
	        "$$EMULATOR_BIN" -avd "$$AVD_NAME" >/dev/null 2>&1 & \
	        for i in $$(seq 1 90); do \
	            sleep 2; \
	            DEVICES_OUTPUT="$$("$$ADB" devices)"; \
	            SERIAL="$$(printf '%s\n' "$$DEVICES_OUTPUT" | sed '1d' | awk '$$1 ~ /^emulator-/ && $$2=="device"{print $$1; exit}')"; \
	            test -n "$$SERIAL" && break; \
	        done; \
	    fi; \
	    test -n "$$SERIAL" || { \
	        echo "ERROR: no Android emulator found in 'device' state."; \
	        echo "Start an emulator or set ANDROID_SERIAL explicitly."; \
	        printf '%s\n' "$$DEVICES_OUTPUT"; \
	        exit 1; \
	    }; \
	else \
	    SERIAL="$$(printf '%s\n' "$$DEVICES_OUTPUT" | sed '1d' | awk '$$1 !~ /^emulator-/ && $$2=="device"{print $$1; exit}')"; \
	    if [ -z "$$SERIAL" ]; then \
	        SERIAL="$$(printf '%s\n' "$$DEVICES_OUTPUT" | sed '1d' | awk '$$2=="device"{print $$1; exit}')"; \
	    fi; \
	fi; \
	test -n "$$SERIAL" || { echo "ERROR: no Android device/emulator connected"; exit 1; }; \
	DEVICES_OUTPUT="$$("$$ADB" devices)"; \
	if ! printf '%s\n' "$$DEVICES_OUTPUT" | sed '1d' | awk '$$1=="'"$$SERIAL"'" && $$2=="device"{found=1} END{exit !found}'; then \
	    echo "ERROR: selected Android serial '$$SERIAL' is not in 'device' state."; \
	    echo "Check connection/authorization, then run: $$ADB devices"; \
	    printf '%s\n' "$$DEVICES_OUTPUT"; \
	    exit 1; \
	fi; \
	APP_PKG="$$(sed -n 's/.*package="\([^"]*\)".*/\1/p' "$(_ANDROID_BUILD_DIR_DEVICE)/android-build/AndroidManifest.xml" | head -1)"; \
	echo "Using device: $$SERIAL"; \
	"$$ADB" -s "$$SERIAL" install -r "$$APK"; \
	"$$ADB" -s "$$SERIAL" logcat -c || true; \
	echo "Starting $$APP_PKG"; \
	"$$ADB" -s "$$SERIAL" shell monkey -p "$$APP_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null; \
	echo "Waiting for app process..."; \
	APP_PID=""; \
	for i in $$(seq 1 20); do \
	    APP_PID="$$("$$ADB" -s "$$SERIAL" shell pidof "$$APP_PKG" 2>/dev/null | tr -d '\r' || true)"; \
	    test -n "$$APP_PID" && break; \
	    sleep 1; \
	done; \
	if [ -n "$$APP_PID" ]; then \
	    echo "App PID: $$APP_PID — streaming logcat (Ctrl+C to stop)"; \
	    "$$ADB" -s "$$SERIAL" logcat --pid "$$APP_PID"; \
	else \
	    echo "App PID not found — streaming filtered logcat (Ctrl+C to stop)"; \
	    "$$ADB" -s "$$SERIAL" logcat -v time Qt:D MobileWebView:D chromium:D AndroidRuntime:E ActivityManager:I '*:S'; \
	fi
