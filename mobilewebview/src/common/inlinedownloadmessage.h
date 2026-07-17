#pragma once

#include <QString>
#include <QUrl>
#include <optional>

class MobileWebViewBackend;

namespace MobileWebView {

/// Parsed `{ "mwvDownload": true, ... }` envelope from the bridge.
struct InlineDownloadEnvelope {
    QUrl url;
    QString fileName;
    QString mimeType;
    QString base64;
};

/// Parse a bridge message. Returns nullopt when the message is not an inline
/// download envelope (caller should forward to WebChannel). Returns an
/// envelope (possibly with empty fields) when `mwvDownload` is true — the
/// message is always consumed in that case.
std::optional<InlineDownloadEnvelope> parseInlineDownloadMessage(const QString &message);

/// If \a message is an inline download envelope, begin an Inline Download.
/// Returns true when the message was consumed (success or rejected payload)
/// so it must not reach WebChannel.
bool tryHandleInlineDownloadMessage(MobileWebViewBackend *backend, const QString &message);

} // namespace MobileWebView
