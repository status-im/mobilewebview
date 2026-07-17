#include <QtTest/QtTest>

#include "downloadregistry.h"
#include "MobileWebView/mobilewebviewdownload.h"

#include <QPointer>
#include <QSignalSpy>
#include <vector>

class DownloadRegistryTest : public QObject
{
    Q_OBJECT

private slots:
    void createRejectsUnsupportedSchemes();
    void createAndEmit();
    void progressUpdatesDownload();
    void finishRemovesAndDeleteLater();
    void cancelAllInvokesPlatformAndMarksCancelled();
};

void DownloadRegistryTest::createRejectsUnsupportedSchemes()
{
    QObject parent;
    DownloadRegistry registry(&parent, {}, {}, {});

    QVERIFY(registry.create(QUrl(QStringLiteral("blob:https://example.com/x")),
                            QString(), QString(), QStringLiteral("application/octet-stream"),
                            10)
            == nullptr);
    QVERIFY(registry.create(QUrl(QStringLiteral("data:text/plain,hi")),
                            QString(), QString(), QStringLiteral("text/plain"),
                            2)
            == nullptr);
    QVERIFY(registry.create(QUrl(), QString(), QString(), QString(), -1) == nullptr);
}

void DownloadRegistryTest::createAndEmit()
{
    QObject parent;
    MobileWebViewDownload *emitted = nullptr;
    DownloadRegistry registry(
        &parent,
        [&](MobileWebViewDownload *d) { emitted = d; },
        {},
        {});

    auto *download = registry.onDetected(
        QUrl(QStringLiteral("https://example.com/file.bin")),
        QStringLiteral("file.bin"),
        QString(),
        QStringLiteral("application/octet-stream"),
        42);

    QVERIFY(download != nullptr);
    QCOMPARE(emitted, download);
    QCOMPARE(download->url(), QUrl(QStringLiteral("https://example.com/file.bin")));
    QCOMPARE(download->suggestedFileName(), QStringLiteral("file.bin"));
    QCOMPARE(download->mimeType(), QStringLiteral("application/octet-stream"));
    QCOMPARE(download->totalBytes(), 42);
    QCOMPARE(download->state(), MobileWebViewDownload::State::Requested);
    QCOMPARE(registry.downloadById(download->downloadId()), download);
}

void DownloadRegistryTest::progressUpdatesDownload()
{
    QObject parent;
    quint64 startedId = 0;
    QUrl startedUrl;
    QString startedPath;
    DownloadRegistry registry(
        &parent,
        {},
        [&](quint64 id, const QUrl &url, const QString &path) {
            startedId = id;
            startedUrl = url;
            startedPath = path;
        },
        {});

    auto *download = registry.create(
        QUrl(QStringLiteral("https://example.com/a.bin")),
        QStringLiteral("a.bin"),
        QString(),
        QStringLiteral("application/octet-stream"),
        100);
    QVERIFY(download);
    download->accept(QStringLiteral("/tmp/a.bin"));
    QCOMPARE(download->state(), MobileWebViewDownload::State::InProgress);
    QCOMPARE(startedId, download->downloadId());
    QCOMPARE(startedUrl, QUrl(QStringLiteral("https://example.com/a.bin")));
    QCOMPARE(startedPath, QStringLiteral("/tmp/a.bin"));

    registry.onProgress(download->downloadId(), 40, 100);
    QCOMPARE(download->receivedBytes(), 40);
    QCOMPARE(download->totalBytes(), 100);
}

void DownloadRegistryTest::finishRemovesAndDeleteLater()
{
    QObject parent;
    DownloadRegistry registry(
        &parent,
        {},
        [](quint64, const QUrl &, const QString &) {},
        {});

    auto *download = registry.create(
        QUrl(QStringLiteral("https://example.com/a.bin")),
        QStringLiteral("a.bin"),
        QString(),
        QStringLiteral("application/octet-stream"),
        100);
    QVERIFY(download);
    const quint64 id = download->downloadId();
    download->accept(QStringLiteral("/tmp/a.bin"));

    QPointer<MobileWebViewDownload> guard(download);
    QSignalSpy finishedSpy(download, &MobileWebViewDownload::finished);

    registry.onFinished(id, true, QString());
    QCOMPARE(finishedSpy.count(), 1);
    QCOMPARE(download->state(), MobileWebViewDownload::State::Completed);
    QVERIFY(registry.downloadById(id) == nullptr);

    QCoreApplication::sendPostedEvents(nullptr, QEvent::DeferredDelete);
    QVERIFY(guard.isNull());
}

void DownloadRegistryTest::cancelAllInvokesPlatformAndMarksCancelled()
{
    QObject parent;
    std::vector<quint64> cancelledIds;
    DownloadRegistry registry(
        &parent,
        {},
        {},
        [&](quint64 id) { cancelledIds.push_back(id); });

    auto *d1 = registry.create(
        QUrl(QStringLiteral("https://example.com/a.bin")),
        QStringLiteral("a.bin"), QString(), QStringLiteral("application/octet-stream"),
        10);
    auto *d2 = registry.create(
        QUrl(QStringLiteral("https://example.com/b.bin")),
        QStringLiteral("b.bin"), QString(), QStringLiteral("application/octet-stream"),
        20);
    QVERIFY(d1);
    QVERIFY(d2);
    const quint64 id1 = d1->downloadId();
    const quint64 id2 = d2->downloadId();

    QPointer<MobileWebViewDownload> g1(d1);
    QPointer<MobileWebViewDownload> g2(d2);
    QSignalSpy f1(d1, &MobileWebViewDownload::finished);
    QSignalSpy f2(d2, &MobileWebViewDownload::finished);

    registry.cancelAll();

    QCOMPARE(cancelledIds.size(), size_t(2));
    QVERIFY(cancelledIds[0] == id1 || cancelledIds[0] == id2);
    QVERIFY(cancelledIds[1] == id1 || cancelledIds[1] == id2);
    QVERIFY(cancelledIds[0] != cancelledIds[1]);

    QCOMPARE(d1->state(), MobileWebViewDownload::State::Cancelled);
    QCOMPARE(d2->state(), MobileWebViewDownload::State::Cancelled);
    QCOMPARE(f1.count(), 1);
    QCOMPARE(f2.count(), 1);
    QVERIFY(registry.downloadById(id1) == nullptr);
    QVERIFY(registry.downloadById(id2) == nullptr);

    QCoreApplication::sendPostedEvents(nullptr, QEvent::DeferredDelete);
    QVERIFY(g1.isNull());
    QVERIFY(g2.isNull());
}

QTEST_MAIN(DownloadRegistryTest)
#include "tst_downloadregistry.moc"
