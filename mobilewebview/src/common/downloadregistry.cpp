#include "downloadregistry.h"

#include "MobileWebView/mobilewebviewdownload.h"
#include "downloadpolicy.h"

#include <QFile>

DownloadRegistry::DownloadRegistry(QObject *parent,
                                   EmitRequested emitRequested,
                                   StartTransfer startTransfer,
                                   CancelPlatform cancelPlatform,
                                   PausePlatform pausePlatform,
                                   ResumePlatform resumePlatform,
                                   RetryRequest retryRequest)
    : m_parent(parent)
    , m_emitRequested(std::move(emitRequested))
    , m_startTransfer(std::move(startTransfer))
    , m_cancelPlatform(std::move(cancelPlatform))
    , m_pausePlatform(std::move(pausePlatform))
    , m_resumePlatform(std::move(resumePlatform))
    , m_retryRequest(std::move(retryRequest))
{
}

void DownloadRegistry::bindHooks(MobileWebViewDownload *download)
{
    if (!download)
        return;

    download->bindTransferHooks({
        [this](quint64 downloadId, const QUrl &url, const QString &path) {
            MobileWebViewDownload *item = m_downloads.value(downloadId, nullptr);
            if (!item)
                return;
            // Inline: write decoded bytes locally; pause/resume are no-ops on the object.
            if (item->hasInlinePayload()) {
                writeInlinePayload(item, path);
                return;
            }
            if (m_startTransfer)
                m_startTransfer(downloadId, url, path);
        },
        [this](quint64 downloadId) {
            if (m_cancelPlatform)
                m_cancelPlatform(downloadId);
            forget(downloadId);
        },
        [this](quint64 downloadId) {
            if (m_pausePlatform)
                m_pausePlatform(downloadId);
            // Keep id in map while Paused so resume/cancelAll still find it.
        },
        [this](quint64 downloadId) {
            if (m_resumePlatform)
                m_resumePlatform(downloadId);
        },
        [this](MobileWebViewDownload *item) {
            if (m_retryRequest)
                m_retryRequest(item);
        },
    });
}

void DownloadRegistry::writeInlinePayload(MobileWebViewDownload *download, const QString &path)
{
    if (!download)
        return;

    const QByteArray payload = download->inlinePayload();
    const quint64 id = download->downloadId();

    QFile file(path);
    if (!file.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
        onFinished(id, false, QStringLiteral("Failed to open destination: %1").arg(file.errorString()));
        return;
    }
    if (file.write(payload) != payload.size()) {
        file.close();
        onFinished(id, false, QStringLiteral("Failed to write inline download"));
        return;
    }
    file.close();

    const qint64 size = payload.size();
    onProgress(id, size, size);
    onFinished(id, true, QString());
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
    bindHooks(download);
    m_downloads.insert(id, download);
    return download;
}

MobileWebViewDownload *DownloadRegistry::createInline(const QUrl &url,
                                                      const QString &platformSuggestion,
                                                      const QString &mimeType,
                                                      QByteArray payload)
{
    if (!MobileWebView::DownloadPolicy::isInlineUrl(url))
        return nullptr;
    if (payload.isEmpty())
        return nullptr;

    const QString name = MobileWebView::DownloadPolicy::suggestedFileName(
        url, platformSuggestion, QString(), mimeType);

    const quint64 id = ++m_nextDownloadId;
    auto *download = new MobileWebViewDownload(
        id, url, name, mimeType, static_cast<qint64>(payload.size()), m_parent);
    download->setInlinePayload(std::move(payload));
    bindHooks(download);
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

MobileWebViewDownload *DownloadRegistry::onInlineDetected(const QUrl &url,
                                                          const QString &platformSuggestion,
                                                          const QString &mimeType,
                                                          QByteArray payload)
{
    MobileWebViewDownload *download =
        createInline(url, platformSuggestion, mimeType, std::move(payload));
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
        // Includes Paused transfers.
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
