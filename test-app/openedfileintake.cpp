#include "openedfileintake.h"

#include "openedfileintake_p.h"

#include <QDateTime>
#include <QDebug>
#include <QDesktopServices>
#include <QDir>
#include <QElapsedTimer>
#include <QFile>
#include <QFileInfo>
#include <QFileOpenEvent>
#include <QGuiApplication>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QStandardPaths>
#include <QTimer>

namespace {

QElapsedTimer &sinceStart()
{
    static QElapsedTimer timer;
    if (!timer.isValid())
        timer.start();
    return timer;
}

QString localPathOf(const QString &url)
{
    const QUrl parsed(url);
    return parsed.isLocalFile() ? parsed.toLocalFile() : QString();
}

// Qt may deliver the URL after the scene delegate; give it this long before a
// native-only receipt is recorded.
constexpr int NativeOnlyGraceMs = 1500;

} // namespace

OpenedFileIntake::OpenedFileIntake(QObject *parent)
    : QObject(parent)
{
    sinceStart();
#if defined(Q_OS_IOS)
    // Registered before the event loop starts so a cold launch is not missed.
    QDesktopServices::setUrlHandler(QStringLiteral("file"), this, "handleUrl");
#endif
    qGuiApp->installEventFilter(this);
    OpenedFilePlatform::setNativeReceiver([this](const QString &url) { onNativeUrl(url); });
#if defined(Q_OS_ANDROID)
    connect(qGuiApp, &QGuiApplication::applicationStateChanged, this,
            [this](Qt::ApplicationState state) {
                if (state == Qt::ApplicationActive)
                    drainIntents();
            });
    QTimer::singleShot(0, this, &OpenedFileIntake::drainIntents);
#endif
}

OpenedFileIntake::~OpenedFileIntake()
{
#if defined(Q_OS_IOS)
    QDesktopServices::unsetUrlHandler(QStringLiteral("file"));
#endif
    OpenedFilePlatform::setNativeReceiver({});
}

QString OpenedFileIntake::platform() const
{
#if defined(Q_OS_IOS)
    return QStringLiteral("ios");
#elif defined(Q_OS_ANDROID)
    return QStringLiteral("android");
#else
    return QStringLiteral("macos");
#endif
}

QString OpenedFileIntake::logPath() const
{
    const QString dir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    QDir().mkpath(dir);
    return dir + QStringLiteral("/opened-file-log.jsonl");
}

QVariantList OpenedFileIntake::receipts() const
{
    return m_receipts;
}

QVariantMap OpenedFileIntake::describe(const QString &url) const
{
    return OpenedFilePlatform::describe(url);
}

QVariantMap OpenedFileIntake::read(const QString &url) const
{
    return OpenedFilePlatform::read(url);
}

QVariantMap OpenedFileIntake::startAccess(const QString &url, bool nativeUrl) const
{
    return OpenedFilePlatform::startAccess(url, nativeUrl);
}

QVariantMap OpenedFileIntake::copyToCache(const QString &url) const
{
    return OpenedFilePlatform::copyToCache(url);
}

QVariantMap OpenedFileIntake::pdfPages(const QString &url) const
{
    return OpenedFilePlatform::pdfPages(url);
}

QVariantList OpenedFileIntake::inboxFiles() const
{
    return OpenedFilePlatform::inboxFiles();
}

QVariantList OpenedFileIntake::nativeContexts() const
{
    return OpenedFilePlatform::nativeContexts();
}

void OpenedFileIntake::log(const QVariantMap &record) const
{
    QVariantMap stamped = record;
    stamped.insert(QStringLiteral("at"), QDateTime::currentDateTime().toString(Qt::ISODateWithMs));
    const QByteArray line =
        QJsonDocument(QJsonObject::fromVariantMap(stamped)).toJson(QJsonDocument::Compact);
    qInfo().noquote() << "[OpenedFile]" << QString::fromUtf8(line);
    QFile file(logPath());
    if (file.open(QIODevice::Append | QIODevice::Text))
        file.write(line + '\n');
}

void OpenedFileIntake::clearLog() const
{
    QFile::remove(logPath());
}

void OpenedFileIntake::handleUrl(const QUrl &url)
{
    accept(url.toString(QUrl::FullyEncoded), QStringLiteral("QDesktopServices"),
           {{QStringLiteral("qtDelivered"), true}});
}

bool OpenedFileIntake::eventFilter(QObject *watched, QEvent *event)
{
    if (event->type() == QEvent::FileOpen) {
        const auto *openEvent = static_cast<QFileOpenEvent *>(event);
        accept(openEvent->url().toString(QUrl::FullyEncoded), QStringLiteral("QFileOpenEvent"),
               {{QStringLiteral("qtDelivered"), true}});
    }
    return QObject::eventFilter(watched, event);
}

void OpenedFileIntake::accept(const QString &url, const QString &via, const QVariantMap &extra)
{
    QVariantMap receipt = extra;
    receipt.insert(QStringLiteral("seq"), m_receipts.size() + 1);
    receipt.insert(QStringLiteral("via"), via);
    receipt.insert(QStringLiteral("url"), url);
    receipt.insert(QStringLiteral("localPath"), localPathOf(url));
    receipt.insert(QStringLiteral("receivedAt"),
                   QDateTime::currentDateTime().toString(Qt::ISODateWithMs));
    receipt.insert(QStringLiteral("msSinceStart"), sinceStart().elapsed());
    receipt.insert(QStringLiteral("appState"), int(qGuiApp->applicationState()));
    m_receipts.append(receipt);
    log({{QStringLiteral("event"), QStringLiteral("receipt")},
         {QStringLiteral("receipt"), receipt}});
    emit received(receipt);
}

void OpenedFileIntake::onNativeUrl(const QString &url)
{
    log({{QStringLiteral("event"), QStringLiteral("native")}, {QStringLiteral("url"), url}});
    QTimer::singleShot(NativeOnlyGraceMs, this, [this, url] {
        if (!hasReceiptFor(url))
            accept(url, QStringLiteral("native-only"), {{QStringLiteral("qtDelivered"), false}});
    });
}

bool OpenedFileIntake::hasReceiptFor(const QString &url) const
{
    const QString path = QFileInfo(localPathOf(url)).canonicalFilePath();
    for (const QVariant &receipt : m_receipts) {
        const QVariantMap map = receipt.toMap();
        if (map.value(QStringLiteral("url")).toString() == url)
            return true;
        const QString seen = QFileInfo(map.value(QStringLiteral("localPath")).toString())
                                 .canonicalFilePath();
        if (!path.isEmpty() && seen == path)
            return true;
    }
    return false;
}

void OpenedFileIntake::drainIntents()
{
    const QJsonArray intents =
        QJsonDocument::fromJson(OpenedFilePlatform::drainIntents().toUtf8()).array();
    for (const QJsonValue &value : intents) {
        const QVariantMap intent = value.toObject().toVariantMap();
        QString url = intent.value(QStringLiteral("data")).toString();
        if (url.isEmpty())
            url = intent.value(QStringLiteral("clipUris")).toList().value(0).toString();
        accept(url, QStringLiteral("intent:") + intent.value(QStringLiteral("via")).toString(),
               {{QStringLiteral("qtDelivered"), false}, {QStringLiteral("intent"), intent}});
    }
}
