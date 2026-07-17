#include "MobileWebView/mobilewebviewbackend.h"

#if defined(Q_OS_MACOS) || defined(Q_OS_IOS)

#include "navigationdelegate.h"
#include "downloaddelegate.h"
#include "dispatch_utils.h"

#import <dispatch/dispatch.h>

@implementation NavigationDelegate

- (void)webView:(WKWebView *)webView
    decidePolicyForNavigationAction:(WKNavigationAction *)navigationAction
                    decisionHandler:(void (^)(WKNavigationActionPolicy))decisionHandler
{
    if (@available(macOS 11.3, iOS 14.5, *)) {
        if (navigationAction.shouldPerformDownload) {
            NSURL *url = navigationAction.request.URL;
            NSString *scheme = url.scheme.lowercaseString;
            if ([scheme isEqualToString:@"blob"] || [scheme isEqualToString:@"data"]) {
                decisionHandler(WKNavigationActionPolicyCancel);
                return;
            }
            decisionHandler(WKNavigationActionPolicyDownload);
            return;
        }
    }
    decisionHandler(WKNavigationActionPolicyAllow);
}

- (void)webView:(WKWebView *)webView
    decidePolicyForNavigationResponse:(WKNavigationResponse *)navigationResponse
                      decisionHandler:(void (^)(WKNavigationResponsePolicy))decisionHandler
{
    NSURL *url = navigationResponse.response.URL;
    NSString *scheme = url.scheme.lowercaseString;
    if ([scheme isEqualToString:@"blob"] || [scheme isEqualToString:@"data"]) {
        decisionHandler(WKNavigationResponsePolicyCancel);
        return;
    }

    if (@available(macOS 11.3, iOS 14.5, *)) {
        // Only main-frame responses become downloads. Subframe / subresource
        // responses must keep loading (or the WebView can fault).
        if (navigationResponse.forMainFrame) {
            BOOL attachment = NO;
            if ([navigationResponse.response isKindOfClass:[NSHTTPURLResponse class]]) {
                NSHTTPURLResponse *http = (NSHTTPURLResponse *)navigationResponse.response;
                NSString *disposition =
                    [http.allHeaderFields[@"Content-Disposition"] description].lowercaseString;
                attachment = [disposition containsString:@"attachment"];
            }
            if (attachment || !navigationResponse.canShowMIMEType) {
                decisionHandler(WKNavigationResponsePolicyDownload);
                return;
            }
        }
    }
    decisionHandler(WKNavigationResponsePolicyAllow);
}

- (void)webView:(WKWebView *)webView
    navigationAction:(WKNavigationAction *)navigationAction
    didBecomeDownload:(WKDownload *)download
API_AVAILABLE(macos(11.3), ios(14.5))
{
    Q_UNUSED(webView);
    Q_UNUSED(navigationAction);
    [self.downloadDelegate attachDownload:download];
}

- (void)webView:(WKWebView *)webView
    navigationResponse:(WKNavigationResponse *)navigationResponse
    didBecomeDownload:(WKDownload *)download
API_AVAILABLE(macos(11.3), ios(14.5))
{
    Q_UNUSED(webView);
    Q_UNUSED(navigationResponse);
    [self.downloadDelegate attachDownload:download];
}

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

