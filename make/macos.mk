# macOS targets

_check_macos:
	@test -n "$(QTDIR)" || (echo "ERROR: QTDIR is not set (e.g. ~/Qt/6.9.2/macos)"; exit 1)
	@command -v "$(QTCMAKE)" >/dev/null 2>&1 || (echo "ERROR: qt-cmake not found: $(QTCMAKE)"; exit 1)

_build_macos: _check_macos
	@mkdir -p "$(BUILD_BASE)/macos"
	"$(QTCMAKE)" \
	    -S "$(APP_SOURCE_DIR)" \
	    -B "$(BUILD_BASE)/macos" \
	    -DCMAKE_BUILD_TYPE=$(CONFIGURATION) \
	    -DBUILD_TESTING=OFF
	cmake --build "$(BUILD_BASE)/macos" --parallel $(JOBS) --config $(CONFIGURATION)

_run_macos:
	@APP="$(BUILD_BASE)/macos/MobileWebViewTest.app"; \
	test -d "$$APP" || (echo "ERROR: app not found: $$APP"; exit 1); \
	echo "Launching $$APP"; \
	open "$$APP"
