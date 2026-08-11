#pragma once

#include "downloadtransfer.h"
#include "inlinedownloadwriter.h"

#include <QByteArray>
#include <QHash>
#include <QObject>
#include <QString>
#include <QUrl>
#include <functional>

class MobileWebViewDownload;

/// Owns active Download objects for one backend: ids, map, lifecycle transitions.
/// Platform transfer is a DownloadTransfer adapter; inline bytes live in the writer.
class DownloadRegistry
{
public:
    /// \a token is the opaque host correlation token from downloadUrl(),
    /// echoed verbatim; empty for page-initiated Downloads.
    using EmitRequested = std::function<void(MobileWebViewDownload *, const QString &token)>;

    DownloadRegistry(QObject *parent,
                     EmitRequested emitRequested,
                     DownloadTransfer *transfer);

    /// Network or inline create. Non-empty \a payload selects the inline path
    /// (requires an inline URL scheme); empty payload requires a fetchable URL.
    MobileWebViewDownload *create(const QUrl &url,
                                  const QString &platformSuggestion,
                                  const QString &contentDisposition,
                                  const QString &mimeType,
                                  qint64 totalBytes,
                                  QByteArray payload = {});

    void emitRequested(MobileWebViewDownload *download, const QString &token = {});

    MobileWebViewDownload *onDetected(const QUrl &url,
                                      const QString &platformSuggestion,
                                      const QString &contentDisposition,
                                      const QString &mimeType,
                                      qint64 totalBytes,
                                      QByteArray payload = {},
                                      const QString &token = {});

    void onProgress(quint64 downloadId, qint64 receivedBytes, qint64 totalBytes);
    void onFinished(quint64 downloadId, bool ok, const QString &error);
    void forget(quint64 downloadId);
    void cancelAll();
    MobileWebViewDownload *downloadById(quint64 downloadId) const;

    /// From Interrupted/Cancelled: emit a new Download Request (does not revive).
    void retry(MobileWebViewDownload *download);

    InlineDownloadWriter &inlineWriter() { return m_inlineWriter; }

private:
    void bindHooks(MobileWebViewDownload *download);

    QObject *m_parent = nullptr;
    EmitRequested m_emitRequested;
    DownloadTransfer *m_transfer = nullptr;
    InlineDownloadWriter m_inlineWriter;
    quint64 m_nextDownloadId = 0;
    QHash<quint64, MobileWebViewDownload *> m_downloads;
};
