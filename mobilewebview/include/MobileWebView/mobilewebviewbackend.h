#pragma once

#include <QQuickItem>
#include <QUrl>
#include <QStringList>
#include <QVariantList>
#include <QWebChannel>

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
    Q_PROPERTY(bool freeze READ freeze WRITE setFreeze NOTIFY freezeChanged)

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
    bool freeze() const;
    void setFreeze(bool freeze);

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

public slots:
    void loadUrl(const QUrl &url);
    void loadHtml(const QString &html, const QUrl &baseUrl = QUrl());
    void goBack();
    void goForward();
    void goBackOrForward(int offset);
    void reload();
    void stop();
    void clearHistory();

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

    // Emitted when a message is received from JavaScript
    void webMessageReceived(const QString &message, const QString &origin, bool isMainFrame);
    void newWindowRequested(const QUrl &url, bool userInitiated);

    // Emitted when JavaScript execution completes
    void javaScriptResult(const QVariant &result, const QString &error);

    // Emitted when a find-in-page result is available
    // activeMatchIndex: 0-based index of the current match (-1 if none)
    // matchCount: total number of matches (0 if none / search cleared)
    void findTextResult(int activeMatchIndex, int matchCount);

protected:
    void geometryChange(const QRectF &newGeometry, const QRectF &oldGeometry) override;
    void itemChange(ItemChange change, const ItemChangeData &value) override;
    void updatePolish() override;

private:
    Q_DECLARE_PRIVATE(MobileWebViewBackend)
    QScopedPointer<MobileWebViewBackendPrivate> d_ptr;
};

#endif // Q_OS_ANDROID || Q_OS_MACOS || Q_OS_IOS
