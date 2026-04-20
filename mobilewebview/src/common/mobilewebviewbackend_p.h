#pragma once

#include <QUrl>
#include <QStringList>
#include <QVariantList>
#include <QWebChannel>
#include <QRectF>
#include <QImage>
#include <functional>

class MobileWebViewBackend;
class WebChannelTransport;
class MobileWebViewSnapshotItem;

// Private implementation interface for MobileWebViewBackend
// Platform-specific implementations (Android, Darwin) inherit from this class
class MobileWebViewBackendPrivate
{
public:
    enum class FreezeState {
        Idle,
        Capturing,
        Frozen,
    };

    explicit MobileWebViewBackendPrivate(MobileWebViewBackend *q);
    virtual ~MobileWebViewBackendPrivate();
    
    // Platform-specific virtual methods (pure virtual)
    virtual bool initNativeView() = 0;
    virtual void loadUrlImpl(const QUrl &url) = 0;
    virtual void loadHtmlImpl(const QString &html, const QUrl &baseUrl) = 0;
    virtual void goBackImpl() = 0;
    virtual void goForwardImpl() = 0;
    virtual void goBackOrForwardImpl(int offset) = 0;
    virtual void reloadImpl() = 0;
    virtual void stopImpl() = 0;
    virtual void clearHistoryImpl() = 0;
    virtual void evaluateJavaScript(const QString &script) = 0;
    virtual void updateNativeGeometry(const QRectF &rect) = 0;
    virtual void updateNativeVisibility(bool visible) = 0;
    virtual bool installBridgeImpl(const QString &ns, const QStringList &origins, 
                                   const QString &invokeKey, const QString &webChannelScriptPath) = 0;
    virtual void postMessageToJavaScript(const QString &json) = 0;
    virtual void setupNativeViewImpl() = 0;
    virtual void updateAllowedOriginsImpl(const QStringList &origins) = 0;
    virtual void updateInteractionEnabled(bool enabled) = 0;
    virtual void setZoomFactorImpl(qreal factor) = 0;
    virtual void findTextImpl(const QString &text, int flags) = 0;
    virtual void stopFindImpl() = 0;
    virtual bool findSupportedImpl() const = 0;
    virtual bool hasNativeFindPanelImpl() const = 0;
    virtual void showFindPanelImpl() = 0;
    virtual void hideFindPanelImpl() = 0;

    // Async snapshot for freeze; must eventually call notifyFreezeCaptureFinished on the Qt thread
    virtual void captureSnapshotImpl(quint64 requestId) = 0;

    // Called when platform snapshot is ready (Qt thread)
    void notifyFreezeCaptureFinished(quint64 requestId, const QImage &image);

    void clearFreezeState();
    void updateFreezeOverlayGeometry();

    /// Native WebView is hidden only in Frozen state (overlay replaces it).
    bool shouldShowNativeWebView(bool qmlItemVisible) const
    {
        return qmlItemVisible && m_nativeViewSetup && m_freezeState != FreezeState::Frozen;
    }

    // Common state shared between platforms
    MobileWebViewBackend *q_ptr;
    bool m_loading = false;
    bool m_loaded = false;
    bool m_nativeViewSetup = false;
    bool m_bridgeInstalled = false;
    bool m_interactionEnabled = true;
    QUrl m_url;
    QString m_title;
    bool m_canGoBack = false;
    bool m_canGoForward = false;
    QVariantList m_historyItems;
    int m_currentHistoryIndex = -1;
    int m_loadProgress = 0;
    QString m_favicon;
    qreal m_zoomFactor = 1.0;
    QVariantList m_userScripts;
    QString m_webChannelNamespace = QStringLiteral("qt");
    QString m_invokeKey;
    QStringList m_allowedOrigins;
    QWebChannel *m_channel = nullptr;
    WebChannelTransport *m_transport = nullptr;

    // Freeze: hide native WebView and show last captured frame in Qt scene
    FreezeState m_freezeState = FreezeState::Idle;
    quint64 m_freezeRequestId = 0;
    MobileWebViewSnapshotItem *m_snapshotItem = nullptr;

    // Common methods (implemented in mobilewebviewbackend.cpp)
    void setLoading(bool loading);
    void setLoaded(bool loaded);
    void setTitle(const QString &title);
    void setCanGoBack(bool canGoBack);
    void setCanGoForward(bool canGoForward);
    void setHistoryState(const QVariantList &historyItems, int currentHistoryIndex);
    void setLoadProgress(int progress);
    void setFavicon(const QString &favicon);
    void updateUrlState(const QUrl &url);
    void updateAllowedOrigins(const QStringList &origins);
    void ensureBridgeInstalled();
    void setupTransport();
};

// Factory function for creating platform-specific implementation
// Implemented separately for each platform in platform-specific .cpp/.mm files
MobileWebViewBackendPrivate *createPlatformBackend(MobileWebViewBackend *q);
