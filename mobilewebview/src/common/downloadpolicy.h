#pragma once

#include <QString>
#include <QUrl>

// Download Request policy: scheme support + suggested filename guessing.
// Shared by all platforms (ADR 0005). Platforms pass raw inputs; this module
// owns the decision.

namespace MobileWebView {
namespace DownloadPolicy {

/// Network self-fetch / WKDownload path: http(s) only.
/// false for blob:/data:/empty/invalid URLs.
bool isSupportedUrl(const QUrl &url);

/// Inline (JS-bridge) path: blob: or data: with a payload held by the library.
bool isInlineUrl(const QUrl &url);

/// Priority: non-empty platformSuggestion (sanitized) → Content-Disposition →
/// URL path → MIME extension → "download".
QString suggestedFileName(const QUrl &url,
                          const QString &platformSuggestion,
                          const QString &contentDisposition,
                          const QString &mimeType);

} // namespace DownloadPolicy
} // namespace MobileWebView
