#pragma once

#include <QObject>
#include <QUrl>
#include <QtQml/qqmlregistration.h>

#include <functional>

#if defined(Q_OS_ANDROID) || defined(Q_OS_MACOS) || defined(Q_OS_IOS)

/// One Download for its full lifecycle: Requested → InProgress → terminal state.
/// Created only by MobileWebViewBackend; surfaced via downloadRequested().
class MobileWebViewDownload : public QObject
{
    Q_OBJECT
    QML_ELEMENT
    QML_UNCREATABLE("Created by MobileWebViewBackend")
    Q_PROPERTY(quint64 downloadId READ downloadId CONSTANT)
    Q_PROPERTY(QUrl url READ url CONSTANT)
    Q_PROPERTY(QString suggestedFileName READ suggestedFileName CONSTANT)
    Q_PROPERTY(QString mimeType READ mimeType CONSTANT)
    Q_PROPERTY(qint64 totalBytes READ totalBytes NOTIFY totalBytesChanged)
    Q_PROPERTY(qint64 receivedBytes READ receivedBytes NOTIFY receivedBytesChanged)
    Q_PROPERTY(State state READ state NOTIFY stateChanged)
    Q_PROPERTY(QString destinationPath READ destinationPath NOTIFY destinationPathChanged)
    Q_PROPERTY(QString errorString READ errorString NOTIFY errorStringChanged)

public:
    enum class State {
        Requested = 0,
        InProgress = 1,
        Completed = 2,
        Cancelled = 3,
        Interrupted = 4,
    };
    Q_ENUM(State)

    struct TransferHooks {
        std::function<void(quint64 id, const QUrl &url, const QString &destination)> start;
        // Platform cancel + registry forget; download still does setCancelled+deleteLater.
        std::function<void(quint64 id)> cancel;
    };

    quint64 downloadId() const { return m_id; }
    QUrl url() const { return m_url; }
    QString suggestedFileName() const { return m_suggestedFileName; }
    QString mimeType() const { return m_mimeType; }
    qint64 totalBytes() const { return m_totalBytes; }
    qint64 receivedBytes() const { return m_receivedBytes; }
    State state() const { return m_state; }
    QString destinationPath() const { return m_destinationPath; }
    QString errorString() const { return m_errorString; }

    bool isTerminal() const
    {
        return m_state == State::Completed
            || m_state == State::Cancelled
            || m_state == State::Interrupted;
    }

    Q_INVOKABLE void accept(const QString &destinationPath);
    Q_INVOKABLE void cancel();

signals:
    void stateChanged();
    void totalBytesChanged();
    void receivedBytesChanged();
    void destinationPathChanged();
    void errorStringChanged();
    void finished();

private:
    friend class DownloadRegistry;

    explicit MobileWebViewDownload(quint64 id,
                                   const QUrl &url,
                                   const QString &suggestedFileName,
                                   const QString &mimeType,
                                   qint64 totalBytes,
                                   QObject *parent = nullptr);

    void bindTransferHooks(TransferHooks hooks);
    void setInProgress();
    void setProgress(qint64 receivedBytes, qint64 totalBytes);
    void setCompleted();
    void setInterrupted(const QString &error);
    void setCancelled();

    quint64 m_id = 0;
    QUrl m_url;
    QString m_suggestedFileName;
    QString m_mimeType;
    qint64 m_totalBytes = -1;
    qint64 m_receivedBytes = 0;
    State m_state = State::Requested;
    QString m_destinationPath;
    QString m_errorString;
    TransferHooks m_hooks;
};

#endif // Q_OS_ANDROID || Q_OS_MACOS || Q_OS_IOS
