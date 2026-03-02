#include "userscript_utils.h"

#include <QMetaType>
#include <QUrl>
#include <QVariantMap>

QString normalizeScriptPath(const QString &rawPath)
{
    if (rawPath.isEmpty()) {
        return rawPath;
    }

    if (rawPath.startsWith(QStringLiteral("qrc:/"))) {
        return QLatin1String(":") + rawPath.mid(4);
    }

    return rawPath;
}

QString extractUserScriptPath(const QVariant &scriptVariant)
{
    QVariant pathVariant = scriptVariant;
    if (scriptVariant.metaType().id() == QMetaType::QVariantMap) {
        const QVariantMap map = scriptVariant.toMap();
        pathVariant = map.contains(QStringLiteral("path"))
            ? map.value(QStringLiteral("path"))
            : map.value(QStringLiteral("sourceUrl"));
    }

    QString path = pathVariant.toString();

    const QUrl url = pathVariant.toUrl();
    if (url.isValid() && !url.isEmpty()) {
        if (url.scheme() == QLatin1String("qrc")) {
            path = QLatin1String(":") + url.path();
        } else if (!url.scheme().isEmpty()) {
            path = url.toString();
        }
    }

    return normalizeScriptPath(path);
}

QString escapeJsonForJs(const QString &json)
{
    QString escaped = json;
    escaped.replace(QStringLiteral("\\"), QStringLiteral("\\\\"));
    escaped.replace(QStringLiteral("'"), QStringLiteral("\\'"));
    escaped.replace(QStringLiteral("\n"), QStringLiteral("\\n"));
    escaped.replace(QStringLiteral("\r"), QStringLiteral("\\r"));
    return escaped;
}
