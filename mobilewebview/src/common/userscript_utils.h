  #pragma once

#include <QString>
#include <QVariant>

// Normalizes URL-like script paths into QFile-compatible paths.
// Example: qrc:/CustomWebView/js/foo.js -> :/CustomWebView/js/foo.js
QString normalizeScriptPath(const QString &rawPath);

// Extracts script path from a user script variant.
// Supports QString values and QVariantMap with "path" or "sourceUrl".
QString extractUserScriptPath(const QVariant &scriptVariant);

// Escape JSON string for embedding in JavaScript single-quoted string.
// Escapes: backslash, single quote, newline, carriage return.
QString escapeJsonForJs(const QString &json);
