#include "downloadregistry.h"

#include "MobileWebView/mobilewebviewdownload.h"
#include "downloadpolicy.h"

DownloadRegistry::DownloadRegistry(QObject *parent,
                                   EmitRequested emitRequested,
                                   StartTransfer startTransfer,
                                   CancelPlatform cancelPlatform)
    : m_parent(parent)
    , m_emitRequested(std::move(emitRequested))
    , m_startTransfer(std::move(startTransfer))
    , m_cancelPlatform(std::move(cancelPlatform))
{
}

MobileWebViewDownload *DownloadRegistry::create(const QUrl &url,
                                                const QString &platformSuggestion,
                                                const QString &contentDisposition,
                                                const QString &mimeType,
                                                qint64 totalBytes)
{
    if (!MobileWebView::DownloadPolicy::isSupportedUrl(url))
        return nullptr;

    const QString name = MobileWebView::DownloadPolicy::suggestedFileName(
        url, platformSuggestion, contentDisposition, mimeType);

    const quint64 id = ++m_nextDownloadId;
    auto *download = new MobileWebViewDownload(id, url, name, mimeType, totalBytes, m_parent);
    download->bindTransferHooks({
        m_startTransfer,
        [this](quint64 downloadId) {
            if (m_cancelPlatform)
                m_cancelPlatform(downloadId);
            forget(downloadId);
        },
    });
    m_downloads.insert(id, download);
    return download;
}

void DownloadRegistry::emitRequested(MobileWebViewDownload *download)
{
    if (!download || !m_emitRequested)
        return;
    m_emitRequested(download);
}

MobileWebViewDownload *DownloadRegistry::onDetected(const QUrl &url,
                                                    const QString &platformSuggestion,
                                                    const QString &contentDisposition,
                                                    const QString &mimeType,
                                                    qint64 totalBytes)
{
    MobileWebViewDownload *download =
        create(url, platformSuggestion, contentDisposition, mimeType, totalBytes);
    emitRequested(download);
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

    if (ok)
        download->setCompleted();
    else
        download->setInterrupted(error);
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
        if (m_cancelPlatform)
            m_cancelPlatform(download->downloadId());
        download->setCancelled();
        download->deleteLater();
    }
}

MobileWebViewDownload *DownloadRegistry::downloadById(quint64 downloadId) const
{
    return m_downloads.value(downloadId, nullptr);
}
