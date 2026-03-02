#include "origin_utils.h"
#include <QUrl>
#include <QStringList>
#include <QRegularExpression>

QString extractOrigin(const QUrl &url)
{
    if (!url.isValid() || url.scheme().isEmpty() || url.host().isEmpty()) {
        return QString();
    }
    
    QString origin = url.scheme() + QStringLiteral("://") + url.host();
    int port = url.port();
    if (port != -1 && port != 80 && port != 443) {
        origin += QLatin1Char(':') + QString::number(port);
    }
    return origin;
}

bool isOriginAllowed(const QString &origin, const QStringList &allowedOrigins)
{
    // If no allowlist is set, reject all
    if (allowedOrigins.isEmpty()) {
        return false;
    }
    
    // Empty origin is never allowed
    if (origin.isEmpty()) {
        return false;
    }
    
    // Check for exact match first (including "*" wildcard)
    if (allowedOrigins.contains(origin) || allowedOrigins.contains(QLatin1String("*"))) {
        return true;
    }
    
    // Check for wildcard patterns (e.g., "*.example.com" or "*://example.com")
    for (const QString &pattern : allowedOrigins) {
        if (pattern.contains(QLatin1Char('*'))) {
            // Convert wildcard pattern to regex
            QString regexPattern = QRegularExpression::escape(pattern);
            regexPattern.replace(QStringLiteral("\\*"), QStringLiteral(".*"));
            regexPattern = QLatin1String("^") + regexPattern + QLatin1String("$");
            
            QRegularExpression regex(regexPattern, QRegularExpression::CaseInsensitiveOption);
            if (regex.match(origin).hasMatch()) {
                return true;
            }
        }
    }
    
    return false;
}
