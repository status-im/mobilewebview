#include <QtTest/QtTest>

#include "inlinedownloadwriter.h"

#include <QFile>
#include <QTemporaryDir>

class InlineDownloadWriterTest : public QObject
{
    Q_OBJECT

private slots:
    void writeOkFreesPayload();
    void openFailureKeepsPayload();
    void missingPayloadFails();
    void takePayloadRemoves();
};

void InlineDownloadWriterTest::writeOkFreesPayload()
{
    InlineDownloadWriter writer;
    writer.store(1, QByteArray("hello-inline"));
    QVERIFY(writer.has(1));

    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    const QString path = dir.filePath(QStringLiteral("out.txt"));

    const auto result = writer.write(1, path);
    QVERIFY(result.ok);
    QCOMPARE(result.bytesWritten, qint64(12));
    QVERIFY(!writer.has(1));

    QFile file(path);
    QVERIFY(file.open(QIODevice::ReadOnly));
    QCOMPARE(file.readAll(), QByteArray("hello-inline"));
}

void InlineDownloadWriterTest::openFailureKeepsPayload()
{
    InlineDownloadWriter writer;
    writer.store(2, QByteArray("keep-me"));
    const auto result = writer.write(2, QStringLiteral("/no/such/dir/out.bin"));
    QVERIFY(!result.ok);
    QVERIFY(!result.error.isEmpty());
    QVERIFY(writer.has(2));
    QCOMPARE(writer.takePayload(2), QByteArray("keep-me"));
}

void InlineDownloadWriterTest::missingPayloadFails()
{
    InlineDownloadWriter writer;
    const auto result = writer.write(99, QStringLiteral("/tmp/x"));
    QVERIFY(!result.ok);
    QCOMPARE(result.error, QStringLiteral("Inline payload missing"));
}

void InlineDownloadWriterTest::takePayloadRemoves()
{
    InlineDownloadWriter writer;
    writer.store(3, QByteArray("abc"));
    QCOMPARE(writer.takePayload(3), QByteArray("abc"));
    QVERIFY(!writer.has(3));
    QVERIFY(writer.takePayload(3).isEmpty());
}

QTEST_MAIN(InlineDownloadWriterTest)
#include "tst_inlinedownloadwriter.moc"
