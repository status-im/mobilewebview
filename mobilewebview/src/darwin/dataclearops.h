#pragma once

#import <WebKit/WebKit.h>

#include <QString>
#include <functional>

class MobileWebViewBackend;

// WKWebsiteDataStore-based data clearing (Darwin counterpart of Android's
// DataClearManager.java). Each operation handles a null webView by completing
// immediately, and always invokes the completion on the Qt thread.
namespace DataClearOps {

void clearHttpCache(WKWebView *webView, MobileWebViewBackend *backend,
                    std::function<void()> completion);

void deleteAllCookies(WKWebView *webView, MobileWebViewBackend *backend,
                      std::function<void()> completion);

void clearDomStorage(WKWebView *webView, MobileWebViewBackend *backend,
                     std::function<void()> completion);

void clearSiteData(WKWebView *webView, MobileWebViewBackend *backend,
                   const QString &origin, std::function<void()> completion);

} // namespace DataClearOps
