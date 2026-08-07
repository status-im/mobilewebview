#include "MobileWebView/mobilewebviewcapabilities.h"

#if defined(Q_OS_ANDROID) || defined(Q_OS_MACOS) || defined(Q_OS_IOS)

// The static accessors live in the platform sources next to the engine they
// describe (src/darwin/mobilewebviewcapabilities_darwin.mm,
// src/android/mobilewebviewcapabilities_android.cpp). Only the QObject shell
// is common: it carries the moc metadata that lets QML register this as a
// singleton, and holds no state of its own.
MobileWebViewCapabilities::MobileWebViewCapabilities(QObject *parent)
    : QObject(parent)
{
}

#endif // Q_OS_ANDROID || Q_OS_MACOS || Q_OS_IOS
