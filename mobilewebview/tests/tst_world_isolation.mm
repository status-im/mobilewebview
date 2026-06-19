#include <QtTest/QtTest>

#include <QJsonDocument>
#include <QJsonObject>
#include <QQuickWindow>
#include <QSignalSpy>
#include <QVariantMap>

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
    return args[0].toString();
}

static QJsonObject runJsAndWaitObject(MobileWebViewBackend &backend, const QString &script)
{
    const QString text = runJsAndWaitResult(backend, script);
    const QJsonDocument doc = QJsonDocument::fromJson(text.toUtf8());
    return doc.isObject() ? doc.object() : QJsonObject();
}

} // namespace

class WorldIsolationTest : public QObject
{
    Q_OBJECT

private slots:
    void bridgeWorldIsolatedFromPageWorld();
};

// Verifies the bridge-world (where runJavaScript / WebChannel transport run on
// macOS 11+/iOS 14+) is isolated from the page-world (page JS + user scripts):
// - JS globals are NOT shared across worlds in either direction.
// - The DOM IS shared (this is the channel the bridge relies on).
void WorldIsolationTest::bridgeWorldIsolatedFromPageWorld()
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

    const QString probeScriptPath = QFINDTESTDATA("world_isolation_probe.js");
    QVERIFY2(!probeScriptPath.isEmpty(), "Failed to locate world_isolation_probe.js");

    QVariantMap scriptMap;
    scriptMap.insert(QStringLiteral("path"), probeScriptPath);
    backend.setUserScripts(QVariantList{scriptMap});

    const QString origin = QStringLiteral("https://example.com");
    const bool installed = backend.installMessageBridge(
        QStringLiteral("qt"),
        QStringList{origin},
        QStringLiteral("iso-key"));
    QVERIFY(installed);

    backend.loadHtml(QStringLiteral("<!doctype html><html><body>isolation</body></html>"),
                     QUrl(QStringLiteral("https://example.com/index.html")));
    QVERIFY2(waitForLoaded(backend), "Page did not finish loading");

    // Phase 1 (isolated world): seed bridge globals + DOM marker, signal page world.
    const QString nonce = QStringLiteral("nonce-1");
    const QString phase1 = QStringLiteral(
        "(function(){"
        "var r=document.documentElement;"
        "window.__bridgeVar='BRIDGE';"
        "r.setAttribute('data-bridge-marker','set');"
        "r.setAttribute('data-probe-nonce','%1');"
        "return JSON.stringify({"
        "  isolatedSeesPageVar: typeof window.__pageVar,"
        "  isolatedSeesUserscriptVar: typeof window.__userscriptVar,"
        "  userscriptRan: r.getAttribute('data-userscript-var')"
        "});"
        "})()").arg(nonce);

    const QJsonObject probe = runJsAndWaitObject(backend, phase1);
    QVERIFY2(!probe.isEmpty(), "Phase 1 probe returned no JSON");

    // User script ran in page world (proves DOM is shared between worlds).
    QCOMPARE(probe.value(QStringLiteral("userscriptRan")).toString(), QStringLiteral("set"));
    // Isolated world cannot see page-world globals.
    QCOMPARE(probe.value(QStringLiteral("isolatedSeesPageVar")).toString(), QStringLiteral("undefined"));
    QCOMPARE(probe.value(QStringLiteral("isolatedSeesUserscriptVar")).toString(), QStringLiteral("undefined"));

    // Phase 2 (readback): poll the shared DOM until the page world has reacted.
    const QString readback = QStringLiteral(
        "(function(){"
        "var r=document.documentElement;"
        "return JSON.stringify({"
        "  pageNonce: r.getAttribute('data-page-nonce'),"
        "  pageSeesBridgeVar: r.getAttribute('data-page-sees-bridge'),"
        "  pageSeesUserscriptVar: r.getAttribute('data-page-sees-userscript'),"
        "  pageDomMarker: r.getAttribute('data-page-dom-marker')"
        "});"
        "})()");

    QJsonObject pageView;
    QElapsedTimer timer;
    timer.start();
    while (timer.elapsed() < 5000) {
        pageView = runJsAndWaitObject(backend, readback);
        if (pageView.value(QStringLiteral("pageNonce")).toString() == nonce) {
            break;
        }
        QCoreApplication::processEvents();
    }

    QCOMPARE(pageView.value(QStringLiteral("pageNonce")).toString(), nonce);
    // Page world cannot see the isolated bridge global.
    QCOMPARE(pageView.value(QStringLiteral("pageSeesBridgeVar")).toString(), QStringLiteral("undefined"));
    // Page world sees its own user-script global (sanity check it ran in page world).
    QCOMPARE(pageView.value(QStringLiteral("pageSeesUserscriptVar")).toString(), QStringLiteral("string"));
    // DOM marker set from the isolated world is visible in the page world.
    QCOMPARE(pageView.value(QStringLiteral("pageDomMarker")).toString(), QStringLiteral("set"));
}

QTEST_MAIN(WorldIsolationTest)
#include "tst_world_isolation.moc"

#endif
