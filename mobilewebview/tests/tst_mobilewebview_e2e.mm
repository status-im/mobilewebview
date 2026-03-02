#include <QtTest/QtTest>

#include <QJsonDocument>
#include <QJsonObject>
#include <QQuickWindow>
#include <QSignalSpy>
#include <QVariantMap>

#include "MobileWebView/mobilewebviewbackend.h"

#if defined(Q_OS_MACOS) || defined(Q_OS_IOS)

struct ReceivedPayload
{
    QString message;
    QString origin;
    bool isMainFrame = false;
    QString invokeKey;
    QString kind;
    QString echo;
};

static bool tryParseEnvelope(const QList<QVariant> &args, ReceivedPayload &parsed)
{
    if (args.size() != 3) {
        return false;
    }

    parsed.message = args[0].toString();
    parsed.origin = args[1].toString();
    parsed.isMainFrame = args[2].toBool();

    const QJsonDocument envelopeDoc = QJsonDocument::fromJson(parsed.message.toUtf8());
    if (!envelopeDoc.isObject()) {
        return false;
    }
    const QJsonObject envelope = envelopeDoc.object();
    parsed.invokeKey = envelope.value(QStringLiteral("invokeKey")).toString();

    const QString dataString = envelope.value(QStringLiteral("data")).toString();
    const QJsonDocument dataDoc = QJsonDocument::fromJson(dataString.toUtf8());
    if (!dataDoc.isObject()) {
        return false;
    }
    const QJsonObject data = dataDoc.object();
    parsed.kind = data.value(QStringLiteral("kind")).toString();
    parsed.echo = data.value(QStringLiteral("echo")).toString();
    return true;
}

static bool waitForKind(QSignalSpy &spy, const QString &kind, ReceivedPayload &parsed, int timeoutMs = 10000)
{
    const auto parseAvailable = [&]() -> bool {
        while (!spy.isEmpty()) {
            const QList<QVariant> args = spy.takeFirst();
            ReceivedPayload candidate;
            if (tryParseEnvelope(args, candidate) && candidate.kind == kind) {
                parsed = candidate;
                return true;
            }
        }
        return false;
    };

    if (parseAvailable()) {
        return true;
    }

    QElapsedTimer timer;
    timer.start();
    while (timer.elapsed() < timeoutMs) {
        const int remaining = timeoutMs - int(timer.elapsed());
        if (!spy.wait(qMin(remaining, 500))) {
            continue;
        }
        if (parseAvailable()) {
            return true;
        }
    }

    return false;
}

class MobileWebViewE2ETest : public QObject
{
    Q_OBJECT

private slots:
    void bridgeRoundtripSmoke();
};

void MobileWebViewE2ETest::bridgeRoundtripSmoke()
{
    QQuickWindow window;
    window.setGeometry(0, 0, 480, 320);

    MobileWebViewBackend backend;
    backend.setParentItem(window.contentItem());
    backend.setWidth(320);
    backend.setHeight(240);
    backend.setVisible(true);
    window.show();
    QCoreApplication::processEvents();

    const QString probeScriptPath = QFINDTESTDATA("e2e_bridge_probe.js");
    QVERIFY2(!probeScriptPath.isEmpty(), "Failed to locate e2e_bridge_probe.js");

    QVariantMap scriptMap;
    scriptMap.insert(QStringLiteral("path"), probeScriptPath);

    backend.setUserScripts(QVariantList{scriptMap});

    const QString invokeKey = QStringLiteral("e2e-key");
    const QString origin = QStringLiteral("https://example.com");

    const bool installed = backend.installMessageBridge(
        QStringLiteral("qt"),
        QStringList{origin},
        invokeKey);
    QVERIFY(installed);

    QSignalSpy messageSpy(&backend, &MobileWebViewBackend::webMessageReceived);
    QSignalSpy jsResultSpy(&backend, &MobileWebViewBackend::javaScriptResult);
    backend.loadHtml(QStringLiteral("<!doctype html><html><body>E2E</body></html>"),
                     QUrl(QStringLiteral("https://example.com/index.html")));

    ReceivedPayload readyPayload;
    QVERIFY2(waitForKind(messageSpy, QStringLiteral("ready"), readyPayload),
             "Did not receive JS->native ready message");
    QCOMPARE(readyPayload.invokeKey, invokeKey);
    QCOMPARE(readyPayload.origin, origin);
    QVERIFY(readyPayload.isMainFrame);

    const QString sentFromNative = QStringLiteral("{\"kind\":\"pong\"}");
    backend.postMessageToJavaScript(sentFromNative);

    ReceivedPayload ackPayload;
    QVERIFY2(waitForKind(messageSpy, QStringLiteral("ack"), ackPayload),
             "Did not receive JS ack after native->JS message");
    QCOMPARE(ackPayload.invokeKey, invokeKey);
    QCOMPARE(ackPayload.origin, origin);
    QVERIFY(ackPayload.isMainFrame);
    QCOMPARE(ackPayload.echo, sentFromNative);

    backend.runJavaScript(QStringLiteral("1 + 1"));
    QVERIFY(jsResultSpy.wait(5000));
    const QList<QVariant> jsResultArgs = jsResultSpy.takeFirst();
    QCOMPARE(jsResultArgs.size(), 2);
    QCOMPARE(jsResultArgs[0].toDouble(), 2.0);
    QCOMPARE(jsResultArgs[1].toString(), QString());

    // Exercise remaining navigation/control API paths on Darwin backend.
    backend.loadUrl(QUrl(QStringLiteral("https://example.com/next")));
    backend.goBack();
    backend.goForward();
    backend.reload();
    backend.stop();
    backend.updateAllowedOrigins({QStringLiteral("https://example.com")});
}

QTEST_MAIN(MobileWebViewE2ETest)
#include "tst_mobilewebview_e2e.moc"

#endif
