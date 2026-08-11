#include <QtTest/QtTest>

#include "downloadregistry.h"
#include "downloadtransfer.h"
#include "MobileWebView/mobilewebviewdownload.h"

#include <QFile>
#include <QPointer>
#include <QSignalSpy>
#include <QTemporaryDir>
#include <vector>

namespace {

class RecordingTransfer final : public DownloadTransfer
{
public:
    void start(quint64 id, const QUrl &url, const QString &path) override
    {
        startedIds.push_back(id);
        lastUrl = url;
        lastPath = path;
    }
    void cancel(quint64 id) override { cancelledIds.push_back(id); }
    void pause(quint64 id) override { pausedIds.push_back(id); }
    void resume(quint64 id) override { resumedIds.push_back(id); }

    std::vector<quint64> startedIds;
    std::vector<quint64> cancelledIds;
    std::vector<quint64> pausedIds;
    std::vector<quint64> resumedIds;
    QUrl lastUrl;
    QString lastPath;
};

} // namespace

class DownloadRegistryTest : public QObject
{
    Q_OBJECT

private slots:
    void createRejectsUnsupportedSchemes();
    void createAndEmit();
    void detectedTokenIsEchoedVerbatim();
    void progressUpdatesDownload();
    void finishRemovesAndDeleteLater();
    void cancelAllInvokesPlatformAndMarksCancelled();
    void inlineAcceptWritesPayload();
    void pauseResumeTransitions();
    void cancelAllCancelsPaused();
    void retryFromInterruptedEmitsNewRequest();
    void retryInlineFromInterrupted();
    void inlinePayloadFreedOnCompleted();
};

void DownloadRegistryTest::createRejectsUnsupportedSchemes()
{
    QObject parent;
    RecordingTransfer transfer;
    DownloadRegistry registry(&parent, {}, &transfer);

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
    RecordingTransfer transfer;
    MobileWebViewDownload *emitted = nullptr;
    QString emittedToken;
    DownloadRegistry registry(
        &parent,
        [&](MobileWebViewDownload *d, const QString &t) { emitted = d; emittedToken = t; },
        &transfer);

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
    QVERIFY(!download->isInline());
    QCOMPARE(registry.downloadById(download->downloadId()), download);
    QVERIFY(emittedToken.isEmpty());
}

// The token is opaque: the registry never reads it, only hands it back with
// the Download Request the host's downloadUrl() started.
void DownloadRegistryTest::detectedTokenIsEchoedVerbatim()
{
    QObject parent;
    RecordingTransfer transfer;
    MobileWebViewDownload *emitted = nullptr;
    QString emittedToken;
    DownloadRegistry registry(
        &parent,
        [&](MobileWebViewDownload *d, const QString &t) { emitted = d; emittedToken = t; },
        &transfer);

    auto *download = registry.onDetected(
        QUrl(QStringLiteral("https://example.com/file.bin")),
        QStringLiteral("file.bin"),
        QString(),
        QStringLiteral("application/octet-stream"),
        -1,
        {},
        QStringLiteral("retry-7"));

    QCOMPARE(emitted, download);
    QCOMPARE(emittedToken, QStringLiteral("retry-7"));

    // A library retry() is the registry's own request, not the host's — no token.
    download->accept(QStringLiteral("/tmp/file.bin"));
    registry.onFinished(download->downloadId(), false, QStringLiteral("network"));
    download->retry();
    QVERIFY(emitted != download);
    QVERIFY(emittedToken.isEmpty());
}

void DownloadRegistryTest::progressUpdatesDownload()
{
    QObject parent;
    RecordingTransfer transfer;
    DownloadRegistry registry(&parent, {}, &transfer);

    auto *download = registry.create(
        QUrl(QStringLiteral("https://example.com/a.bin")),
        QStringLiteral("a.bin"),
        QString(),
        QStringLiteral("application/octet-stream"),
        100);
    QVERIFY(download);
    download->accept(QStringLiteral("/tmp/a.bin"));
    QCOMPARE(download->state(), MobileWebViewDownload::State::InProgress);
    QCOMPARE(transfer.startedIds.size(), size_t(1));
    QCOMPARE(transfer.startedIds[0], download->downloadId());
    QCOMPARE(transfer.lastUrl, QUrl(QStringLiteral("https://example.com/a.bin")));
    QCOMPARE(transfer.lastPath, QStringLiteral("/tmp/a.bin"));

    registry.onProgress(download->downloadId(), 40, 100);
    QCOMPARE(download->receivedBytes(), 40);
    QCOMPARE(download->totalBytes(), 100);
}

void DownloadRegistryTest::finishRemovesAndDeleteLater()
{
    QObject parent;
    RecordingTransfer transfer;
    DownloadRegistry registry(&parent, {}, &transfer);

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
    RecordingTransfer transfer;
    DownloadRegistry registry(&parent, {}, &transfer);

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

    QCOMPARE(transfer.cancelledIds.size(), size_t(2));
    QVERIFY(transfer.cancelledIds[0] == id1 || transfer.cancelledIds[0] == id2);
    QVERIFY(transfer.cancelledIds[1] == id1 || transfer.cancelledIds[1] == id2);
    QVERIFY(transfer.cancelledIds[0] != transfer.cancelledIds[1]);

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

void DownloadRegistryTest::inlineAcceptWritesPayload()
{
    QObject parent;
    RecordingTransfer transfer;
    DownloadRegistry registry(&parent, {}, &transfer);

    const QByteArray payload("hello-inline");
    auto *download = registry.create(
        QUrl(QStringLiteral("blob:https://example.com/uuid")),
        QStringLiteral("hello.txt"),
        QString(),
        QStringLiteral("text/plain"),
        -1,
        payload);
    QVERIFY(download);
    QVERIFY(download->isInline());
    QCOMPARE(download->totalBytes(), payload.size());

    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    const QString path = dir.filePath(QStringLiteral("out.txt"));

    QSignalSpy finishedSpy(download, &MobileWebViewDownload::finished);
    download->accept(path);

    QCOMPARE(transfer.startedIds.size(), size_t(0));
    QCOMPARE(finishedSpy.count(), 1);
    QCOMPARE(download->state(), MobileWebViewDownload::State::Completed);
    QCOMPARE(download->receivedBytes(), payload.size());
    QVERIFY(registry.downloadById(download->downloadId()) == nullptr);
    QVERIFY(!registry.inlineWriter().has(download->downloadId()));

    QFile file(path);
    QVERIFY(file.open(QIODevice::ReadOnly));
    QCOMPARE(file.readAll(), payload);
}

void DownloadRegistryTest::pauseResumeTransitions()
{
    QObject parent;
    RecordingTransfer transfer;
    DownloadRegistry registry(&parent, {}, &transfer);

    auto *download = registry.create(
        QUrl(QStringLiteral("https://example.com/a.bin")),
        QStringLiteral("a.bin"),
        QString(),
        QStringLiteral("application/octet-stream"),
        100);
    QVERIFY(download);
    download->accept(QStringLiteral("/tmp/a.bin"));
    QCOMPARE(download->state(), MobileWebViewDownload::State::InProgress);
    QVERIFY(!download->isPaused());

    download->pause();
    QCOMPARE(download->state(), MobileWebViewDownload::State::Paused);
    QVERIFY(download->isPaused());
    QCOMPARE(transfer.pausedIds.size(), size_t(1));
    QCOMPARE(transfer.pausedIds[0], download->downloadId());
    QCOMPARE(registry.downloadById(download->downloadId()), download);

    download->resume();
    QCOMPARE(download->state(), MobileWebViewDownload::State::InProgress);
    QVERIFY(!download->isPaused());
    QCOMPARE(transfer.resumedIds.size(), size_t(1));
    QCOMPARE(transfer.resumedIds[0], download->downloadId());
}

void DownloadRegistryTest::cancelAllCancelsPaused()
{
    QObject parent;
    RecordingTransfer transfer;
    DownloadRegistry registry(&parent, {}, &transfer);

    auto *download = registry.create(
        QUrl(QStringLiteral("https://example.com/a.bin")),
        QStringLiteral("a.bin"),
        QString(),
        QStringLiteral("application/octet-stream"),
        100);
    QVERIFY(download);
    download->accept(QStringLiteral("/tmp/a.bin"));
    download->pause();
    QCOMPARE(download->state(), MobileWebViewDownload::State::Paused);

    registry.cancelAll();
    QCOMPARE(transfer.cancelledIds.size(), size_t(1));
    QCOMPARE(download->state(), MobileWebViewDownload::State::Cancelled);
}

void DownloadRegistryTest::retryFromInterruptedEmitsNewRequest()
{
    QObject parent;
    RecordingTransfer transfer;
    MobileWebViewDownload *emitted = nullptr;
    QString emittedToken;
    DownloadRegistry registry(
        &parent,
        [&](MobileWebViewDownload *d, const QString &t) { emitted = d; emittedToken = t; },
        &transfer);

    auto *download = registry.create(
        QUrl(QStringLiteral("https://example.com/a.bin")),
        QStringLiteral("a.bin"),
        QString(),
        QStringLiteral("application/octet-stream"),
        100);
    QVERIFY(download);
    const quint64 id = download->downloadId();
    download->accept(QStringLiteral("/tmp/a.bin"));
    registry.onFinished(id, false, QStringLiteral("network"));

    QCOMPARE(download->state(), MobileWebViewDownload::State::Interrupted);
    emitted = nullptr;
    download->retry();
    QVERIFY(emitted != nullptr);
    QVERIFY(emitted != download);
    QCOMPARE(emitted->url(), QUrl(QStringLiteral("https://example.com/a.bin")));
    QCOMPARE(emitted->suggestedFileName(), QStringLiteral("a.bin"));
    QCOMPARE(emitted->state(), MobileWebViewDownload::State::Requested);
}

void DownloadRegistryTest::retryInlineFromInterrupted()
{
    QObject parent;
    RecordingTransfer transfer;
    MobileWebViewDownload *emitted = nullptr;
    QString emittedToken;
    DownloadRegistry registry(
        &parent,
        [&](MobileWebViewDownload *d, const QString &t) { emitted = d; emittedToken = t; },
        &transfer);

    const QByteArray payload("retry-me");
    auto *download = registry.onDetected(
        QUrl(QStringLiteral("blob:https://example.com/uuid")),
        QStringLiteral("x.txt"),
        QString(),
        QStringLiteral("text/plain"),
        -1,
        payload);
    QVERIFY(download);
    QVERIFY(download->isInline());
    const quint64 id = download->downloadId();

    // Force interrupt without writing (simulate open failure path).
    registry.onFinished(id, false, QStringLiteral("Failed to open destination"));
    QCOMPARE(download->state(), MobileWebViewDownload::State::Interrupted);
    QVERIFY(registry.inlineWriter().has(id));

    emitted = nullptr;
    download->retry();
    QVERIFY(emitted != nullptr);
    QVERIFY(emitted != download);
    QVERIFY(emitted->isInline());
    QVERIFY(!registry.inlineWriter().has(id));
    QVERIFY(registry.inlineWriter().has(emitted->downloadId()));

    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    const QString path = dir.filePath(QStringLiteral("retry.txt"));
    emitted->accept(path);
    QCOMPARE(emitted->state(), MobileWebViewDownload::State::Completed);
    QFile file(path);
    QVERIFY(file.open(QIODevice::ReadOnly));
    QCOMPARE(file.readAll(), payload);
}

void DownloadRegistryTest::inlinePayloadFreedOnCompleted()
{
    QObject parent;
    RecordingTransfer transfer;
    DownloadRegistry registry(&parent, {}, &transfer);

    auto *download = registry.create(
        QUrl(QStringLiteral("blob:https://example.com/uuid")),
        QStringLiteral("x.txt"),
        QString(),
        QStringLiteral("text/plain"),
        -1,
        QByteArray("gone"));
    QVERIFY(download);
    const quint64 id = download->downloadId();
    QVERIFY(registry.inlineWriter().has(id));

    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    download->accept(dir.filePath(QStringLiteral("x.txt")));
    QCOMPARE(download->state(), MobileWebViewDownload::State::Completed);
    QVERIFY(!registry.inlineWriter().has(id));
}

QTEST_MAIN(DownloadRegistryTest)
#include "tst_downloadregistry.moc"
