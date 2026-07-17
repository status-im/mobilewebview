#include "MobileWebView/mobilewebviewbackend.h"
#include "../common/mobilewebviewbackend_p.h"
#include "../common/origin_utils.h"
#include "../common/storage_profile_utils.h"
#include "origin_utils.h"
#include "navigationdelegate.h"
#include "downloaddelegate.h"
#include "dataclearops.h"
#include "snapshotcapture.h"
#include "userscripts.h"
#include "script_utils.h"
#include "dispatch_utils.h"

#import <WebKit/WebKit.h>
#import <dispatch/dispatch.h>
#import <CoreGraphics/CoreGraphics.h>

#ifdef Q_OS_IOS
#import <UIKit/UIKit.h>
#else
#import <AppKit/AppKit.h>
#endif

#include <QQuickWindow>
#include <QDebug>
#include <QPointer>
#include <QFile>
#include <QVariantMap>
#include <optional>
#include <functional>
#if defined(Q_OS_MACOS) || defined(Q_OS_IOS)

namespace {

QVariantMap toHistoryItemVariant(WKBackForwardListItem *item)
{
    QVariantMap historyItem;
    if (!item) {
        historyItem.insert(QStringLiteral("url"), QString());
        historyItem.insert(QStringLiteral("title"), QString());
        return historyItem;
    }

    const NSString *itemUrl = item.URL.absoluteString ?: @"";
    const NSString *itemTitle = item.title ?: @"";
    historyItem.insert(QStringLiteral("url"), QString::fromNSString(itemUrl));
    historyItem.insert(QStringLiteral("title"), QString::fromNSString(itemTitle));
    return historyItem;
}

QVariantList toHistoryItems(WKWebView *webView)
{
    QVariantList historyItems;
    if (!webView) {
        return historyItems;
    }

    WKBackForwardList *list = webView.backForwardList;
    for (WKBackForwardListItem *item in list.backList) {
        historyItems.append(toHistoryItemVariant(item));
    }
    if (list.currentItem) {
        historyItems.append(toHistoryItemVariant(list.currentItem));
    }
    for (WKBackForwardListItem *item in list.forwardList) {
        historyItems.append(toHistoryItemVariant(item));
    }

    return historyItems;
}

int currentHistoryIndex(WKWebView *webView)
{
    if (!webView || !webView.backForwardList.currentItem) {
        return -1;
    }
    return static_cast<int>(webView.backForwardList.backList.count);
}

} // namespace

@interface WebViewStateObserver : NSObject
@property (nonatomic, assign) MobileWebViewBackend *owner;
@end

@implementation WebViewStateObserver

- (void)observeValueForKeyPath:(NSString *)keyPath
                      ofObject:(id)object
                        change:(NSDictionary<NSKeyValueChangeKey, id> *)change
                       context:(void *)context
{
    Q_UNUSED(change)
    Q_UNUSED(context)

    MobileWebViewBackend *backend = self.owner;
    WKWebView *webView = [object isKindOfClass:[WKWebView class]] ? (WKWebView *)object : nil;
    if (!backend || !webView) {
        return;
    }

    if ([keyPath isEqualToString:@"title"]) {
        QString title = QString::fromNSString(webView.title ?: @"");
        QVariantList historyItems = toHistoryItems(webView);
        const int historyIndex = currentHistoryIndex(webView);
        QMetaObject::invokeMethod(backend, [backend, title, historyItems, historyIndex]() {
            backend->setTitle(title);
            backend->setHistoryState(historyItems, historyIndex);
        }, Qt::QueuedConnection);
        return;
    }

    if ([keyPath isEqualToString:@"canGoBack"]) {
        const bool canGoBack = webView.canGoBack;
        QVariantList historyItems = toHistoryItems(webView);
        const int historyIndex = currentHistoryIndex(webView);
        QMetaObject::invokeMethod(backend, [backend, canGoBack, historyItems, historyIndex]() {
            backend->setCanGoBack(canGoBack);
            backend->setHistoryState(historyItems, historyIndex);
        }, Qt::QueuedConnection);
        return;
    }

    if ([keyPath isEqualToString:@"canGoForward"]) {
        const bool canGoForward = webView.canGoForward;
        QVariantList historyItems = toHistoryItems(webView);
        const int historyIndex = currentHistoryIndex(webView);
        QMetaObject::invokeMethod(backend, [backend, canGoForward, historyItems, historyIndex]() {
            backend->setCanGoForward(canGoForward);
            backend->setHistoryState(historyItems, historyIndex);
        }, Qt::QueuedConnection);
        return;
    }

    if ([keyPath isEqualToString:@"estimatedProgress"]) {
        const int progress = static_cast<int>(webView.estimatedProgress * 100.0);
        QMetaObject::invokeMethod(backend, [backend, progress]() {
            backend->setLoadProgress(progress);
        }, Qt::QueuedConnection);
        return;
    }

    [super observeValueForKeyPath:keyPath ofObject:object change:change context:context];
}

@end

@interface WebViewUiDelegate : NSObject <WKUIDelegate>
@property (nonatomic, assign) MobileWebViewBackend *owner;
@end

@implementation WebViewUiDelegate

- (WKWebView *)webView:(WKWebView *)webView
createWebViewWithConfiguration:(WKWebViewConfiguration *)configuration
 forNavigationAction:(WKNavigationAction *)navigationAction
      windowFeatures:(WKWindowFeatures *)windowFeatures
{
    Q_UNUSED(webView)
    Q_UNUSED(configuration)
    Q_UNUSED(windowFeatures)

    MobileWebViewBackend *backend = self.owner;
    NSURL *requestedUrl = navigationAction.request.URL;
    if (backend && requestedUrl) {
        const bool userInitiated = navigationAction.navigationType == WKNavigationTypeLinkActivated;
        backend->emitNewWindowRequested(QUrl::fromNSURL(requestedUrl), userInitiated);
    }

    // Returning nil blocks native popup creation. QML handles opening in a new tab.
    return nil;
}

@end

// =============================================================================
// DarwinWebViewPrivate - Darwin-specific implementation
// =============================================================================

class DarwinWebViewPrivate : public MobileWebViewBackendPrivate
{
public:
    explicit DarwinWebViewPrivate(MobileWebViewBackend *q);
    ~DarwinWebViewPrivate() override;
    
    // Platform-specific implementations
    bool initNativeView() override;
    void destroyNativeView() override;
    void loadUrlImpl(const QUrl &url) override;
    void loadHtmlImpl(const QString &html, const QUrl &baseUrl) override;
    void goBackImpl() override;
    void goForwardImpl() override;
    void goBackOrForwardImpl(int offset) override;
    void reloadImpl() override;
    void reloadAndBypassCacheImpl() override;
    void stopImpl() override;
    void clearHistoryImpl() override;
    void clearHttpCacheImpl(std::function<void()> completion) override;
    void deleteAllCookiesImpl(std::function<void()> completion) override;
    void clearDomStorageImpl(std::function<void()> completion) override;
    void clearSiteDataImpl(const QString &origin, std::function<void()> completion) override;
    bool clearSiteDataSupportedImpl() const override;
    void evaluateJavaScript(const QString &script) override;
    void updateNativeGeometry(const QRectF &rect) override;
    void updateNativeVisibility(bool visible) override;
    bool installBridgeImpl(const QString &ns, const QStringList &origins, 
                          const QString &invokeKey, const QString &webChannelScriptPath) override;
    void postMessageToJavaScript(const QString &json) override;
    void setupNativeViewImpl() override;
    void updateAllowedOriginsImpl(const QStringList &origins) override;
    void updateInteractionEnabled(bool enabled) override;
    void setZoomFactorImpl(qreal factor) override;
    void setHttpUserAgentImpl(const QString &userAgent) override;
    // findTextImpl/stopFindImpl: base-class no-ops.
    // macOS: find-in-page not supported (no public API for programmatic find with highlight).
    // iOS: uses native WKFindInteraction via showFindPanel/hideFindPanel.
    bool findSupportedImpl() const override;
    bool hasNativeFindPanelImpl() const override;
    void showFindPanelImpl() override;
    void hideFindPanelImpl() override;
    void captureSnapshotImpl(quint64 requestId) override;
    void detachNativeViewFromSceneImpl() override;
    void startDownloadImpl(quint64 downloadId, const QUrl &url,
                           const QString &destinationPath) override;
    void cancelDownloadImpl(quint64 downloadId) override;
    void pauseDownloadImpl(quint64 downloadId) override;
    void resumeDownloadImpl(quint64 downloadId) override;

private:
    WKWebView *m_webView = nullptr;
    NavigationDelegate *m_navigationDelegate = nullptr;
    DownloadDelegate *m_downloadDelegate = nullptr;
    WebViewUiDelegate *m_uiDelegate = nullptr;
    WebViewStateObserver *m_stateObserver = nullptr;
    UserScriptsManager *m_userScriptsManager = nullptr;
    void *m_hostView = nullptr;

    std::optional<QRect> m_lastGeometry;
};

DarwinWebViewPrivate::DarwinWebViewPrivate(MobileWebViewBackend *q)
    : MobileWebViewBackendPrivate(q)
{
}

DarwinWebViewPrivate::~DarwinWebViewPrivate()
{
    destroyNativeView();
}

void DarwinWebViewPrivate::destroyNativeView()
{
    if (m_navigationDelegate) {
        m_navigationDelegate.owner = nullptr;
        m_navigationDelegate.downloadDelegate = nil;
    }
    if (m_downloadDelegate) {
        // Null owner first so cancel/fail callbacks cannot re-enter the backend.
        m_downloadDelegate.owner = nullptr;
        [m_downloadDelegate cancelAll];
    }
    if (m_uiDelegate) {
        m_uiDelegate.owner = nullptr;
    }

    if (m_webView && m_stateObserver) {
        WKWebView *webView = m_webView;
        WebViewStateObserver *observer = m_stateObserver;
        runOnMainThread(^{
            @try { [webView removeObserver:observer forKeyPath:@"title"]; } @catch (NSException *) {}
            @try { [webView removeObserver:observer forKeyPath:@"canGoBack"]; } @catch (NSException *) {}
            @try { [webView removeObserver:observer forKeyPath:@"canGoForward"]; } @catch (NSException *) {}
            @try { [webView removeObserver:observer forKeyPath:@"estimatedProgress"]; } @catch (NSException *) {}
            [observer release];
        });
        m_stateObserver = nullptr;
    }

    delete m_userScriptsManager;
    m_userScriptsManager = nullptr;

    if (m_webView) {
        WKWebView *webView = m_webView;
        NavigationDelegate *delegate = m_navigationDelegate;
        DownloadDelegate *downloadDelegate = m_downloadDelegate;
        WebViewUiDelegate *uiDelegate = m_uiDelegate;

        m_webView = nullptr;
        m_navigationDelegate = nullptr;
        m_downloadDelegate = nullptr;
        m_uiDelegate = nullptr;

        runOnMainThread(^{
            [webView stopLoading];
            [webView removeFromSuperview];
            webView.navigationDelegate = nil;
            webView.UIDelegate = nil;
            [webView release];
            [delegate release];
            [downloadDelegate release];
            [uiDelegate release];
        });
    } else if (m_downloadDelegate) {
        DownloadDelegate *downloadDelegate = m_downloadDelegate;
        m_downloadDelegate = nullptr;
        runOnMainThread(^{
            [downloadDelegate release];
        });
    }

    m_lastGeometry.reset();
}

bool DarwinWebViewPrivate::initNativeView()
{
    WKWebViewConfiguration *config = [[WKWebViewConfiguration alloc] init];
#ifdef Q_OS_IOS
    config.allowsInlineMediaPlayback = YES;
    config.mediaTypesRequiringUserActionForPlayback = WKAudiovisualMediaTypeNone;
#endif
#ifdef QT_DEBUG
    [config.preferences setValue:@YES forKey:@"developerExtrasEnabled"];
#endif

    if (m_offTheRecord) {
        config.websiteDataStore = [WKWebsiteDataStore nonPersistentDataStore];
    } else if (m_storageName.isEmpty()) {
        config.websiteDataStore = [WKWebsiteDataStore defaultDataStore];
    } else {
        const QUuid profileId = storageProfileIdentifier(m_storageName);
        const QString uuidStr = profileId.toString(QUuid::WithoutBraces);
        NSUUID *storeUuid = [[NSUUID alloc] initWithUUIDString:uuidStr.toNSString()];
        if (storeUuid) {
            config.websiteDataStore = [WKWebsiteDataStore dataStoreForIdentifier:storeUuid];
        } else {
            config.websiteDataStore = [WKWebsiteDataStore defaultDataStore];
        }
        [storeUuid release];
    }

    m_webView = [[WKWebView alloc] initWithFrame:CGRectZero configuration:config];

    m_navigationDelegate = [[NavigationDelegate alloc] init];
    m_navigationDelegate.owner = q_ptr;
    if (@available(macOS 11.3, iOS 14.5, *)) {
        m_downloadDelegate = [[DownloadDelegate alloc] init];
        m_downloadDelegate.owner = q_ptr;
        m_navigationDelegate.downloadDelegate = m_downloadDelegate;
    }
    m_webView.navigationDelegate = m_navigationDelegate;

    m_uiDelegate = [[WebViewUiDelegate alloc] init];
    m_uiDelegate.owner = q_ptr;
    m_webView.UIDelegate = m_uiDelegate;

    m_stateObserver = [[WebViewStateObserver alloc] init];
    m_stateObserver.owner = q_ptr;
    [m_webView addObserver:m_stateObserver
                forKeyPath:@"title"
                   options:NSKeyValueObservingOptionNew
                   context:nil];
    [m_webView addObserver:m_stateObserver
                forKeyPath:@"canGoBack"
                   options:NSKeyValueObservingOptionNew
                   context:nil];
    [m_webView addObserver:m_stateObserver
                forKeyPath:@"canGoForward"
                   options:NSKeyValueObservingOptionNew
                   context:nil];
    [m_webView addObserver:m_stateObserver
                forKeyPath:@"estimatedProgress"
                   options:NSKeyValueObservingOptionNew
                   context:nil];

    m_userScriptsManager = new UserScriptsManager(m_webView, q_ptr);

#ifdef Q_OS_IOS
    if (@available(iOS 16.0, *)) {
        m_webView.findInteractionEnabled = YES;
    }
#endif

    [m_webView setHidden:YES];

    m_viewStoreOffTheRecord = m_offTheRecord;
    m_viewStoreName = m_storageName;

    // Apply before any load that setupNativeViewImpl may kick off on first create.
    setHttpUserAgentImpl(m_httpUserAgent);

    return true;
}

void DarwinWebViewPrivate::loadUrlImpl(const QUrl &url)
{
    if (!m_webView) {
        qWarning() << "DarwinWebViewPrivate: webView is null";
        return;
    }

    WKWebView *webView = m_webView;
    NSURL *nsUrl = url.toNSURL();

    runOnMainThread(^{
        NSURLRequest *request = [NSURLRequest requestWithURL:nsUrl];
        [webView loadRequest:request];
    });
}

void DarwinWebViewPrivate::loadHtmlImpl(const QString &html, const QUrl &baseUrl)
{
    if (!m_webView) {
        qWarning() << "DarwinWebViewPrivate: webView is null";
        return;
    }

    WKWebView *webView = m_webView;
    NSString *htmlString = html.toNSString();
    NSURL *nsBaseUrl = baseUrl.isValid() ? baseUrl.toNSURL() : nil;

    runOnMainThread(^{
        [webView loadHTMLString:htmlString baseURL:nsBaseUrl];
    });
}

void DarwinWebViewPrivate::goBackImpl()
{
    if (!m_webView) {
        return;
    }

    WKWebView *webView = m_webView;
    runOnMainThread(^{
        if (webView.canGoBack) {
            [webView goBack];
        }
    });
}

void DarwinWebViewPrivate::goForwardImpl()
{
    if (!m_webView) {
        return;
    }

    WKWebView *webView = m_webView;
    runOnMainThread(^{
        if (webView.canGoForward) {
            [webView goForward];
        }
    });
}

void DarwinWebViewPrivate::goBackOrForwardImpl(int offset)
{
    if (!m_webView || offset == 0) {
        return;
    }

    WKWebView *webView = m_webView;
    runOnMainThread(^{
        WKBackForwardList *list = webView.backForwardList;
        const NSInteger backCount = list.backList.count;
        const BOOL hasCurrent = list.currentItem != nil;
        const NSInteger forwardCount = list.forwardList.count;
        const NSInteger currentIndex = hasCurrent ? backCount : -1;
        const NSInteger totalCount = backCount + (hasCurrent ? 1 : 0) + forwardCount;
        const NSInteger targetIndex = currentIndex + static_cast<NSInteger>(offset);

        if (!hasCurrent || targetIndex < 0 || targetIndex >= totalCount || targetIndex == currentIndex) {
            return;
        }

        WKBackForwardListItem *targetItem = nil;
        if (targetIndex < backCount) {
            targetItem = [list.backList objectAtIndex:targetIndex];
        } else if (targetIndex == backCount) {
            targetItem = list.currentItem;
        } else {
            targetItem = [list.forwardList objectAtIndex:(targetIndex - backCount - 1)];
        }

        if (targetItem) {
            [webView goToBackForwardListItem:targetItem];
        }
    });
}

void DarwinWebViewPrivate::reloadImpl()
{
    if (!m_webView) {
        return;
    }

    WKWebView *webView = m_webView;
    runOnMainThread(^{
        [webView reload];
    });
}

void DarwinWebViewPrivate::reloadAndBypassCacheImpl()
{
    if (!m_webView) {
        return;
    }

    WKWebView *webView = m_webView;
    runOnMainThread(^{
        [webView reloadFromOrigin];
    });
}

void DarwinWebViewPrivate::clearHttpCacheImpl(std::function<void()> completion)
{
    DataClearOps::clearHttpCache(m_webView, q_ptr, std::move(completion));
}

void DarwinWebViewPrivate::deleteAllCookiesImpl(std::function<void()> completion)
{
    DataClearOps::deleteAllCookies(m_webView, q_ptr, std::move(completion));
}

void DarwinWebViewPrivate::clearDomStorageImpl(std::function<void()> completion)
{
    DataClearOps::clearDomStorage(m_webView, q_ptr, std::move(completion));
}

void DarwinWebViewPrivate::clearSiteDataImpl(const QString &origin, std::function<void()> completion)
{
    DataClearOps::clearSiteData(m_webView, q_ptr, origin, std::move(completion));
}

bool DarwinWebViewPrivate::clearSiteDataSupportedImpl() const
{
    return true;
}

void DarwinWebViewPrivate::stopImpl()
{
    if (!m_webView) {
        return;
    }

    WKWebView *webView = m_webView;
    runOnMainThread(^{
        [webView stopLoading];
    });
}

void DarwinWebViewPrivate::clearHistoryImpl()
{
    if (!m_webView) {
        return;
    }

    WKWebView *webView = m_webView;
    runOnMainThread(^{
        // WKWebView does not expose a direct clearHistory() API.
        // The standard approach is to recreate the WKBackForwardList by loading
        // about:blank and then immediately reloading the current page, which
        // collapses the back/forward list to a single entry.
        NSURL *currentURL = webView.URL;
        NSURLRequest *blankRequest = [NSURLRequest requestWithURL:[NSURL URLWithString:@"about:blank"]];
        [webView loadRequest:blankRequest];

        if (currentURL && ![currentURL.absoluteString isEqualToString:@"about:blank"]) {
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(50 * NSEC_PER_MSEC)),
                           dispatch_get_main_queue(), ^{
                NSURLRequest *restoreRequest = [NSURLRequest requestWithURL:currentURL];
                [webView loadRequest:restoreRequest];
            });
        }
    });
}

void DarwinWebViewPrivate::evaluateJavaScript(const QString &script)
{
    if (!m_userScriptsManager) {
        qWarning() << "DarwinWebViewPrivate: userScriptsManager is null";
        return;
    }

    m_userScriptsManager->evaluateJavaScript(script, ^(id result, NSError *error) {
        QVariant qResult;
        QString qError;

        if (error) {
            qError = QString::fromNSString(error.localizedDescription);
        } else if (result) {
            if ([result isKindOfClass:[NSString class]]) {
                qResult = QString::fromNSString((NSString *)result);
            } else if ([result isKindOfClass:[NSNumber class]]) {
                NSNumber *num = (NSNumber *)result;
                if (strcmp([num objCType], @encode(BOOL)) == 0) {
                    qResult = [num boolValue];
                } else {
                    qResult = [num doubleValue];
                }
            } else if ([result isKindOfClass:[NSNull class]]) {
                qResult = QVariant();
            } else {
                NSData *jsonData = [NSJSONSerialization dataWithJSONObject:result options:0 error:nil];
                if (jsonData) {
                    NSString *jsonStr = [[NSString alloc] initWithData:jsonData encoding:NSUTF8StringEncoding];
                    qResult = QString::fromNSString(jsonStr);
                    [jsonStr release];
                } else {
                    qResult = QString::fromNSString([result description]);
                }
            }
        }

        emit q_ptr->javaScriptResult(qResult, qError);
    });
}

void DarwinWebViewPrivate::updateNativeGeometry(const QRectF &rect)
{
    if (!m_webView || !m_nativeViewSetup) {
        return;
    }

    QQuickWindow *win = q_ptr->window();
    if (!win) {
        return;
    }

    QPointF scenePos = q_ptr->mapToScene(QPointF(0, 0));
    qreal itemWidth = rect.width();
    qreal itemHeight = rect.height();

    if (itemWidth <= 0 || itemHeight <= 0) {
        return;
    }

    WKWebView *webView = m_webView;

#ifdef Q_OS_IOS
    CGFloat x = scenePos.x();
    CGFloat y = scenePos.y();
    CGFloat w = itemWidth;
    CGFloat h = itemHeight;

    const QRect geometry(qRound(x), qRound(y), qRound(w), qRound(h));
    if (m_lastGeometry == geometry) {
        return;
    }

    runOnMainThread(^{
        webView.frame = CGRectMake(x, y, w, h);
    });

    m_lastGeometry = geometry;
#else
    NSView *hostView = reinterpret_cast<NSView *>(m_hostView);
    if (!hostView) {
        return;
    }

    CGFloat x = scenePos.x();
    CGFloat y;
    CGFloat w = itemWidth;
    CGFloat h = itemHeight;

    if ([hostView isFlipped]) {
        y = scenePos.y();
    } else {
        CGFloat hostHeight = hostView.bounds.size.height;
        y = hostHeight - scenePos.y() - itemHeight;
    }

    const QRect geometry(qRound(x), qRound(y), qRound(w), qRound(h));
    if (m_lastGeometry == geometry) {
        return;
    }

    runOnMainThread(^{
        webView.frame = NSMakeRect(x, y, w, h);
    });

    m_lastGeometry = geometry;
#endif
}

void DarwinWebViewPrivate::updateNativeVisibility(bool visible)
{
    if (!m_webView) {
        return;
    }

    const bool shouldBeVisible = shouldShowNativeWebView(visible);
    WKWebView *webView = m_webView;

    runOnMainThread(^{
        [webView setHidden:!shouldBeVisible];
    });
}

void DarwinWebViewPrivate::detachNativeViewFromSceneImpl()
{
    m_lastGeometry.reset();
    m_hostView = nullptr;

    if (!m_webView) {
        return;
    }

    WKWebView *webView = m_webView;
    runOnMainThread(^{
        [webView setHidden:YES];
        [webView removeFromSuperview];
    });
}

bool DarwinWebViewPrivate::installBridgeImpl(const QString &ns, const QStringList &origins, 
                                              const QString &invokeKey, const QString &webChannelScriptPath)
{
    if (!m_userScriptsManager) {
        qWarning() << "DarwinWebViewPrivate: userScriptsManager is null";
        return false;
    }

    QList<UserScriptInfo> scriptInfos = parseUserScripts(m_userScripts);

    return m_userScriptsManager->installMessageBridge(ns, origins, invokeKey,
                                                      webChannelScriptPath, scriptInfos);
}

void DarwinWebViewPrivate::postMessageToJavaScript(const QString &json)
{
    if (!m_userScriptsManager) {
        qWarning() << "DarwinWebViewPrivate: userScriptsManager is null";
        return;
    }

    m_userScriptsManager->postMessageToJavaScript(json);
}

void DarwinWebViewPrivate::updateAllowedOriginsImpl(const QStringList &origins)
{
    if (m_userScriptsManager) {
        m_userScriptsManager->updateAllowedOrigins(origins);
    }
}

void DarwinWebViewPrivate::setZoomFactorImpl(qreal factor)
{
    if (!m_webView) {
        return;
    }

    WKWebView *webView = m_webView;
    runOnMainThread(^{
        NSString *js = [NSString stringWithFormat:
            @"document.documentElement.style.zoom = '%f'",
            static_cast<double>(factor)];
        [webView evaluateJavaScript:js completionHandler:nil];
    });
}

void DarwinWebViewPrivate::setHttpUserAgentImpl(const QString &userAgent)
{
    if (!m_webView) {
        return;
    }

    WKWebView *webView = m_webView;
    NSString *ua = userAgent.isEmpty() ? nil : userAgent.toNSString();
    runOnMainThread(^{
        webView.customUserAgent = ua;
    });
}

bool DarwinWebViewPrivate::findSupportedImpl() const
{
#ifdef Q_OS_IOS
    return true;
#else
    return false;
#endif
}

bool DarwinWebViewPrivate::hasNativeFindPanelImpl() const
{
#ifdef Q_OS_IOS
    return true;
#else
    return false;
#endif
}

void DarwinWebViewPrivate::showFindPanelImpl()
{
#ifdef Q_OS_IOS
    if (!m_webView) {
        return;
    }

    WKWebView *webView = m_webView;
    runOnMainThread(^{
        if (@available(iOS 16.0, *)) {
            if (webView.findInteractionEnabled) {
                [webView.findInteraction presentFindNavigatorShowingReplace:NO];
            }
        }
    });
#endif
}

void DarwinWebViewPrivate::hideFindPanelImpl()
{
#ifdef Q_OS_IOS
    if (!m_webView) {
        return;
    }

    WKWebView *webView = m_webView;
    runOnMainThread(^{
        if (@available(iOS 16.0, *)) {
            if (webView.findInteractionEnabled) {
                [webView.findInteraction dismissFindNavigator];
            }
        }
    });
#endif
}

void DarwinWebViewPrivate::updateInteractionEnabled(bool enabled)
{
    if (!m_webView) {
        return;
    }

    WKWebView *webView = m_webView;
    runOnMainThread(^{
#ifdef Q_OS_IOS
        webView.userInteractionEnabled = enabled;
        if (!enabled) {
            [webView endEditing:YES];
            [webView resignFirstResponder];
            for (UIView *subview in webView.scrollView.subviews) {
                if ([subview canBecomeFirstResponder]) {
                    [subview resignFirstResponder];
                    subview.userInteractionEnabled = NO;
                }
            }
        } else {
            for (UIView *subview in webView.scrollView.subviews) {
                subview.userInteractionEnabled = YES;
            }
        }
#else
        if (!enabled) {
            // Hand first responder to the Qt content view (not nil) so that key
            // events reach the focused QML item (e.g. an address field) on the
            // first interaction instead of being swallowed until a second click.
            NSWindow *window = webView.window;
            if (window) {
                NSView *contentView = window.contentView;
                if (contentView && [contentView acceptsFirstResponder]) {
                    [window makeFirstResponder:contentView];
                } else {
                    [window makeFirstResponder:nil];
                }
            }
        }
#endif
    });
}

void DarwinWebViewPrivate::setupNativeViewImpl()
{
    const bool createdNow = !m_webView;
    if (!m_webView && !initNativeView()) {
        qWarning() << "DarwinWebViewPrivate::setupNativeViewImpl: initNativeView failed";
        return;
    }

    QQuickWindow *win = q_ptr->window();
    if (!win) {
        qWarning() << "DarwinWebViewPrivate::setupNativeViewImpl: no window";
        return;
    }

    WId winId = win->winId();
    if (!winId) {
        qWarning() << "DarwinWebViewPrivate::setupNativeViewImpl: winId is null";
        return;
    }

#ifdef Q_OS_IOS
    UIView *hostView = reinterpret_cast<UIView *>(winId);
#else
    NSView *hostView = reinterpret_cast<NSView *>(winId);
#endif

    if (!hostView) {
        qWarning() << "DarwinWebViewPrivate::setupNativeViewImpl: hostView is null";
        return;
    }

    m_hostView = hostView;
    WKWebView *webView = m_webView;
    bool wasSetup = m_nativeViewSetup;
    m_nativeViewSetup = true;
    MobileWebViewBackend *backend = q_ptr;

    // Sync initial state after backend is fully constructed.
    runOnMainThread(^{
        if (!backend || !webView)
            return;
        const QString title = QString::fromNSString(webView.title ?: @"");
        const bool canGoBack = webView.canGoBack;
        const bool canGoForward = webView.canGoForward;
        QVariantList historyItems = toHistoryItems(webView);
        const int historyIndex = currentHistoryIndex(webView);
        QMetaObject::invokeMethod(backend, [backend, title, canGoBack, canGoForward, historyItems, historyIndex]() {
            backend->setTitle(title);
            backend->setCanGoBack(canGoBack);
            backend->setCanGoForward(canGoForward);
            backend->setHistoryState(historyItems, historyIndex);
        }, Qt::QueuedConnection);
    });

    // Snapshot geometry now (on Qt thread) before jumping to main thread.
    QPointF scenePos = q_ptr->mapToScene(QPointF(0, 0));
    CGFloat geoX = scenePos.x();
    CGFloat geoY = scenePos.y();
    CGFloat geoW = q_ptr->width();
    CGFloat geoH = q_ptr->height();
    const bool currentVisible = q_ptr->isVisible();
    const bool showNativeWeb = shouldShowNativeWebView(currentVisible);

    DarwinWebViewPrivate *self = this;
    QPointer<MobileWebViewBackend> guard(backend);
    dispatch_async(dispatch_get_main_queue(), ^{
        if (!guard || !self || webView != self->m_webView) {
            if (webView) {
                [webView setHidden:YES];
                [webView removeFromSuperview];
            }
            return;
        }
        if (wasSetup) {
            [webView removeFromSuperview];
        }
        [hostView addSubview:webView];

        // Apply geometry immediately so the view is not stuck at CGRectZero.
#ifdef Q_OS_IOS
        if (geoW > 0 && geoH > 0) {
            webView.frame = CGRectMake(geoX, geoY, geoW, geoH);
        }
#endif

        // Unhide only when the item is actually visible; if not, the
        // ItemVisibleHasChanged handler will call updateNativeVisibility later.
        [webView setHidden:!showNativeWeb];
    });

    if (createdNow) {
        ensureBridgeInstalled();
        if (m_hasLastHtml) {
            loadHtmlImpl(m_lastHtml, m_lastHtmlBaseUrl);
        } else if (m_url.isValid() && !m_url.isEmpty()) {
            loadUrlImpl(m_url);
        }
    }
}

void DarwinWebViewPrivate::captureSnapshotImpl(quint64 requestId)
{
    SnapshotCapture::capture(m_webView, this, requestId);
}

void DarwinWebViewPrivate::startDownloadImpl(quint64 downloadId, const QUrl &url,
                                             const QString &destinationPath)
{
    if (!m_downloadDelegate) {
        QMetaObject::invokeMethod(q_ptr, [this, downloadId]() {
            onDownloadFinished(downloadId, false,
                               QStringLiteral("Downloads unsupported on this OS version"));
        }, Qt::QueuedConnection);
        return;
    }

    DownloadDelegate *delegate = m_downloadDelegate;
    WKWebView *webView = m_webView;
    NSString *path = destinationPath.toNSString();
    NSURL *nsUrl = url.toNSURL();

    // Page-initiated: id is already registered → provide destination (may stash).
    // Explicit downloadUrl(): id unknown to the delegate → start a new WKDownload.
    if ([delegate provideDestinationPath:path forDownloadId:downloadId])
        return;

    runOnMainThread(^{
        [delegate startExplicitDownloadWithURL:nsUrl
                                    downloadId:downloadId
                               destinationPath:path
                                       webView:webView];
    });
}

void DarwinWebViewPrivate::cancelDownloadImpl(quint64 downloadId)
{
    if (!m_downloadDelegate)
        return;
    DownloadDelegate *delegate = m_downloadDelegate;
    runOnMainThread(^{
        [delegate cancelDownloadId:downloadId];
    });
}

void DarwinWebViewPrivate::pauseDownloadImpl(quint64 downloadId)
{
    if (!m_downloadDelegate)
        return;
    DownloadDelegate *delegate = m_downloadDelegate;
    runOnMainThread(^{
        [delegate pauseDownloadId:downloadId];
    });
}

void DarwinWebViewPrivate::resumeDownloadImpl(quint64 downloadId)
{
    if (!m_downloadDelegate) {
        QMetaObject::invokeMethod(q_ptr, [this, downloadId]() {
            onDownloadFinished(downloadId, false, QStringLiteral("Resume data unavailable"));
        }, Qt::QueuedConnection);
        return;
    }
    DownloadDelegate *delegate = m_downloadDelegate;
    WKWebView *webView = m_webView;
    runOnMainThread(^{
        [delegate resumeDownloadId:downloadId webView:webView];
    });
}

// =============================================================================
// Factory function for Darwin
// =============================================================================

MobileWebViewBackendPrivate *createPlatformBackend(MobileWebViewBackend *q)
{
    return new DarwinWebViewPrivate(q);
}

#endif // Q_OS_MACOS || Q_OS_IOS
