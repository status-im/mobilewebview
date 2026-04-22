#include "snapshotimageprovider.h"

#include <QMutexLocker>

QMutex MobileWebViewSnapshotImageProvider::s_mutex;
QHash<QString, QImage> MobileWebViewSnapshotImageProvider::s_images;

MobileWebViewSnapshotImageProvider::MobileWebViewSnapshotImageProvider()
    : QQuickImageProvider(QQuickImageProvider::Image)
{
}

QImage MobileWebViewSnapshotImageProvider::requestImage(const QString &id, QSize *size,
                                                        const QSize &requestedSize)
{
    QMutexLocker locker(&s_mutex);
    QImage img = s_images.value(id);
    locker.unlock();

    if (size && !img.isNull()) {
        *size = img.size();
    }
    if (requestedSize.isValid() && !img.isNull()) {
        return img.scaled(requestedSize, Qt::KeepAspectRatio, Qt::SmoothTransformation);
    }
    return img;
}

void MobileWebViewSnapshotImageProvider::registerImage(const QString &id, const QImage &image)
{
    QMutexLocker locker(&s_mutex);
    s_images.insert(id, image);
}

void MobileWebViewSnapshotImageProvider::releaseImage(const QString &id)
{
    QMutexLocker locker(&s_mutex);
    s_images.remove(id);
}
