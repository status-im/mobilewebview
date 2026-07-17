#include "inlinedownloadmessage.h"

#include <QJsonDocument>
#include <QJsonObject>

namespace MobileWebView {

std::optional<InlineDownloadEnvelope> parseInlineDownloadMessage(const QString &message)
{
    if (message.isEmpty())
        return std::nullopt;

    const QJsonDocument doc = QJsonDocument::fromJson(message.toUtf8());
    if (!doc.isObject())
        return std::nullopt;

    const QJsonObject obj = doc.object();
    if (!obj.value(QLatin1String("mwvDownload")).toBool(false))
        return std::nullopt;

    InlineDownloadEnvelope envelope;
    envelope.url = QUrl(obj.value(QLatin1String("url")).toString());
    envelope.fileName = obj.value(QLatin1String("fileName")).toString();
    envelope.mimeType = obj.value(QLatin1String("mimeType")).toString();
    envelope.base64 = obj.value(QLatin1String("base64")).toString();
    return envelope;
}

} // namespace MobileWebView
