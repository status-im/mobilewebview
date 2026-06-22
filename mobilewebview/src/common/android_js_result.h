#pragma once

#include <QString>
#include <QVariant>

// Decode the raw string returned by Android's WebView.evaluateJavascript(),
// which JSON-encodes every value, into a QVariant that matches the type
// semantics of the Apple WKWebView backend:
//   - JSON string   -> QString (unwrapped)
//   - JSON number   -> double
//   - JSON bool     -> bool
//   - JSON null / "" -> invalid QVariant
//   - JSON object/array -> compact JSON QString (as Apple emits)
//   - malformed input   -> the raw string unchanged (best-effort fallback)
QVariant decodeAndroidEvaluateJsResult(const QString &raw);
