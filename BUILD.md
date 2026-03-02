# Building MobileWebView

## Library type: static vs dynamic

By default `MobileWebView` builds as a **shared** library on macOS and Android, and as a **static** library on iOS (forced — iOS does not support dynamic libraries in app bundles).

| Platform | Default | Override |
|---|---|---|
| macOS | shared (`SHARED`) | `MOBILEWEBVIEW_STATIC_LIB=ON` → static |
| iOS | static (`STATIC`) | not overridable (always static) |
| Android | shared (`SHARED`) | `MOBILEWEBVIEW_STATIC_LIB=ON` → static |

### Build as static library (macOS / Android)

Pass `-DMOBILEWEBVIEW_STATIC_LIB=ON` to CMake:

```bash
qt-cmake -S test-app -B build/macos-static \
    -DCMAKE_BUILD_TYPE=Release \
    -DMOBILEWEBVIEW_STATIC_LIB=ON
cmake --build build/macos-static --parallel
```

### When to choose static vs dynamic

**Dynamic (default)**

- Smaller app binary if multiple targets share the library.
- On macOS, the `.dylib` is bundled inside the `.app`.
- On Android, the `.so` is packaged into the APK alongside Qt's own `.so` files.
- Easier to update the library without recompiling the host app.

**Static**

- Single self-contained binary — no `.dylib`/`.so` to deploy.
- Required on iOS.
- Slightly larger final binary but simpler deployment.
- On macOS, useful when distributing the app via a signed `.dmg` where dylib
  signing would otherwise need to be handled separately.

---

## QML resource embedding (`MOBILEWEBVIEW_SHADOW_BUILD`)

The `MOBILEWEBVIEW_SHADOW_BUILD` option (default `ON`) compiles the JavaScript bridge
scripts (`src/customwebview.qrc`) directly into the library binary via Qt's `AUTORCC`.
This means consumers do not need to ship separate resource files.

To disable (e.g. if you want to override the scripts at runtime):

```cmake
-DMOBILEWEBVIEW_SHADOW_BUILD=OFF
```

---

## Using MobileWebView in your own project

### Option 1 — add_subdirectory

The simplest approach. Copy (or `git submodule add`) the `mobilewebview/` directory
into your project, then:

```cmake
add_subdirectory(mobilewebview)

target_link_libraries(MyApp PRIVATE MobileWebView)
target_include_directories(MyApp PRIVATE mobilewebview/include)
```

On Android, also set the Java source directory on your app target:

```cmake
if(ANDROID)
    set_property(TARGET MyApp APPEND PROPERTY
        QT_ANDROID_PACKAGE_SOURCE_DIR "${MOBILEWEBVIEW_ANDROID_JAVA_DIR}"
    )
endif()
```

### Option 2 — install + find_package

Build and install the library to a prefix:

```bash
qt-cmake -S mobilewebview -B build/install \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX=/opt/MobileWebView
cmake --build build/install
cmake --install build/install
```

Then in your project:

```cmake
find_package(MobileWebView REQUIRED)
target_link_libraries(MyApp PRIVATE MobileWebView)
```

---

## Platform notes

### iOS

- Library type is **always** static regardless of `MOBILEWEBVIEW_STATIC_LIB`.
- Code signing is **not** applied to the library target; set signing attributes
  on the app target only.
- Requires Xcode generator (`-G Xcode`) and `CMAKE_SYSTEM_NAME=iOS`.
- Qt iOS kit requires `QT_HOST_PATH` pointing to the macOS tools kit.

### Android

- The library is compiled as a `.so` and packaged into the APK by `androiddeployqt`.
- `MobileWebView.java` (the JNI bridge) must be present in the Android build's
  Java source tree. `test-app/CMakeLists.txt` sets `QT_ANDROID_PACKAGE_SOURCE_DIR`
  to handle this automatically; replicate this in your own app's CMakeLists.
- `MOBILEWEBVIEW_ANDROID_JAVA_DIR` CMake variable is exported to the parent scope
  so the host app can reference it.

### macOS

- Uses `WKWebView` via Objective-C++ (`src/darwin/`). Requires macOS 10.15+.
- Links `Foundation` and `WebKit` frameworks.
