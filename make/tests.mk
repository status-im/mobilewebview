# Test targets

_test_macos: _check_macos
	@rm -rf "$(BUILD_BASE)/tests-macos"
	@mkdir -p "$(BUILD_BASE)/tests-macos"
	env -u ANDROID_SDK_ROOT -u ANDROID_NDK_ROOT -u ANDROID_SERIAL -u ANDROID_AVD -u JAVA_HOME \
	"$(QTCMAKE)" \
	    -S "$(SOURCE_DIR)/mobilewebview" \
	    -B "$(BUILD_BASE)/tests-macos" \
	    -DCMAKE_BUILD_TYPE=$(CONFIGURATION) \
	    -DCMAKE_SYSTEM_NAME=Darwin \
	    -DBUILD_TESTING=ON
	cmake --build "$(BUILD_BASE)/tests-macos" --parallel $(JOBS) --config $(CONFIGURATION)
	ctest --test-dir "$(BUILD_BASE)/tests-macos" -C $(CONFIGURATION) --output-on-failure

_test_ios-simulator:
	@echo "ERROR: make test for iOS Simulator is not supported yet. Use TARGET_OS=macos."
	@exit 1

_test_ios:
	@echo "ERROR: make test for iOS device is not supported yet. Use TARGET_OS=macos."
	@exit 1

_test_android:
	@echo "ERROR: make test for Android is not supported yet. Use TARGET_OS=macos."
	@exit 1

_test_android-emulator:
	@echo "ERROR: make test for Android emulator is not supported yet. Use TARGET_OS=macos."
	@exit 1
