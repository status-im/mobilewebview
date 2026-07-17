#pragma once

#include <QHash>
#include <QObject>
#include <QString>
#include <QUrl>
#include <functional>

class MobileWebViewDownload;

/// Owns active Download objects for one backend: ids, map, lifecycle transitions.
/// Platform transfer/cancel and QML emit are injected callbacks (narrow seam).
class DownloadRegistry
{
public:
    using EmitRequested = std::function<void(MobileWebViewDownload *)>;
    using StartTransfer = std::function<void(quint64 id, const QUrl &url, const QString &path)>;
    using CancelPlatform = std::function<void(quint64)>;

    DownloadRegistry(QObject *parent,
                     EmitRequested emitRequested,
                     StartTransfer startTransfer,
                     CancelPlatform cancelPlatform);

    MobileWebViewDownload *create(const QUrl &url,
                                  const QString &platformSuggestion,
                                  const QString &contentDisposition,
                                  const QString &mimeType,
                                  qint64 totalBytes);

    void emitRequested(MobileWebViewDownload *download);

    MobileWebViewDownload *onDetected(const QUrl &url,
                                      const QString &platformSuggestion,
                                      const QString &contentDisposition,
                                      const QString &mimeType,
                                      qint64 totalBytes);

    void onProgress(quint64 downloadId, qint64 receivedBytes, qint64 totalBytes);
    void onFinished(quint64 downloadId, bool ok, const QString &error);
    void forget(quint64 downloadId);
    void cancelAll();
    MobileWebViewDownload *downloadById(quint64 downloadId) const;

private:
    QObject *m_parent = nullptr;
    EmitRequested m_emitRequested;
    StartTransfer m_startTransfer;
    CancelPlatform m_cancelPlatform;
    quint64 m_nextDownloadId = 0;
    QHash<quint64, MobileWebViewDownload *> m_downloads;
};
