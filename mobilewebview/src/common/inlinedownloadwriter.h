#pragma once

#include <QByteArray>
#include <QHash>
#include <QString>
#include <QtGlobal>

/// Owns decoded inline (blob:/data:) payloads and writes them to a Target path.
/// Payload lifetime: kept until successful write (then freed) or takePayload();
/// retained on write failure so retry() can re-emit a new Download Request.
class InlineDownloadWriter
{
public:
    void store(quint64 id, QByteArray payload);
    bool has(quint64 id) const;
    QByteArray takePayload(quint64 id);
    void discard(quint64 id);

    struct WriteResult {
        bool ok = false;
        qint64 bytesWritten = 0;
        QString error;
    };

    /// Write stored payload for \a id to \a path. On success, frees the payload.
    /// On failure, keeps the payload for retry.
    WriteResult write(quint64 id, const QString &path);

private:
    QHash<quint64, QByteArray> m_payloads;
};
