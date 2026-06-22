#include "storage_profile_utils.h"

namespace {
// Project-specific namespace UUID for storageName -> WKWebsiteDataStore identifier mapping.
const QUuid kStorageProfileNamespace = QUuid(QStringLiteral("f47ac10b-58cc-4372-a567-0e02b2c3d479"));
} // namespace

QUuid storageProfileIdentifier(const QString &storageName)
{
    if (storageName.isEmpty()) {
        return QUuid::createUuid();
    }
    return QUuid::createUuidV5(kStorageProfileNamespace, storageName.toUtf8());
}
