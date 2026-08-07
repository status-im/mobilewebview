#include <QtTest/QtTest>

#include <QCoreApplication>
#include <QFile>
#include <QFileInfo>
#include <QPointer>
#include <QQuickView>
#include <QQuickWindow>
#include <QSignalSpy>
#include <QTemporaryDir>
#include <QVariantMap>

#include "MobileWebView/mobilewebviewbackend.h"
#include "MobileWebView/mobilewebviewcapabilities.h"
#include "MobileWebView/mobilewebviewdownload.h"
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

    void loadFileUrlImpl(const QUrl &fileUrl, const QUrl &readAccessDirUrl) override
    {
        ++loadFileUrlCalls;
        lastLoadedFileUrl = fileUrl;
        lastFileReadAccessUrl = readAccessDirUrl;
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

    bool inPageMediaPlaybackSupportedImpl() const override
    {
        return inPageMediaPlaybackSupportedValue;
    }

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

    // updateInteractionEnabled / setZoomFactorImpl / find-in-page methods:
    // base-class defaults (no-ops) — not probed by these tests.
    void setHttpUserAgentImpl(const QString &userAgent) override
    {
        ++setHttpUserAgentCalls;
        lastHttpUserAgent = userAgent;
    }

    void captureSnapshotImpl(quint64 requestId) override
    {
        ++freezeCaptureCalls;
        lastFreezeCaptureRequestId = requestId;
    }

    void startDownloadImpl(quint64 downloadId, const QUrl &url,
                           const QString &destinationPath) override
    {
        ++startDownloadCalls;
        lastStartDownloadId = downloadId;
        lastStartDownloadUrl = url;
        lastStartDownloadDestination = destinationPath;
    }

    void cancelDownloadImpl(quint64 downloadId) override
    {
        ++cancelDownloadCalls;
        lastCancelDownloadId = downloadId;
    }

    void pauseDownloadImpl(quint64 downloadId) override
    {
        if (!pauseSupported) {
            MobileWebViewBackendPrivate::pauseDownloadImpl(downloadId);
            return;
        }
        ++pauseDownloadCalls;
        lastPauseDownloadId = downloadId;
    }

    void resumeDownloadImpl(quint64 downloadId) override
    {
        if (!pauseSupported) {
            MobileWebViewBackendPrivate::resumeDownloadImpl(downloadId);
            return;
        }
        ++resumeDownloadCalls;
        lastResumeDownloadId = downloadId;
    }

    int loadUrlCalls = 0;
    int loadFileUrlCalls = 0;
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
    bool inPageMediaPlaybackSupportedValue = true;
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
    int setHttpUserAgentCalls = 0;
    int startDownloadCalls = 0;
    int cancelDownloadCalls = 0;
    int pauseDownloadCalls = 0;
    int resumeDownloadCalls = 0;
    bool pauseSupported = true;
    quint64 lastFreezeCaptureRequestId = 0;
    quint64 lastStartDownloadId = 0;
    quint64 lastCancelDownloadId = 0;
    quint64 lastPauseDownloadId = 0;
    quint64 lastResumeDownloadId = 0;
    QUrl lastStartDownloadUrl;
    QString lastStartDownloadDestination;

    bool lastVisible = false;
    bool installBridgeResult = true;
    int lastGoBackOrForwardOffset = 0;
    QString lastHtml;
    QString lastScript;
    QString lastHttpUserAgent;
    QString lastBridgeNs;
    QString lastBridgeInvokeKey;
    QString lastBridgeScriptPath;
    QString lastPostedJson;
    QStringList lastAllowedOrigins;
    QStringList lastBridgeOrigins;
    QUrl lastLoadedUrl;
    QUrl lastLoadedFileUrl;
    QUrl lastFileReadAccessUrl;
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
    void loadFileUrlRejectsNonFileUrls();
    void loadFileUrlDefaultsReadAccessToOwnDirectory();
    void loadFileUrlContentSurvivesStoreRecreateAsFileLoad();
    void loadFileUrlReplayDroppedAfterNavigatingAwayFromFile();
    void loadHtmlContentSurvivesStoreRecreate();
    void offTheRecordSameValueDoesNotRecreateNativeView();
    void storageNameChangeRecreatesNativeViewInStandardMode();
    void storageNameChangeIgnoredInIncognitoMode();
    void recreateReinstallsBridgeAndPreservesUserScripts();
    void httpUserAgentDefaultsEmptyAndAppliesWithoutRecreate();
    void httpUserAgentSurvivesStoreRecreate();
    void downloadUrlEmitsRequestedAndAcceptStartsTransfer();
    void downloadCancelFromRequestedDoesNotStartTransfer();
    void downloadProgressAndCompletionReachTerminalState();
    void downloadRejectsBlobAndDataSchemes();
    void downloadCancelledOnProfileSwitch();
    void beginInlineDownloadAcceptWritesFile();
    void downloadPauseResumeInvokesPlatform();
    void downloadPauseUnsupportedInterrupts();
    void downloadRetryEmitsNewRequest();
    void downloadRetryInlineEmitsNewRequest();
    void inPageMediaPlaybackSupportedReflectsPlatformImpl();
    void inPageMediaPlaybackSupportedIsReadableWithoutABackend();
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
    QCOMPARE(d->m_freeze->state(), FS::Capturing);
    QCOMPARE(d->freezeCaptureCalls, 1);
    QCOMPARE(d->lastFreezeCaptureRequestId, d->m_freeze->freezeRequestId());

    QImage img(2, 2, QImage::Format_ARGB32);
    img.fill(QColor(Qt::red));
    d->notifySnapshotReady(d->m_freeze->freezeRequestId(), img);

    QCOMPARE(d->m_freeze->state(), FS::Capturing);
    QVERIFY(d->m_snapshotItem != nullptr);
    QTRY_COMPARE(d->m_freeze->state(), FS::Frozen);
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
    d->notifySnapshotReady(d->m_freeze->freezeRequestId(), img);

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
    const quint64 rid = d->m_freeze->freezeRequestId();

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
    const quint64 rid = d->m_freeze->freezeRequestId();
    QCOMPARE(d->m_freeze->state(), FS::Capturing);

    backend.setFreeze(false);
    QCOMPARE(d->m_freeze->state(), FS::Idle);
    QCOMPARE(freezeSpy.count(), 2);

    QImage img(1, 1, QImage::Format_ARGB32);
    img.fill(Qt::blue);
    d->notifySnapshotReady(rid, img);

    QCOMPARE(d->m_freeze->state(), FS::Idle);
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
    d->notifySnapshotReady(d->m_freeze->freezeRequestId(), QImage());

    QCOMPARE(d->m_freeze->state(), FS::Idle);
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
    d->notifySnapshotReady(d->m_freeze->freezeRequestId(), img);

    QVERIFY(d->m_snapshotItem != nullptr);
    QTRY_COMPARE(d->m_freeze->state(), FS::Frozen);

    QPointer<QQuickItem> overlay(d->m_snapshotItem);
    backend.setFreeze(false);

    QCOMPARE(d->m_freeze->state(), FS::Idle);
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
    QVERIFY(!d->m_freeze->publicSnapshotPending());
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
    const qreal dpr = d->m_freeze->publicSnapshotDpr();
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
    QCOMPARE(d->m_freeze->freezeRequestId(), quint64(1));
    backend.requestSnapshot(QSize());
    QCOMPARE(d->lastFreezeCaptureRequestId, quint64(2));
    QCOMPARE(d->freezeCaptureCalls, 2);

    QImage snapImg(3, 3, QImage::Format_ARGB32);
    snapImg.fill(QColor(Qt::blue));
    d->notifySnapshotReady(2, snapImg);

    QCOMPARE(snapshotSpy.count(), 1);
    QCOMPARE(d->m_freeze->state(), FS::Capturing);

    QImage freezeImg(2, 2, QImage::Format_ARGB32);
    freezeImg.fill(QColor(Qt::red));
    d->notifySnapshotReady(1, freezeImg);

    QVERIFY(d->m_snapshotItem != nullptr);
    QTRY_COMPARE(d->m_freeze->state(), FS::Frozen);
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

void MobileWebViewBackendCommonTest::loadFileUrlRejectsNonFileUrls()
{
    // loadFileUrl() is the local-file entry point only; a remote URL must not
    // silently fall through to a normal load (it would bypass the caller's
    // intent and, on Darwin, hand a non-file URL to
    // -loadFileURL:allowingReadAccessToURL:, which throws).
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    QVERIFY(g_lastCreatedPrivate != nullptr);
    auto *d = g_lastCreatedPrivate;

    backend.loadFileUrl(QUrl(QStringLiteral("https://example.com/report.pdf")));
    QCOMPARE(d->loadFileUrlCalls, 0);
    QCOMPARE(d->loadUrlCalls, 0);
    QVERIFY(backend.url().isEmpty());

    backend.loadFileUrl(QUrl());
    QCOMPARE(d->loadFileUrlCalls, 0);
    QCOMPARE(d->loadUrlCalls, 0);

    // A read-access URL that is not a local directory URL is rejected too, so a
    // bad grant can never reach the platform.
    backend.loadFileUrl(QUrl::fromLocalFile(QStringLiteral("/tmp/mwv/report.pdf")),
                        QUrl(QStringLiteral("https://example.com/")));
    QCOMPARE(d->loadFileUrlCalls, 0);
    QCOMPARE(d->loadUrlCalls, 0);
}

void MobileWebViewBackendCommonTest::loadFileUrlDefaultsReadAccessToOwnDirectory()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    QVERIFY(g_lastCreatedPrivate != nullptr);
    auto *d = g_lastCreatedPrivate;

    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    const QString filePath = dir.filePath(QStringLiteral("clip.mp4"));
    const QUrl fileUrl = QUrl::fromLocalFile(filePath);

    QSignalSpy urlSpy(&backend, &MobileWebViewBackend::urlChanged);
    backend.loadFileUrl(fileUrl);

    QCOMPARE(d->loadFileUrlCalls, 1);
    QCOMPARE(d->loadUrlCalls, 0);
    QCOMPARE(d->lastLoadedFileUrl, fileUrl);
    // Empty read access resolves to the file's own directory, not to the file.
    QCOMPARE(d->lastFileReadAccessUrl,
             QUrl::fromLocalFile(QFileInfo(filePath).absolutePath()));
    QCOMPARE(backend.url(), fileUrl);
    QCOMPARE(urlSpy.count(), 1);

    // An explicit directory is passed through untouched.
    const QUrl explicitDir = QUrl::fromLocalFile(dir.path());
    backend.loadFileUrl(fileUrl, explicitDir);
    QCOMPARE(d->loadFileUrlCalls, 2);
    QCOMPARE(d->lastFileReadAccessUrl, explicitDir);
}

void MobileWebViewBackendCommonTest::loadFileUrlContentSurvivesStoreRecreateAsFileLoad()
{
    // A local file opened in the tab must be replayed through the file path
    // after a store recreate. Degrading to loadUrlImpl() would blank the tab on
    // iOS, where -loadRequest: ignores file:// URLs.
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    QVERIFY(g_lastCreatedPrivate != nullptr);
    auto *d = g_lastCreatedPrivate;

    const QUrl fileUrl = QUrl::fromLocalFile(QStringLiteral("/tmp/mwv-downloads/clip.mp4"));
    const QUrl readAccessDir = QUrl::fromLocalFile(QStringLiteral("/tmp/mwv-downloads"));
    backend.loadFileUrl(fileUrl, readAccessDir);
    d->m_nativeViewSetup = true;

    const int loadFileUrlBefore = d->loadFileUrlCalls;
    const int loadUrlBefore = d->loadUrlCalls;

    backend.setOffTheRecord(true);

    QCOMPARE(d->loadFileUrlCalls, loadFileUrlBefore + 1);
    QCOMPARE(d->loadUrlCalls, loadUrlBefore);
    QCOMPARE(d->lastLoadedFileUrl, fileUrl);
    QCOMPARE(d->lastFileReadAccessUrl, readAccessDir);
}

void MobileWebViewBackendCommonTest::loadFileUrlReplayDroppedAfterNavigatingAwayFromFile()
{
    // Once the view leaves file://, the recorded file load is stale: a recreate
    // must replay the live URL instead of reopening the old file.
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    QVERIFY(g_lastCreatedPrivate != nullptr);
    auto *d = g_lastCreatedPrivate;

    backend.loadFileUrl(QUrl::fromLocalFile(QStringLiteral("/tmp/mwv-downloads/page.html")));
    d->m_nativeViewSetup = true;

    const QUrl webUrl(QStringLiteral("https://example.com/after"));
    backend.updateUrlState(webUrl);

    const int loadFileUrlBefore = d->loadFileUrlCalls;
    const int loadUrlBefore = d->loadUrlCalls;

    backend.setOffTheRecord(true);

    QCOMPARE(d->loadFileUrlCalls, loadFileUrlBefore);
    QCOMPARE(d->loadUrlCalls, loadUrlBefore + 1);
    QCOMPARE(d->lastLoadedUrl, webUrl);
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

void MobileWebViewBackendCommonTest::httpUserAgentDefaultsEmptyAndAppliesWithoutRecreate()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    QVERIFY(g_lastCreatedPrivate != nullptr);
    auto *d = g_lastCreatedPrivate;

    QCOMPARE(backend.httpUserAgent(), QString());

    d->m_nativeViewSetup = true;
    const int destroyBefore = d->destroyNativeViewCalls;
    const int initBefore = d->initNativeViewCalls;
    const int applyBefore = d->setHttpUserAgentCalls;

    QSignalSpy spy(&backend, &MobileWebViewBackend::httpUserAgentChanged);
    const QString ua = QStringLiteral("StatusMobile/1.0");
    backend.setHttpUserAgent(ua);

    QCOMPARE(backend.httpUserAgent(), ua);
    QCOMPARE(spy.count(), 1);
    QCOMPARE(d->setHttpUserAgentCalls, applyBefore + 1);
    QCOMPARE(d->lastHttpUserAgent, ua);
    QCOMPARE(d->destroyNativeViewCalls, destroyBefore);
    QCOMPARE(d->initNativeViewCalls, initBefore);

    backend.setHttpUserAgent(ua);
    QCOMPARE(spy.count(), 1);
    QCOMPARE(d->setHttpUserAgentCalls, applyBefore + 1);

    backend.setHttpUserAgent(QString());
    QCOMPARE(backend.httpUserAgent(), QString());
    QCOMPARE(spy.count(), 2);
    QCOMPARE(d->lastHttpUserAgent, QString());
}

void MobileWebViewBackendCommonTest::httpUserAgentSurvivesStoreRecreate()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    QVERIFY(g_lastCreatedPrivate != nullptr);
    auto *d = g_lastCreatedPrivate;

    const QString ua = QStringLiteral("StatusMobile/1.0");
    backend.setHttpUserAgent(ua);
    backend.setUrl(QUrl(QStringLiteral("https://example.com/page")));
    d->m_nativeViewSetup = true;

    const int applyBefore = d->setHttpUserAgentCalls;
    backend.setOffTheRecord(true);

    QCOMPARE(backend.httpUserAgent(), ua);
    QCOMPARE(d->setHttpUserAgentCalls, applyBefore + 1);
    QCOMPARE(d->lastHttpUserAgent, ua);
}

void MobileWebViewBackendCommonTest::downloadUrlEmitsRequestedAndAcceptStartsTransfer()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    auto *d = g_lastCreatedPrivate;
    QVERIFY(d);

    QSignalSpy requestedSpy(&backend, &MobileWebViewBackend::downloadRequested);
    backend.downloadUrl(QUrl(QStringLiteral("https://example.com/file.pdf")),
                        QStringLiteral("report.pdf"));

    QCOMPARE(requestedSpy.count(), 1);
    auto *download = qvariant_cast<MobileWebViewDownload *>(requestedSpy.at(0).at(0));
    QVERIFY(download);
    QCOMPARE(download->url().toString(), QStringLiteral("https://example.com/file.pdf"));
    QCOMPARE(download->suggestedFileName(), QStringLiteral("report.pdf"));
    QCOMPARE(download->state(), MobileWebViewDownload::State::Requested);
    QCOMPARE(d->startDownloadCalls, 0);

    QSignalSpy stateSpy(download, &MobileWebViewDownload::stateChanged);
    download->accept(QStringLiteral("/tmp/report.pdf"));

    QCOMPARE(download->state(), MobileWebViewDownload::State::InProgress);
    QCOMPARE(download->destinationPath(), QStringLiteral("/tmp/report.pdf"));
    QCOMPARE(d->startDownloadCalls, 1);
    QCOMPARE(d->lastStartDownloadId, download->downloadId());
    QCOMPARE(d->lastStartDownloadUrl, download->url());
    QCOMPARE(d->lastStartDownloadDestination, QStringLiteral("/tmp/report.pdf"));
    QVERIFY(stateSpy.count() >= 1);
}

void MobileWebViewBackendCommonTest::downloadCancelFromRequestedDoesNotStartTransfer()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    auto *d = g_lastCreatedPrivate;

    QSignalSpy requestedSpy(&backend, &MobileWebViewBackend::downloadRequested);
    backend.downloadUrl(QUrl(QStringLiteral("https://example.com/a.bin")));
    auto *download = qvariant_cast<MobileWebViewDownload *>(requestedSpy.at(0).at(0));
    QVERIFY(download);

    QSignalSpy finishedSpy(download, &MobileWebViewDownload::finished);
    QPointer<MobileWebViewDownload> guard(download);
    download->cancel();

    QCOMPARE(download->state(), MobileWebViewDownload::State::Cancelled);
    QCOMPARE(finishedSpy.count(), 1);
    QCOMPARE(d->startDownloadCalls, 0);
    // Platform is notified so pending destination handlers can be released.
    QCOMPARE(d->cancelDownloadCalls, 1);
    QCoreApplication::sendPostedEvents(nullptr, QEvent::DeferredDelete);
    QVERIFY(guard.isNull());
}

void MobileWebViewBackendCommonTest::downloadProgressAndCompletionReachTerminalState()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;

    QSignalSpy requestedSpy(&backend, &MobileWebViewBackend::downloadRequested);
    backend.downloadUrl(QUrl(QStringLiteral("https://example.com/big.bin")),
                        QStringLiteral("big.bin"));
    auto *download = qvariant_cast<MobileWebViewDownload *>(requestedSpy.at(0).at(0));
    QVERIFY(download);
    download->accept(QStringLiteral("/tmp/big.bin"));

    QSignalSpy progressSpy(download, &MobileWebViewDownload::receivedBytesChanged);
    QSignalSpy finishedSpy(download, &MobileWebViewDownload::finished);
    QPointer<MobileWebViewDownload> guard(download);

    backend.reportDownloadProgress(download->downloadId(), 50, 100);
    QCOMPARE(download->receivedBytes(), 50);
    QCOMPARE(download->totalBytes(), 100);
    QCOMPARE(progressSpy.count(), 1);

    backend.reportDownloadFinished(download->downloadId(), true);
    QCOMPARE(download->state(), MobileWebViewDownload::State::Completed);
    QCOMPARE(finishedSpy.count(), 1);
    QCoreApplication::sendPostedEvents(nullptr, QEvent::DeferredDelete);
    QVERIFY(guard.isNull());
}

void MobileWebViewBackendCommonTest::downloadRejectsBlobAndDataSchemes()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;

    QSignalSpy requestedSpy(&backend, &MobileWebViewBackend::downloadRequested);
    backend.downloadUrl(QUrl(QStringLiteral("blob:https://example.com/uuid")));
    backend.downloadUrl(QUrl(QStringLiteral("data:text/plain,hello")));
    QCOMPARE(requestedSpy.count(), 0);

    QVERIFY(backend.beginDownload(QUrl(QStringLiteral("blob:https://example.com/x")),
                                  QStringLiteral("x.bin"),
                                  QStringLiteral("application/octet-stream"),
                                  10)
            == nullptr);
}

void MobileWebViewBackendCommonTest::downloadCancelledOnProfileSwitch()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    auto *d = g_lastCreatedPrivate;

    QSignalSpy requestedSpy(&backend, &MobileWebViewBackend::downloadRequested);
    backend.downloadUrl(QUrl(QStringLiteral("https://example.com/keep.bin")),
                        QStringLiteral("keep.bin"));
    auto *download = qvariant_cast<MobileWebViewDownload *>(requestedSpy.at(0).at(0));
    QVERIFY(download);
    download->accept(QStringLiteral("/tmp/keep.bin"));
    QCOMPARE(download->state(), MobileWebViewDownload::State::InProgress);

    QSignalSpy finishedSpy(download, &MobileWebViewDownload::finished);
    QPointer<MobileWebViewDownload> guard(download);
    d->m_nativeViewSetup = true;
    backend.setOffTheRecord(true);

    QCOMPARE(download->state(), MobileWebViewDownload::State::Cancelled);
    QCOMPARE(finishedSpy.count(), 1);
    QCOMPARE(d->cancelDownloadCalls, 1);
    QCOMPARE(d->lastCancelDownloadId, download->downloadId());
    // deleteLater is posted; may not flush while nested in recreate — state is the contract.
    Q_UNUSED(guard);
}

void MobileWebViewBackendCommonTest::beginInlineDownloadAcceptWritesFile()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    auto *d = g_lastCreatedPrivate;
    QVERIFY(d);

    QSignalSpy requestedSpy(&backend, &MobileWebViewBackend::downloadRequested);
    // "hello" in base64
    auto *download = backend.beginInlineDownload(
        QUrl(QStringLiteral("blob:https://example.com/uuid")),
        QStringLiteral("hello.txt"),
        QStringLiteral("text/plain"),
        QStringLiteral("aGVsbG8="));
    QVERIFY(download);
    QCOMPARE(requestedSpy.count(), 1);
    QVERIFY(download->isInline());

    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    const QString path = dir.filePath(QStringLiteral("hello.txt"));
    QSignalSpy finishedSpy(download, &MobileWebViewDownload::finished);
    download->accept(path);

    QCOMPARE(d->startDownloadCalls, 0);
    QCOMPARE(finishedSpy.count(), 1);
    QCOMPARE(download->state(), MobileWebViewDownload::State::Completed);
    QFile file(path);
    QVERIFY(file.open(QIODevice::ReadOnly));
    QCOMPARE(file.readAll(), QByteArray("hello"));

    QVERIFY(backend.beginInlineDownload(
                QUrl(QStringLiteral("https://example.com/x")),
                QStringLiteral("x.bin"),
                QStringLiteral("application/octet-stream"),
                QStringLiteral("aGVsbG8="))
            == nullptr);
    QVERIFY(backend.beginInlineDownload(
                QUrl(QStringLiteral("blob:https://example.com/x")),
                QStringLiteral("x.bin"),
                QStringLiteral("application/octet-stream"),
                QString())
            == nullptr);
}

void MobileWebViewBackendCommonTest::downloadPauseResumeInvokesPlatform()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    auto *d = g_lastCreatedPrivate;

    QSignalSpy requestedSpy(&backend, &MobileWebViewBackend::downloadRequested);
    backend.downloadUrl(QUrl(QStringLiteral("https://example.com/p.bin")),
                        QStringLiteral("p.bin"));
    auto *download = qvariant_cast<MobileWebViewDownload *>(requestedSpy.at(0).at(0));
    QVERIFY(download);
    download->accept(QStringLiteral("/tmp/p.bin"));
    QCOMPARE(download->state(), MobileWebViewDownload::State::InProgress);

    download->pause();
    QCOMPARE(download->state(), MobileWebViewDownload::State::Paused);
    QVERIFY(download->isPaused());
    QCOMPARE(d->pauseDownloadCalls, 1);
    QCOMPARE(d->lastPauseDownloadId, download->downloadId());

    download->resume();
    QCOMPARE(download->state(), MobileWebViewDownload::State::InProgress);
    QVERIFY(!download->isPaused());
    QCOMPARE(d->resumeDownloadCalls, 1);
    QCOMPARE(d->lastResumeDownloadId, download->downloadId());
}

void MobileWebViewBackendCommonTest::downloadPauseUnsupportedInterrupts()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    auto *d = g_lastCreatedPrivate;
    d->pauseSupported = false;

    QSignalSpy requestedSpy(&backend, &MobileWebViewBackend::downloadRequested);
    backend.downloadUrl(QUrl(QStringLiteral("https://example.com/u.bin")),
                        QStringLiteral("u.bin"));
    auto *download = qvariant_cast<MobileWebViewDownload *>(requestedSpy.at(0).at(0));
    QVERIFY(download);
    download->accept(QStringLiteral("/tmp/u.bin"));
    QCOMPARE(download->state(), MobileWebViewDownload::State::InProgress);

    // Capture at finished emit — deleteLater may flush during QTRY event processing.
    auto terminal = MobileWebViewDownload::State::Requested;
    QString error;
    QObject::connect(download, &MobileWebViewDownload::finished, download, [&]() {
        terminal = download->state();
        error = download->errorString();
    });
    download->pause();
    QTRY_COMPARE(terminal, MobileWebViewDownload::State::Interrupted);
    QCOMPARE(error, QStringLiteral("Pause not supported"));
}

void MobileWebViewBackendCommonTest::downloadRetryEmitsNewRequest()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;

    QSignalSpy requestedSpy(&backend, &MobileWebViewBackend::downloadRequested);
    backend.downloadUrl(QUrl(QStringLiteral("https://example.com/r.bin")),
                        QStringLiteral("r.bin"));
    auto *download = qvariant_cast<MobileWebViewDownload *>(requestedSpy.at(0).at(0));
    QVERIFY(download);
    download->accept(QStringLiteral("/tmp/r.bin"));
    backend.reportDownloadFinished(download->downloadId(), false, QStringLiteral("fail"));
    QCOMPARE(download->state(), MobileWebViewDownload::State::Interrupted);

    download->retry();
    QCOMPARE(requestedSpy.count(), 2);
    auto *retryDownload = qvariant_cast<MobileWebViewDownload *>(requestedSpy.at(1).at(0));
    QVERIFY(retryDownload);
    QVERIFY(retryDownload != download);
    QCOMPARE(retryDownload->url(), QUrl(QStringLiteral("https://example.com/r.bin")));
    QCOMPARE(retryDownload->suggestedFileName(), QStringLiteral("r.bin"));
    QCOMPARE(retryDownload->state(), MobileWebViewDownload::State::Requested);
}

void MobileWebViewBackendCommonTest::downloadRetryInlineEmitsNewRequest()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;

    QSignalSpy requestedSpy(&backend, &MobileWebViewBackend::downloadRequested);
    auto *download = backend.beginInlineDownload(
        QUrl(QStringLiteral("blob:https://example.com/uuid")),
        QStringLiteral("hello.txt"),
        QStringLiteral("text/plain"),
        QStringLiteral("aGVsbG8="));
    QVERIFY(download);
    QVERIFY(download->isInline());
    QCOMPARE(requestedSpy.count(), 1);

    // Interrupt without writing so payload remains for retry.
    backend.reportDownloadFinished(download->downloadId(), false, QStringLiteral("fail"));
    QCOMPARE(download->state(), MobileWebViewDownload::State::Interrupted);

    download->retry();
    QCOMPARE(requestedSpy.count(), 2);
    auto *retryDownload = qvariant_cast<MobileWebViewDownload *>(requestedSpy.at(1).at(0));
    QVERIFY(retryDownload);
    QVERIFY(retryDownload != download);
    QVERIFY(retryDownload->isInline());
    QCOMPARE(retryDownload->suggestedFileName(), QStringLiteral("hello.txt"));

    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    const QString path = dir.filePath(QStringLiteral("hello.txt"));
    retryDownload->accept(path);
    QCOMPARE(retryDownload->state(), MobileWebViewDownload::State::Completed);
    QFile file(path);
    QVERIFY(file.open(QIODevice::ReadOnly));
    QCOMPARE(file.readAll(), QByteArray("hello"));
}

void MobileWebViewBackendCommonTest::inPageMediaPlaybackSupportedReflectsPlatformImpl()
{
    g_lastCreatedPrivate = nullptr;
    MobileWebViewBackend backend;
    auto *d = g_lastCreatedPrivate;
    QVERIFY(d);

    // The property is a pure read of the platform private, both ways round —
    // a Backend that cannot play media in a page must be able to say so.
    d->inPageMediaPlaybackSupportedValue = true;
    QVERIFY(backend.inPageMediaPlaybackSupported());
    QCOMPARE(backend.property("inPageMediaPlaybackSupported").toBool(), true);

    d->inPageMediaPlaybackSupportedValue = false;
    QVERIFY(!backend.inPageMediaPlaybackSupported());
    QCOMPARE(backend.property("inPageMediaPlaybackSupported").toBool(), false);
}

void MobileWebViewBackendCommonTest::inPageMediaPlaybackSupportedIsReadableWithoutABackend()
{
    // The point of the static: a host answers the question with no instance,
    // and no platform check of its own.
    const bool supported = MobileWebViewCapabilities::isInPageMediaPlaybackSupported();

    // macOS and iOS 17.4+ play WebM in a page; the Darwin build is only ever
    // false below that iOS floor, which this suite cannot be run on.
    QVERIFY(supported);

    // Same answer through the QML-facing singleton object.
    MobileWebViewCapabilities capabilities;
    QCOMPARE(capabilities.inPageMediaPlaybackSupported(), supported);
    QCOMPARE(capabilities.property("inPageMediaPlaybackSupported").toBool(), supported);
}

QTEST_MAIN(MobileWebViewBackendCommonTest)
#include "tst_mobilewebviewbackend_common.moc"

#endif
