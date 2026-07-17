#pragma once

#include <QObject>
#include <QString>

/// Filesystem helpers for the Downloads harness screen (temp Target dir + checks).
class DownloadTestSupport : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QString downloadsDir READ downloadsDir CONSTANT)

public:
    explicit DownloadTestSupport(QObject *parent = nullptr);

    QString downloadsDir() const;

    Q_INVOKABLE bool ensureDownloadsDir() const;
    Q_INVOKABLE QString targetPath(const QString &suggestedFileName) const;
    Q_INVOKABLE bool fileExists(const QString &path) const;
    Q_INVOKABLE qint64 fileSize(const QString &path) const;
    Q_INVOKABLE QString readTextFile(const QString &path) const;
    Q_INVOKABLE bool removeFile(const QString &path) const;
};
