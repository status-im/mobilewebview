#pragma once

#include <QString>
#include <QUrl>
#include <QtGlobal>

/// Narrow seam for platform transfer ops. Registry owns lifecycle; adapters
/// forward to startDownloadImpl / cancelDownloadImpl / pause / resume.
struct DownloadTransfer {
    virtual ~DownloadTransfer() = default;

    virtual void start(quint64 id, const QUrl &url, const QString &path) = 0;
    virtual void cancel(quint64 id) = 0;

    /// Default: unsupported — adapters that do not override leave pause unused.
    virtual void pause(quint64 /*id*/) {}
    virtual void resume(quint64 /*id*/) {}
};
