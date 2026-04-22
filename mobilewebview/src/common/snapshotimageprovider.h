#pragma once

#include <QHash>
#include <QImage>
#include <QMutex>
#include <QQuickImageProvider>
#include <QString>

// Thread-safe store for image://mobilewebview-snapshot/<key> (one key per backend instance).
class MobileWebViewSnapshotImageProvider final : public QQuickImageProvider
{
public:
    MobileWebViewSnapshotImageProvider();

    QImage requestImage(const QString &id, QSize *size, const QSize &requestedSize) override;

    static void registerImage(const QString &id, const QImage &image);
    static void releaseImage(const QString &id);

private:
    static QMutex s_mutex;
    static QHash<QString, QImage> s_images;
};
