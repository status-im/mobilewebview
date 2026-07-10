#include <QtTest/QtTest>

#include <QCoreApplication>
#include <QPointer>
#include <QQuickView>
#include <QQuickWindow>
#include <QSignalSpy>
#include <QVariantMap>

#include "MobileWebView/mobilewebviewbackend.h"
#include "../src/common/mobilewebviewbackend_p.h"
#include "../src/common/snapshotimageprovider.h"
#include "../src/common/snapshotitem.h"
#include "../src/common/userscript_utils.h"
#include "../src/common/webchanneltransport.h"
#include "../src/darwin/navigationdelegate.h"
#include "../src/darwin/origin_utils.h"
#include "../src/darwin/script_utils.h"
#include "../src/darwin/userscripts.h"

#if defined(Q_OS_MACOS) || defined(Q_OS_IOS)

class FakeBackendPrivate final : public MobileWebViewBackendPrivate
{
public:
    explicit FakeBackendPrivate(MobileWebViewBackend *q)
        : MobileWebViewBackendPrivate(q)
    {
    }

    bool initNativeView() override
    {
        ++initNativeViewCalls;
        return true;
    }

    void destroyNativeView() override { ++destroyNativeViewCalls; }

    void loadUrlImpl(const QUrl &url) override
    {
        ++loadUrlCalls;
        lastLoadedUrl = url;
    }

    void loadHtmlImpl(const QString &html, const QUrl &baseUrl) override
    {
        ++loadHtmlCalls;
        lastHtml = html;
        lastHtmlBaseUrl = baseUrl;
    }

    void goBackImpl() override { ++goBackCalls; }
    void goForwardImpl() override { ++goForwardCalls; }
    void goBackOrForwardImpl(int offset) override
    {
        ++goBackOrForwardCalls;
        lastGoBackOrForwardOffset = offset;
    }
    void reloadImpl() override { ++reloadCalls; }
    void reloadAndBypassCacheImpl() override { ++reloadAndBypassCacheCalls; }
    void stopImpl() override { ++stopCalls; }
    void clearHistoryImpl() override { ++clearHistoryCalls; }
    void clearHttpCacheImpl(std::function<void()> completion) override
    {
        ++clearHttpCacheCalls;
        if (completion) {
            completion();
        }
    }
    void deleteAllCookiesImpl(std::function<void()> completion) override
    {
        ++deleteAllCookiesCalls;
        if (completion) {
            completion();
        }
    }
    void clearDomStorageImpl(std::function<void()> completion) override
    {
        ++clearDomStorageCalls;
        if (completion) {
            completion();
        }
    }

    void clearSiteDataImpl(const QString &origin, std::function<void()> completion) override
    {
        ++clearSiteDataCalls;
        lastClearSiteDataOrigin = origin;
        if (completion) {
            completion();
        }
    }

    bool clearSiteDataSupportedImpl() const override { return clearSiteDataSupportedValue; }

    void evaluateJavaScript(const QString &script) override
    {
        ++evaluateCalls;
        lastScript = script;
    }

    void updateNativeGeometry(const QRectF &rect) override
    {
        ++updateGeometryCalls;
        lastGeometry = rect;
    }

    void updateNativeVisibility(bool visible) override
    {
        ++updateVisibilityCalls;
        lastVisible = visible;
    }

    bool installBridgeImpl(const QString &ns, const QStringList &origins,
                           const QString &invokeKey, const QString &webChannelScriptPath) override
    {
        ++installBridgeCalls;
        lastBridgeNs = ns;
        lastBridgeOrigins = origins;
        lastBridgeInvokeKey = invokeKey;
        lastBridgeScriptPath = webChannelScriptPath;
        return installBridgeResult;
    }

    void postMessageToJavaScript(const QString &json) override
    {
        ++postMessageCalls;
        lastPostedJson = json;
    }

    void setupNativeViewImpl() override
    {
        ++setupNativeViewCalls;
        m_nativeViewSetup = true;
    }

    void updateAllowedOriginsImpl(const QStringList &origins) override
    {
        ++updateAllowedOriginsCalls;
        lastAllowedOrigins = origins;
    }

    void updateInteractionEnabled(bool) override {}
    void setZoomFactorImpl(qreal) override {}
    void findTextImpl(const QString &, int) override {}
    void stopFindImpl() override {}
    bool findSupportedImpl() const override { return true; }
    bool hasNativeFindPanelImpl() const override { return false; }
    void showFindPanelImpl() override {}
    void hideFindPanelImpl() override {}

    void captureSnapshotImpl(quint64 requestId) override
    {
        ++freezeCaptureCalls;
        lastFreezeCaptureRequestId = requestId;
    }

    int loadUrlCalls = 0;
    int loadHtmlCalls = 0;
    int goBackCalls = 0;
    int goForwardCalls = 0;
    int goBackOrForwardCalls = 0;
    int reloadCalls = 0;
    int reloadAndBypassCacheCalls = 0;
    int stopCalls = 0;
    int clearHistoryCalls = 0;
    int clearHttpCacheCalls = 0;
    int deleteAllCookiesCalls = 0;
    int clearDomStorageCalls = 0;
    int clearSiteDataCalls = 0;
    bool clearSiteDataSupportedValue = true;
    QString lastClearSiteDataOrigin;
    int evaluateCalls = 0;
    int updateGeometryCalls = 0;
    int updateVisibilityCalls = 0;
    int installBridgeCalls = 0;
    int postMessageCalls = 0;
    int setupNativeViewCalls = 0;
    int updateAllowedOriginsCalls = 0;
    int freezeCaptureCalls = 0;
    int initNativeViewCalls = 0;
    int destroyNativeViewCalls = 0;
    quint64 lastFreezeCaptureRequestId = 0;

    bool lastVisible = false;
    bool installBridgeResult = true;
    int lastGoBackOrForwardOffset = 0;
    QString lastHtml;
    QString lastScript;
    QString lastBridgeNs;
    QString lastBridgeInvokeKey;
    QString lastBridgeScriptPath;
    QString lastPostedJson;
    QStringList lastAllowedOrigins;
    QStringList lastBridgeOrigins;
    QUrl lastLoadedUrl;
    QUrl lastHtmlBaseUrl;
    QRectF lastGeometry;
};

static FakeBackendPrivate *g_lastCreatedPrivate = nullptr;

MobileWebViewBackendPrivate *createPlatformBackend(MobileWebViewBackend *q)
{
    g_lastCreatedPrivate = new FakeBackendPrivate(q);
    return g_lastCreatedPrivate;
}

class MobileWebViewBackendCommonTest : public QObject
{
    Q_OBJECT

private slots:
    void forwardsCallsAndStateChanges();
    void freezeIntentIsSynchronousAndCaptureCompletes();
    void freezeOverlayKeepsCaptureSizeOnResize();
    void freezeOverlayUsesCapturedImageSizeNotCurrentBackend();
    void freezeCancelledBeforeNotifyIgnoresStaleCallback();
    void freezeEmptySnapshotAbortsAndEmits();
    void freezeDoubleSetTrueOnlyCapturesOnce();
    void unfreezeFromFrozenDefersOverlayRemovalAndEmits();
    void lifecycleHooksTriggerNativeCallbacks();
    void bridgeEdgeBranchesAreCovered();
    void navigationDelegateUpdatesStates();
    void updateUrlStateRefreshesAllowedOriginsOnCrossOriginNavigation();
    void updateUrlStateKeepsAllowedOriginsOnSameOriginNavigation();
    void updateUrlStateAtNavigationStartRefreshesOriginsBeforeFinish();
    void parseUserScriptsCoversVariants();
    void escapeJsonForJsEscapesRequiredCharacters();
    void extractOriginFromFrameInfoHandlesNull();
    void requestSnapshotCompletesAndRegistersProviderImage();
    void requestSnapshotNullImageFails();
    void requestSnapshotScalesToTargetSize();
    void requestSnapshotScalesLogicalSizeByWindowDevicePixelRatio();
    void freezeAndRequestSnapshotAreIndependent();
    void offTheRecordChangeRecreatesNativeViewAndReloadsCurrentUrl();
    void loadUrlContentSurvivesStoreRecreate();
    void loadHtmlContentSurvivesStoreRecreate();
    void offTheRecordSameValueDoesNotRecreateNativeView();
    void storageNameChangeRecreatesNativeViewInStandardMode();
    void storageNameChangeIgnoredInIncognitoMode();
    void recreateReinstallsBridgeAndPreservesUserScripts();
};

void MobileWebViewBackendCommonTest::forwardsCallsAndStateChanges()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    QVERIFY(g_lastCreatedPrivate != nullptr);

    auto *d = g_lastCreatedPrivate;
    QSignalSpy urlSpy(&backend, &MobileWebViewBackend::urlChanged);
    QSignalSpy loadingSpy(&backend, &MobileWebViewBackend::loadingChanged);
    QSignalSpy loadedSpy(&backend, &MobileWebViewBackend::loadedChanged);
    QSignalSpy userScriptsSpy(&backend, &MobileWebViewBackend::userScriptsChanged);
    QSignalSpy nsSpy(&backend, &MobileWebViewBackend::webChannelNamespaceChanged);
    QSignalSpy webChannelSpy(&backend, &MobileWebViewBackend::webChannelChanged);
    QSignalSpy historyItemsSpy(&backend, &MobileWebViewBackend::historyItemsChanged);
    QSignalSpy historyIndexSpy(&backend, &MobileWebViewBackend::currentHistoryIndexChanged);

    backend.setUrl(QUrl(QStringLiteral("https://example.com/path")));
    QCOMPARE(backend.url().toString(), QStringLiteral("https://example.com/path"));
    QCOMPARE(urlSpy.count(), 1);
    QCOMPARE(d->loadUrlCalls, 1);
    QCOMPARE(d->lastLoadedUrl.toString(), QStringLiteral("https://example.com/path"));
    QCOMPARE(d->lastAllowedOrigins, QStringList{QStringLiteral("https://example.com")});
    QCOMPARE(d->updateAllowedOriginsCalls, 1);
    QCOMPARE(d->installBridgeCalls, 1);

    backend.loadHtml(QStringLiteral("<html/>"), QUrl(QStringLiteral("https://base.example")));
    QCOMPARE(d->loadHtmlCalls, 1);
    QCOMPARE(d->lastHtml, QStringLiteral("<html/>"));
    QCOMPARE(d->lastHtmlBaseUrl.toString(), QStringLiteral("https://base.example"));

    backend.goBack();
    backend.goForward();
    backend.goBackOrForward(-2);
    backend.reload();
    backend.stop();
    backend.clearHistory();
    QCOMPARE(d->goBackCalls, 1);
    QCOMPARE(d->goForwardCalls, 1);
    QCOMPARE(d->goBackOrForwardCalls, 1);
    QCOMPARE(d->lastGoBackOrForwardOffset, -2);
    QCOMPARE(d->reloadCalls, 1);
    QCOMPARE(d->stopCalls, 1);
    QCOMPARE(d->clearHistoryCalls, 1);

    d->m_nativeViewSetup = true;
    QSignalSpy clearingSpy(&backend, &MobileWebViewBackend::clearingChanged);
    QSignalSpy clearHttpCacheCompletedSpy(&backend, &MobileWebViewBackend::clearHttpCacheCompleted);
    QSignalSpy deleteAllCookiesCompletedSpy(&backend, &MobileWebViewBackend::deleteAllCookiesCompleted);
    QSignalSpy clearDomStorageCompletedSpy(&backend, &MobileWebViewBackend::clearDomStorageCompleted);
    QSignalSpy clearSiteDataCompletedSpy(&backend, &MobileWebViewBackend::clearSiteDataCompleted);
    QSignalSpy clearProfileDataCompletedSpy(&backend, &MobileWebViewBackend::clearProfileDataCompleted);

    backend.clearHttpCache();
    QCOMPARE(clearHttpCacheCompletedSpy.count(), 1);
    QCOMPARE(backend.clearing(), false);

    backend.deleteAllCookies();
    QCOMPARE(deleteAllCookiesCompletedSpy.count(), 1);
    QCOMPARE(backend.clearing(), false);

    backend.clearDomStorage();
    QCOMPARE(clearDomStorageCompletedSpy.count(), 1);
    QCOMPARE(backend.clearing(), false);

    backend.clearSiteData();
    QCOMPARE(clearSiteDataCompletedSpy.count(), 1);
    QCOMPARE(backend.clearing(), false);

    backend.clearProfileData();
    QCOMPARE(clearProfileDataCompletedSpy.count(), 1);
    QCOMPARE(backend.clearing(), false);

    backend.reloadAndBypassCache();
    QCOMPARE(d->clearHttpCacheCalls, 2); // once + clearProfileData
    QCOMPARE(d->deleteAllCookiesCalls, 2); // once + clearProfileData
    QCOMPARE(d->clearDomStorageCalls, 2); // once + clearProfileData
    QCOMPARE(d->clearSiteDataCalls, 1);
    QCOMPARE(d->lastClearSiteDataOrigin, QStringLiteral("https://example.com"));
    // clearSiteData auto-reloads, plus the explicit reloadAndBypassCache call
    QCOMPARE(d->reloadAndBypassCacheCalls, 2);

    QVariantList historyItems{
        QVariantMap{
            {QStringLiteral("url"), QStringLiteral("https://example.com/1")},
            {QStringLiteral("title"), QStringLiteral("Page 1")}
        },
        QVariantMap{
            {QStringLiteral("url"), QStringLiteral("https://example.com/2")},
            {QStringLiteral("title"), QStringLiteral("Page 2")}
        }
    };
    backend.setHistoryState(historyItems, 1);
    QCOMPARE(historyItemsSpy.count(), 1);
    QCOMPARE(historyIndexSpy.count(), 1);
    QCOMPARE(backend.historyItems(), historyItems);
    QCOMPARE(backend.currentHistoryIndex(), 1);

    backend.runJavaScript(QStringLiteral("1 + 1"));
    QCOMPARE(d->evaluateCalls, 1);
    QCOMPARE(d->lastScript, QStringLiteral("1 + 1"));

    backend.setLoadingState(true);
    backend.setLoadedState(true);
    QCOMPARE(loadingSpy.count(), 1);
    QCOMPARE(loadedSpy.count(), 1);
    QCOMPARE(backend.loading(), true);
    QCOMPARE(backend.loaded(), true);

    backend.setUserScripts(QVariantList{QStringLiteral(":/script1.js")});
    QCOMPARE(userScriptsSpy.count(), 1);
    QCOMPARE(backend.userScripts().size(), 1);

    backend.setWebChannelNamespace(QStringLiteral("custom"));
    QCOMPARE(nsSpy.count(), 1);
    QCOMPARE(backend.webChannelNamespace(), QStringLiteral("custom"));

    const bool bridgeInstalled = backend.installMessageBridge(
        QStringLiteral("bridgeNs"),
        {QStringLiteral("https://allowed.example")},
        QStringLiteral("invoke"),
        QStringLiteral(":/qwebchannel.js"));
    QVERIFY(bridgeInstalled);
    QCOMPARE(d->lastBridgeNs, QStringLiteral("bridgeNs"));
    QCOMPARE(d->lastBridgeOrigins, QStringList{QStringLiteral("https://allowed.example")});
    QCOMPARE(d->lastBridgeInvokeKey, QStringLiteral("invoke"));
    QCOMPARE(d->lastBridgeScriptPath, QStringLiteral(":/qwebchannel.js"));

    QWebChannel channel;
    backend.setWebChannel(&channel);
    QCOMPARE(backend.webChannel(), &channel);
    QCOMPARE(webChannelSpy.count(), 1);
    backend.setWebChannel(&channel); // no-op branch
    QCOMPARE(webChannelSpy.count(), 1);
    QVERIFY(d->m_transport != nullptr);

    d->m_transport->sendMessage(QJsonObject{{QStringLiteral("ping"), 1}});
    QCOMPARE(d->postMessageCalls, 1);
    QCOMPARE(d->lastPostedJson, QStringLiteral("{\"ping\":1}"));
}

void MobileWebViewBackendCommonTest::freezeIntentIsSynchronousAndCaptureCompletes()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    auto *d = g_lastCreatedPrivate;
    QVERIFY(d != nullptr);

    using FS = MobileWebViewBackendPrivate::FreezeState;
    QSignalSpy freezeSpy(&backend, &MobileWebViewBackend::freezeChanged);

    backend.setFreeze(true);
    QCOMPARE(freezeSpy.count(), 1);
    QCOMPARE(backend.freeze(), true);
    QCOMPARE(d->m_freezeState, FS::Capturing);
    QCOMPARE(d->freezeCaptureCalls, 1);
    QCOMPARE(d->lastFreezeCaptureRequestId, d->m_freezeRequestId);

    QImage img(2, 2, QImage::Format_ARGB32);
    img.fill(QColor(Qt::red));
    d->notifySnapshotReady(d->m_freezeRequestId, img);

    QCOMPARE(d->m_freezeState, FS::Capturing);
    QVERIFY(d->m_snapshotItem != nullptr);
    QTRY_COMPARE(d->m_freezeState, FS::Frozen);
    QCOMPARE(d->freezeCaptureCalls, 1);
    QCOMPARE(backend.freeze(), true);
    QCOMPARE(freezeSpy.count(), 1);
}

void MobileWebViewBackendCommonTest::freezeOverlayKeepsCaptureSizeOnResize()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    auto *d = g_lastCreatedPrivate;
    QVERIFY(d != nullptr);

    backend.setWidth(200);
    backend.setHeight(100);
    backend.setFreeze(true);

    QImage img(200, 100, QImage::Format_ARGB32);
    img.fill(QColor(Qt::red));
    d->notifySnapshotReady(d->m_freezeRequestId, img);

    QVERIFY(d->m_snapshotItem != nullptr);
    QCOMPARE(d->m_snapshotItem->width(), 200.0);
    QCOMPARE(d->m_snapshotItem->height(), 100.0);

    backend.setWidth(100);
    backend.setHeight(50);
    QCOMPARE(d->m_snapshotItem->width(), 200.0);
    QCOMPARE(d->m_snapshotItem->height(), 100.0);

    backend.setWidth(400);
    backend.setHeight(200);
    QCOMPARE(d->m_snapshotItem->width(), 200.0);
    QCOMPARE(d->m_snapshotItem->height(), 100.0);
}

void MobileWebViewBackendCommonTest::freezeOverlayUsesCapturedImageSizeNotCurrentBackend()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    auto *d = g_lastCreatedPrivate;
    QVERIFY(d != nullptr);

    backend.setWidth(200);
    backend.setHeight(100);
    backend.setFreeze(true);
    const quint64 rid = d->m_freezeRequestId;

    // Simulate a resize while async snapshot capture is still in flight.
    backend.setWidth(50);
    backend.setHeight(25);

    QImage img(200, 100, QImage::Format_ARGB32);
    img.fill(QColor(Qt::red));
    d->notifySnapshotReady(rid, img);

    QVERIFY(d->m_snapshotItem != nullptr);
    QCOMPARE(d->m_snapshotItem->width(), 200.0);
    QCOMPARE(d->m_snapshotItem->height(), 100.0);
}

void MobileWebViewBackendCommonTest::freezeCancelledBeforeNotifyIgnoresStaleCallback()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    auto *d = g_lastCreatedPrivate;
    QVERIFY(d != nullptr);

    using FS = MobileWebViewBackendPrivate::FreezeState;
    QSignalSpy freezeSpy(&backend, &MobileWebViewBackend::freezeChanged);

    backend.setFreeze(true);
    const quint64 rid = d->m_freezeRequestId;
    QCOMPARE(d->m_freezeState, FS::Capturing);

    backend.setFreeze(false);
    QCOMPARE(d->m_freezeState, FS::Idle);
    QCOMPARE(freezeSpy.count(), 2);

    QImage img(1, 1, QImage::Format_ARGB32);
    img.fill(Qt::blue);
    d->notifySnapshotReady(rid, img);

    QCOMPARE(d->m_freezeState, FS::Idle);
    QVERIFY(d->m_snapshotItem == nullptr);
}

void MobileWebViewBackendCommonTest::freezeEmptySnapshotAbortsAndEmits()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    auto *d = g_lastCreatedPrivate;
    QVERIFY(d != nullptr);

    using FS = MobileWebViewBackendPrivate::FreezeState;
    QSignalSpy freezeSpy(&backend, &MobileWebViewBackend::freezeChanged);

    backend.setFreeze(true);
    QCOMPARE(freezeSpy.count(), 1);

    QTest::ignoreMessage(QtWarningMsg, "MobileWebViewBackend: freeze snapshot failed or empty");
    d->notifySnapshotReady(d->m_freezeRequestId, QImage());

    QCOMPARE(d->m_freezeState, FS::Idle);
    QCOMPARE(backend.freeze(), false);
    QCOMPARE(freezeSpy.count(), 2);
}

void MobileWebViewBackendCommonTest::freezeDoubleSetTrueOnlyCapturesOnce()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    auto *d = g_lastCreatedPrivate;
    QVERIFY(d != nullptr);

    backend.setFreeze(true);
    backend.setFreeze(true);
    QCOMPARE(d->freezeCaptureCalls, 1);
}

void MobileWebViewBackendCommonTest::unfreezeFromFrozenDefersOverlayRemovalAndEmits()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    auto *d = g_lastCreatedPrivate;
    QVERIFY(d != nullptr);

    using FS = MobileWebViewBackendPrivate::FreezeState;
    QSignalSpy freezeSpy(&backend, &MobileWebViewBackend::freezeChanged);

    backend.setFreeze(true);
    QCOMPARE(freezeSpy.count(), 1);

    QImage img(2, 2, QImage::Format_ARGB32);
    img.fill(QColor(Qt::red));
    d->notifySnapshotReady(d->m_freezeRequestId, img);

    QVERIFY(d->m_snapshotItem != nullptr);
    QTRY_COMPARE(d->m_freezeState, FS::Frozen);

    QPointer<QQuickItem> overlay(d->m_snapshotItem);
    backend.setFreeze(false);

    QCOMPARE(d->m_freezeState, FS::Idle);
    QCOMPARE(d->m_snapshotItem, nullptr);
    QCOMPARE(backend.freeze(), false);
    QCOMPARE(freezeSpy.count(), 2);

    QTRY_VERIFY(overlay.isNull());
}

void MobileWebViewBackendCommonTest::lifecycleHooksTriggerNativeCallbacks()
{
    g_lastCreatedPrivate = nullptr;
    QQuickWindow window;
    window.setGeometry(0, 0, 320, 240);

    MobileWebViewBackend backend;
    QVERIFY(g_lastCreatedPrivate != nullptr);
    auto *d = g_lastCreatedPrivate;

    backend.setParentItem(window.contentItem());
    backend.setWidth(200);
    backend.setHeight(120);
    backend.setVisible(true);

    window.show();
    QCoreApplication::processEvents();

    QVERIFY(d->setupNativeViewCalls >= 1);
    QVERIFY(d->updateGeometryCalls >= 1);

    const int visibilityCallsBefore = d->updateVisibilityCalls;
    backend.setVisible(false);
    backend.setVisible(true);
    QCoreApplication::processEvents();
    QVERIFY(d->updateVisibilityCalls >= visibilityCallsBefore);
}

void MobileWebViewBackendCommonTest::bridgeEdgeBranchesAreCovered()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    QVERIFY(g_lastCreatedPrivate != nullptr);
    auto *d = g_lastCreatedPrivate;

    // setupTransport + ensureBridgeInstalled path when channel is set before URL.
    QWebChannel channel;
    backend.setWebChannel(&channel);
    QVERIFY(d->m_transport != nullptr);
    QCOMPARE(d->installBridgeCalls, 1);

    // Public loadUrl() method path.
    backend.loadUrl(QUrl(QStringLiteral("https://public-api.example/path")));
    QCOMPARE(d->loadUrlCalls, 1);

    // updateAllowedOrigins path with existing transport.
    const int originsCallsBefore = d->updateAllowedOriginsCalls;
    backend.updateAllowedOrigins({QStringLiteral("https://allowed.example")});
    QCOMPARE(d->updateAllowedOriginsCalls, originsCallsBefore + 1);

    // Failure branch in ensureBridgeInstalled().
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backendFail;
    QVERIFY(g_lastCreatedPrivate != nullptr);
    auto *dFail = g_lastCreatedPrivate;
    dFail->installBridgeResult = false;
    backendFail.loadHtml(QStringLiteral("<html/>"), QUrl());
    QCOMPARE(dFail->installBridgeCalls, 1);
    QVERIFY(!dFail->m_bridgeInstalled);
}

void MobileWebViewBackendCommonTest::navigationDelegateUpdatesStates()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    QVERIFY(g_lastCreatedPrivate != nullptr);

    NavigationDelegate *delegate = [[NavigationDelegate alloc] init];
    delegate.owner = &backend;
    WKWebView *webView = [[WKWebView alloc] initWithFrame:CGRectZero];
    NSError *navError = [NSError errorWithDomain:@"test" code:1 userInfo:nil];

    [delegate webView:webView didStartProvisionalNavigation:nil];
    QCOMPARE(backend.loading(), true);
    QCOMPARE(backend.loaded(), false);

    [delegate webView:webView didFinishNavigation:nil];
    QCOMPARE(backend.loading(), false);
    QCOMPARE(backend.loaded(), true);

    [delegate webView:webView didFailNavigation:nil withError:navError];
    QCOMPARE(backend.loading(), false);
    QCOMPARE(backend.loaded(), false);

    [delegate webView:webView didFailProvisionalNavigation:nil withError:navError];
    QCOMPARE(backend.loading(), false);
    QCOMPARE(backend.loaded(), false);

    [webView release];
    [delegate release];
}

void MobileWebViewBackendCommonTest::updateUrlStateRefreshesAllowedOriginsOnCrossOriginNavigation()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    QVERIFY(g_lastCreatedPrivate != nullptr);

    auto *d = g_lastCreatedPrivate;
    QSignalSpy urlSpy(&backend, &MobileWebViewBackend::urlChanged);

    backend.setUrl(QUrl(QStringLiteral("https://a.example/path")));
    const int originsCallsBefore = d->updateAllowedOriginsCalls;
    const QStringList originsBefore = d->lastAllowedOrigins;
    urlSpy.clear();

    backend.updateUrlState(QUrl(QStringLiteral("https://b.example/page")));

    QCOMPARE(backend.url().toString(), QStringLiteral("https://b.example/page"));
    QCOMPARE(urlSpy.count(), 1);
    QCOMPARE(d->updateAllowedOriginsCalls, originsCallsBefore + 1);
    const QStringList expectedOrigins{
        QStringLiteral("https://a.example"), QStringLiteral("https://b.example")};
    QCOMPARE(d->lastAllowedOrigins, expectedOrigins);
    QVERIFY(originsBefore != d->lastAllowedOrigins);
}

void MobileWebViewBackendCommonTest::updateUrlStateAtNavigationStartRefreshesOriginsBeforeFinish()
{
    // Android onPageStarted calls updateUrlState before bridge scripts / QWebChannel connect.
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    QVERIFY(g_lastCreatedPrivate != nullptr);

    auto *d = g_lastCreatedPrivate;
    backend.setUrl(QUrl(QStringLiteral("https://duckduckgo.com/?q=opensea")));

    backend.updateUrlState(QUrl(QStringLiteral("https://opensea.io/")));

    const QStringList expectedOrigins{
        QStringLiteral("https://duckduckgo.com"), QStringLiteral("https://opensea.io")};
    QCOMPARE(d->lastAllowedOrigins, expectedOrigins);
    QCOMPARE(backend.url().toString(), QStringLiteral("https://opensea.io/"));
}

void MobileWebViewBackendCommonTest::updateUrlStateKeepsAllowedOriginsOnSameOriginNavigation()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    QVERIFY(g_lastCreatedPrivate != nullptr);

    auto *d = g_lastCreatedPrivate;

    backend.setUrl(QUrl(QStringLiteral("https://a.example/path")));
    const int originsCallsAfterSetUrl = d->updateAllowedOriginsCalls;
    const QStringList originsAfterSetUrl = d->lastAllowedOrigins;

    backend.updateUrlState(QUrl(QStringLiteral("https://a.example/other")));
    QCOMPARE(d->updateAllowedOriginsCalls, originsCallsAfterSetUrl);
    QCOMPARE(d->lastAllowedOrigins, originsAfterSetUrl);
    QCOMPARE(backend.url().toString(), QStringLiteral("https://a.example/other"));

    const int originsCallsBeforeRepeat = d->updateAllowedOriginsCalls;
    backend.updateUrlState(QUrl(QStringLiteral("https://a.example/other")));
    QCOMPARE(d->updateAllowedOriginsCalls, originsCallsBeforeRepeat);
}

void MobileWebViewBackendCommonTest::parseUserScriptsCoversVariants()
{
    QVariantMap mapScript;
    mapScript.insert(QStringLiteral("path"), QUrl(QStringLiteral("qrc:/CustomWebView/js/bootstrap_page.js")));
    mapScript.insert(QStringLiteral("runOnSubFrames"), true);

    QVariantMap emptyPath;
    emptyPath.insert(QStringLiteral("path"), QString());

    QVariantList scripts{
        mapScript,
        QStringLiteral(":/CustomWebView/js/bootstrap_bridge.js"),
        emptyPath
    };

    const QList<UserScriptInfo> parsed = parseUserScripts(scripts);
    QCOMPARE(parsed.size(), 2);
    QCOMPARE(parsed[0].path, QStringLiteral(":/CustomWebView/js/bootstrap_page.js"));
    QCOMPARE(parsed[0].runOnSubFrames, true);
    QCOMPARE(parsed[1].path, QStringLiteral(":/CustomWebView/js/bootstrap_bridge.js"));
    QCOMPARE(parsed[1].runOnSubFrames, false);
}

void MobileWebViewBackendCommonTest::escapeJsonForJsEscapesRequiredCharacters()
{
    const QString input = QStringLiteral("{\"k\":\"line1\\nline2\\rquote'\\\\\"}");
    const QString escaped = escapeJsonForJs(input);

    QVERIFY(escaped.contains(QStringLiteral("\\\\n")));
    QVERIFY(escaped.contains(QStringLiteral("\\\\r")));
    QVERIFY(escaped.contains(QStringLiteral("\\'")));
    QVERIFY(escaped.contains(QStringLiteral("\\\\\\\\")));
}

void MobileWebViewBackendCommonTest::extractOriginFromFrameInfoHandlesNull()
{
    NSString *origin = extractOriginFromFrameInfo(nil);
    QVERIFY([origin isEqualToString:@""]);
}

void MobileWebViewBackendCommonTest::requestSnapshotCompletesAndRegistersProviderImage()
{
    g_lastCreatedPrivate = nullptr;
    QQuickView view;
    view.resize(320, 240);

    MobileWebViewBackend backend;
    backend.setParentItem(view.contentItem());
    view.show();
    QCoreApplication::processEvents();

    auto *d = g_lastCreatedPrivate;
    QVERIFY(d != nullptr);

    QSignalSpy snapshotSpy(&backend, &MobileWebViewBackend::snapshotReady);

    backend.requestSnapshot(QSize());
    QCOMPARE(d->freezeCaptureCalls, 1);
    const quint64 rid = d->lastFreezeCaptureRequestId;

    QImage img(3, 3, QImage::Format_ARGB32);
    img.fill(QColor(Qt::green));
    d->notifySnapshotReady(rid, img);

    QCOMPARE(snapshotSpy.count(), 1);
    const QList<QVariant> args = snapshotSpy.takeFirst();
    QVERIFY(args.at(1).toBool());
    QString key = args.at(0).toUrl().path();
    if (key.startsWith(QLatin1Char('/'))) {
        key = key.mid(1);
    }
    QVERIFY(!key.isEmpty());

    auto *provider = static_cast<QQuickImageProvider *>(
        view.engine()->imageProvider(QStringLiteral("mobilewebview-snapshot")));
    QVERIFY(provider != nullptr);
    QSize sz;
    const QImage got = provider->requestImage(key, &sz, QSize());
    QCOMPARE(got.size(), img.size());
}

void MobileWebViewBackendCommonTest::requestSnapshotNullImageFails()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    auto *d = g_lastCreatedPrivate;
    QVERIFY(d != nullptr);

    QSignalSpy snapshotSpy(&backend, &MobileWebViewBackend::snapshotReady);
    backend.requestSnapshot(QSize());
    d->notifySnapshotReady(d->lastFreezeCaptureRequestId, QImage());

    QCOMPARE(snapshotSpy.count(), 1);
    QVERIFY(!snapshotSpy.at(0).at(1).toBool());
    QVERIFY(snapshotSpy.at(0).at(0).toUrl().isEmpty());
    QVERIFY(!d->m_publicSnapshotPending);
}

void MobileWebViewBackendCommonTest::requestSnapshotScalesToTargetSize()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    auto *d = g_lastCreatedPrivate;
    QVERIFY(d != nullptr);

    // No QQuickWindow: DPR is 1.0, so logical 10x10 => 10px output.
    QSignalSpy snapshotSpy(&backend, &MobileWebViewBackend::snapshotReady);
    backend.requestSnapshot(QSize(10, 10));

    QImage img(40, 30, QImage::Format_ARGB32);
    img.fill(QColor(Qt::red));
    d->notifySnapshotReady(d->lastFreezeCaptureRequestId, img);

    QCOMPARE(snapshotSpy.count(), 1);
    QString key = snapshotSpy.at(0).at(0).toUrl().path();
    if (key.startsWith(QLatin1Char('/'))) {
        key = key.mid(1);
    }

    MobileWebViewSnapshotImageProvider provider;
    const QImage got = provider.requestImage(key, nullptr, QSize());
    QCOMPARE(got.width(), 10);
    // 40x30 scaled to width 10 (KeepAspectRatio) -> height 7 or 8 depending on rounding
    QVERIFY(got.height() >= 7 && got.height() <= 8);
}

void MobileWebViewBackendCommonTest::requestSnapshotScalesLogicalSizeByWindowDevicePixelRatio()
{
    g_lastCreatedPrivate = nullptr;
    QQuickView view;
    view.resize(320, 240);
    MobileWebViewBackend backend;
    backend.setParentItem(view.contentItem());
    view.show();
    QCoreApplication::processEvents();

    auto *d = g_lastCreatedPrivate;
    QVERIFY(d != nullptr);

    QSignalSpy snapshotSpy(&backend, &MobileWebViewBackend::snapshotReady);
    backend.requestSnapshot(QSize(10, 10));
    const qreal dpr = d->m_publicSnapshotDpr;
    const int expectW = qRound(10 * dpr);

    QImage img(400, 300, QImage::Format_ARGB32);
    img.fill(QColor(Qt::red));
    d->notifySnapshotReady(d->lastFreezeCaptureRequestId, img);

    QCOMPARE(snapshotSpy.count(), 1);
    QString key = snapshotSpy.at(0).at(0).toUrl().path();
    if (key.startsWith(QLatin1Char('/'))) {
        key = key.mid(1);
    }

    MobileWebViewSnapshotImageProvider provider;
    const QImage got = provider.requestImage(key, nullptr, QSize());
    QCOMPARE(got.width(), expectW);
    const int expectTarget = qRound(10 * dpr);
    const QSize expected = img.size().scaled(QSize(expectTarget, expectTarget), Qt::KeepAspectRatio);
    QCOMPARE(got.size(), expected);
}

void MobileWebViewBackendCommonTest::freezeAndRequestSnapshotAreIndependent()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    auto *d = g_lastCreatedPrivate;
    QVERIFY(d != nullptr);

    using FS = MobileWebViewBackendPrivate::FreezeState;
    QSignalSpy snapshotSpy(&backend, &MobileWebViewBackend::snapshotReady);

    backend.setFreeze(true);
    QCOMPARE(d->m_freezeRequestId, quint64(1));
    backend.requestSnapshot(QSize());
    QCOMPARE(d->lastFreezeCaptureRequestId, quint64(2));
    QCOMPARE(d->freezeCaptureCalls, 2);

    QImage snapImg(3, 3, QImage::Format_ARGB32);
    snapImg.fill(QColor(Qt::blue));
    d->notifySnapshotReady(2, snapImg);

    QCOMPARE(snapshotSpy.count(), 1);
    QCOMPARE(d->m_freezeState, FS::Capturing);

    QImage freezeImg(2, 2, QImage::Format_ARGB32);
    freezeImg.fill(QColor(Qt::red));
    d->notifySnapshotReady(1, freezeImg);

    QVERIFY(d->m_snapshotItem != nullptr);
    QTRY_COMPARE(d->m_freezeState, FS::Frozen);
    QCOMPARE(snapshotSpy.count(), 1);
}

void MobileWebViewBackendCommonTest::offTheRecordChangeRecreatesNativeViewAndReloadsCurrentUrl()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    QVERIFY(g_lastCreatedPrivate != nullptr);
    auto *d = g_lastCreatedPrivate;

    const QUrl pageUrl(QStringLiteral("https://example.com/page"));
    backend.setUrl(pageUrl);
    d->m_nativeViewSetup = true;

    const int loadUrlBefore = d->loadUrlCalls;
    const int initBefore = d->initNativeViewCalls;
    const int destroyBefore = d->destroyNativeViewCalls;

    backend.setOffTheRecord(true);

    QCOMPARE(backend.offTheRecord(), true);
    QCOMPARE(d->destroyNativeViewCalls, destroyBefore + 1);
    QCOMPARE(d->initNativeViewCalls, initBefore + 1);
    QCOMPARE(d->loadUrlCalls, loadUrlBefore + 1);
    QCOMPARE(d->lastLoadedUrl, pageUrl);
}

void MobileWebViewBackendCommonTest::loadUrlContentSurvivesStoreRecreate()
{
    // A page loaded via the imperative loadUrl() must be reloaded after an internal
    // store recreate, exactly like one set via setUrl(). Currently loadUrl() never
    // records the URL for replay, so the page is lost on store switch.
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    QVERIFY(g_lastCreatedPrivate != nullptr);
    auto *d = g_lastCreatedPrivate;

    const QUrl pageUrl(QStringLiteral("https://example.com/page"));
    backend.loadUrl(pageUrl);
    d->m_nativeViewSetup = true;

    const int loadUrlBefore = d->loadUrlCalls;

    backend.setOffTheRecord(true);

    QCOMPARE(d->loadUrlCalls, loadUrlBefore + 1);
    QCOMPARE(d->lastLoadedUrl, pageUrl);
}

void MobileWebViewBackendCommonTest::loadHtmlContentSurvivesStoreRecreate()
{
    // Contrast to loadUrlContentSurvivesStoreRecreate: loadHtml() content is replayed
    // after a store recreate because m_lastHtml is preserved.
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    QVERIFY(g_lastCreatedPrivate != nullptr);
    auto *d = g_lastCreatedPrivate;

    backend.loadHtml(QStringLiteral("<html/>"), QUrl(QStringLiteral("https://example.com/")));
    d->m_nativeViewSetup = true;

    const int loadHtmlBefore = d->loadHtmlCalls;

    backend.setOffTheRecord(true);

    QCOMPARE(d->loadHtmlCalls, loadHtmlBefore + 1);
    QCOMPARE(d->lastHtml, QStringLiteral("<html/>"));
}

void MobileWebViewBackendCommonTest::offTheRecordSameValueDoesNotRecreateNativeView()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    QVERIFY(g_lastCreatedPrivate != nullptr);
    auto *d = g_lastCreatedPrivate;

    backend.setUrl(QUrl(QStringLiteral("https://example.com/page")));
    d->m_nativeViewSetup = true;

    const int destroyBefore = d->destroyNativeViewCalls;
    const int initBefore = d->initNativeViewCalls;
    const int loadUrlBefore = d->loadUrlCalls;

    backend.setOffTheRecord(false);

    QCOMPARE(d->destroyNativeViewCalls, destroyBefore);
    QCOMPARE(d->initNativeViewCalls, initBefore);
    QCOMPARE(d->loadUrlCalls, loadUrlBefore);
}

void MobileWebViewBackendCommonTest::storageNameChangeRecreatesNativeViewInStandardMode()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    QVERIFY(g_lastCreatedPrivate != nullptr);
    auto *d = g_lastCreatedPrivate;

    const QUrl pageUrl(QStringLiteral("https://example.com/page"));
    backend.setUrl(pageUrl);
    backend.setStorageName(QStringLiteral("Profile_A"));
    d->m_nativeViewSetup = true;

    const int destroyBefore = d->destroyNativeViewCalls;
    const int initBefore = d->initNativeViewCalls;
    const int loadUrlBefore = d->loadUrlCalls;

    backend.setStorageName(QStringLiteral("Profile_B"));

    QCOMPARE(backend.storageName(), QStringLiteral("Profile_B"));
    QCOMPARE(d->destroyNativeViewCalls, destroyBefore + 1);
    QCOMPARE(d->initNativeViewCalls, initBefore + 1);
    QCOMPARE(d->loadUrlCalls, loadUrlBefore + 1);
    QCOMPARE(d->lastLoadedUrl, pageUrl);
}

void MobileWebViewBackendCommonTest::storageNameChangeIgnoredInIncognitoMode()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    QVERIFY(g_lastCreatedPrivate != nullptr);
    auto *d = g_lastCreatedPrivate;

    backend.setUrl(QUrl(QStringLiteral("https://example.com/page")));
    backend.setOffTheRecord(true);
    backend.setStorageName(QStringLiteral("Profile_A"));
    d->m_nativeViewSetup = true;

    const int destroyBefore = d->destroyNativeViewCalls;
    const int initBefore = d->initNativeViewCalls;
    const int loadUrlBefore = d->loadUrlCalls;

    backend.setStorageName(QStringLiteral("Profile_B"));

    QCOMPARE(backend.storageName(), QStringLiteral("Profile_B"));
    QCOMPARE(d->destroyNativeViewCalls, destroyBefore);
    QCOMPARE(d->initNativeViewCalls, initBefore);
    QCOMPARE(d->loadUrlCalls, loadUrlBefore);
}

void MobileWebViewBackendCommonTest::recreateReinstallsBridgeAndPreservesUserScripts()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    QVERIFY(g_lastCreatedPrivate != nullptr);
    auto *d = g_lastCreatedPrivate;

    const QVariantList scripts{QVariantMap{
        {QStringLiteral("path"), QStringLiteral(":/script1.js")}}};
    backend.setUserScripts(scripts);
    backend.setUrl(QUrl(QStringLiteral("https://example.com/page")));
    d->m_nativeViewSetup = true;

    const int bridgeCallsBefore = d->installBridgeCalls;
    QVERIFY(d->m_bridgeInstalled);

    backend.setOffTheRecord(true);

    QCOMPARE(backend.userScripts(), scripts);
    QCOMPARE(d->installBridgeCalls, bridgeCallsBefore + 1);
    QVERIFY(d->m_bridgeInstalled);
}

QTEST_MAIN(MobileWebViewBackendCommonTest)
#include "tst_mobilewebviewbackend_common.moc"

#endif
