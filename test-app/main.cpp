#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QUrl>
#include <QWebChannel>
#include <qqml.h>

#include "MobileWebView/mobilewebviewbackend.h"

int main(int argc, char *argv[])
{
    QGuiApplication app(argc, argv);
    qmlRegisterType<MobileWebViewBackend>("MobileWebView", 1, 0, "MobileWebViewBackend");
    qmlRegisterUncreatableType<QWebChannel>("QtWebChannel", 1, 0, "QWebChannel",
                                            "QWebChannel is provided via WebChannel QML type");

    QQmlApplicationEngine engine;

    const QUrl url(QStringLiteral("qrc:/qml/main.qml"));
    QObject::connect(
        &engine,
        &QQmlApplicationEngine::objectCreated,
        &app,
        [url](QObject *obj, const QUrl &objUrl) {
            if (!obj && url == objUrl) {
                QCoreApplication::exit(-1);
            }
        },
        Qt::QueuedConnection);
    engine.load(url);

    return app.exec();
}
