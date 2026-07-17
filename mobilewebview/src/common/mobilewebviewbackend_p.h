#pragma once

#include <QUrl>
#include <QStringList>
#include <QVariantList>
#include <QWebChannel>
#include <QRectF>
#include <QImage>
#include <QSize>
#include <QHash>
#include <QMetaObject>
#include <functional>

class MobileWebViewBackend;
class MobileWebViewDownload;
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
    
    // =========================================================================
    // Mandatory core (pure virtual) — every platform must implement these.
    // =========================================================================
    virtual bool initNativeView() = 0;
    virtual void destroyNativeView() = 0;
    virtual void setupNativeViewImpl() = 0;
    virtual void loadUrlImpl(const QUrl &url) = 0;
    virtual void loadHtmlImpl(const QString &html, const QUrl &baseUrl) = 0;
    virtual void goBackImpl() = 0;
    virtual void goForwardImpl() = 0;
    virtual void goBackOrForwardImpl(int offset) = 0;
    virtual void reloadImpl() = 0;
    virtual void reloadAndBypassCacheImpl() = 0;
    virtual void stopImpl() = 0;
    virtual void clearHistoryImpl() = 0;
    virtual void evaluateJavaScript(const QString &script) = 0;
    virtual void updateNativeGeometry(const QRectF &rect) = 0;
    virtual void updateNativeVisibility(bool visible) = 0;

    // Async snapshot for freeze and public requestSnapshot; must eventually call
    // notifySnapshotReady on the Qt thread
    virtual void captureSnapshotImpl(quint64 requestId) = 0;

    // =========================================================================
    // Optional capability: data clearing.
    // Defaults invoke the completion immediately so the "Clearing" busy counter
    // (beginClear/endClear in mobilewebviewbackend.cpp) never hangs on
    // platforms without clearing support. Overrides must eventually invoke the
    // completion on the Qt thread.
    // =========================================================================
    virtual void clearHttpCacheImpl(std::function<void()> completion);
    virtual void deleteAllCookiesImpl(std::function<void()> completion);
    virtual void clearDomStorageImpl(std::function<void()> completion);
    virtual void clearSiteDataImpl(const QString &origin, std::function<void()> completion);
    virtual bool clearSiteDataSupportedImpl() const { return false; }

    // =========================================================================
    // Optional capability: find-in-page. Defaults: unsupported, no-ops.
    // =========================================================================
    virtual void findTextImpl(const QString &, int) {}
    virtual void stopFindImpl() {}
    virtual bool findSupportedImpl() const { return false; }
    virtual bool hasNativeFindPanelImpl() const { return false; }
    virtual void showFindPanelImpl() {}
    virtual void hideFindPanelImpl() {}

    // =========================================================================
    // Optional capability: downloads (ADR 0005).
    // Platform performs the transfer after host accept(). Default
    // startDownloadImpl immediately reports the download as Interrupted.
    // =========================================================================
    virtual void startDownloadImpl(quint64 downloadId, const QUrl &url,
                                   const QString &destinationPath);
    virtual void cancelDownloadImpl(quint64) {}

    // =========================================================================
    // Optional capability: JS message bridge. Defaults: no bridge, no-ops.
    // =========================================================================
    virtual bool installBridgeImpl(const QString &, const QStringList &,
                                   const QString &, const QString &) { return false; }
    virtual void postMessageToJavaScript(const QString &) {}
    virtual void updateAllowedOriginsImpl(const QStringList &) {}

    // =========================================================================
    // Optional capability: view settings. Defaults: no-ops.
    // =========================================================================
    virtual void updateInteractionEnabled(bool) {}
    virtual void setZoomFactorImpl(qreal) {}
    virtual void setHttpUserAgentImpl(const QString &) {}

    // Called when platform snapshot is ready (Qt thread)
    void notifySnapshotReady(quint64 requestId, const QImage &image);

    // Download lifecycle (Qt thread). Platform calls onDownloadDetected for
    // page-initiated downloads; downloadUrl() uses the same path.
    // createDownload does not emit; emitDownloadRequested notifies the host.
    // Darwin page-initiated flow registers the id with WKDownload before emit
    // so accept() during the handler can supply the destination safely.
    MobileWebViewDownload *createDownload(const QUrl &url,
                                          const QString &suggestedFileName,
                                          const QString &mimeType,
                                          qint64 totalBytes);
    void emitDownloadRequested(MobileWebViewDownload *download);
    MobileWebViewDownload *onDownloadDetected(const QUrl &url,
                                              const QString &suggestedFileName,
                                              const QString &mimeType,
                                              qint64 totalBytes);
    void onDownloadProgress(quint64 downloadId, qint64 receivedBytes, qint64 totalBytes);
    void onDownloadFinished(quint64 downloadId, bool ok, const QString &error);
    void forgetDownload(quint64 downloadId);
    void cancelAllDownloads();
    MobileWebViewDownload *downloadById(quint64 downloadId) const;

    void clearFreezeState();
    void applyFreezeOverlaySizeFromImage(const QImage &image);
    void restoreClipState();

    /// Native WebView is hidden only in Frozen state (overlay replaces it).
    bool shouldShowNativeWebView(bool qmlItemVisible) const
    {
        return qmlItemVisible && m_nativeViewSetup && m_freezeState != FreezeState::Frozen;
    }

    /// Whether the live native view's data store matches the requested store.
    bool nativeViewStoreMatches() const
    {
        if (m_viewStoreOffTheRecord != m_offTheRecord)
            return false;
        // storageName is irrelevant for incognito (always a fresh ephemeral store).
        return m_offTheRecord || m_viewStoreName == m_storageName;
    }

    // Common state shared between platforms
    MobileWebViewBackend *q_ptr;
    bool m_loading = false;
    bool m_loaded = false;
    bool m_nativeViewSetup = false;
    bool m_bridgeInstalled = false;
    int m_pendingClears = 0;
    bool m_interactionEnabled = true;
    bool m_offTheRecord = false;
    QString m_storageName;
    // Store parameters the live native view was actually built with.
    bool m_viewStoreOffTheRecord = false;
    QString m_viewStoreName;
    // Last content loaded, replayed verbatim after an internal store recreate so
    // loadHtml() content survives teardown+rebuild (ADR 0001), not just URL loads.
    bool m_hasLastHtml = false;
    QString m_lastHtml;
    QUrl m_lastHtmlBaseUrl;
    QUrl m_url;
    QString m_title;
    bool m_canGoBack = false;
    bool m_canGoForward = false;
    QVariantList m_historyItems;
    int m_currentHistoryIndex = -1;
    int m_loadProgress = 0;
    QString m_favicon;
    qreal m_zoomFactor = 1.0;
    QString m_httpUserAgent;
    QVariantList m_userScripts;
    QString m_webChannelNamespace = QStringLiteral("qt");
    QString m_invokeKey;
    QStringList m_allowedOrigins;
    QWebChannel *m_channel = nullptr;
    WebChannelTransport *m_transport = nullptr;

    // Freeze: hide native WebView and show last captured frame in Qt scene
    FreezeState m_freezeState = FreezeState::Idle;
    quint64 m_freezeRequestId = 0;
    /// Monotonic id for all captureSnapshotImpl calls (freeze + public snapshots).
    quint64 m_nextSnapshotId = 0;
    bool m_publicSnapshotPending = false;
    quint64 m_publicSnapshotRequestId = 0;
    QSize m_publicSnapshotTargetSize;
    /// DPR from QQuickWindow at requestSnapshot time; used to convert logical targetSize to pixels.
    qreal m_publicSnapshotDpr = 1.0;
    MobileWebViewSnapshotItem *m_snapshotItem = nullptr;
    bool m_freezeClipStateStored = false;
    bool m_clipStateBeforeFreeze = false;

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
    void appendAllowedOrigin(const QString &origin);
    void ensureBridgeInstalled();
    void setupTransport();
    void recreateNativeViewForStore();
    void beginClear();
    void endClear();

    /// Re-sync native overlay position from mapToScene (e.g. after StackView slide).
    void syncNativeGeometryFromScene();

    /// Hide and detach the native view when the QML item leaves the scene (e.g. StackView pop).
    void detachNativeViewFromScene();

    /// Platform hook: remove native view from the window hierarchy (Darwin: removeFromSuperview).
    virtual void detachNativeViewFromSceneImpl() {}

    QMetaObject::Connection m_afterAnimatingConnection;

    // Active (non-terminal) downloads owned by this backend.
    quint64 m_nextDownloadId = 0;
    QHash<quint64, MobileWebViewDownload *> m_downloads;
};

// Factory function for creating platform-specific implementation
// Implemented separately for each platform in platform-specific .cpp/.mm files
MobileWebViewBackendPrivate *createPlatformBackend(MobileWebViewBackend *q);
