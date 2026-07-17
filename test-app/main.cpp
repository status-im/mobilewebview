#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QFile>
#include <QUrl>
#include <QWebChannel>
#include <qqml.h>

#include "MobileWebView/mobilewebviewbackend.h"
#include "MobileWebView/mobilewebviewdownload.h"
#include "downloadtestsupport.h"

static QString loadTextResource(const QString &path)
{
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly | QIODevice::Text)) {
        return {};
    }
    return QString::fromUtf8(file.readAll());
}

int main(int argc, char *argv[])
{
    QGuiApplication app(argc, argv);
    qmlRegisterType<MobileWebViewBackend>("MobileWebView", 1, 0, "MobileWebViewBackend");
    qmlRegisterUncreatableType<MobileWebViewDownload>(
        "MobileWebView", 1, 0, "MobileWebViewDownload",
        QStringLiteral("Created by MobileWebViewBackend via downloadRequested"));
    qmlRegisterUncreatableType<QWebChannel>("QtWebChannel", 1, 0, "QWebChannel",
                                            "QWebChannel is provided via WebChannel QML type");

    DownloadTestSupport downloadTestSupport;

    QQmlApplicationEngine engine;
    engine.addImportPath(QStringLiteral("qrc:/"));
    engine.rootContext()->setContextProperty(
        QStringLiteral("_storageTestPageHtml"),
        loadTextResource(QStringLiteral(":/MobileWebViewTest/web/storage_profile_test.html")));
    engine.rootContext()->setContextProperty(
        QStringLiteral("_webChannelTestPageHtml"),
        loadTextResource(QStringLiteral(":/MobileWebViewTest/web/test_webchannel.html")));
    engine.rootContext()->setContextProperty(
        QStringLiteral("_downloadTest"), &downloadTestSupport);

    engine.load(QUrl(QStringLiteral("qrc:/MobileWebViewTest/qml/main.qml")));

    if (engine.rootObjects().isEmpty()) {
        return -1;
    }

    return app.exec();
}
