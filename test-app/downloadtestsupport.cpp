#include "downloadtestsupport.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QStandardPaths>

DownloadTestSupport::DownloadTestSupport(QObject *parent)
    : QObject(parent)
{
}

QString DownloadTestSupport::downloadsDir() const
{
    return QDir(QStandardPaths::writableLocation(QStandardPaths::TempLocation))
        .filePath(QStringLiteral("mwv-downloads"));
}

bool DownloadTestSupport::ensureDownloadsDir() const
{
    QDir dir(downloadsDir());
    if (dir.exists())
        return true;
    return dir.mkpath(QStringLiteral("."));
}

QString DownloadTestSupport::targetPath(const QString &suggestedFileName) const
{
    QString name = suggestedFileName.trimmed();
    if (name.isEmpty())
        name = QStringLiteral("download.bin");
    // Strip path separators so the Target stays inside downloadsDir.
    name.replace(QLatin1Char('/'), QLatin1Char('_'));
    name.replace(QLatin1Char('\\'), QLatin1Char('_'));
    return QDir(downloadsDir()).filePath(name);
}

bool DownloadTestSupport::fileExists(const QString &path) const
{
    return QFileInfo::exists(path) && QFileInfo(path).isFile();
}

qint64 DownloadTestSupport::fileSize(const QString &path) const
{
    if (!fileExists(path))
        return -1;
    return QFileInfo(path).size();
}

QString DownloadTestSupport::readTextFile(const QString &path) const
{
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly | QIODevice::Text))
        return {};
    return QString::fromUtf8(file.readAll());
}

bool DownloadTestSupport::removeFile(const QString &path) const
{
    if (!fileExists(path))
        return true;
    return QFile::remove(path);
}
