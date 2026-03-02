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
    Q_PROPERTY(QVariantList userScripts READ userScripts WRITE setUserScripts NOTIFY userScriptsChanged)
    Q_PROPERTY(QString webChannelNamespace READ webChannelNamespace WRITE setWebChannelNamespace NOTIFY webChannelNamespaceChanged)
    Q_PROPERTY(QWebChannel* webChannel READ webChannel WRITE setWebChannel NOTIFY webChannelChanged)

public:
    explicit MobileWebViewBackend(QQuickItem *parent = nullptr);
    ~MobileWebViewBackend() override;

    // Property accessors
    bool loading() const;
    bool loaded() const;
    QUrl url() const;
    void setUrl(const QUrl &url);
    QVariantList userScripts() const;
    void setUserScripts(const QVariantList &scripts);
    QString webChannelNamespace() const;
    void setWebChannelNamespace(const QString &ns);
    QWebChannel* webChannel() const;
    void setWebChannel(QWebChannel* channel);

    // Internal methods (used by private implementation and platform delegates)
    void updateUrlState(const QUrl &url);
    void updateAllowedOrigins(const QStringList &origins);
    void setLoadingState(bool loading);
    void setLoadedState(bool loaded);

public slots:
    void loadUrl(const QUrl &url);
    void loadHtml(const QString &html, const QUrl &baseUrl = QUrl());
    void goBack();
    void goForward();
    void reload();
    void stop();

    // Install WebChannel bridge; must be called BEFORE loadUrl/loadHtml
    bool installMessageBridge(const QString &ns,
                              const QStringList &allowedOrigins,
                              const QString &invokeKey,
                              const QString &webChannelScriptPath = QString());

    // Post a JSON message to JavaScript via WebChannel transport
    void postMessageToJavaScript(const QString &json);

    // Execute JavaScript code in the web view
    void runJavaScript(const QString &script);

signals:
    void loadingChanged();
    void loadedChanged();
    void urlChanged();
    void userScriptsChanged();
    void webChannelNamespaceChanged();
    void webChannelChanged();

    // Emitted when a message is received from JavaScript
    void webMessageReceived(const QString &message, const QString &origin, bool isMainFrame);

    // Emitted when JavaScript execution completes
    void javaScriptResult(const QVariant &result, const QString &error);

protected:
    void geometryChange(const QRectF &newGeometry, const QRectF &oldGeometry) override;
    void itemChange(ItemChange change, const ItemChangeData &value) override;
    void updatePolish() override;

private:
    Q_DECLARE_PRIVATE(MobileWebViewBackend)
    QScopedPointer<MobileWebViewBackendPrivate> d_ptr;
};

#endif // Q_OS_ANDROID || Q_OS_MACOS || Q_OS_IOS
