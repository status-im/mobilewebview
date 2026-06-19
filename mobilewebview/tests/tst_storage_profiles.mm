#include <QtTest/QtTest>

#include <QQuickWindow>
#include <QSignalSpy>

#include "MobileWebView/mobilewebviewbackend.h"

#if defined(Q_OS_MACOS) || defined(Q_OS_IOS)

namespace {

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

static void loadExamplePage(MobileWebViewBackend &backend)
{
    backend.loadHtml(QStringLiteral("<!doctype html><html><body>storage</body></html>"),
                     QUrl(QStringLiteral("https://example.com/index.html")));
    QVERIFY2(waitForLoaded(backend), "Page did not finish loading");
}

static void loadPageAt(MobileWebViewBackend &backend, const QString &baseUrl)
{
    backend.loadHtml(QStringLiteral("<!doctype html><html><body>storage</body></html>"),
                     QUrl(baseUrl));
    QVERIFY2(waitForLoaded(backend), "Page did not finish loading");
}

} // namespace

class StorageProfilesE2ETest : public QObject
{
    Q_OBJECT

private slots:
    void standardProfilePersistsLocalStorageAcrossRecreate();
    void incognitoProfileDoesNotPersistLocalStorageAcrossRecreate();
    void standardProfilesAreIsolatedByStorageName();
    void incognitoIsIsolatedFromStandardProfile();
    void threeSimultaneousProfilesIsolateLocalStorage();
};

void StorageProfilesE2ETest::threeSimultaneousProfilesIsolateLocalStorage()
{
    const QString base = QStringLiteral("https://storage-test.local/");

    // Mirror the app: three backends as siblings in ONE window, stores assigned
    // via property setters before the views are attached to the scene.
    QQuickWindow window;
    window.setGeometry(0, 0, 960, 320);

    MobileWebViewBackend backendA;
    MobileWebViewBackend backendB;
    MobileWebViewBackend backendInco;

    backendA.setStorageName(QStringLiteral("Profile_A"));
    backendA.setOffTheRecord(false);
    backendB.setStorageName(QStringLiteral("Profile_B"));
    backendB.setOffTheRecord(false);
    backendInco.setOffTheRecord(true);

    for (MobileWebViewBackend *b : {&backendA, &backendB, &backendInco}) {
        b->setParentItem(window.contentItem());
        b->setWidth(320);
        b->setHeight(240);
        b->setVisible(true);
    }
    window.show();
    QCoreApplication::processEvents();

    loadPageAt(backendA, base);
    loadPageAt(backendB, base);
    loadPageAt(backendInco, base);

    // Dispatch all three writes in parallel (mirrors the QML screen), each confirmed,
    // then read all back as a separate round (also mirrors the screen's read-back phase).
    QCOMPARE(runJsAndWaitResult(backendA,
                                QStringLiteral("localStorage.setItem('mwv_iso','v-A'); 'ok'")),
             QStringLiteral("ok"));
    QCOMPARE(runJsAndWaitResult(backendB,
                                QStringLiteral("localStorage.setItem('mwv_iso','v-B'); 'ok'")),
             QStringLiteral("ok"));
    QCOMPARE(runJsAndWaitResult(backendInco,
                                QStringLiteral("localStorage.setItem('mwv_iso','v-INCO'); 'ok'")),
             QStringLiteral("ok"));

    QCOMPARE(runJsAndWaitResult(backendA, QStringLiteral("localStorage.getItem('mwv_iso')")),
             QStringLiteral("v-A"));
    QCOMPARE(runJsAndWaitResult(backendB, QStringLiteral("localStorage.getItem('mwv_iso')")),
             QStringLiteral("v-B"));
    QCOMPARE(runJsAndWaitResult(backendInco, QStringLiteral("localStorage.getItem('mwv_iso')")),
             QStringLiteral("v-INCO"));
}

void StorageProfilesE2ETest::standardProfilePersistsLocalStorageAcrossRecreate()
{
    QQuickWindow window;
    MobileWebViewBackend backend;
    attachToWindow(backend, window);

    backend.setStorageName(QStringLiteral("Profile_test_standard"));
    backend.setOffTheRecord(false);
    loadExamplePage(backend);

    QCOMPARE(runJsAndWaitResult(backend,
                                QStringLiteral("localStorage.setItem('mwv_key','persisted'); 'ok'")),
             QStringLiteral("ok"));

    backend.setOffTheRecord(true);
    backend.setOffTheRecord(false);

    loadExamplePage(backend);
    QCOMPARE(runJsAndWaitResult(backend, QStringLiteral("localStorage.getItem('mwv_key')")),
             QStringLiteral("persisted"));
}

void StorageProfilesE2ETest::incognitoProfileDoesNotPersistLocalStorageAcrossRecreate()
{
    QQuickWindow window;
    MobileWebViewBackend backend;
    attachToWindow(backend, window);

    backend.setOffTheRecord(true);
    loadExamplePage(backend);

    QCOMPARE(runJsAndWaitResult(backend,
                                QStringLiteral("localStorage.setItem('mwv_key','secret'); 'ok'")),
             QStringLiteral("ok"));

    backend.setOffTheRecord(false);
    backend.setOffTheRecord(true);

    loadExamplePage(backend);
    const QString value = runJsAndWaitResult(backend, QStringLiteral("localStorage.getItem('mwv_key')"));
    QVERIFY(value.isEmpty() || value == QStringLiteral("null"));
}

void StorageProfilesE2ETest::standardProfilesAreIsolatedByStorageName()
{
    QQuickWindow windowA;
    QQuickWindow windowB;

    MobileWebViewBackend backendA;
    MobileWebViewBackend backendB;
    attachToWindow(backendA, windowA);
    attachToWindow(backendB, windowB);

    backendA.setStorageName(QStringLiteral("Profile_test_A"));
    backendB.setStorageName(QStringLiteral("Profile_test_B"));
    backendA.setOffTheRecord(false);
    backendB.setOffTheRecord(false);

    loadExamplePage(backendA);
    QCOMPARE(runJsAndWaitResult(backendA,
                                QStringLiteral("localStorage.setItem('mwv_key','from_a'); 'ok'")),
             QStringLiteral("ok"));

    loadExamplePage(backendB);
    const QString value = runJsAndWaitResult(backendB, QStringLiteral("localStorage.getItem('mwv_key')"));
    QVERIFY(value.isEmpty() || value == QStringLiteral("null"));
}

void StorageProfilesE2ETest::incognitoIsIsolatedFromStandardProfile()
{
    QQuickWindow windowStandard;
    QQuickWindow windowIncognito;

    MobileWebViewBackend standardBackend;
    MobileWebViewBackend incognitoBackend;
    attachToWindow(standardBackend, windowStandard);
    attachToWindow(incognitoBackend, windowIncognito);

    standardBackend.setStorageName(QStringLiteral("Profile_test_isolation"));
    standardBackend.setOffTheRecord(false);
    incognitoBackend.setOffTheRecord(true);

    loadExamplePage(standardBackend);
    QCOMPARE(runJsAndWaitResult(standardBackend,
                                QStringLiteral("localStorage.setItem('mwv_key','standard'); 'ok'")),
             QStringLiteral("ok"));

    loadExamplePage(incognitoBackend);
    const QString value = runJsAndWaitResult(incognitoBackend,
                                             QStringLiteral("localStorage.getItem('mwv_key')"));
    QVERIFY(value.isEmpty() || value == QStringLiteral("null"));
}

QTEST_MAIN(StorageProfilesE2ETest)
#include "tst_storage_profiles.moc"

#endif
