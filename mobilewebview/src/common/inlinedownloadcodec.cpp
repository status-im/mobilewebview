#include "inlinedownloadcodec.h"

namespace MobileWebView {
namespace InlineDownloadCodec {

QString stripDataUrlBase64Prefix(const QString &payload)
{
    const QString lower = payload.left(64).toLower();
    if (!lower.startsWith(QLatin1String("data:")))
        return payload;
    const int comma = payload.indexOf(QLatin1Char(','));
    if (comma < 0)
        return payload;
    // Only treat as data-URL when ;base64 is present before the comma.
    const QString header = payload.left(comma).toLower();
    if (!header.contains(QLatin1String(";base64")))
        return payload;
    return payload.mid(comma + 1);
}

DecodeResult decodeBase64(const QString &base64, qint64 maxDecodedBytes)
{
    DecodeResult result;
    if (base64.isEmpty()) {
        result.error = QStringLiteral("Empty inline download payload");
        return result;
    }
    if (maxDecodedBytes <= 0) {
        result.error = QStringLiteral("Invalid max decoded size");
        return result;
    }

    const QString stripped = stripDataUrlBase64Prefix(base64);
    // Rough upper bound: base64 expands ~4/3; reject obviously oversized input early.
    const qint64 approxDecoded = (static_cast<qint64>(stripped.size()) * 3) / 4;
    if (approxDecoded > maxDecodedBytes) {
        result.error = QStringLiteral("Inline download exceeds size limit");
        return result;
    }

    QByteArray raw = stripped.toUtf8();
    QByteArray decoded = QByteArray::fromBase64(
        raw, QByteArray::Base64Encoding | QByteArray::OmitTrailingEquals);
    // Retry URL-safe alphabet if standard decode looks empty but input was not.
    if (decoded.isEmpty() && !raw.isEmpty()) {
        decoded = QByteArray::fromBase64(
            raw, QByteArray::Base64UrlEncoding | QByteArray::OmitTrailingEquals);
    }
    if (decoded.isEmpty()) {
        result.error = QStringLiteral("Invalid base64 payload");
        return result;
    }
    if (static_cast<qint64>(decoded.size()) > maxDecodedBytes) {
        result.error = QStringLiteral("Inline download exceeds size limit");
        return result;
    }

    result.ok = true;
    result.bytes = std::move(decoded);
    return result;
}

} // namespace InlineDownloadCodec
} // namespace MobileWebView
