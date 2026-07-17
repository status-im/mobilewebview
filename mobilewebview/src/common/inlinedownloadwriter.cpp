#include "inlinedownloadwriter.h"

#include <QFile>

void InlineDownloadWriter::store(quint64 id, QByteArray payload)
{
    if (id == 0 || payload.isEmpty())
        return;
    m_payloads.insert(id, std::move(payload));
}

bool InlineDownloadWriter::has(quint64 id) const
{
    return m_payloads.contains(id);
}

QByteArray InlineDownloadWriter::takePayload(quint64 id)
{
    return m_payloads.take(id);
}

void InlineDownloadWriter::discard(quint64 id)
{
    m_payloads.remove(id);
}

InlineDownloadWriter::WriteResult InlineDownloadWriter::write(quint64 id, const QString &path)
{
    WriteResult result;
    if (!m_payloads.contains(id)) {
        result.error = QStringLiteral("Inline payload missing");
        return result;
    }
    if (path.isEmpty()) {
        result.error = QStringLiteral("Empty destination path");
        return result;
    }

    const QByteArray &payload = m_payloads[id];
    QFile file(path);
    if (!file.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
        result.error = QStringLiteral("Failed to open destination: %1").arg(file.errorString());
        return result;
    }
    if (file.write(payload) != payload.size()) {
        file.close();
        result.error = QStringLiteral("Failed to write inline download");
        return result;
    }
    file.close();

    result.ok = true;
    result.bytesWritten = payload.size();
    m_payloads.remove(id);
    return result;
}
