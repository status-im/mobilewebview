#include "inlinedownloadmessage.h"

#include "MobileWebView/mobilewebviewbackend.h"

#include <QJsonDocument>
#include <QJsonObject>
#include <QUrl>

namespace MobileWebView {

bool tryHandleInlineDownloadMessage(MobileWebViewBackend *backend, const QString &message)
{
    if (!backend || message.isEmpty())
        return false;

    const QJsonDocument doc = QJsonDocument::fromJson(message.toUtf8());
    if (!doc.isObject())
        return false;

    const QJsonObject obj = doc.object();
    if (!obj.value(QLatin1String("mwvDownload")).toBool(false))
        return false;

    const QUrl url(obj.value(QLatin1String("url")).toString());
    const QString fileName = obj.value(QLatin1String("fileName")).toString();
    const QString mimeType = obj.value(QLatin1String("mimeType")).toString();
    const QString base64 = obj.value(QLatin1String("base64")).toString();

    // Consumed either way — never forward mwvDownload packets to WebChannel.
    backend->beginInlineDownload(url, fileName, mimeType, base64);
    return true;
}

} // namespace MobileWebView
