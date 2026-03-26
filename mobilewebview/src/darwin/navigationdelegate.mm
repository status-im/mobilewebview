#include "MobileWebView/mobilewebviewbackend.h"

#if defined(Q_OS_MACOS) || defined(Q_OS_IOS)

#include "navigationdelegate.h"
#include "dispatch_utils.h"

#import <dispatch/dispatch.h>

@implementation NavigationDelegate

- (void)webView:(WKWebView *)webView didStartProvisionalNavigation:(WKNavigation *)navigation {
    if (self.owner) {
        runOnMainThread(^{
            MobileWebViewBackend *backend = self.owner;
            if (backend) {
                backend->setLoadingState(true);
                backend->setLoadedState(false);
                backend->setLoadProgress(0);
                backend->setFavicon(QString());
            }
        });
    }
}

- (void)webView:(WKWebView *)webView didFinishNavigation:(WKNavigation *)navigation {
    if (self.owner) {
        WKWebView *wv = webView;
        runOnMainThread(^{
            MobileWebViewBackend *backend = self.owner;
            if (backend) {
                backend->setLoadingState(false);
                backend->setLoadedState(true);
                backend->setLoadProgress(100);
                // Update URL from the webview (without triggering another load)
                NSURL *currentURL = wv.URL;
                if (currentURL) {
                    backend->updateUrlState(QUrl::fromNSURL(currentURL));
                }
                // Fetch favicon URL via JavaScript
                [wv evaluateJavaScript:
                    @"(function(){"
                     "var icons=document.querySelectorAll(\"link[rel~='icon'],link[rel~='shortcut']\");"
                     "for(var i=icons.length-1;i>=0;i--){"
                     "  var href=icons[i].href;"
                     "  if(href&&href.length>0) return href;"
                     "}"
                     "return '';"
                     "})()"
                 completionHandler:^(id result, NSError *error) {
                    if (!error && [result isKindOfClass:[NSString class]]) {
                        NSString *faviconUrl = (NSString *)result;
                        QString qFaviconUrl = QString::fromNSString(faviconUrl);
                        // Fall back to /favicon.ico if no <link> tag found
                        if (qFaviconUrl.isEmpty() && currentURL) {
                            NSURL *base = [NSURL URLWithString:@"/favicon.ico" relativeToURL:currentURL];
                            qFaviconUrl = QString::fromNSString(base.absoluteString ?: @"");
                        }
                        MobileWebViewBackend *b = self.owner;
                        if (b && !qFaviconUrl.isEmpty()) {
                            QMetaObject::invokeMethod(b, [b, qFaviconUrl]() {
                                b->setFavicon(qFaviconUrl);
                            }, Qt::QueuedConnection);
                        }
                    }
                }];
            }
        });
    }
}

- (void)webView:(WKWebView *)webView didFailNavigation:(WKNavigation *)navigation withError:(NSError *)error {
    if (self.owner) {
        runOnMainThread(^{
            MobileWebViewBackend *backend = self.owner;
            if (backend) {
                backend->setLoadingState(false);
                backend->setLoadedState(false);
                backend->setLoadProgress(0);
            }
        });
    }
}

- (void)webView:(WKWebView *)webView didFailProvisionalNavigation:(WKNavigation *)navigation withError:(NSError *)error {
    if (self.owner) {
        runOnMainThread(^{
            MobileWebViewBackend *backend = self.owner;
            if (backend) {
                backend->setLoadingState(false);
                backend->setLoadedState(false);
                backend->setLoadProgress(0);
            }
        });
    }
}

@end

#endif // Q_OS_MACOS || Q_OS_IOS

