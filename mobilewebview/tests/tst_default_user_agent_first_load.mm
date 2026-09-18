#include <QtTest/QtTest>
#include <QQuickWindow>
#include <QSignalSpy>
#include <QTcpServer>
#include <QTcpSocket>

#include "MobileWebView/mobilewebviewbackend.h"

#if defined(Q_OS_MACOS) || defined(Q_OS_IOS)

// Its own executable: the default User-Agent is cached per process, and this
// test needs the first view of a fresh process.
class DefaultUserAgentFirstLoadTest : public QObject
{
    Q_OBJECT

private slots:
    void firstRequestCarriesTheOverrideBuiltFromTheDefault();
};

void DefaultUserAgentFirstLoadTest::firstRequestCarriesTheOverrideBuiltFromTheDefault()
{
    QTcpServer server;
    QVERIFY(server.listen(QHostAddress::LocalHost));
    QStringList agents;
    connect(&server, &QTcpServer::newConnection, &server, [&server, &agents]() {
        while (QTcpSocket *socket = server.nextPendingConnection()) {
            connect(socket, &QTcpSocket::readyRead, socket, [socket, &agents]() {
                const QByteArray request = socket->readAll();
                for (const QByteArray &line : request.split('\n')) {
                    if (line.toLower().startsWith("user-agent:"))
                        agents << QString::fromUtf8(line.mid(11).trimmed());
                }
                socket->write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n"
                              "Content-Length: 2\r\nConnection: close\r\n\r\nok");
                socket->disconnectFromHost();
            });
        }
    });

    MobileWebViewBackend backend;
    QVERIFY(backend.defaultHttpUserAgent().isEmpty());

    // What a host like Status does: build the override from the engine default.
    connect(&backend, &MobileWebViewBackend::defaultHttpUserAgentChanged, &backend, [&backend]() {
        backend.setHttpUserAgent(backend.defaultHttpUserAgent() + QStringLiteral(" Probe/1"));
    });
    backend.setUrl(QUrl(QStringLiteral("http://127.0.0.1:%1/").arg(server.serverPort())));

    QQuickWindow window;
    backend.setParentItem(window.contentItem());
    backend.setWidth(320);
    backend.setHeight(240);
    backend.setVisible(true);
    window.setGeometry(0, 0, 480, 320);
    window.show();

    QTRY_VERIFY_WITH_TIMEOUT(!agents.isEmpty(), 15000);
    QVERIFY2(agents.first().endsWith(QStringLiteral(" Probe/1")), qPrintable(agents.first()));
    QVERIFY2(agents.first().contains(QStringLiteral("AppleWebKit/")), qPrintable(agents.first()));
}

QTEST_MAIN(DefaultUserAgentFirstLoadTest)
#include "tst_default_user_agent_first_load.moc"

#endif
