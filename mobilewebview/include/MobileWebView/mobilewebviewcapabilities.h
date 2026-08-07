#pragma once

#include <QObject>
#include <QtQml/qqmlregistration.h>

#if defined(Q_OS_ANDROID) || defined(Q_OS_MACOS) || defined(Q_OS_IOS)

/// Process-wide capability answers for this Backend.
///
/// What a Backend can do is a fact about the Backend — the engine it wraps and
/// the OS it runs on — not about any one Web View. Answering it needs no
/// instance, so a host must not have to construct one (or reach for a platform
/// check of its own) to find out. Every accessor here is static; the QObject
/// exists only so QML can read the same answers through a singleton.
///
/// The instance properties on MobileWebViewBackend keep working and stay the
/// convenient form inside a view; they delegate here rather than repeat a
/// version check, so there is exactly one answer per capability.
class MobileWebViewCapabilities : public QObject
{
    Q_OBJECT
    QML_ELEMENT
    QML_SINGLETON
    Q_PROPERTY(bool inPageMediaPlaybackSupported READ inPageMediaPlaybackSupported CONSTANT)

public:
    explicit MobileWebViewCapabilities(QObject *parent = nullptr);

    /// Can the engine decode and play audio/video inside a loaded page?
    /// Answering "no" means an in-page player would come up dead, so a host
    /// should hand the file to the OS instead.
    static bool isInPageMediaPlaybackSupported();

    bool inPageMediaPlaybackSupported() const { return isInPageMediaPlaybackSupported(); }
};

#endif // Q_OS_ANDROID || Q_OS_MACOS || Q_OS_IOS
