#pragma once

#include <QString>
#include <QVariantList>
#include <QVariantMap>

#include <functional>

/// Platform halves of OpenedFileIntake: Apple (Foundation/UIKit) and Android (JNI).
namespace OpenedFilePlatform {
QVariantMap describe(const QString &url);
QVariantMap read(const QString &url);
QVariantMap startAccess(const QString &url, bool nativeUrl);
QVariantMap copyToCache(const QString &url);
QVariantMap pdfPages(const QString &url);
QVariantList inboxFiles();
/// URLs as the iOS scene delegate received them, before Qt saw them.
QVariantList nativeContexts();
/// Android: "Open with" intents queued by the activity, as a JSON array.
QString drainIntents();
/// Called for every native context, including ones received before this call.
void setNativeReceiver(std::function<void(const QString &url)> receiver);
}
