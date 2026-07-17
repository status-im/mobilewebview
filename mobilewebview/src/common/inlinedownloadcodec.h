#pragma once

#include <QByteArray>
#include <QString>

// Decode base64 payloads for inline (blob:/data:) Downloads (ADR 0005 v2).

namespace MobileWebView {
namespace InlineDownloadCodec {

/// Default hard cap for a single inline payload (32 MiB).
constexpr qint64 kMaxDecodedBytes = 32LL * 1024 * 1024;

struct DecodeResult {
    bool ok = false;
    QByteArray bytes;
    QString error;
};

/// Decode standard or URL-safe base64. Rejects empty input and payloads that
/// would exceed maxDecodedBytes after decode.
DecodeResult decodeBase64(const QString &base64,
                          qint64 maxDecodedBytes = kMaxDecodedBytes);

/// Strip a data: URL prefix ("data:mime;base64,") if present; otherwise return
/// the input unchanged (already raw base64 from the bridge).
QString stripDataUrlBase64Prefix(const QString &payload);

} // namespace InlineDownloadCodec
} // namespace MobileWebView
