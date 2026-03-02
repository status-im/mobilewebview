# MobileWebView

Cross-platform native WebView component for Qt/QML applications with WebChannel integration.

## Supported Platforms

- **Android** - Uses native Android WebView via JNI
- **iOS** - Uses WKWebView
- **macOS** - Uses WKWebView

## Features

- Native WebView rendering on mobile platforms
- Qt WebChannel integration for JavaScript-to-C++ communication
- User scripts injection
- Origin-based security for message passing
- Seamless integration with Qt Quick

## Usage

### CMake Integration

```cmake
add_subdirectory(path/to/MobileWebView)
target_link_libraries(YourApp PRIVATE MobileWebView)
```

### QML Usage

```qml
import MobileWebView 1.0

MobileWebViewBackend {
    id: webView
    anchors.fill: parent
    url: "https://example.com"
    
    webChannel: WebChannel {
        id: channel
        registeredObjects: [myObject]
    }
    
    Component.onCompleted: {
        installMessageBridge("appBridge", ["https://example.com"], "invokeKey")
    }
}
```

## Building

### Requirements

- Qt 6.x with Core, Qml, Gui, Quick, WebChannel modules
- CMake 3.19+
- Platform-specific SDK (Xcode for Apple, Android NDK for Android)

### Build Options

| Option | Default | Description |
|--------|---------|-------------|
| `MOBILEWEBVIEW_STATIC_LIB` | OFF | Build as static library |
| `MOBILEWEBVIEW_SHADOW_BUILD` | ON | Bundle QML resources |

### Android Notes

For Android, you need to include the Java sources in your project. The path is exported as `MOBILEWEBVIEW_ANDROID_JAVA_DIR`.

## Architecture

```
MobileWebView/
├── include/MobileWebView/
│   └── mobilewebviewbackend.h    # Public API
├── src/
│   ├── common/                    # Platform-independent code
│   ├── darwin/                    # iOS/macOS implementation (WKWebView)
│   ├── android/                   # Android implementation (JNI)
│   └── js/                        # JavaScript bridge scripts
└── android/                       # Android Java sources
```

## License

Same as the parent project.
