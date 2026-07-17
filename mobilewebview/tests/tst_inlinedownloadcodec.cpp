#include <QtTest/QtTest>

#include "inlinedownloadcodec.h"

using namespace MobileWebView::InlineDownloadCodec;

class InlineDownloadCodecTest : public QObject
{
    Q_OBJECT

private slots:
    void decode_helloWorld();
    void decode_rejectsEmpty();
    void decode_rejectsOversize();
    void stripDataUrlPrefix();
    void decode_dataUrlPayload();
};

void InlineDownloadCodecTest::decode_helloWorld()
{
    // "hello" in standard base64
    const auto result = decodeBase64(QStringLiteral("aGVsbG8="));
    QVERIFY(result.ok);
    QCOMPARE(result.bytes, QByteArray("hello"));
    QVERIFY(result.error.isEmpty());
}

void InlineDownloadCodecTest::decode_rejectsEmpty()
{
    const auto result = decodeBase64(QString());
    QVERIFY(!result.ok);
    QVERIFY(!result.error.isEmpty());
}

void InlineDownloadCodecTest::decode_rejectsOversize()
{
    // Request a tiny limit so a modest payload fails.
    const auto result = decodeBase64(QStringLiteral("aGVsbG8="), 2);
    QVERIFY(!result.ok);
    QVERIFY(result.error.contains(QStringLiteral("size limit")));
}

void InlineDownloadCodecTest::stripDataUrlPrefix()
{
    QCOMPARE(stripDataUrlBase64Prefix(QStringLiteral("aGVsbG8=")),
             QStringLiteral("aGVsbG8="));
    QCOMPARE(stripDataUrlBase64Prefix(
                 QStringLiteral("data:text/plain;base64,aGVsbG8=")),
             QStringLiteral("aGVsbG8="));
}

void InlineDownloadCodecTest::decode_dataUrlPayload()
{
    const auto result =
        decodeBase64(QStringLiteral("data:application/octet-stream;base64,aGVsbG8="));
    QVERIFY(result.ok);
    QCOMPARE(result.bytes, QByteArray("hello"));
}

QTEST_MAIN(InlineDownloadCodecTest)
#include "tst_inlinedownloadcodec.moc"
