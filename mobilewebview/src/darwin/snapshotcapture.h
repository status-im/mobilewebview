#pragma once

#import <WebKit/WebKit.h>

#include <QtGlobal>

class MobileWebViewBackendPrivate;

// Async WKWebView snapshot capture for freeze and public requestSnapshot.
// Eventually calls MobileWebViewBackendPrivate::notifySnapshotReady on the Qt
// thread (with a null QImage if the webView is null or the capture fails).
namespace SnapshotCapture {

void capture(WKWebView *webView, MobileWebViewBackendPrivate *priv, quint64 requestId);

} // namespace SnapshotCapture
