#include <QtTest/QtTest>

#include "inlinedownloadmessage.h"

class InlineDownloadMessageTest : public QObject
{
    Q_OBJECT

private slots:
    void parseValidEnvelope();
    void parseUrlOnlyEnvelope();
    void parseRejectsNonJson();
    void parseRejectsNonObject();
    void parseRejectsMissingFlag();
    void parseRejectsEmpty();
};

void InlineDownloadMessageTest::parseValidEnvelope()
{
    const QString json =
        QStringLiteral("{\"mwvDownload\":true,\"url\":\"blob:https://example.com/u\","
                       "\"fileName\":\"a.txt\",\"mimeType\":\"text/plain\",\"base64\":\"aGVsbG8=\"}");
    const auto envelope = MobileWebView::parseInlineDownloadMessage(json);
    QVERIFY(envelope.has_value());
    QCOMPARE(envelope->url, QUrl(QStringLiteral("blob:https://example.com/u")));
    QCOMPARE(envelope->fileName, QStringLiteral("a.txt"));
    QCOMPARE(envelope->mimeType, QStringLiteral("text/plain"));
    QCOMPARE(envelope->base64, QStringLiteral("aGVsbG8="));
}

void InlineDownloadMessageTest::parseUrlOnlyEnvelope()
{
    // http(s) <a download> click: no payload — the handler routes an empty-base64
    // envelope to downloadUrl instead of the Inline Download path.
    const QString json =
        QStringLiteral("{\"mwvDownload\":true,\"url\":\"https://example.com/sample.mp3\","
                       "\"fileName\":\"\"}");
    const auto envelope = MobileWebView::parseInlineDownloadMessage(json);
    QVERIFY(envelope.has_value());
    QCOMPARE(envelope->url, QUrl(QStringLiteral("https://example.com/sample.mp3")));
    QVERIFY(envelope->fileName.isEmpty());
    QVERIFY(envelope->base64.isEmpty());
}

void InlineDownloadMessageTest::parseRejectsNonJson()
{
    QVERIFY(!MobileWebView::parseInlineDownloadMessage(QStringLiteral("not-json")).has_value());
}

void InlineDownloadMessageTest::parseRejectsNonObject()
{
    QVERIFY(!MobileWebView::parseInlineDownloadMessage(QStringLiteral("[1,2]")).has_value());
}

void InlineDownloadMessageTest::parseRejectsMissingFlag()
{
    QVERIFY(!MobileWebView::parseInlineDownloadMessage(
                QStringLiteral("{\"url\":\"blob:x\",\"base64\":\"YQ==\"}"))
                 .has_value());
    QVERIFY(!MobileWebView::parseInlineDownloadMessage(
                QStringLiteral("{\"mwvDownload\":false,\"url\":\"blob:x\"}"))
                 .has_value());
}

void InlineDownloadMessageTest::parseRejectsEmpty()
{
    QVERIFY(!MobileWebView::parseInlineDownloadMessage(QString()).has_value());
}

QTEST_MAIN(InlineDownloadMessageTest)
#include "tst_inlinedownloadmessage.moc"
