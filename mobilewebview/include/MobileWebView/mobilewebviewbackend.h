#pragma once

#include <QQuickItem>
#include <QSize>
#include <QUrl>
#include <QStringList>
#include <QVariantList>
#include <QWebChannel>

#include "MobileWebView/mobilewebviewdownload.h"

#if defined(Q_OS_ANDROID) || defined(Q_OS_MACOS) || defined(Q_OS_IOS)

class MobileWebViewBackendPrivate;

// Unified native WebView integration for mobile platforms (Android, macOS, iOS)
// Uses pimpl pattern to hide platform-specific implementation details
class MobileWebViewBackend : public QQuickItem
{
    Q_OBJECT
    QML_ELEMENT
    Q_PROPERTY(bool loading READ loading NOTIFY loadingChanged)
    Q_PROPERTY(bool loaded READ loaded NOTIFY loadedChanged)
    Q_PROPERTY(QUrl url READ url WRITE setUrl NOTIFY urlChanged)
    Q_PROPERTY(QString title READ title NOTIFY titleChanged)
    Q_PROPERTY(bool canGoBack READ canGoBack NOTIFY canGoBackChanged)
    Q_PROPERTY(bool canGoForward READ canGoForward NOTIFY canGoForwardChanged)
    Q_PROPERTY(QVariantList historyItems READ historyItems NOTIFY historyItemsChanged)
    Q_PROPERTY(int currentHistoryIndex READ currentHistoryIndex NOTIFY currentHistoryIndexChanged)
    Q_PROPERTY(QVariantList userScripts READ userScripts WRITE setUserScripts NOTIFY userScriptsChanged)
    Q_PROPERTY(QString webChannelNamespace READ webChannelNamespace WRITE setWebChannelNamespace NOTIFY webChannelNamespaceChanged)
    Q_PROPERTY(QWebChannel* webChannel READ webChannel WRITE setWebChannel NOTIFY webChannelChanged)
    Q_PROPERTY(bool interactionEnabled READ interactionEnabled WRITE setInteractionEnabled NOTIFY interactionEnabledChanged)
    Q_PROPERTY(int loadProgress READ loadProgress NOTIFY loadProgressChanged)
    Q_PROPERTY(QString favicon READ favicon NOTIFY faviconChanged)
    Q_PROPERTY(qreal zoomFactor READ zoomFactor WRITE setZoomFactor NOTIFY zoomFactorChanged)
    Q_PROPERTY(bool findSupported READ findSupported CONSTANT)
    Q_PROPERTY(bool hasNativeFindPanel READ hasNativeFindPanel CONSTANT)
    Q_PROPERTY(bool clearSiteDataSupported READ clearSiteDataSupported CONSTANT)
    Q_PROPERTY(bool freeze READ freeze WRITE setFreeze NOTIFY freezeChanged)
    Q_PROPERTY(bool offTheRecord READ offTheRecord WRITE setOffTheRecord NOTIFY offTheRecordChanged)
    Q_PROPERTY(QString storageName READ storageName WRITE setStorageName NOTIFY storageNameChanged)
    Q_PROPERTY(QString httpUserAgent READ httpUserAgent WRITE setHttpUserAgent NOTIFY httpUserAgentChanged)
    Q_PROPERTY(bool clearing READ clearing NOTIFY clearingChanged)

public:
    explicit MobileWebViewBackend(QQuickItem *parent = nullptr);
    ~MobileWebViewBackend() override;

    // Property accessors
    bool loading() const;
    bool loaded() const;
    QUrl url() const;
    QString title() const;
    bool canGoBack() const;
    bool canGoForward() const;
    QVariantList historyItems() const;
    int currentHistoryIndex() const;
    void setUrl(const QUrl &url);
    QVariantList userScripts() const;
    void setUserScripts(const QVariantList &scripts);
    QString webChannelNamespace() const;
    void setWebChannelNamespace(const QString &ns);
    QWebChannel* webChannel() const;
    void setWebChannel(QWebChannel* channel);
    bool interactionEnabled() const;
    void setInteractionEnabled(bool enabled);
    int loadProgress() const;
    QString favicon() const;
    qreal zoomFactor() const;
    void setZoomFactor(qreal factor);
    bool findSupported() const;
    bool hasNativeFindPanel() const;
    bool clearSiteDataSupported() const;
    bool freeze() const;
    void setFreeze(bool freeze);
    bool offTheRecord() const;
    void setOffTheRecord(bool offTheRecord);
    QString storageName() const;
    void setStorageName(const QString &storageName);
    QString httpUserAgent() const;
    void setHttpUserAgent(const QString &httpUserAgent);
    bool clearing() const;

    /// Async native snapshot. On success, snapshotReady carries a stable
    /// image://mobilewebview-snapshot/<key> URL for this backend instance (Image.source).
    /// \a targetSize, if set, is in logical (device-independent) points like QML item sizes;
    /// the image is scaled to that size times the window devicePixelRatio. Omit for full native size.
    Q_INVOKABLE void requestSnapshot(const QSize &targetSize = QSize());

    // Internal methods (used by private implementation and platform delegates)
    void updateUrlState(const QUrl &url);
    void updateAllowedOrigins(const QStringList &origins);
    void setLoadingState(bool loading);
    void setLoadedState(bool loaded);
    void setTitle(const QString &title);
    void setCanGoBack(bool canGoBack);
    void setCanGoForward(bool canGoForward);
    void setHistoryState(const QVariantList &historyItems, int currentHistoryIndex);
    void setLoadProgress(int progress);
    void setFavicon(const QString &favicon);
    void emitNewWindowRequested(const QUrl &url, bool userInitiated);

    // Platform → common download bridge (Qt thread)
    MobileWebViewDownload *beginDownload(const QUrl &url,
                                         const QString &suggestedFileName,
                                         const QString &mimeType,
                                         qint64 totalBytes,
                                         const QString &contentDisposition = QString());
    /// Inline blob:/data: Download from a base64 payload (JS bridge). nullptr on failure.
    MobileWebViewDownload *beginInlineDownload(const QUrl &url,
                                               const QString &suggestedFileName,
                                               const QString &mimeType,
                                               const QString &base64Payload);
    /// Create without emitting; pair with emitDownloadRequested after platform registration.
    MobileWebViewDownload *createDownload(const QUrl &url,
                                          const QString &suggestedFileName,
                                          const QString &mimeType,
                                          qint64 totalBytes,
                                          const QString &contentDisposition = QString());
    void emitDownloadRequested(MobileWebViewDownload *download);
    void reportDownloadProgress(quint64 downloadId, qint64 receivedBytes, qint64 totalBytes);
    void reportDownloadFinished(quint64 downloadId, bool ok, const QString &error = QString());

public slots:
    void loadUrl(const QUrl &url);
    void loadHtml(const QString &html, const QUrl &baseUrl = QUrl());
    void goBack();
    void goForward();
    void goBackOrForward(int offset);
    void reload();
    void reloadAndBypassCache();
    void stop();
    void clearHistory();
    void clearHttpCache();
    void deleteAllCookies();
    void clearDomStorage();
    void clearSiteData();
    void clearProfileData();

    /// Explicit download trigger ("save link", host-side retry). Emits downloadRequested.
    void downloadUrl(const QUrl &url, const QString &suggestedFileName = QString());

    // Install WebChannel bridge; must be called BEFORE loadUrl/loadHtml
    bool installMessageBridge(const QString &ns,
                              const QStringList &allowedOrigins,
                              const QString &invokeKey,
                              const QString &webChannelScriptPath = QString());

    // Post a JSON message to JavaScript via WebChannel transport
    void postMessageToJavaScript(const QString &json);

    // Execute JavaScript code in the web view
    void runJavaScript(const QString &script);

    // Find text in the page; flags: 0 = forward, 1 = backwards, 2 = case-sensitive
    void findText(const QString &text, int flags = 0);

    // Stop an active find session and clear highlights
    void stopFind();

    // Show/hide the platform's native find-in-page panel when available.
    void showFindPanel();
    void hideFindPanel();

signals:
    void loadingChanged();
    void loadedChanged();
    void urlChanged();
    void titleChanged();
    void canGoBackChanged();
    void canGoForwardChanged();
    void historyItemsChanged();
    void currentHistoryIndexChanged();
    void userScriptsChanged();
    void webChannelNamespaceChanged();
    void webChannelChanged();
    void interactionEnabledChanged();
    void loadProgressChanged();
    void faviconChanged();
    void zoomFactorChanged();
    void freezeChanged();
    void offTheRecordChanged();
    void storageNameChanged();
    void httpUserAgentChanged();
    void clearingChanged();

    void clearHttpCacheCompleted();
    void deleteAllCookiesCompleted();
    void clearDomStorageCompleted();
    void clearSiteDataCompleted();
    void clearProfileDataCompleted();

    // Emitted when a message is received from JavaScript
    void webMessageReceived(const QString &message, const QString &origin, bool isMainFrame);
    void newWindowRequested(const QUrl &url, bool userInitiated);

    /// Emitted when a Download is detected (page-initiated or downloadUrl).
    /// Host must accept(destination) or cancel(); no accept ⇒ cancelled on destroy/profile switch.
    void downloadRequested(MobileWebViewDownload *download);

    /// Long-press on a link and/or image (Android; other platforms never emit).
    /// linkUrl is the anchor href, imageUrl the <img> src — either may be empty,
    /// never both. position is item-local logical px for anchoring a menu.
    /// downloadUrl() is the matching "save link" trigger.
    void linkLongPressed(const QUrl &linkUrl, const QUrl &imageUrl, const QPointF &position);

    // Emitted when JavaScript execution completes
    void javaScriptResult(const QVariant &result, const QString &error);

    // Emitted when a find-in-page result is available
    // activeMatchIndex: 0-based index of the current match (-1 if none)
    // matchCount: total number of matches (0 if none / search cleared)
    void findTextResult(int activeMatchIndex, int matchCount);

    /// Emitted when requestSnapshot completes (\a imageUrl empty if \a ok is false).
    void snapshotReady(const QUrl &imageUrl, bool ok);

protected:
    void componentComplete() override;
    void geometryChange(const QRectF &newGeometry, const QRectF &oldGeometry) override;
    void itemChange(ItemChange change, const ItemChangeData &value) override;
    void updatePolish() override;

private:
    Q_DECLARE_PRIVATE(MobileWebViewBackend)
    QScopedPointer<MobileWebViewBackendPrivate> d_ptr;
};

#endif // Q_OS_ANDROID || Q_OS_MACOS || Q_OS_IOS
