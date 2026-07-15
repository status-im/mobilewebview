#include <QtTest/QtTest>

#include <QCoreApplication>
#include <QElapsedTimer>
#include <QHostAddress>
#include <QQuickWindow>
#include <QRegularExpression>
#include <QSignalSpy>
#include <QTcpServer>
#include <QTcpSocket>

#include "MobileWebView/mobilewebviewbackend.h"
#include "../src/common/storage_profile_utils.h"

#if defined(Q_OS_MACOS) || defined(Q_OS_IOS)

#import <WebKit/WebKit.h>

namespace {

class HttpServer : public QTcpServer
{
    Q_OBJECT
public:
    explicit HttpServer(QObject *parent = nullptr)
        : QTcpServer(parent)
    {
    }

    void setResponse(const QByteArray &response) { m_response = response; }

    int requestCount() const { return m_requestCount; }

protected:
    void incomingConnection(qintptr socketDescriptor) override
    {
        ++m_requestCount;
        QTcpSocket *socket = new QTcpSocket(this);
        QByteArray *buffer = new QByteArray();
        connect(socket, &QTcpSocket::readyRead, [this, socket, buffer]() {
            *buffer += socket->readAll();
            if (buffer->contains("\r\n\r\n") && !buffer->isEmpty()) {
                buffer->clear();
                socket->write(m_response);
                socket->disconnectFromHost();
            }
        });
        connect(socket, &QTcpSocket::disconnected, socket, &QTcpSocket::deleteLater);
        connect(socket, &QObject::destroyed, [buffer]() { delete buffer; });
        socket->setSocketDescriptor(socketDescriptor);
    }

private:
    QByteArray m_response;
    int m_requestCount = 0;
};

static int findFreePort()
{
    QTcpServer server;
    if (server.listen(QHostAddress::LocalHost, 0)) {
        return server.serverPort();
    }
    return -1;
}

static bool waitForLoaded(MobileWebViewBackend &backend, int timeoutMs = 10000)
{
    if (backend.loaded()) {
        return true;
    }

    QSignalSpy loadedSpy(&backend, &MobileWebViewBackend::loadedChanged);
    QElapsedTimer timer;
    timer.start();
    while (timer.elapsed() < timeoutMs) {
        QCoreApplication::processEvents();
        if (backend.loaded()) {
            return true;
        }
        loadedSpy.wait(qMin(timeoutMs - int(timer.elapsed()), 500));
    }
    return backend.loaded();
}

static bool waitForClearCompleted(MobileWebViewBackend &backend, QSignalSpy &completedSpy,
                                  int timeoutMs = 10000)
{
    QElapsedTimer timer;
    timer.start();
    while (timer.elapsed() < timeoutMs) {
        QCoreApplication::processEvents();
        if (completedSpy.count() >= 1 && !backend.clearing()) {
            return true;
        }
        completedSpy.wait(qMin(timeoutMs - int(timer.elapsed()), 500));
    }
    return completedSpy.count() >= 1 && !backend.clearing();
}

static QString runJsAndWaitResult(MobileWebViewBackend &backend, const QString &script,
                                  int timeoutMs = 10000)
{
    QSignalSpy resultSpy(&backend, &MobileWebViewBackend::javaScriptResult);
    backend.runJavaScript(script);
    if (!resultSpy.wait(timeoutMs)) {
        return QString();
    }

    const QList<QVariant> args = resultSpy.takeFirst();
    if (args.size() != 2 || !args[1].toString().isEmpty()) {
        return QString();
    }

    const QVariant result = args[0];
    if (result.typeId() == QMetaType::QString) {
        return result.toString();
    }
    if (result.canConvert<double>()) {
        return QString::number(result.toDouble());
    }
    return result.toString();
}

static void attachToWindow(MobileWebViewBackend &backend, QQuickWindow &window)
{
    backend.setParentItem(window.contentItem());
    backend.setWidth(320);
    backend.setHeight(240);
    backend.setVisible(true);
    window.setGeometry(0, 0, 480, 320);
    window.show();
    QCoreApplication::processEvents();
}

static void loadPageAt(MobileWebViewBackend &backend, const QString &baseUrl)
{
    backend.loadHtml(QStringLiteral("<!doctype html><html><body>data clearing</body></html>"),
                     QUrl(baseUrl));
    QVERIFY2(waitForLoaded(backend), "Page did not finish loading");
}

static void loadUrl(MobileWebViewBackend &backend, const QUrl &url)
{
    backend.loadUrl(url);
    QVERIFY2(waitForLoaded(backend), "Page did not finish loading");
}

static WKWebsiteDataStore *dataStoreForBackend(MobileWebViewBackend &backend)
{
    const QString storageName = backend.storageName();
    if (storageName.isEmpty() || backend.offTheRecord()) {
        return [WKWebsiteDataStore defaultDataStore];
    }
    const QUuid uuid = storageProfileIdentifier(storageName);
    const QString uuidStr = uuid.toString(QUuid::WithoutBraces);
    NSUUID *nsUuid = [[NSUUID alloc] initWithUUIDString:uuidStr.toNSString()];
    WKWebsiteDataStore *store = [WKWebsiteDataStore dataStoreForIdentifier:nsUuid];
    [nsUuid release];
    return store;
}

static QList<WKWebsiteDataRecord *> fetchRecordsSync(WKWebsiteDataStore *store, NSSet *dataTypes)
{
    __block QList<WKWebsiteDataRecord *> records;
    __block bool done = false;
    [store fetchDataRecordsOfTypes:dataTypes completionHandler:^(NSArray<WKWebsiteDataRecord *> *array) {
        for (WKWebsiteDataRecord *record in array) {
            records.append([record retain]);
        }
        done = true;
    }];

    QElapsedTimer timer;
    timer.start();
    while (!done && timer.elapsed() < 10000) {
        QCoreApplication::processEvents();
    }
    return records;
}

static void releaseFetchedRecords(QList<WKWebsiteDataRecord *> &records)
{
    for (WKWebsiteDataRecord *record : records) {
        [record release];
    }
    records.clear();
}

static bool waitForRecordsEmpty(WKWebsiteDataStore *store, NSSet *dataTypes,
                                int timeoutMs = 10000)
{
    QElapsedTimer timer;
    timer.start();
    while (timer.elapsed() < timeoutMs) {
        QList<WKWebsiteDataRecord *> records = fetchRecordsSync(store, dataTypes);
        const bool empty = records.isEmpty();
        releaseFetchedRecords(records);
        if (empty) {
            return true;
        }
        QCoreApplication::processEvents();
    }
    QList<WKWebsiteDataRecord *> records = fetchRecordsSync(store, dataTypes);
    const bool empty = records.isEmpty();
    releaseFetchedRecords(records);
    return empty;
}

static bool waitForRecordsNonEmpty(WKWebsiteDataStore *store, NSSet *dataTypes,
                                   int timeoutMs = 10000)
{
    QElapsedTimer timer;
    timer.start();
    while (timer.elapsed() < timeoutMs) {
        QList<WKWebsiteDataRecord *> records = fetchRecordsSync(store, dataTypes);
        const bool nonEmpty = !records.isEmpty();
        releaseFetchedRecords(records);
        if (nonEmpty) {
            return true;
        }
        QCoreApplication::processEvents();
    }
    return false;
}

struct CookieSnapshot {
    QString name;
    QString domain;
};

static QList<CookieSnapshot> fetchCookieSnapshotsSync(WKWebsiteDataStore *store)
{
    __block QList<CookieSnapshot> cookies;
    __block bool done = false;
    [store.httpCookieStore getAllCookies:^(NSArray<NSHTTPCookie *> *array) {
        // Copy into Qt strings inside the callback — under ARC, NSHTTPCookie*
        // pointers must not outlive this autorelease pool / processEvents.
        for (NSHTTPCookie *cookie in array) {
            CookieSnapshot snap;
            snap.name = QString::fromNSString(cookie.name);
            snap.domain = QString::fromNSString(cookie.domain);
            cookies.append(snap);
        }
        done = true;
    }];

    QElapsedTimer timer;
    timer.start();
    while (!done && timer.elapsed() < 10000) {
        QCoreApplication::processEvents();
    }
    return cookies;
}

static bool waitForCookiesEmpty(WKWebsiteDataStore *store, int timeoutMs = 10000)
{
    QElapsedTimer timer;
    timer.start();
    while (timer.elapsed() < timeoutMs) {
        if (fetchCookieSnapshotsSync(store).isEmpty()) {
            return true;
        }
        QCoreApplication::processEvents();
    }
    return fetchCookieSnapshotsSync(store).isEmpty();
}

static bool waitForCookiesNonEmpty(WKWebsiteDataStore *store, int timeoutMs = 10000)
{
    QElapsedTimer timer;
    timer.start();
    while (timer.elapsed() < timeoutMs) {
        if (!fetchCookieSnapshotsSync(store).isEmpty()) {
            return true;
        }
        QCoreApplication::processEvents();
    }
    return false;
}

static bool recordListContainsHost(const QList<WKWebsiteDataRecord *> &records, NSString *hostName)
{
    for (WKWebsiteDataRecord *record : records) {
        if (record.displayName != nil
            && [record.displayName rangeOfString:hostName].location != NSNotFound) {
            return true;
        }
    }
    return false;
}

// Exact displayName match — required to detect substring false-positives in clearSiteData.
static bool recordListContainsExactHost(const QList<WKWebsiteDataRecord *> &records, NSString *hostName)
{
    for (WKWebsiteDataRecord *record : records) {
        if (record.displayName != nil && [record.displayName isEqualToString:hostName]) {
            return true;
        }
    }
    return false;
}

static bool waitForRecordWithDisplayNameGone(WKWebsiteDataStore *store, NSSet *dataTypes,
                                             const QString &host, int timeoutMs = 10000)
{
    QElapsedTimer timer;
    timer.start();
    NSString *hostName = host.toNSString();
    while (timer.elapsed() < timeoutMs) {
        QList<WKWebsiteDataRecord *> records = fetchRecordsSync(store, dataTypes);
        const bool found = recordListContainsHost(records, hostName);
        releaseFetchedRecords(records);
        if (!found) {
            return true;
        }
        QCoreApplication::processEvents();
    }
    return false;
}

static bool waitForRecordWithDisplayNamePresent(WKWebsiteDataStore *store, NSSet *dataTypes,
                                                const QString &host, int timeoutMs = 10000)
{
    QElapsedTimer timer;
    timer.start();
    NSString *hostName = host.toNSString();
    while (timer.elapsed() < timeoutMs) {
        QList<WKWebsiteDataRecord *> records = fetchRecordsSync(store, dataTypes);
        const bool found = recordListContainsHost(records, hostName);
        releaseFetchedRecords(records);
        if (found) {
            return true;
        }
        QCoreApplication::processEvents();
    }
    return false;
}

static bool waitForExactRecordPresent(WKWebsiteDataStore *store, NSSet *dataTypes,
                                      const QString &host, int timeoutMs = 10000)
{
    QElapsedTimer timer;
    timer.start();
    NSString *hostName = host.toNSString();
    while (timer.elapsed() < timeoutMs) {
        QList<WKWebsiteDataRecord *> records = fetchRecordsSync(store, dataTypes);
        const bool found = recordListContainsExactHost(records, hostName);
        releaseFetchedRecords(records);
        if (found) {
            return true;
        }
        QCoreApplication::processEvents();
    }
    return false;
}

static bool waitForExactRecordGone(WKWebsiteDataStore *store, NSSet *dataTypes,
                                   const QString &host, int timeoutMs = 10000)
{
    QElapsedTimer timer;
    timer.start();
    NSString *hostName = host.toNSString();
    while (timer.elapsed() < timeoutMs) {
        QList<WKWebsiteDataRecord *> records = fetchRecordsSync(store, dataTypes);
        const bool found = recordListContainsExactHost(records, hostName);
        releaseFetchedRecords(records);
        if (!found) {
            return true;
        }
        QCoreApplication::processEvents();
    }
    return false;
}

static NSSet *cacheDataTypes()
{
    return [NSSet setWithObjects:
        WKWebsiteDataTypeDiskCache,
        WKWebsiteDataTypeMemoryCache,
        WKWebsiteDataTypeOfflineWebApplicationCache,
        nil];
}

static NSSet *cookieDataTypes()
{
    return [NSSet setWithObjects:WKWebsiteDataTypeCookies, nil];
}

static NSSet *domStorageDataTypes()
{
    return [NSSet setWithObjects:
        WKWebsiteDataTypeLocalStorage,
        WKWebsiteDataTypeSessionStorage,
        WKWebsiteDataTypeIndexedDBDatabases,
        WKWebsiteDataTypeWebSQLDatabases,
        WKWebsiteDataTypeServiceWorkerRegistrations,
        WKWebsiteDataTypeOfflineWebApplicationCache,
        nil];
}

} // namespace

class DataClearingTest : public QObject
{
    Q_OBJECT

private slots:
    void clearSiteDataSupportedIsTrueOnApple();
    void noViewClearMethodsNoOp();
    void clearHttpCacheRemovesCacheRecords();
    void deleteAllCookiesRemovesCookies();
    void clearDomStorageRemovesAllDomStorage();
    void clearProfileDataRemovesCacheCookiesAndDomStorage();
    void clearSiteDataRemovesCurrentSiteCookiesCacheAndStorage();
    void clearSiteDataDoesNotClearSubstringRelatedHosts();
    void clearSiteDataClearsRegistrableDomainRecordForSubdomain();
    void clearSiteDataReloadsCurrentPage();
    void clearSiteDataOnBlankUrlIsNoOp();
    void reloadAndBypassCacheReloadsCurrentPage();
};

void DataClearingTest::clearSiteDataSupportedIsTrueOnApple()
{
    MobileWebViewBackend backend;
    QVERIFY(backend.clearSiteDataSupported());
}

void DataClearingTest::noViewClearMethodsNoOp()
{
    MobileWebViewBackend backend;
    QSignalSpy clearHttpCacheCompletedSpy(&backend, &MobileWebViewBackend::clearHttpCacheCompleted);
    QSignalSpy deleteAllCookiesCompletedSpy(&backend, &MobileWebViewBackend::deleteAllCookiesCompleted);
    QSignalSpy clearDomStorageCompletedSpy(&backend, &MobileWebViewBackend::clearDomStorageCompleted);
    QSignalSpy clearSiteDataCompletedSpy(&backend, &MobileWebViewBackend::clearSiteDataCompleted);
    QSignalSpy clearProfileDataCompletedSpy(&backend, &MobileWebViewBackend::clearProfileDataCompleted);

    QTest::ignoreMessage(QtWarningMsg,
                         "MobileWebViewBackend::clearHttpCache: no native view set up; ignoring");
    QTest::ignoreMessage(QtWarningMsg,
                         "MobileWebViewBackend::deleteAllCookies: no native view set up; ignoring");
    QTest::ignoreMessage(QtWarningMsg,
                         "MobileWebViewBackend::clearDomStorage: no native view set up; ignoring");
    QTest::ignoreMessage(QtWarningMsg,
                         "MobileWebViewBackend::clearSiteData: no native view set up; ignoring");
    QTest::ignoreMessage(QtWarningMsg,
                         "MobileWebViewBackend::clearProfileData: no native view set up; ignoring");
    QTest::ignoreMessage(QtWarningMsg,
                         "MobileWebViewBackend::reloadAndBypassCache: no native view set up; ignoring");

    backend.clearHttpCache();
    backend.deleteAllCookies();
    backend.clearDomStorage();
    backend.clearSiteData();
    backend.clearProfileData();
    backend.reloadAndBypassCache();

    QCoreApplication::processEvents();
    QCOMPARE(clearHttpCacheCompletedSpy.count(), 1);
    QCOMPARE(deleteAllCookiesCompletedSpy.count(), 1);
    QCOMPARE(clearDomStorageCompletedSpy.count(), 1);
    QCOMPARE(clearSiteDataCompletedSpy.count(), 1);
    QCOMPARE(clearProfileDataCompletedSpy.count(), 1);
    QVERIFY(!backend.clearing());
}

void DataClearingTest::clearHttpCacheRemovesCacheRecords()
{
    QQuickWindow window;
    MobileWebViewBackend backend;
    backend.setStorageName(QStringLiteral("DataClearingTest_cache"));
    attachToWindow(backend, window);

    HttpServer server;
    const int port = findFreePort();
    QVERIFY(port > 0);
    QVERIFY(server.listen(QHostAddress::LocalHost, port));

    const QByteArray page =
        "<!doctype html><html><body>cache test</body></html>";
    const QByteArray response =
        QByteArray("HTTP/1.1 200 OK\r\n"
                   "Content-Type: text/html\r\n"
                   "Cache-Control: public, max-age=3600\r\n"
                   "Content-Length: ") + QByteArray::number(page.size()) + "\r\n"
        "\r\n" + page;
    server.setResponse(response);

    const QUrl pageUrl(QStringLiteral("http://127.0.0.1:") + QString::number(port) + QStringLiteral("/page.html"));
    loadUrl(backend, pageUrl);

    WKWebsiteDataStore *store = dataStoreForBackend(backend);
    QVERIFY(waitForRecordsNonEmpty(store, cacheDataTypes()));

    QSignalSpy clearHttpCacheCompletedSpy(&backend, &MobileWebViewBackend::clearHttpCacheCompleted);
    backend.clearHttpCache();
    QVERIFY(waitForClearCompleted(backend, clearHttpCacheCompletedSpy));
    QVERIFY(waitForRecordsEmpty(store, cacheDataTypes()));
}

void DataClearingTest::deleteAllCookiesRemovesCookies()
{
    QQuickWindow window;
    MobileWebViewBackend backend;
    backend.setStorageName(QStringLiteral("DataClearingTest_cookies"));
    attachToWindow(backend, window);

    loadPageAt(backend, QStringLiteral("https://cookie-test.local/index.html"));

    QCOMPARE(runJsAndWaitResult(backend,
                                QStringLiteral("document.cookie = 'mwv_test=abc; Max-Age=3600'; 'ok'")),
             QStringLiteral("ok"));

    WKWebsiteDataStore *store = dataStoreForBackend(backend);
    QVERIFY(waitForCookiesNonEmpty(store));

    QSignalSpy deleteAllCookiesCompletedSpy(&backend, &MobileWebViewBackend::deleteAllCookiesCompleted);
    backend.deleteAllCookies();
    QVERIFY(waitForClearCompleted(backend, deleteAllCookiesCompletedSpy));
    QVERIFY(waitForCookiesEmpty(store));
}

void DataClearingTest::clearDomStorageRemovesAllDomStorage()
{
    QQuickWindow window;
    MobileWebViewBackend backend;
    backend.setStorageName(QStringLiteral("DataClearingTest_dom_storage"));
    attachToWindow(backend, window);

    HttpServer server;
    const int port = findFreePort();
    QVERIFY(port > 0);
    QVERIFY(server.listen(QHostAddress::LocalHost, port));

    const QByteArray page =
        "<!doctype html><html><body>dom storage</body></html>";
    const QByteArray response =
        QByteArray("HTTP/1.0 200 OK\r\n"
                   "Content-Type: text/html\r\n"
                   "Cache-Control: public, max-age=3600\r\n"
                   "Connection: close\r\n"
                   "Content-Length: ") + QByteArray::number(page.size()) + "\r\n"
        "\r\n" + page;
    server.setResponse(response);

    const QUrl pageUrl(QStringLiteral("http://127.0.0.1:") + QString::number(port)
                       + QStringLiteral("/page.html"));
    loadUrl(backend, pageUrl);

    QCOMPARE(runJsAndWaitResult(backend,
                                QStringLiteral("localStorage.setItem('mwv_key','value'); 'ok'")),
             QStringLiteral("ok"));

    // Recreate the web view to flush the in-memory localStorage to the data store.
    backend.setOffTheRecord(true);
    backend.setOffTheRecord(false);
    QVERIFY(waitForLoaded(backend));
    QCOMPARE(runJsAndWaitResult(backend, QStringLiteral("localStorage.getItem('mwv_key')")),
             QStringLiteral("value"));

    QSignalSpy clearDomStorageCompletedSpy(&backend, &MobileWebViewBackend::clearDomStorageCompleted);
    backend.clearDomStorage();
    QVERIFY(waitForClearCompleted(backend, clearDomStorageCompletedSpy));

    // Recreate again so the next page reads localStorage from the cleared store.
    backend.setOffTheRecord(true);
    backend.setOffTheRecord(false);
    QVERIFY(waitForLoaded(backend));
    const QString value = runJsAndWaitResult(backend,
                                             QStringLiteral("localStorage.getItem('mwv_key')"));
    QVERIFY(value.isEmpty() || value == QStringLiteral("null"));
}

void DataClearingTest::clearProfileDataRemovesCacheCookiesAndDomStorage()
{
    QQuickWindow window;
    MobileWebViewBackend backend;
    backend.setStorageName(QStringLiteral("DataClearingTest_profile"));
    attachToWindow(backend, window);

    HttpServer server;
    const int port = findFreePort();
    QVERIFY(port > 0);
    QVERIFY(server.listen(QHostAddress::LocalHost, port));

    const QByteArray page =
        "<!doctype html><html><body>profile data</body></html>";
    const QByteArray response =
        QByteArray("HTTP/1.1 200 OK\r\n"
                   "Content-Type: text/html\r\n"
                   "Cache-Control: public, max-age=3600\r\n"
                   "Content-Length: ") + QByteArray::number(page.size()) + "\r\n"
        "\r\n" + page;
    server.setResponse(response);

    const QUrl pageUrl(QStringLiteral("http://127.0.0.1:") + QString::number(port)
                       + QStringLiteral("/page.html"));
    loadUrl(backend, pageUrl);

    // Cookie
    QCOMPARE(runJsAndWaitResult(backend,
                                QStringLiteral("document.cookie = 'mwv_profile=1; Max-Age=3600'; 'ok'")),
             QStringLiteral("ok"));

    // DOM storage
    QCOMPARE(runJsAndWaitResult(backend,
                                QStringLiteral("localStorage.setItem('mwv_profile','2'); 'ok'")),
             QStringLiteral("ok"));

    // Flush localStorage to the store by recreating the web view.
    backend.setOffTheRecord(true);
    backend.setOffTheRecord(false);
    QVERIFY(waitForLoaded(backend));
    QCOMPARE(runJsAndWaitResult(backend, QStringLiteral("localStorage.getItem('mwv_profile')")),
             QStringLiteral("2"));

    QSignalSpy clearProfileDataCompletedSpy(&backend, &MobileWebViewBackend::clearProfileDataCompleted);
    backend.clearProfileData();
    QVERIFY(waitForClearCompleted(backend, clearProfileDataCompletedSpy));

    WKWebsiteDataStore *store = dataStoreForBackend(backend);
    QVERIFY(waitForRecordsEmpty(store, cacheDataTypes()));
    QVERIFY(waitForCookiesEmpty(store));

    // Verify DOM storage is gone after recreating once more.
    backend.setOffTheRecord(true);
    backend.setOffTheRecord(false);
    QVERIFY(waitForLoaded(backend));
    const QString value = runJsAndWaitResult(backend,
                                             QStringLiteral("localStorage.getItem('mwv_profile')"));
    QVERIFY(value.isEmpty() || value == QStringLiteral("null"));
}

void DataClearingTest::clearSiteDataRemovesCurrentSiteCookiesCacheAndStorage()
{
    QQuickWindow window;

    // Distinct hostnames so WKWebsiteDataRecord.displayName can distinguish sites.
    const QString hostA = QStringLiteral("siteaaa.invalid");
    const QString hostB = QStringLiteral("sitebbb.invalid");
    const QString baseA = QStringLiteral("http://") + hostA + QStringLiteral("/page.html");
    const QString baseB = QStringLiteral("http://") + hostB + QStringLiteral("/page.html");

    // Write site B first and flush it to the shared profile store.
    {
        MobileWebViewBackend backend;
        backend.setStorageName(QStringLiteral("DataClearingTest_clear_site"));
        attachToWindow(backend, window);
        loadPageAt(backend, baseB);
        QCOMPARE(runJsAndWaitResult(backend,
                                    QStringLiteral("document.cookie = 'mwv_b=1; Max-Age=3600'; "
                                                   "localStorage.setItem('mwv_key','b'); 'ok'")),
                 QStringLiteral("ok"));
        backend.setOffTheRecord(true);
        backend.setOffTheRecord(false);
        QVERIFY(waitForLoaded(backend));
        backend.setParentItem(nullptr);
    }

    MobileWebViewBackend backend;
    backend.setStorageName(QStringLiteral("DataClearingTest_clear_site"));
    attachToWindow(backend, window);

    // Prefer loadHtml for host A so cookie/storage records use siteaaa.invalid.
    loadPageAt(backend, baseA);
    QCOMPARE(runJsAndWaitResult(backend,
                                QStringLiteral("document.cookie = 'mwv_a=1; Max-Age=3600'; "
                                               "localStorage.setItem('mwv_key','a'); 'ok'")),
             QStringLiteral("ok"));

    // Flush site A to the store.
    backend.setOffTheRecord(true);
    backend.setOffTheRecord(false);
    QVERIFY(waitForLoaded(backend));

    WKWebsiteDataStore *store = dataStoreForBackend(backend);
    QVERIFY(waitForRecordWithDisplayNamePresent(store, domStorageDataTypes(), hostA));
    QVERIFY(waitForRecordWithDisplayNamePresent(store, domStorageDataTypes(), hostB));
    QVERIFY(waitForCookiesNonEmpty(store));

    QSignalSpy clearSiteDataCompletedSpy(&backend, &MobileWebViewBackend::clearSiteDataCompleted);
    backend.clearSiteData();
    QVERIFY(waitForClearCompleted(backend, clearSiteDataCompletedSpy));

    QVERIFY(waitForRecordWithDisplayNameGone(store, domStorageDataTypes(), hostA));
    QVERIFY(waitForRecordWithDisplayNamePresent(store, domStorageDataTypes(), hostB));

    // Site A's cookie should be gone; site B's cookie should remain.
    const QList<CookieSnapshot> cookies = fetchCookieSnapshotsSync(store);
    bool foundA = false;
    bool foundB = false;
    for (const CookieSnapshot &cookie : cookies) {
        if (cookie.name == QStringLiteral("mwv_a")) {
            foundA = true;
        }
        if (cookie.name == QStringLiteral("mwv_b")) {
            foundB = true;
        }
    }
    QVERIFY(!foundA);
    QVERIFY(foundB);

    // Drain in-flight fetch/remove completion handlers before teardown.
    QTest::qWait(500);
}

void DataClearingTest::clearSiteDataDoesNotClearSubstringRelatedHosts()
{
    // Bugbot finding: clearSiteDataImpl matches displayName with rangeOfString:,
    // so clearing host "aaa.invalid" also removes "siteaaa.invalid".
    QQuickWindow window;

    const QString hostShort = QStringLiteral("aaa.invalid");
    const QString hostLong = QStringLiteral("siteaaa.invalid");
    const QString baseShort = QStringLiteral("http://") + hostShort + QStringLiteral("/page.html");
    const QString baseLong = QStringLiteral("http://") + hostLong + QStringLiteral("/page.html");
    const QString storageName = QStringLiteral("DataClearingTest_clear_site_substring");

    {
        MobileWebViewBackend backend;
        backend.setStorageName(storageName);
        attachToWindow(backend, window);
        loadPageAt(backend, baseLong);
        QCOMPARE(runJsAndWaitResult(backend,
                                    QStringLiteral("localStorage.setItem('mwv_key','long'); 'ok'")),
                 QStringLiteral("ok"));
        backend.setOffTheRecord(true);
        backend.setOffTheRecord(false);
        QVERIFY(waitForLoaded(backend));
        backend.setParentItem(nullptr);
    }

    MobileWebViewBackend backend;
    backend.setStorageName(storageName);
    attachToWindow(backend, window);
    loadPageAt(backend, baseShort);
    QCOMPARE(runJsAndWaitResult(backend,
                                QStringLiteral("localStorage.setItem('mwv_key','short'); 'ok'")),
             QStringLiteral("ok"));
    backend.setOffTheRecord(true);
    backend.setOffTheRecord(false);
    QVERIFY(waitForLoaded(backend));

    WKWebsiteDataStore *store = dataStoreForBackend(backend);
    QVERIFY(waitForExactRecordPresent(store, domStorageDataTypes(), hostShort));
    QVERIFY(waitForExactRecordPresent(store, domStorageDataTypes(), hostLong));

    QSignalSpy clearSiteDataCompletedSpy(&backend, &MobileWebViewBackend::clearSiteDataCompleted);
    backend.clearSiteData();
    QVERIFY(waitForClearCompleted(backend, clearSiteDataCompletedSpy));

    QVERIFY(waitForExactRecordGone(store, domStorageDataTypes(), hostShort));
    // Must survive: hostLong merely contains hostShort as a substring.
    QVERIFY2(waitForExactRecordPresent(store, domStorageDataTypes(), hostLong),
             "clearSiteData must not remove records whose displayName only contains "
             "the current host as a substring");

    QTest::qWait(500);
}

void DataClearingTest::clearSiteDataClearsRegistrableDomainRecordForSubdomain()
{
    // WKWebsiteDataRecord.displayName is the eTLD+1 (registrable domain), not the
    // full host. Clearing from "sub.siteaaa.invalid" must remove the record named
    // "siteaaa.invalid" — exact host equality alone leaves that data behind.
    QQuickWindow window;

    const QString registrableDomain = QStringLiteral("siteaaa.invalid");
    const QString subdomainHost = QStringLiteral("sub.") + registrableDomain;
    const QString otherDomain = QStringLiteral("sitebbb.invalid");
    const QString baseSub = QStringLiteral("http://") + subdomainHost + QStringLiteral("/page.html");
    const QString baseOther = QStringLiteral("http://") + otherDomain + QStringLiteral("/page.html");
    const QString storageName = QStringLiteral("DataClearingTest_clear_site_etld");

    {
        MobileWebViewBackend backend;
        backend.setStorageName(storageName);
        attachToWindow(backend, window);
        loadPageAt(backend, baseOther);
        QCOMPARE(runJsAndWaitResult(backend,
                                    QStringLiteral("localStorage.setItem('mwv_key','other'); 'ok'")),
                 QStringLiteral("ok"));
        backend.setOffTheRecord(true);
        backend.setOffTheRecord(false);
        QVERIFY(waitForLoaded(backend));
        backend.setParentItem(nullptr);
    }

    MobileWebViewBackend backend;
    backend.setStorageName(storageName);
    attachToWindow(backend, window);
    loadPageAt(backend, baseSub);
    QCOMPARE(backend.url().host(), subdomainHost);
    QCOMPARE(runJsAndWaitResult(backend,
                                QStringLiteral("localStorage.setItem('mwv_key','sub'); 'ok'")),
             QStringLiteral("ok"));
    backend.setOffTheRecord(true);
    backend.setOffTheRecord(false);
    QVERIFY(waitForLoaded(backend));

    WKWebsiteDataStore *store = dataStoreForBackend(backend);
    // Subdomain pages are keyed under the registrable domain, not the full host.
    QVERIFY2(waitForExactRecordPresent(store, domStorageDataTypes(), registrableDomain),
             "expected WKWebsiteDataRecord.displayName to be eTLD+1 for subdomain page");
    QVERIFY(waitForExactRecordPresent(store, domStorageDataTypes(), otherDomain));

    QSignalSpy clearSiteDataCompletedSpy(&backend, &MobileWebViewBackend::clearSiteDataCompleted);
    backend.clearSiteData();
    QVERIFY(waitForClearCompleted(backend, clearSiteDataCompletedSpy));

    QVERIFY2(waitForExactRecordGone(store, domStorageDataTypes(), registrableDomain),
             "clearSiteData from a subdomain must remove the eTLD+1 data record");
    QVERIFY(waitForExactRecordPresent(store, domStorageDataTypes(), otherDomain));

    QTest::qWait(500);
}

void DataClearingTest::clearSiteDataReloadsCurrentPage()
{
    QQuickWindow window;
    MobileWebViewBackend backend;
    backend.setStorageName(QStringLiteral("DataClearingTest_clear_site_reload"));
    attachToWindow(backend, window);

    HttpServer server;
    const int port = findFreePort();
    QVERIFY(port > 0);
    QVERIFY(server.listen(QHostAddress::LocalHost, port));

    const QByteArray page =
        "<!doctype html><html><body>clear site reload</body></html>";
    const QByteArray response =
        QByteArray("HTTP/1.0 200 OK\r\n"
                   "Content-Type: text/html\r\n"
                   "Cache-Control: public, max-age=3600\r\n"
                   "Connection: close\r\n"
                   "Content-Length: ") + QByteArray::number(page.size()) + "\r\n"
        "\r\n" + page;
    server.setResponse(response);

    const QUrl pageUrl(QStringLiteral("http://127.0.0.1:") + QString::number(port)
                       + QStringLiteral("/page.html"));
    loadUrl(backend, pageUrl);
    const QUrl loadedUrl = backend.url();
    QCOMPARE(loadedUrl, pageUrl);
    const int requestsBefore = server.requestCount();
    QVERIFY(requestsBefore >= 1);

    QSignalSpy clearSiteDataCompletedSpy(&backend, &MobileWebViewBackend::clearSiteDataCompleted);
    backend.clearSiteData();
    QVERIFY(waitForClearCompleted(backend, clearSiteDataCompletedSpy));

    // clearSiteData finishes with reloadAndBypassCache — prove a fresh network fetch.
    QElapsedTimer timer;
    timer.start();
    while (timer.elapsed() < 10000 && server.requestCount() <= requestsBefore) {
        QCoreApplication::processEvents();
        QTest::qWait(10);
    }
    QVERIFY2(server.requestCount() > requestsBefore,
             "clearSiteData did not trigger a cache-bypass network reload");
    QVERIFY(waitForLoaded(backend));
    QCOMPARE(backend.url(), loadedUrl);
}

void DataClearingTest::clearSiteDataOnBlankUrlIsNoOp()
{
    QQuickWindow window;
    MobileWebViewBackend backend;
    attachToWindow(backend, window);

    // Force a blank URL with no clearable host after the native view is up.
    backend.loadUrl(QUrl(QStringLiteral("about:blank")));
    QVERIFY(waitForLoaded(backend));

    QSignalSpy clearSiteDataCompletedSpy(&backend, &MobileWebViewBackend::clearSiteDataCompleted);
    QTest::ignoreMessage(QtWarningMsg,
                         QRegularExpression(QStringLiteral(
                             "MobileWebViewBackend::clearSiteData: current url has no clearable host")));
    backend.clearSiteData();
    QCoreApplication::processEvents();
    QCOMPARE(clearSiteDataCompletedSpy.count(), 1);
    QVERIFY(!backend.clearing());
}

void DataClearingTest::reloadAndBypassCacheReloadsCurrentPage()
{
    QQuickWindow window;
    MobileWebViewBackend backend;
    attachToWindow(backend, window);

    HttpServer server;
    const int port = findFreePort();
    QVERIFY(port > 0);
    QVERIFY(server.listen(QHostAddress::LocalHost, port));

    const QByteArray page =
        "<!doctype html><html><body>reload bypass</body></html>";
    const QByteArray response =
        QByteArray("HTTP/1.0 200 OK\r\n"
                   "Content-Type: text/html\r\n"
                   "Cache-Control: public, max-age=3600\r\n"
                   "Connection: close\r\n"
                   "Content-Length: ") + QByteArray::number(page.size()) + "\r\n"
        "\r\n" + page;
    server.setResponse(response);

    const QUrl pageUrl(QStringLiteral("http://127.0.0.1:") + QString::number(port)
                       + QStringLiteral("/page.html"));
    loadUrl(backend, pageUrl);
    const QUrl loadedUrl = backend.url();
    QCOMPARE(loadedUrl, pageUrl);

    backend.reloadAndBypassCache();

    bool sawLoadingCycle = false;
    QElapsedTimer timer;
    timer.start();
    while (timer.elapsed() < 10000) {
        QCoreApplication::processEvents();
        if (backend.loading()) {
            sawLoadingCycle = true;
        }
        if (backend.loaded() && sawLoadingCycle) {
            break;
        }
        QTest::qWait(10);
    }

    QVERIFY2(sawLoadingCycle, "reloadAndBypassCache did not start loading");
    QVERIFY2(backend.loaded(), "reloadAndBypassCache did not finish loading");
    QCOMPARE(backend.url(), loadedUrl);
}

QTEST_MAIN(DataClearingTest)
#include "tst_data_clearing.moc"

#endif
