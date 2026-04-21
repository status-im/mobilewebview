#include <QtTest/QtTest>

#include <QPointer>
#include <QQuickWindow>
#include <QSignalSpy>
#include <QVariantMap>

#include "MobileWebView/mobilewebviewbackend.h"
#include "../src/common/mobilewebviewbackend_p.h"
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

    bool initNativeView() override { return true; }

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
    void stopImpl() override { ++stopCalls; }
    void clearHistoryImpl() override { ++clearHistoryCalls; }

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
    int stopCalls = 0;
    int clearHistoryCalls = 0;
    int evaluateCalls = 0;
    int updateGeometryCalls = 0;
    int updateVisibilityCalls = 0;
    int installBridgeCalls = 0;
    int postMessageCalls = 0;
    int setupNativeViewCalls = 0;
    int updateAllowedOriginsCalls = 0;
    int freezeCaptureCalls = 0;
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
    void freezeCancelledBeforeNotifyIgnoresStaleCallback();
    void freezeEmptySnapshotAbortsAndEmits();
    void freezeDoubleSetTrueOnlyCapturesOnce();
    void unfreezeFromFrozenDefersOverlayRemovalAndEmits();
    void lifecycleHooksTriggerNativeCallbacks();
    void bridgeEdgeBranchesAreCovered();
    void navigationDelegateUpdatesStates();
    void parseUserScriptsCoversVariants();
    void escapeJsonForJsEscapesRequiredCharacters();
    void extractOriginFromFrameInfoHandlesNull();
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
    d->notifyFreezeCaptureFinished(d->m_freezeRequestId, img);

    QCOMPARE(d->m_freezeState, FS::Capturing);
    QVERIFY(d->m_snapshotItem != nullptr);
    QTRY_COMPARE(d->m_freezeState, FS::Frozen);
    QCOMPARE(d->freezeCaptureCalls, 1);
    QCOMPARE(backend.freeze(), true);
    QCOMPARE(freezeSpy.count(), 1);
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
    d->notifyFreezeCaptureFinished(rid, img);

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
    d->notifyFreezeCaptureFinished(d->m_freezeRequestId, QImage());

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
    d->notifyFreezeCaptureFinished(d->m_freezeRequestId, img);

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

QTEST_MAIN(MobileWebViewBackendCommonTest)
#include "tst_mobilewebviewbackend_common.moc"

#endif
