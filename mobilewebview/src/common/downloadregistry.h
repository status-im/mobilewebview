#pragma once

#include <QByteArray>
#include <QHash>
#include <QObject>
#include <QString>
#include <QUrl>
#include <functional>

class MobileWebViewDownload;

/// Owns active Download objects for one backend: ids, map, lifecycle transitions.
/// Platform transfer/cancel/pause/resume and QML emit are injected callbacks (narrow seam).
class DownloadRegistry
{
public:
    using EmitRequested = std::function<void(MobileWebViewDownload *)>;
    using StartTransfer = std::function<void(quint64 id, const QUrl &url, const QString &path)>;
    using CancelPlatform = std::function<void(quint64)>;
    using PausePlatform = std::function<void(quint64)>;
    using ResumePlatform = std::function<void(quint64)>;
    using RetryRequest = std::function<void(MobileWebViewDownload *)>;

    DownloadRegistry(QObject *parent,
                     EmitRequested emitRequested,
                     StartTransfer startTransfer,
                     CancelPlatform cancelPlatform,
                     PausePlatform pausePlatform = {},
                     ResumePlatform resumePlatform = {},
                     RetryRequest retryRequest = {});

    MobileWebViewDownload *create(const QUrl &url,
                                  const QString &platformSuggestion,
                                  const QString &contentDisposition,
                                  const QString &mimeType,
                                  qint64 totalBytes);

    /// Inline (blob:/data:) Download with decoded payload written on accept.
    MobileWebViewDownload *createInline(const QUrl &url,
                                        const QString &platformSuggestion,
                                        const QString &mimeType,
                                        QByteArray payload);

    void emitRequested(MobileWebViewDownload *download);

    MobileWebViewDownload *onDetected(const QUrl &url,
                                      const QString &platformSuggestion,
                                      const QString &contentDisposition,
                                      const QString &mimeType,
                                      qint64 totalBytes);

    MobileWebViewDownload *onInlineDetected(const QUrl &url,
                                            const QString &platformSuggestion,
                                            const QString &mimeType,
                                            QByteArray payload);

    void onProgress(quint64 downloadId, qint64 receivedBytes, qint64 totalBytes);
    void onFinished(quint64 downloadId, bool ok, const QString &error);
    void forget(quint64 downloadId);
    void cancelAll();
    MobileWebViewDownload *downloadById(quint64 downloadId) const;

private:
    void bindHooks(MobileWebViewDownload *download);
    void writeInlinePayload(MobileWebViewDownload *download, const QString &path);

    QObject *m_parent = nullptr;
    EmitRequested m_emitRequested;
    StartTransfer m_startTransfer;
    CancelPlatform m_cancelPlatform;
    PausePlatform m_pausePlatform;
    ResumePlatform m_resumePlatform;
    RetryRequest m_retryRequest;
    quint64 m_nextDownloadId = 0;
    QHash<quint64, MobileWebViewDownload *> m_downloads;
};
