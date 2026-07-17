#include "MobileWebView/mobilewebviewdownload.h"

#if defined(Q_OS_ANDROID) || defined(Q_OS_MACOS) || defined(Q_OS_IOS)

MobileWebViewDownload::MobileWebViewDownload(quint64 id,
                                             const QUrl &url,
                                             const QString &suggestedFileName,
                                             const QString &mimeType,
                                             qint64 totalBytes,
                                             bool isInline,
                                             QObject *parent)
    : QObject(parent)
    , m_id(id)
    , m_url(url)
    , m_suggestedFileName(suggestedFileName)
    , m_mimeType(mimeType)
    , m_totalBytes(totalBytes)
    , m_inline(isInline)
{
}

void MobileWebViewDownload::bindTransferHooks(TransferHooks hooks)
{
    m_hooks = std::move(hooks);
}

void MobileWebViewDownload::accept(const QString &destinationPath)
{
    if (m_state != State::Requested || !m_hooks.start)
        return;
    if (destinationPath.isEmpty())
        return;

    m_destinationPath = destinationPath;
    emit destinationPathChanged();
    setInProgress();
    m_hooks.start(m_id, m_url, m_destinationPath);
}

void MobileWebViewDownload::cancel()
{
    if (isTerminal() || !m_hooks.cancel)
        return;

    // Always notify the platform: Requested may still hold a pending destination
    // handler (Apple WKDownload) that must be released with nil.
    m_hooks.cancel(m_id);
    setCancelled();
    deleteLater();
}

void MobileWebViewDownload::pause()
{
    // Inline Downloads write on accept; pause/resume are not applicable.
    if (m_inline)
        return;
    if (m_state != State::InProgress || !m_hooks.pause)
        return;

    m_hooks.pause(m_id);
    setPaused();
}

void MobileWebViewDownload::resume()
{
    if (m_inline)
        return;
    if (m_state != State::Paused || !m_hooks.resume)
        return;

    setInProgress();
    m_hooks.resume(m_id);
}

void MobileWebViewDownload::retry()
{
    if (m_state != State::Interrupted && m_state != State::Cancelled)
        return;
    if (!m_hooks.retryRequest)
        return;
    m_hooks.retryRequest(this);
}

void MobileWebViewDownload::setInProgress()
{
    if (m_state == State::InProgress)
        return;
    m_state = State::InProgress;
    emit stateChanged();
}

void MobileWebViewDownload::setPaused()
{
    if (m_state == State::Paused)
        return;
    m_state = State::Paused;
    emit stateChanged();
}

void MobileWebViewDownload::setProgress(qint64 receivedBytes, qint64 totalBytes)
{
    if (m_state != State::InProgress)
        return;

    if (m_receivedBytes != receivedBytes) {
        m_receivedBytes = receivedBytes;
        emit receivedBytesChanged();
    }
    if (totalBytes >= 0 && m_totalBytes != totalBytes) {
        m_totalBytes = totalBytes;
        emit totalBytesChanged();
    }
}

void MobileWebViewDownload::setCompleted()
{
    if (isTerminal())
        return;
    m_state = State::Completed;
    emit stateChanged();
    emit finished();
}

void MobileWebViewDownload::setInterrupted(const QString &error)
{
    if (isTerminal())
        return;
    m_errorString = error;
    emit errorStringChanged();
    m_state = State::Interrupted;
    emit stateChanged();
    emit finished();
}

void MobileWebViewDownload::setCancelled()
{
    if (isTerminal())
        return;
    m_state = State::Cancelled;
    emit stateChanged();
    emit finished();
}

#endif // Q_OS_ANDROID || Q_OS_MACOS || Q_OS_IOS
