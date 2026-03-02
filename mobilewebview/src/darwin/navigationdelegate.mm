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
                // Update URL from the webview (without triggering another load)
                NSURL *currentURL = wv.URL;
                if (currentURL) {
                    backend->updateUrlState(QUrl::fromNSURL(currentURL));
                }
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
            }
        });
    }
}

@end

#endif // Q_OS_MACOS || Q_OS_IOS

