#pragma once

#include <QString>
#include <QStringList>
#include <QUrl>

// Shared origin utility functions for WebView implementations
// These functions are identical across Android and Darwin platforms

// Extracts the origin string from a QUrl (format: "protocol://host" or "protocol://host:port")
QString extractOrigin(const QUrl &url);

// Checks if an origin is in the allowed origins list (supports exact matches and wildcard patterns like "*.example.com", "*")
bool isOriginAllowed(const QString &origin, const QStringList &allowedOrigins);
