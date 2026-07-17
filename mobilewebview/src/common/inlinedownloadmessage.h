#pragma once

#include <QString>

class MobileWebViewBackend;

namespace MobileWebView {

/// If \a message is an `{ "mwvDownload": true, ... }` envelope, decode and
/// begin an Inline Download. Returns true when the message was consumed
/// (success or rejected payload) so it must not reach WebChannel.
bool tryHandleInlineDownloadMessage(MobileWebViewBackend *backend, const QString &message);

} // namespace MobileWebView
