#include "downloadregistry.h"

#include "MobileWebView/mobilewebviewdownload.h"
#include "downloadpolicy.h"

DownloadRegistry::DownloadRegistry(QObject *parent,
                                   EmitRequested emitRequested,
                                   DownloadTransfer *transfer)
    : m_parent(parent)
    , m_emitRequested(std::move(emitRequested))
    , m_transfer(transfer)
{
}

void DownloadRegistry::bindHooks(MobileWebViewDownload *download)
{
    if (!download)
        return;

    download->bindTransferHooks({
        [this](quint64 downloadId, const QUrl &url, const QString &path) {
            if (m_inlineWriter.has(downloadId)) {
                const auto result = m_inlineWriter.write(downloadId, path);
                if (!result.ok) {
                    onFinished(downloadId, false, result.error);
                    return;
                }
                onProgress(downloadId, result.bytesWritten, result.bytesWritten);
                onFinished(downloadId, true, QString());
                return;
            }
            if (m_transfer)
                m_transfer->start(downloadId, url, path);
        },
        [this](quint64 downloadId) {
            if (m_transfer)
                m_transfer->cancel(downloadId);
            // Keep inline payload for retry() from Cancelled; cancelAll discards.
            forget(downloadId);
        },
        [this](quint64 downloadId) {
            if (m_transfer)
                m_transfer->pause(downloadId);
            // Keep id in map while Paused so resume/cancelAll still find it.
        },
        [this](quint64 downloadId) {
            if (m_transfer)
                m_transfer->resume(downloadId);
        },
        [this](MobileWebViewDownload *item) { retry(item); },
    });
}

MobileWebViewDownload *DownloadRegistry::create(const QUrl &url,
                                                const QString &platformSuggestion,
                                                const QString &contentDisposition,
                                                const QString &mimeType,
                                                qint64 totalBytes,
                                                QByteArray payload)
{
    const bool inlineKind = !payload.isEmpty();
    if (inlineKind) {
        if (!MobileWebView::DownloadPolicy::isInlineUrl(url))
            return nullptr;
    } else if (!MobileWebView::DownloadPolicy::isSupportedUrl(url)) {
        return nullptr;
    }

    const QString name = MobileWebView::DownloadPolicy::suggestedFileName(
        url, platformSuggestion, contentDisposition, mimeType);

    const qint64 bytes = inlineKind ? static_cast<qint64>(payload.size()) : totalBytes;
    const quint64 id = ++m_nextDownloadId;
    auto *download = new MobileWebViewDownload(
        id, url, name, mimeType, bytes, inlineKind, m_parent);
    if (inlineKind)
        m_inlineWriter.store(id, std::move(payload));
    bindHooks(download);
    m_downloads.insert(id, download);
    return download;
}

void DownloadRegistry::emitRequested(MobileWebViewDownload *download, const QString &token)
{
    if (!download || !m_emitRequested)
        return;
    m_emitRequested(download, token);
}

MobileWebViewDownload *DownloadRegistry::onDetected(const QUrl &url,
                                                    const QString &platformSuggestion,
                                                    const QString &contentDisposition,
                                                    const QString &mimeType,
                                                    qint64 totalBytes,
                                                    QByteArray payload,
                                                    const QString &token)
{
    MobileWebViewDownload *download = create(
        url, platformSuggestion, contentDisposition, mimeType, totalBytes, std::move(payload));
    emitRequested(download, token);
    return download;
}

void DownloadRegistry::onProgress(quint64 downloadId, qint64 receivedBytes, qint64 totalBytes)
{
    if (auto *download = m_downloads.value(downloadId, nullptr))
        download->setProgress(receivedBytes, totalBytes);
}

void DownloadRegistry::onFinished(quint64 downloadId, bool ok, const QString &error)
{
    auto *download = m_downloads.take(downloadId);
    if (!download)
        return;

    if (ok) {
        m_inlineWriter.discard(downloadId); // already freed on write success; safe no-op
        download->setCompleted();
    } else {
        // Keep inline payload for retry() from Interrupted.
        download->setInterrupted(error);
    }
    // deleteLater: host (DownloadsStore) may still hold a ref and call retry().
    download->deleteLater();
}

void DownloadRegistry::forget(quint64 downloadId)
{
    m_downloads.remove(downloadId);
}

void DownloadRegistry::cancelAll()
{
    const auto active = m_downloads;
    m_downloads.clear();
    for (auto it = active.cbegin(); it != active.cend(); ++it) {
        MobileWebViewDownload *download = it.value();
        if (!download)
            continue;
        if (m_transfer)
            m_transfer->cancel(download->downloadId());
        m_inlineWriter.discard(download->downloadId());
        download->setCancelled();
        download->deleteLater();
    }
}

MobileWebViewDownload *DownloadRegistry::downloadById(quint64 downloadId) const
{
    return m_downloads.value(downloadId, nullptr);
}

void DownloadRegistry::retry(MobileWebViewDownload *download)
{
    if (!download)
        return;
    if (download->state() != MobileWebViewDownload::State::Interrupted
        && download->state() != MobileWebViewDownload::State::Cancelled) {
        return;
    }

    if (download->isInline()) {
        QByteArray payload = m_inlineWriter.takePayload(download->downloadId());
        if (payload.isEmpty())
            return;
        onDetected(download->url(),
                   download->suggestedFileName(),
                   QString(),
                   download->mimeType(),
                   -1,
                   std::move(payload));
        return;
    }

    onDetected(download->url(),
               download->suggestedFileName(),
               QString(),
               download->mimeType(),
               -1);
}
