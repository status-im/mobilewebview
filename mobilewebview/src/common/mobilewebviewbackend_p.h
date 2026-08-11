#pragma once

#include <QByteArray>
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
#include <memory>

#include "downloadregistry.h"
#include "downloadtransfer.h"
#include "freezecontroller.h"

class MobileWebViewBackend;
class MobileWebViewDownload;
class WebChannelTransport;
class MobileWebViewSnapshotItem;

// Private implementation interface for MobileWebViewBackend
// Platform-specific implementations (Android, Darwin) inherit from this class
class MobileWebViewBackendPrivate
{
public:
    using FreezeState = FreezeController::State;

    /// Forwards DownloadTransfer ops to the platform virtuals on this private.
    class BackendDownloadTransfer final : public DownloadTransfer
    {
    public:
        explicit BackendDownloadTransfer(MobileWebViewBackendPrivate *owner)
            : m_owner(owner)
        {
        }

        void start(quint64 id, const QUrl &url, const QString &path) override
        {
            if (m_owner)
                m_owner->startDownloadImpl(id, url, path);
        }
        void cancel(quint64 id) override
        {
            if (m_owner)
                m_owner->cancelDownloadImpl(id);
        }
        void pause(quint64 id) override
        {
            if (m_owner)
                m_owner->pauseDownloadImpl(id);
        }
        void resume(quint64 id) override
        {
            if (m_owner)
                m_owner->resumeDownloadImpl(id);
        }

    private:
        MobileWebViewBackendPrivate *m_owner = nullptr;
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
    // Supported-ness is answered from MobileWebViewCapabilities, so the
    // instance property and the static accessor cannot drift apart.
    virtual bool clearSiteDataSupportedImpl() const { return false; }

    // =========================================================================
    // Optional capability: local file loads.
    // Default: a plain loadUrlImpl(), which is correct for engines that accept
    // a top-level file:// through the normal load path (Android WebView, with
    // setAllowFileAccess(true) and "file" in WebViewUrlPolicy). WKWebView
    // ignores file:// URLs passed to -loadRequest: (no sandbox extension
    // reaches the WebContent process), so Darwin overrides this with
    // -loadFileURL:allowingReadAccessToURL:.
    // \a readAccessDirUrl is a directory, already resolved by loadFileUrl() to
    // the file's own directory when the caller passed an empty URL.
    // =========================================================================
    virtual void loadFileUrlImpl(const QUrl &fileUrl, const QUrl &readAccessDirUrl);

    // =========================================================================
    // Optional capability: find-in-page. Defaults: unsupported, no-ops.
    // The two supported-ness overrides answer from MobileWebViewCapabilities,
    // so the instance properties and the static accessors cannot drift apart.
    // =========================================================================
    virtual void findTextImpl(const QString &, int) {}
    virtual void stopFindImpl() {}
    virtual bool findSupportedImpl() const { return false; }
    virtual bool hasNativeFindPanelImpl() const { return false; }
    virtual void showFindPanelImpl() {}
    virtual void hideFindPanelImpl() {}

    // =========================================================================
    // Optional capability: in-page media playback. Default: unsupported.
    // Overrides answer from MobileWebViewCapabilities so the instance property
    // and the static accessor cannot drift apart.
    // =========================================================================
    virtual bool inPageMediaPlaybackSupportedImpl() const { return false; }

    // =========================================================================
    // Optional capability: downloads (ADR 0005).
    // Platform performs the transfer after host accept(). Default
    // startDownloadImpl immediately reports the download as Interrupted.
    // =========================================================================
    virtual void startDownloadImpl(quint64 downloadId, const QUrl &url,
                                   const QString &destinationPath);
    /// downloadUrl() entry. Default: detect with given name, no metadata.
    /// Platforms may probe headers when name is empty (Android). \a token is
    /// carried untouched to downloadRequested.
    virtual void requestUrlDownloadImpl(const QUrl &url, const QString &suggestedFileName,
                                        const QString &token)
    {
        onDownloadDetected(url, suggestedFileName, QString(), QString(), -1, token);
    }
    virtual void cancelDownloadImpl(quint64) {}
    /// Default: interrupt with "pause not supported".
    virtual void pauseDownloadImpl(quint64 downloadId);
    /// Default: interrupt with "resume not supported".
    virtual void resumeDownloadImpl(quint64 downloadId);

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
    /// \a platformSuggestion is a host/engine-resolved name (e.g. WKDownload
    /// suggestedFilename); \a contentDisposition is the raw header when available.
    MobileWebViewDownload *createDownload(const QUrl &url,
                                          const QString &platformSuggestion,
                                          const QString &contentDisposition,
                                          const QString &mimeType,
                                          qint64 totalBytes);
    void emitDownloadRequested(MobileWebViewDownload *download);
    /// Link/image long-press (Qt thread). \a logicalPos: item-local logical px.
    void emitLinkLongPressed(const QUrl &linkUrl, const QUrl &imageUrl, QPointF logicalPos);
    MobileWebViewDownload *onDownloadDetected(const QUrl &url,
                                              const QString &platformSuggestion,
                                              const QString &contentDisposition,
                                              const QString &mimeType,
                                              qint64 totalBytes,
                                              const QString &token = {});
    MobileWebViewDownload *onInlineDownloadDetected(const QUrl &url,
                                                    const QString &platformSuggestion,
                                                    const QString &mimeType,
                                                    QByteArray payload);
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
        if (!m_freeze)
            return qmlItemVisible && m_nativeViewSetup;
        return m_freeze->shouldShowNativeWebView(qmlItemVisible, m_nativeViewSetup);
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
    // Last local-file load, replayed through loadFileUrlImpl (never as a plain
    // URL load, which iOS drops for file://) after a store recreate or a
    // native-view rebuild. Cleared as soon as the view leaves that file.
    bool m_hasLastFileUrl = false;
    QUrl m_lastFileUrl;
    QUrl m_lastFileReadAccessUrl;
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
    std::unique_ptr<FreezeController> m_freeze;
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
    BackendDownloadTransfer m_downloadTransfer{this};
    std::unique_ptr<DownloadRegistry> m_downloadRegistry;
};

// Factory function for creating platform-specific implementation
// Implemented separately for each platform in platform-specific .cpp/.mm files
MobileWebViewBackendPrivate *createPlatformBackend(MobileWebViewBackend *q);
