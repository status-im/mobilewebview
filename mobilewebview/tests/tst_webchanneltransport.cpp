#include <QtTest/QtTest>

#include <QJsonDocument>
#include <QJsonObject>

#include "webchanneltransport.h"

class WebChannelTransportTest : public QObject
{
    Q_OBJECT

private slots:
    void sendMessageEmitsJson();
    void handleJsEnvelopeRejectsInvalidPayload();
    void handleJsEnvelopeRejectsInvalidJsonDataField();
    void handleJsEnvelopeRejectsWrongInvokeKey();
    void handleJsEnvelopeAcceptsWhenInvokeKeyNotSet();
    void handleJsEnvelopeRejectsDisallowedOrigin();
    void handleJsEnvelopeAcceptsWhenAllowedOriginsNotSet();
    void handleJsEnvelopeAcceptsValidMessage();
};

void WebChannelTransportTest::sendMessageEmitsJson()
{
    WebChannelTransport transport;
    QString emitted;
    QObject::connect(&transport, &WebChannelTransport::sendMessageRequested,
                     this, [&emitted](const QString &json) { emitted = json; });

    transport.sendMessage(QJsonObject{{QStringLiteral("type"), QStringLiteral("ping")}});
    QCOMPARE(emitted, QStringLiteral("{\"type\":\"ping\"}"));
}

void WebChannelTransportTest::handleJsEnvelopeRejectsInvalidPayload()
{
    WebChannelTransport transport;
    int receivedCount = 0;
    QObject::connect(&transport, &QWebChannelAbstractTransport::messageReceived,
                     this, [&receivedCount](const QJsonObject &, QWebChannelAbstractTransport *) {
                         ++receivedCount;
                     });

    transport.handleJsEnvelope(QStringLiteral("{not-json"), QStringLiteral("https://example.com"), true);
    QCOMPARE(receivedCount, 0);
}

void WebChannelTransportTest::handleJsEnvelopeRejectsInvalidJsonDataField()
{
    WebChannelTransport transport;

    int receivedCount = 0;
    QObject::connect(&transport, &QWebChannelAbstractTransport::messageReceived,
                     this, [&receivedCount](const QJsonObject &, QWebChannelAbstractTransport *) {
                         ++receivedCount;
                     });

    const QJsonObject envelope{
        {QStringLiteral("invokeKey"), QStringLiteral("k")},
        {QStringLiteral("data"), QStringLiteral("not-json")}
    };
    transport.handleJsEnvelope(QString::fromUtf8(QJsonDocument(envelope).toJson(QJsonDocument::Compact)),
                               QStringLiteral("https://example.com"),
                               true);

    QCOMPARE(receivedCount, 0);
}

void WebChannelTransportTest::handleJsEnvelopeRejectsWrongInvokeKey()
{
    WebChannelTransport transport;
    transport.setInvokeKey(QStringLiteral("expected-key"));

    int receivedCount = 0;
    QObject::connect(&transport, &QWebChannelAbstractTransport::messageReceived,
                     this, [&receivedCount](const QJsonObject &, QWebChannelAbstractTransport *) {
                         ++receivedCount;
                     });

    const QJsonObject payload{{QStringLiteral("id"), 1}};
    const QJsonObject envelope{
        {QStringLiteral("invokeKey"), QStringLiteral("wrong-key")},
        {QStringLiteral("data"),
         QString::fromUtf8(QJsonDocument(payload).toJson(QJsonDocument::Compact))}
    };

    transport.handleJsEnvelope(QString::fromUtf8(QJsonDocument(envelope).toJson(QJsonDocument::Compact)),
                               QStringLiteral("https://example.com"),
                               true);
    QCOMPARE(receivedCount, 0);
}

void WebChannelTransportTest::handleJsEnvelopeAcceptsWhenInvokeKeyNotSet()
{
    WebChannelTransport transport;
    transport.setAllowedOrigins({QStringLiteral("https://example.com")});

    int receivedCount = 0;
    QObject::connect(&transport, &QWebChannelAbstractTransport::messageReceived,
                     this, [&receivedCount](const QJsonObject &, QWebChannelAbstractTransport *) {
                         ++receivedCount;
                     });

    const QJsonObject payload{{QStringLiteral("id"), 7}};
    const QJsonObject envelope{
        {QStringLiteral("invokeKey"), QStringLiteral("any-key")},
        {QStringLiteral("data"),
         QString::fromUtf8(QJsonDocument(payload).toJson(QJsonDocument::Compact))}
    };

    transport.handleJsEnvelope(QString::fromUtf8(QJsonDocument(envelope).toJson(QJsonDocument::Compact)),
                               QStringLiteral("https://example.com"),
                               true);
    QCOMPARE(receivedCount, 1);
}

void WebChannelTransportTest::handleJsEnvelopeRejectsDisallowedOrigin()
{
    WebChannelTransport transport;
    transport.setAllowedOrigins({QStringLiteral("https://allowed.example.com")});

    int receivedCount = 0;
    QObject::connect(&transport, &QWebChannelAbstractTransport::messageReceived,
                     this, [&receivedCount](const QJsonObject &, QWebChannelAbstractTransport *) {
                         ++receivedCount;
                     });

    const QJsonObject payload{{QStringLiteral("id"), 1}};
    const QJsonObject envelope{
        {QStringLiteral("invokeKey"), QStringLiteral("ignored")},
        {QStringLiteral("data"),
         QString::fromUtf8(QJsonDocument(payload).toJson(QJsonDocument::Compact))}
    };

    transport.handleJsEnvelope(QString::fromUtf8(QJsonDocument(envelope).toJson(QJsonDocument::Compact)),
                               QStringLiteral("https://other.example.com"),
                               true);
    QCOMPARE(receivedCount, 0);
}

void WebChannelTransportTest::handleJsEnvelopeAcceptsWhenAllowedOriginsNotSet()
{
    WebChannelTransport transport;
    transport.setInvokeKey(QStringLiteral("ok"));

    int receivedCount = 0;
    QObject::connect(&transport, &QWebChannelAbstractTransport::messageReceived,
                     this, [&receivedCount](const QJsonObject &, QWebChannelAbstractTransport *) {
                         ++receivedCount;
                     });

    const QJsonObject payload{{QStringLiteral("id"), 11}};
    const QJsonObject envelope{
        {QStringLiteral("invokeKey"), QStringLiteral("ok")},
        {QStringLiteral("data"),
         QString::fromUtf8(QJsonDocument(payload).toJson(QJsonDocument::Compact))}
    };

    // No allowlist configured: any origin is accepted.
    transport.handleJsEnvelope(QString::fromUtf8(QJsonDocument(envelope).toJson(QJsonDocument::Compact)),
                               QStringLiteral("https://unlisted.example.org"),
                               true);
    QCOMPARE(receivedCount, 1);
}

void WebChannelTransportTest::handleJsEnvelopeAcceptsValidMessage()
{
    WebChannelTransport transport;
    transport.setInvokeKey(QStringLiteral("session-key"));
    transport.setAllowedOrigins({QStringLiteral("https://*.example.com")});

    int receivedCount = 0;
    QJsonObject lastMessage;
    QObject::connect(&transport, &QWebChannelAbstractTransport::messageReceived,
                     this,
                     [&receivedCount, &lastMessage](const QJsonObject &message,
                                                    QWebChannelAbstractTransport *) {
                         ++receivedCount;
                         lastMessage = message;
                     });

    const QJsonObject payload{
        {QStringLiteral("id"), 42},
        {QStringLiteral("type"), QStringLiteral("invokeMethod")}
    };
    const QJsonObject envelope{
        {QStringLiteral("invokeKey"), QStringLiteral("session-key")},
        {QStringLiteral("data"),
         QString::fromUtf8(QJsonDocument(payload).toJson(QJsonDocument::Compact))}
    };

    transport.handleJsEnvelope(QString::fromUtf8(QJsonDocument(envelope).toJson(QJsonDocument::Compact)),
                               QStringLiteral("https://api.example.com"),
                               true);

    QCOMPARE(receivedCount, 1);
    QCOMPARE(lastMessage.value(QStringLiteral("id")).toInt(), 42);
    QCOMPARE(lastMessage.value(QStringLiteral("type")).toString(), QStringLiteral("invokeMethod"));
}

QTEST_MAIN(WebChannelTransportTest)
#include "tst_webchanneltransport.moc"
