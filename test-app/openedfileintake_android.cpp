#include "openedfileintake_p.h"

#include <QJniObject>
#include <QJsonDocument>
#include <QJsonObject>

namespace {

constexpr char ProbeClass[] = "org/mobilewebview/testapp/OpenedFileProbe";

QVariantMap callProbe(const char *method, const QString &url)
{
    const QJniObject result = QJniObject::callStaticObjectMethod(
        ProbeClass, method, "(Ljava/lang/String;)Ljava/lang/String;",
        QJniObject::fromString(url).object<jstring>());
    return QJsonDocument::fromJson(result.toString().toUtf8()).object().toVariantMap();
}

} // namespace

namespace OpenedFilePlatform {

QVariantMap describe(const QString &url)
{
    return callProbe("describe", url);
}

QVariantMap read(const QString &url)
{
    return callProbe("read", url);
}

QVariantMap startAccess(const QString &, bool)
{
    // Android grants read access with the intent itself.
    return {{QStringLiteral("supported"), false}};
}

QVariantMap copyToCache(const QString &url)
{
    return callProbe("copyToCache", url);
}

QVariantMap pdfPages(const QString &url)
{
    return callProbe("pdfPages", url);
}

QVariantList inboxFiles()
{
    return {};
}

QVariantList nativeContexts()
{
    return {};
}

QString drainIntents()
{
    return QJniObject::callStaticObjectMethod(ProbeClass, "drain", "()Ljava/lang/String;")
        .toString();
}

void setNativeReceiver(std::function<void(const QString &)>)
{
}

} // namespace OpenedFilePlatform
