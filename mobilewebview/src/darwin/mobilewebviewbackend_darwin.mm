#include "MobileWebView/mobilewebviewbackend.h"
#include "../common/mobilewebviewbackend_p.h"
#include "../common/origin_utils.h"
#include "origin_utils.h"
#include "navigationdelegate.h"
#include "userscripts.h"
#include "script_utils.h"
#include "dispatch_utils.h"

#import <WebKit/WebKit.h>
#import <dispatch/dispatch.h>

#ifdef Q_OS_IOS
#import <UIKit/UIKit.h>
#else
#import <AppKit/AppKit.h>
#endif

#include <QQuickWindow>
#include <QDebug>
#include <QPointer>
#include <QFile>

#if defined(Q_OS_MACOS) || defined(Q_OS_IOS)

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
    void loadUrlImpl(const QUrl &url) override;
    void loadHtmlImpl(const QString &html, const QUrl &baseUrl) override;
    void goBackImpl() override;
    void goForwardImpl() override;
    void reloadImpl() override;
    void stopImpl() override;
    void evaluateJavaScript(const QString &script) override;
    void updateNativeGeometry(const QRectF &rect) override;
    void updateNativeVisibility(bool visible) override;
    bool installBridgeImpl(const QString &ns, const QStringList &origins, 
                          const QString &invokeKey, const QString &webChannelScriptPath) override;
    void postMessageToJavaScript(const QString &json) override;
    void setupNativeViewImpl() override;
    void updateAllowedOriginsImpl(const QStringList &origins) override;
    
private:
    WKWebView *m_webView = nullptr;
    NavigationDelegate *m_navigationDelegate = nullptr;
    UserScriptsManager *m_userScriptsManager = nullptr;
    void *m_hostView = nullptr;
};

DarwinWebViewPrivate::DarwinWebViewPrivate(MobileWebViewBackend *q)
    : MobileWebViewBackendPrivate(q)
{
    initNativeView();
}

DarwinWebViewPrivate::~DarwinWebViewPrivate()
{
    if (m_navigationDelegate) {
        m_navigationDelegate.owner = nullptr;
    }

    delete m_userScriptsManager;
    m_userScriptsManager = nullptr;

    if (m_webView) {
        WKWebView *webView = m_webView;
        NavigationDelegate *delegate = m_navigationDelegate;

        m_webView = nullptr;
        m_navigationDelegate = nullptr;

        dispatch_async(dispatch_get_main_queue(), ^{
            [webView stopLoading];
            [webView removeFromSuperview];
            webView.navigationDelegate = nil;
            [webView release];
            [delegate release];
        });
    }
}

bool DarwinWebViewPrivate::initNativeView()
{
    WKWebViewConfiguration *config = [[WKWebViewConfiguration alloc] init];
#ifdef QT_DEBUG
    [config.preferences setValue:@YES forKey:@"developerExtrasEnabled"];
#endif

    m_webView = [[WKWebView alloc] initWithFrame:CGRectZero configuration:config];

    m_navigationDelegate = [[NavigationDelegate alloc] init];
    m_navigationDelegate.owner = q_ptr;
    m_webView.navigationDelegate = m_navigationDelegate;

    m_userScriptsManager = new UserScriptsManager(m_webView, q_ptr);

    [m_webView setHidden:YES];

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

    runOnMainThread(^{
        webView.frame = CGRectMake(x, y, w, h);
    });
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

    runOnMainThread(^{
        webView.frame = NSMakeRect(x, y, w, h);
    });
#endif
}

void DarwinWebViewPrivate::updateNativeVisibility(bool visible)
{
    if (!m_webView) {
        return;
    }

    bool shouldBeVisible = visible && m_nativeViewSetup;
    WKWebView *webView = m_webView;

    runOnMainThread(^{
        [webView setHidden:!shouldBeVisible];
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

void DarwinWebViewPrivate::setupNativeViewImpl()
{
    if (!m_webView) {
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

    // Snapshot geometry now (on Qt thread) before jumping to main thread.
    QPointF scenePos = q_ptr->mapToScene(QPointF(0, 0));
    CGFloat geoX = scenePos.x();
    CGFloat geoY = scenePos.y();
    CGFloat geoW = q_ptr->width();
    CGFloat geoH = q_ptr->height();
    bool currentVisible = q_ptr->isVisible();

    dispatch_async(dispatch_get_main_queue(), ^{
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
        [webView setHidden:!currentVisible];
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
