#include "downloadpolicy.h"

#include <QRegularExpression>

namespace MobileWebView {
namespace DownloadPolicy {
namespace {

QString trimQuotes(QString value)
{
    value = value.trimmed();
    if (value.size() >= 2) {
        const QChar first = value.front();
        const QChar last = value.back();
        if ((first == QLatin1Char('"') && last == QLatin1Char('"'))
            || (first == QLatin1Char('\'') && last == QLatin1Char('\''))) {
            return value.mid(1, value.size() - 2);
        }
    }
    return value;
}

int indexOfIgnoreCase(const QString &haystack, const QString &needle)
{
    return haystack.toLower().indexOf(needle.toLower());
}

QString filenameFromContentDisposition(const QString &contentDisposition)
{
    if (contentDisposition.isEmpty())
        return {};

    const int star = indexOfIgnoreCase(contentDisposition, QStringLiteral("filename*="));
    if (star >= 0) {
        constexpr int kFilenameStarLen = 10; // "filename*="
        QString rest = contentDisposition.mid(star + kFilenameStarLen).trimmed();
        const int end = rest.indexOf(QLatin1Char(';'));
        if (end >= 0)
            rest = rest.left(end);
        rest = trimQuotes(rest);
        const int tick = rest.lastIndexOf(QLatin1String("''"));
        if (tick >= 0 && tick + 2 < rest.size())
            return rest.mid(tick + 2);
        return rest;
    }

    const int idx = indexOfIgnoreCase(contentDisposition, QStringLiteral("filename="));
    if (idx < 0)
        return {};

    constexpr int kFilenameLen = 9; // "filename="
    QString rest = contentDisposition.mid(idx + kFilenameLen).trimmed();
    const int end = rest.indexOf(QLatin1Char(';'));
    if (end >= 0)
        rest = rest.left(end);
    return trimQuotes(rest);
}

QString filenameFromUrl(const QUrl &url)
{
    if (!url.isValid() || url.isEmpty())
        return {};
    const QString fileName = url.fileName();
    return fileName;
}

QString extensionForMime(const QString &mimeType)
{
    if (mimeType.isEmpty())
        return {};
    const QString mime = mimeType.toLower();
    if (mime.contains(QLatin1String("pdf")))
        return QStringLiteral("pdf");
    if (mime.contains(QLatin1String("zip")))
        return QStringLiteral("zip");
    if (mime.contains(QLatin1String("png")))
        return QStringLiteral("png");
    if (mime.contains(QLatin1String("jpeg")) || mime.contains(QLatin1String("jpg")))
        return QStringLiteral("jpg");
    if (mime.contains(QLatin1String("octet-stream")))
        return QStringLiteral("bin");
    if (mime.startsWith(QLatin1String("text/")))
        return QStringLiteral("txt");
    return {};
}

QString sanitizeFileName(const QString &name)
{
    static const QRegularExpression forbidden(QStringLiteral("[\\\\/:*?\"<>|]"));
    QString cleaned = name;
    cleaned.replace(forbidden, QStringLiteral("_"));
    cleaned = cleaned.trimmed();
    return cleaned.isEmpty() ? QStringLiteral("download") : cleaned;
}

} // namespace

bool isSupportedUrl(const QUrl &url)
{
    if (!url.isValid() || url.isEmpty() || url.scheme().isEmpty())
        return false;
    const QString scheme = url.scheme().toLower();
    // Network fetch only — inline blob/data use isInlineUrl + beginInlineDownload.
    if (scheme == QLatin1String("blob") || scheme == QLatin1String("data"))
        return false;
    return scheme == QLatin1String("http") || scheme == QLatin1String("https");
}

bool isInlineUrl(const QUrl &url)
{
    if (!url.isValid() || url.isEmpty())
        return false;
    const QString scheme = url.scheme().toLower();
    return scheme == QLatin1String("blob") || scheme == QLatin1String("data");
}

QString suggestedFileName(const QUrl &url,
                          const QString &platformSuggestion,
                          const QString &contentDisposition,
                          const QString &mimeType)
{
    if (!platformSuggestion.isEmpty())
        return sanitizeFileName(platformSuggestion);

    const QString fromDisposition = filenameFromContentDisposition(contentDisposition);
    if (!fromDisposition.isEmpty())
        return sanitizeFileName(fromDisposition);

    const QString fromUrl = filenameFromUrl(url);
    if (!fromUrl.isEmpty())
        return sanitizeFileName(fromUrl);

    const QString ext = extensionForMime(mimeType);
    return ext.isEmpty() ? QStringLiteral("download")
                         : (QStringLiteral("download.") + ext);
}

} // namespace DownloadPolicy
} // namespace MobileWebView
