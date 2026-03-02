# iOS targets

_check_ios_sim:
	@test -n "$(QTDIR)" || (echo "ERROR: QTDIR is not set (e.g. ~/Qt/6.9.2/ios)"; exit 1)
	@test -n "$(QT_HOST_PATH)" || (echo "ERROR: QT_HOST_PATH is not set (e.g. ~/Qt/6.9.2/macos)"; exit 1)
	@command -v "$(QTCMAKE)" >/dev/null 2>&1 || (echo "ERROR: qt-cmake not found: $(QTCMAKE)"; exit 1)
	@command -v xcodebuild >/dev/null 2>&1 || (echo "ERROR: xcodebuild not found"; exit 1)

_IOS_SIM_ARCH := $(if $(ARCH),$(ARCH),$(shell [ "$$(uname -m)" = "arm64" ] && echo x86_64 || uname -m))

_build_ios-simulator: _check_ios_sim
	@mkdir -p "$(BUILD_BASE)/ios-simulator"
	"$(QTCMAKE)" \
	    -S "$(APP_SOURCE_DIR)" \
	    -B "$(BUILD_BASE)/ios-simulator" \
	    -G Xcode \
	    -DCMAKE_SYSTEM_NAME=iOS \
	    -DCMAKE_OSX_SYSROOT=iphonesimulator \
	    -DCMAKE_OSX_ARCHITECTURES=$(_IOS_SIM_ARCH) \
	    -DBUILD_TESTING=OFF
	cmake --build "$(BUILD_BASE)/ios-simulator" --config $(CONFIGURATION) --parallel $(JOBS)

_run_ios-simulator:
	@APP="$(BUILD_BASE)/ios-simulator/$(CONFIGURATION)-iphonesimulator/MobileWebViewTest.app"; \
	test -d "$$APP" || (echo "ERROR: app not found: $$APP"; exit 1); \
	BUNDLE_ID="$$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$$APP/Info.plist")"; \
	UDID="$$(xcrun simctl list devices | awk -F'[()]' '/Booted/{print $$2; exit}')"; \
	if [ -z "$$UDID" ]; then \
	    UDID="$$(xcrun simctl list devices | awk -F'[()]' '/iPhone/ && /Shutdown/{print $$2; exit}')"; \
	    test -n "$$UDID" || (echo "ERROR: no iOS simulator found"; exit 1); \
	    echo "Booting simulator $$UDID..."; \
	    open -a Simulator; \
	    xcrun simctl boot "$$UDID" >/dev/null 2>&1 || true; \
	    for i in $$(seq 1 30); do \
	        xcrun simctl list devices | grep -q "$$UDID.*Booted" && break; \
	        sleep 1; \
	    done; \
	fi; \
	echo "Installing $$APP on simulator $$UDID"; \
	xcrun simctl install "$$UDID" "$$APP"; \
	echo "Launching $$BUNDLE_ID"; \
	xcrun simctl launch --console-pty --terminate-running-process "$$UDID" "$$BUNDLE_ID"

_check_ios_device:
	@test -n "$(QTDIR)" || (echo "ERROR: QTDIR is not set (e.g. ~/Qt/6.9.2/ios)"; exit 1)
	@test -n "$(QT_HOST_PATH)" || (echo "ERROR: QT_HOST_PATH is not set (e.g. ~/Qt/6.9.2/macos)"; exit 1)
	@test -n "$(DEVELOPMENT_TEAM)" || (echo "ERROR: DEVELOPMENT_TEAM is not set (Apple team ID, e.g. YOUR_APPLE_TEAM_ID)"; exit 1)
	@command -v "$(QTCMAKE)" >/dev/null 2>&1 || (echo "ERROR: qt-cmake not found: $(QTCMAKE)"; exit 1)
	@command -v xcodebuild >/dev/null 2>&1 || (echo "ERROR: xcodebuild not found"; exit 1)

_build_ios: _check_ios_device
	@mkdir -p "$(BUILD_BASE)/ios"
	"$(QTCMAKE)" \
	    -S "$(APP_SOURCE_DIR)" \
	    -B "$(BUILD_BASE)/ios" \
	    -G Xcode \
	    -DCMAKE_SYSTEM_NAME=iOS \
	    -DCMAKE_OSX_SYSROOT=iphoneos \
	    -DCMAKE_OSX_ARCHITECTURES=$(if $(ARCH),$(ARCH),arm64) \
	    -DCMAKE_XCODE_ATTRIBUTE_DEVELOPMENT_TEAM=$(DEVELOPMENT_TEAM) \
	    -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED=YES \
	    -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_REQUIRED=YES \
	    -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGN_STYLE=Automatic \
	    -DBUILD_TESTING=OFF
	cmake --build "$(BUILD_BASE)/ios" --config $(CONFIGURATION) --parallel $(JOBS) \
	    -- -allowProvisioningUpdates

_run_ios:
	@APP="$(BUILD_BASE)/ios/$(CONFIGURATION)-iphoneos/MobileWebViewTest.app"; \
	test -d "$$APP" || (echo "ERROR: app not found: $$APP"; exit 1); \
	BUNDLE_ID="$$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$$APP/Info.plist")"; \
	if [ -n "$(DEVICE_ID)" ]; then \
	    UDID="$(DEVICE_ID)"; \
	else \
	    UDID="$$(xcrun devicectl list devices 2>/dev/null | awk -F'   +' 'NR>2 && $$4~/^available/{print $$3; exit}')"; \
	    test -n "$$UDID" || (echo "ERROR: no available iOS device found. Connect and unlock your iPhone."; exit 1); \
	fi; \
	echo "Installing on device $$UDID"; \
	xcrun devicectl device install app --device "$$UDID" "$$APP"; \
	echo "Launching $$BUNDLE_ID"; \
	xcrun devicectl device process launch --terminate-existing --console --device "$$UDID" "$$BUNDLE_ID"
