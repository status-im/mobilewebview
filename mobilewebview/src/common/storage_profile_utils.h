#pragma once

#include <QUuid>
#include <QString>

/// Deterministic UUID for a persistent storage partition (WKWebsiteDataStore identifier).
QUuid storageProfileIdentifier(const QString &storageName);
