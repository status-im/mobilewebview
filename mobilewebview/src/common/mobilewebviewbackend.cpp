#include "MobileWebView/mobilewebviewbackend.h"
#include "mobilewebviewbackend_p.h"
#include "snapshotimageprovider.h"
#include "snapshotitem.h"
#include "webchanneltransport.h"
#include "origin_utils.h"

#include <QUuid>
#include <QDebug>
#include <QMutex>
#include <QMutexLocker>
#include <QPointer>
#include <QPointF>
#include <QQmlEngine>
#include <QtQml>
#include <QSet>
#include <QTimer>
#include <QtMath>
#include <QQuickView>
#include <QQuickWindow>
#include <memory>
#include <mutex>

namespace {
// Delay before hiding native WebView after overlay is ready (freeze) and before removing overlay (unfreeze).
constexpr int kFreezeOverlayFrameDelayMs = 48;

QString snapshotImageProviderKey(const MobileWebViewBackend *backend)
{
    return QStringLiteral("mwv") + QString::number(reinterpret_cast<quintptr>(backend), 16);
}

void ensureSnapshotImageProviderRegistered(QQmlEngine *engine)
{
    if (!engine) {
        return;
    }

    static QMutex mutex;
    static QSet<QQmlEngine *> registered;

    {
        QMutexLocker locker(&mutex);
        if (registered.contains(engine)) {
            return;
        }
        registered.insert(engine);
    }

    const QString providerId = QStringLiteral("mobilewebview-snapshot");
    if (!engine->imageProvider(providerId)) {
        engine->addImageProvider(providerId, new MobileWebViewSnapshotImageProvider());
    }

    QQmlEngine *raw = engine;
    QObject::connect(engine, &QObject::destroyed, [raw]() {
        QMutexLocker lock(&mutex);
        registered.remove(raw);
    });
}
} // namespace

// =============================================================================
// MobileWebViewBackendPrivate - Common implementation
// =============================================================================

MobileWebViewBackendPrivate::MobileWebViewBackendPrivate(MobileWebViewBackend *q)
    : q_ptr(q)
{
}

MobileWebViewBackendPrivate::~MobileWebViewBackendPrivate()
{
    QObject::disconnect(m_afterAnimatingConnection);
}

void MobileWebViewBackendPrivate::syncNativeGeometryFromScene()
{
    if (!m_nativeViewSetup || !q_ptr->isVisible()) {
        return;
    }

    const qreal w = q_ptr->width();
    const qreal h = q_ptr->height();
    if (w <= 0 || h <= 0) {
        return;
    }

    updateNativeGeometry(QRectF(0, 0, w, h));
}

void MobileWebViewBackendPrivate::detachNativeViewFromScene()
{
    if (m_freezeState != FreezeState::Idle) {
        m_freezeState = FreezeState::Idle;
        if (m_snapshotItem) {
            m_snapshotItem->deleteLater();
            m_snapshotItem = nullptr;
        }
        restoreClipState();
    }
    updateNativeVisibility(false);
    detachNativeViewFromSceneImpl();
}

void MobileWebViewBackendPrivate::setLoading(bool loading)
{
    if (m_loading != loading) {
        m_loading = loading;
        emit q_ptr->loadingChanged();
    }
}

void MobileWebViewBackendPrivate::setLoaded(bool loaded)
{
    if (m_loaded != loaded) {
        m_loaded = loaded;
        emit q_ptr->loadedChanged();
    }
}

void MobileWebViewBackendPrivate::setTitle(const QString &title)
{
    if (m_title != title) {
        m_title = title;
        emit q_ptr->titleChanged();
    }
}

void MobileWebViewBackendPrivate::setCanGoBack(bool canGoBack)
{
    if (m_canGoBack != canGoBack) {
        m_canGoBack = canGoBack;
        emit q_ptr->canGoBackChanged();
    }
}

void MobileWebViewBackendPrivate::setCanGoForward(bool canGoForward)
{
    if (m_canGoForward != canGoForward) {
        m_canGoForward = canGoForward;
        emit q_ptr->canGoForwardChanged();
    }
}

void MobileWebViewBackendPrivate::setHistoryState(const QVariantList &historyItems, int currentHistoryIndex)
{
    if (m_historyItems != historyItems) {
        m_historyItems = historyItems;
        emit q_ptr->historyItemsChanged();
    }

    if (m_currentHistoryIndex != currentHistoryIndex) {
        m_currentHistoryIndex = currentHistoryIndex;
        emit q_ptr->currentHistoryIndexChanged();
    }
}

void MobileWebViewBackendPrivate::setLoadProgress(int progress)
{
    if (m_loadProgress != progress) {
        m_loadProgress = progress;
        emit q_ptr->loadProgressChanged();
    }
}

void MobileWebViewBackendPrivate::setFavicon(const QString &favicon)
{
    if (m_favicon != favicon) {
        m_favicon = favicon;
        emit q_ptr->faviconChanged();
    }
}

void MobileWebViewBackendPrivate::updateUrlState(const QUrl &url)
{
    if (m_url == url) {
        return;
    }
    const QString prevOrigin = extractOrigin(m_url);
    const QString newOrigin = extractOrigin(url);
    m_url = url;
    emit q_ptr->urlChanged();

    if (!newOrigin.isEmpty() && newOrigin != prevOrigin) {
        appendAllowedOrigin(newOrigin);
    }
}

void MobileWebViewBackendPrivate::updateAllowedOrigins(const QStringList &origins)
{
    QStringList filtered;
    for (const QString &origin : origins) {
        if (!origin.isEmpty() && !filtered.contains(origin)) {
            filtered.append(origin);
        }
    }

    if (m_allowedOrigins == filtered) {
        return;
    }

    m_allowedOrigins = filtered;

    if (m_transport) {
        m_transport->setAllowedOrigins(m_allowedOrigins);
    }

    updateAllowedOriginsImpl(m_allowedOrigins);
}

void MobileWebViewBackendPrivate::appendAllowedOrigin(const QString &origin)
{
    if (origin.isEmpty() || m_allowedOrigins.contains(origin)) {
        return;
    }

    m_allowedOrigins.append(origin);

    if (m_transport) {
        m_transport->setAllowedOrigins(m_allowedOrigins);
    }

    updateAllowedOriginsImpl(m_allowedOrigins);
}

void MobileWebViewBackendPrivate::notifySnapshotReady(quint64 requestId, const QImage &image)
{
    if (m_publicSnapshotPending && requestId == m_publicSnapshotRequestId) {
        m_publicSnapshotPending = false;
        const QString key = snapshotImageProviderKey(q_ptr);
        const bool ok = !image.isNull();
        QUrl imageUrl;
        if (ok) {
            QImage out = image;
            if (m_publicSnapshotTargetSize.isValid() && m_publicSnapshotTargetSize.width() > 0
                && m_publicSnapshotTargetSize.height() > 0) {
                const int tw = qRound(m_publicSnapshotTargetSize.width() * m_publicSnapshotDpr);
                const int th = qRound(m_publicSnapshotTargetSize.height() * m_publicSnapshotDpr);
                if (tw > 0 && th > 0) {
                    out = image.scaled(QSize(tw, th), Qt::KeepAspectRatio,
                                        Qt::SmoothTransformation);
                }
            }
            MobileWebViewSnapshotImageProvider::registerImage(key, out);
            imageUrl = QUrl(QStringLiteral("image://mobilewebview-snapshot/") + key);
        } else {
            MobileWebViewSnapshotImageProvider::releaseImage(key);
        }
        emit q_ptr->snapshotReady(imageUrl, ok);
        return;
    }

    if (requestId != m_freezeRequestId) {
        return;
    }
    if (m_freezeState != FreezeState::Capturing) {
        return;
    }

    if (image.isNull()) {
        qWarning() << "MobileWebViewBackend: freeze snapshot failed or empty";
        clearFreezeState();
        emit q_ptr->freezeChanged();
        return;
    }

    if (!m_snapshotItem) {
        m_snapshotItem = new MobileWebViewSnapshotItem(q_ptr);
    }
    if (!m_freezeClipStateStored) {
        m_clipStateBeforeFreeze = q_ptr->clip();
        m_freezeClipStateStored = true;
    }
    q_ptr->setClip(true);
    m_snapshotItem->setImage(image);
    m_snapshotItem->setVisible(true);
    applyFreezeOverlaySizeFromImage(image);

    const quint64 captureToken = requestId;
    QPointer<MobileWebViewBackend> guard(q_ptr);
    QTimer::singleShot(kFreezeOverlayFrameDelayMs, q_ptr, [this, guard, captureToken]() {
        if (!guard) {
            return;
        }
        if (m_freezeState != FreezeState::Capturing || m_freezeRequestId != captureToken) {
            return;
        }
        m_freezeState = FreezeState::Frozen;
        updateNativeVisibility(guard->isVisible());
    });
}

void MobileWebViewBackendPrivate::clearFreezeState()
{
    m_freezeState = FreezeState::Idle;
    if (m_snapshotItem) {
        m_snapshotItem->deleteLater();
        m_snapshotItem = nullptr;
    }
    restoreClipState();
    updateNativeVisibility(q_ptr->isVisible());
}

void MobileWebViewBackendPrivate::applyFreezeOverlaySizeFromImage(const QImage &image)
{
    if (!m_snapshotItem || !q_ptr || image.isNull()) {
        return;
    }

    qreal dpr = 1.0;
    if (QQuickWindow *window = q_ptr->window()) {
        dpr = window->devicePixelRatio();
    }
    if (dpr <= 0) {
        dpr = 1.0;
    }

    m_snapshotItem->setPosition(QPointF(0, 0));
    m_snapshotItem->setWidth(image.width() / dpr);
    m_snapshotItem->setHeight(image.height() / dpr);
}

void MobileWebViewBackendPrivate::restoreClipState()
{
    if (!q_ptr || !m_freezeClipStateStored) {
        return;
    }
    q_ptr->setClip(m_clipStateBeforeFreeze);
    m_freezeClipStateStored = false;
}

void MobileWebViewBackendPrivate::setupTransport()
{
    if (m_channel && !m_transport) {
        m_transport = new WebChannelTransport(q_ptr);
        
        QString origin = extractOrigin(m_url);
        if (!origin.isEmpty()) {
            m_transport->setAllowedOrigins({origin});
        } else {
            // If no valid URL yet, allow everything temporarily (will be updated on navigation)
            m_transport->setAllowedOrigins({QStringLiteral("*")});
        }
        
        // Set invokeKey if bridge is already installed
        if (m_bridgeInstalled && !m_invokeKey.isEmpty()) {
            m_transport->setInvokeKey(m_invokeKey);
        }
        
        // Connect sendMessageRequested -> postMessageToJavaScript
        QObject::connect(m_transport, &WebChannelTransport::sendMessageRequested,
                        q_ptr, [this](const QString &json) {
            postMessageToJavaScript(json);
        });
        
        // Connect webMessageReceived -> transport
        QObject::connect(q_ptr, &MobileWebViewBackend::webMessageReceived,
                        m_transport, &WebChannelTransport::handleJsEnvelope);
        
        // Connect transport to channel
        m_channel->connectTo(m_transport);
    }
}

void MobileWebViewBackendPrivate::ensureBridgeInstalled()
{
    if (m_bridgeInstalled) {
        return;
    }

    m_invokeKey = QUuid::createUuid().toString(QUuid::WithoutBraces);
    
    QString origin = extractOrigin(m_url);
    QStringList allowedOrigins;
    if (!origin.isEmpty()) {
        allowedOrigins = {origin};
    } else {
        allowedOrigins = {QStringLiteral("*")};
    }

    m_bridgeInstalled = installBridgeImpl(
        m_webChannelNamespace, 
        allowedOrigins, 
        m_invokeKey,
        QString()
    );
    
    if (m_bridgeInstalled) {
        if (m_transport) {
            m_transport->setInvokeKey(m_invokeKey);
        }
    } else {
        qWarning() << "MobileWebViewBackend: Failed to install message bridge";
    }
}

void MobileWebViewBackendPrivate::recreateNativeViewForStore()
{
    const QUrl urlToReload = m_url;

    clearFreezeState();
    m_bridgeInstalled = false;

    destroyNativeView();
    m_nativeViewSetup = false;

    if (!initNativeView()) {
        qWarning() << "MobileWebViewBackend: Failed to re-initialize native view";
        return;
    }

    m_viewStoreOffTheRecord = m_offTheRecord;
    m_viewStoreName = m_storageName;

    setupNativeViewImpl();
    ensureBridgeInstalled();

    setLoading(true);
    setLoaded(false);

    if (m_hasLastHtml) {
        loadHtmlImpl(m_lastHtml, m_lastHtmlBaseUrl);
    } else if (urlToReload.isValid() && !urlToReload.isEmpty()) {
        loadUrlImpl(urlToReload);
    }
}

void MobileWebViewBackendPrivate::beginClear()
{
    if (m_pendingClears++ == 0) {
        emit q_ptr->clearingChanged();
    }
}

void MobileWebViewBackendPrivate::endClear()
{
    if (m_pendingClears > 0 && --m_pendingClears == 0) {
        emit q_ptr->clearingChanged();
    }
}

// =============================================================================
// MobileWebViewBackend - Public API implementation
// =============================================================================

MobileWebViewBackend::MobileWebViewBackend(QQuickItem *parent)
    : QQuickItem(parent)
    , d_ptr(createPlatformBackend(this))
{
#if defined(Q_OS_IOS) || defined(Q_OS_MACOS)
    static std::once_flag resourcesInitOnce;
    std::call_once(resourcesInitOnce, []() {
        Q_INIT_RESOURCE(customwebview);
    });
#endif
    setFlag(ItemHasContents, false);
}

MobileWebViewBackend::~MobileWebViewBackend()
{
    Q_D(MobileWebViewBackend);
    MobileWebViewSnapshotImageProvider::releaseImage(snapshotImageProviderKey(this));
    d->clearFreezeState();
}

void MobileWebViewBackend::requestSnapshot(const QSize &targetSize)
{
    Q_D(MobileWebViewBackend);
    QQmlEngine *engine = qmlEngine(this);
    if (!engine) {
        if (auto *qv = qobject_cast<QQuickView *>(window())) {
            engine = qv->engine();
        }
    }
    ensureSnapshotImageProviderRegistered(engine);
    d->m_publicSnapshotRequestId = ++d->m_nextSnapshotId;
    d->m_publicSnapshotPending = true;
    d->m_publicSnapshotTargetSize = targetSize;
    d->m_publicSnapshotDpr = 1.0;
    if (QQuickWindow *w = window()) {
        d->m_publicSnapshotDpr = w->devicePixelRatio();
    }
    d->captureSnapshotImpl(d->m_publicSnapshotRequestId);
}

bool MobileWebViewBackend::loading() const
{
    Q_D(const MobileWebViewBackend);
    return d->m_loading;
}

bool MobileWebViewBackend::loaded() const
{
    Q_D(const MobileWebViewBackend);
    return d->m_loaded;
}

QUrl MobileWebViewBackend::url() const
{
    Q_D(const MobileWebViewBackend);
    return d->m_url;
}

QString MobileWebViewBackend::title() const
{
    Q_D(const MobileWebViewBackend);
    return d->m_title;
}

bool MobileWebViewBackend::canGoBack() const
{
    Q_D(const MobileWebViewBackend);
    return d->m_canGoBack;
}

bool MobileWebViewBackend::canGoForward() const
{
    Q_D(const MobileWebViewBackend);
    return d->m_canGoForward;
}

QVariantList MobileWebViewBackend::historyItems() const
{
    Q_D(const MobileWebViewBackend);
    return d->m_historyItems;
}

int MobileWebViewBackend::currentHistoryIndex() const
{
    Q_D(const MobileWebViewBackend);
    return d->m_currentHistoryIndex;
}

void MobileWebViewBackend::setUrl(const QUrl &url)
{
    Q_D(MobileWebViewBackend);
    if (d->m_url != url) {
        d->m_url = url;
        emit urlChanged();

        QString origin = extractOrigin(url);
        if (!origin.isEmpty()) {
            updateAllowedOrigins({origin});
        }

        d->ensureBridgeInstalled();
        d->loadUrlImpl(url);
    }
}

QVariantList MobileWebViewBackend::userScripts() const
{
    Q_D(const MobileWebViewBackend);
    return d->m_userScripts;
}

void MobileWebViewBackend::setUserScripts(const QVariantList &scripts)
{
    Q_D(MobileWebViewBackend);
    if (d->m_userScripts != scripts) {
        d->m_userScripts = scripts;
        emit userScriptsChanged();
    }
}

QString MobileWebViewBackend::webChannelNamespace() const
{
    Q_D(const MobileWebViewBackend);
    return d->m_webChannelNamespace;
}

void MobileWebViewBackend::setWebChannelNamespace(const QString &ns)
{
    Q_D(MobileWebViewBackend);
    if (d->m_webChannelNamespace != ns) {
        d->m_webChannelNamespace = ns;
        emit webChannelNamespaceChanged();
    }
}

QWebChannel* MobileWebViewBackend::webChannel() const
{
    Q_D(const MobileWebViewBackend);
    return d->m_channel;
}

void MobileWebViewBackend::setWebChannel(QWebChannel *channel)
{
    Q_D(MobileWebViewBackend);
    if (d->m_channel == channel)
        return;
    
    d->m_channel = channel;
    
    // Create transport if needed
    d->setupTransport();
    
    // Ensure bridge is installed when channel is set (handles race condition where setWebChannel is called after loadUrl)
    if (d->m_channel && !d->m_bridgeInstalled) {
        d->ensureBridgeInstalled();
    }
    
    emit webChannelChanged();
}

void MobileWebViewBackend::updateUrlState(const QUrl &url)
{
    Q_D(MobileWebViewBackend);
    d->updateUrlState(url);
}

void MobileWebViewBackend::updateAllowedOrigins(const QStringList &origins)
{
    Q_D(MobileWebViewBackend);
    d->updateAllowedOrigins(origins);
}

void MobileWebViewBackend::setLoadingState(bool loading)
{
    Q_D(MobileWebViewBackend);
    d->setLoading(loading);
}

void MobileWebViewBackend::setLoadedState(bool loaded)
{
    Q_D(MobileWebViewBackend);
    d->setLoaded(loaded);
}

void MobileWebViewBackend::setTitle(const QString &title)
{
    Q_D(MobileWebViewBackend);
    d->setTitle(title);
}

void MobileWebViewBackend::setCanGoBack(bool canGoBack)
{
    Q_D(MobileWebViewBackend);
    d->setCanGoBack(canGoBack);
}

void MobileWebViewBackend::setCanGoForward(bool canGoForward)
{
    Q_D(MobileWebViewBackend);
    d->setCanGoForward(canGoForward);
}

void MobileWebViewBackend::setHistoryState(const QVariantList &historyItems, int currentHistoryIndex)
{
    Q_D(MobileWebViewBackend);
    d->setHistoryState(historyItems, currentHistoryIndex);
}

void MobileWebViewBackend::emitNewWindowRequested(const QUrl &url, bool userInitiated)
{
    emit newWindowRequested(url, userInitiated);
}

bool MobileWebViewBackend::interactionEnabled() const
{
    Q_D(const MobileWebViewBackend);
    return d->m_interactionEnabled;
}

void MobileWebViewBackend::setInteractionEnabled(bool enabled)
{
    Q_D(MobileWebViewBackend);
    if (d->m_interactionEnabled != enabled) {
        d->m_interactionEnabled = enabled;
        d->updateInteractionEnabled(enabled);
        emit interactionEnabledChanged();
    }
}

int MobileWebViewBackend::loadProgress() const
{
    Q_D(const MobileWebViewBackend);
    return d->m_loadProgress;
}

QString MobileWebViewBackend::favicon() const
{
    Q_D(const MobileWebViewBackend);
    return d->m_favicon;
}

qreal MobileWebViewBackend::zoomFactor() const
{
    Q_D(const MobileWebViewBackend);
    return d->m_zoomFactor;
}

bool MobileWebViewBackend::findSupported() const
{
    Q_D(const MobileWebViewBackend);
    return d->findSupportedImpl();
}

bool MobileWebViewBackend::hasNativeFindPanel() const
{
    Q_D(const MobileWebViewBackend);
    return d->hasNativeFindPanelImpl();
}

bool MobileWebViewBackend::freeze() const
{
    Q_D(const MobileWebViewBackend);
    return d->m_freezeState != MobileWebViewBackendPrivate::FreezeState::Idle;
}

bool MobileWebViewBackend::offTheRecord() const
{
    Q_D(const MobileWebViewBackend);
    return d->m_offTheRecord;
}

void MobileWebViewBackend::setOffTheRecord(bool offTheRecord)
{
    Q_D(MobileWebViewBackend);
    if (d->m_offTheRecord == offTheRecord) {
        return;
    }

    d->m_offTheRecord = offTheRecord;
    emit offTheRecordChanged();

    if (d->m_nativeViewSetup) {
        d->recreateNativeViewForStore();
    }
}

QString MobileWebViewBackend::storageName() const
{
    Q_D(const MobileWebViewBackend);
    return d->m_storageName;
}

void MobileWebViewBackend::setStorageName(const QString &storageName)
{
    Q_D(MobileWebViewBackend);
    if (d->m_storageName == storageName) {
        return;
    }

    d->m_storageName = storageName;
    emit storageNameChanged();

    if (d->m_nativeViewSetup && !d->m_offTheRecord) {
        d->recreateNativeViewForStore();
    }
}

bool MobileWebViewBackend::clearing() const
{
    Q_D(const MobileWebViewBackend);
    return d->m_pendingClears > 0;
}

void MobileWebViewBackend::setFreeze(bool freeze)
{
    Q_D(MobileWebViewBackend);
    using FS = MobileWebViewBackendPrivate::FreezeState;

    if (freeze) {
        if (d->m_freezeState == FS::Capturing || d->m_freezeState == FS::Frozen) {
            return;
        }
        d->m_freezeState = FS::Capturing;
        d->m_freezeRequestId = ++d->m_nextSnapshotId;
        emit freezeChanged();
        d->captureSnapshotImpl(d->m_freezeRequestId);
        return;
    }

    if (d->m_freezeState == FS::Idle) {
        return;
    }

    if (d->m_freezeState == FS::Frozen) {
        MobileWebViewSnapshotItem *overlay = d->m_snapshotItem;
        d->m_snapshotItem = nullptr;
        d->m_freezeState = FS::Idle;
        d->restoreClipState();
        d->updateNativeVisibility(d->q_ptr->isVisible());
        emit freezeChanged();
        QTimer::singleShot(kFreezeOverlayFrameDelayMs, this, [overlay]() {
            if (overlay) {
                overlay->deleteLater();
            }
        });
        return;
    }

    d->clearFreezeState();
    emit freezeChanged();
}

void MobileWebViewBackend::setZoomFactor(qreal factor)
{
    Q_D(MobileWebViewBackend);
    if (!qFuzzyCompare(d->m_zoomFactor, factor)) {
        d->m_zoomFactor = factor;
        d->setZoomFactorImpl(factor);
        emit zoomFactorChanged();
    }
}

void MobileWebViewBackend::setLoadProgress(int progress)
{
    Q_D(MobileWebViewBackend);
    d->setLoadProgress(progress);
}

void MobileWebViewBackend::setFavicon(const QString &favicon)
{
    Q_D(MobileWebViewBackend);
    d->setFavicon(favicon);
}

void MobileWebViewBackend::loadUrl(const QUrl &url)
{
    Q_D(MobileWebViewBackend);
    
    QString origin = extractOrigin(url);
    if (!origin.isEmpty()) {
        updateAllowedOrigins({origin});
    }

    d->m_hasLastHtml = false;
    // Record the requested URL so it survives an internal store recreate (and a
    // deferred native-view setup), matching setUrl() and loadHtml() replay semantics.
    if (d->m_url != url) {
        d->m_url = url;
        emit urlChanged();
    }
    d->ensureBridgeInstalled();
    d->loadUrlImpl(url);
}

void MobileWebViewBackend::loadHtml(const QString &html, const QUrl &baseUrl)
{
    Q_D(MobileWebViewBackend);

    const QUrl originSource = baseUrl.isValid() ? baseUrl : d->m_url;
    QString origin = extractOrigin(originSource);
    if (!origin.isEmpty()) {
        updateAllowedOrigins({origin});
    }

    d->m_hasLastHtml = true;
    d->m_lastHtml = html;
    d->m_lastHtmlBaseUrl = baseUrl;
    d->ensureBridgeInstalled();
    d->loadHtmlImpl(html, baseUrl);
}

void MobileWebViewBackend::goBack()
{
    Q_D(MobileWebViewBackend);
    d->goBackImpl();
}

void MobileWebViewBackend::goForward()
{
    Q_D(MobileWebViewBackend);
    d->goForwardImpl();
}

void MobileWebViewBackend::goBackOrForward(int offset)
{
    Q_D(MobileWebViewBackend);
    d->goBackOrForwardImpl(offset);
}

void MobileWebViewBackend::reload()
{
    Q_D(MobileWebViewBackend);
    d->reloadImpl();
}

void MobileWebViewBackend::stop()
{
    Q_D(MobileWebViewBackend);
    d->stopImpl();
}

void MobileWebViewBackend::clearHistory()
{
    Q_D(MobileWebViewBackend);
    d->clearHistoryImpl();
}

void MobileWebViewBackend::clearHttpCache()
{
    Q_D(MobileWebViewBackend);
    if (!d->m_nativeViewSetup) {
        qWarning() << "MobileWebViewBackend::clearHttpCache: no native view set up; ignoring";
        QMetaObject::invokeMethod(this, [this]() { emit clearHttpCacheCompleted(); },
                                Qt::QueuedConnection);
        return;
    }

    QPointer<MobileWebViewBackend> guard(this);
    d->beginClear();
    d->clearHttpCacheImpl([guard, d]() {
        if (!guard) {
            return;
        }
        emit guard->clearHttpCacheCompleted();
        d->endClear();
    });
}

void MobileWebViewBackend::deleteAllCookies()
{
    Q_D(MobileWebViewBackend);
    if (!d->m_nativeViewSetup) {
        qWarning() << "MobileWebViewBackend::deleteAllCookies: no native view set up; ignoring";
        QMetaObject::invokeMethod(this, [this]() { emit deleteAllCookiesCompleted(); },
                                Qt::QueuedConnection);
        return;
    }

    QPointer<MobileWebViewBackend> guard(this);
    d->beginClear();
    d->deleteAllCookiesImpl([guard, d]() {
        if (!guard) {
            return;
        }
        emit guard->deleteAllCookiesCompleted();
        d->endClear();
    });
}

void MobileWebViewBackend::clearDomStorage()
{
    Q_D(MobileWebViewBackend);
    if (!d->m_nativeViewSetup) {
        qWarning() << "MobileWebViewBackend::clearDomStorage: no native view set up; ignoring";
        QMetaObject::invokeMethod(this, [this]() { emit clearDomStorageCompleted(); },
                                Qt::QueuedConnection);
        return;
    }

    QPointer<MobileWebViewBackend> guard(this);
    d->beginClear();
    d->clearDomStorageImpl([guard, d]() {
        if (!guard) {
            return;
        }
        emit guard->clearDomStorageCompleted();
        d->endClear();
    });
}

void MobileWebViewBackend::clearDomStorage(const QString &origin)
{
    Q_D(MobileWebViewBackend);
    if (!d->m_nativeViewSetup) {
        qWarning() << "MobileWebViewBackend::clearDomStorage(origin): no native view set up; ignoring";
        QMetaObject::invokeMethod(this, [this]() { emit clearDomStorageCompleted(); },
                                Qt::QueuedConnection);
        return;
    }

    QPointer<MobileWebViewBackend> guard(this);
    d->beginClear();
    d->clearDomStorageImpl(origin, [guard, d]() {
        if (!guard) {
            return;
        }
        emit guard->clearDomStorageCompleted();
        d->endClear();
    });
}

void MobileWebViewBackend::clearProfileData()
{
    Q_D(MobileWebViewBackend);
    if (!d->m_nativeViewSetup) {
        qWarning() << "MobileWebViewBackend::clearProfileData: no native view set up; ignoring";
        QMetaObject::invokeMethod(this, [this]() { emit clearProfileDataCompleted(); },
                                Qt::QueuedConnection);
        return;
    }

    QPointer<MobileWebViewBackend> guard(this);
    auto remaining = std::make_shared<int>(3);

    const auto subCompletion = [guard, d, remaining]() {
        if (!guard) {
            return;
        }
        d->endClear();
        if (--(*remaining) == 0) {
            emit guard->clearProfileDataCompleted();
        }
    };

    d->beginClear();
    d->beginClear();
    d->beginClear();
    d->clearHttpCacheImpl(subCompletion);
    d->deleteAllCookiesImpl(subCompletion);
    d->clearDomStorageImpl(subCompletion);
}

void MobileWebViewBackend::reloadAndBypassCache()
{
    Q_D(MobileWebViewBackend);
    if (!d->m_nativeViewSetup) {
        qWarning() << "MobileWebViewBackend::reloadAndBypassCache: no native view set up; ignoring";
        return;
    }
    d->reloadAndBypassCacheImpl();
}

bool MobileWebViewBackend::installMessageBridge(const QString &ns,
                                                 const QStringList &allowedOrigins,
                                                 const QString &invokeKey,
                                                 const QString &webChannelScriptPath)
{
    Q_D(MobileWebViewBackend);
    
    setWebChannelNamespace(ns);
    d->m_invokeKey = invokeKey;
    d->m_allowedOrigins = allowedOrigins;

    d->m_bridgeInstalled = d->installBridgeImpl(ns, allowedOrigins, invokeKey, webChannelScriptPath);
    
    if (d->m_bridgeInstalled && d->m_transport) {
        d->m_transport->setInvokeKey(invokeKey);
    }

    return d->m_bridgeInstalled;
}

void MobileWebViewBackend::postMessageToJavaScript(const QString &json)
{
    Q_D(MobileWebViewBackend);
    d->postMessageToJavaScript(json);
}

void MobileWebViewBackend::runJavaScript(const QString &script)
{
    Q_D(MobileWebViewBackend);
    d->evaluateJavaScript(script);
}

void MobileWebViewBackend::findText(const QString &text, int flags)
{
    Q_D(MobileWebViewBackend);
    d->findTextImpl(text, flags);
}

void MobileWebViewBackend::stopFind()
{
    Q_D(MobileWebViewBackend);
    d->stopFindImpl();
}

void MobileWebViewBackend::showFindPanel()
{
    Q_D(MobileWebViewBackend);
    d->showFindPanelImpl();
}

void MobileWebViewBackend::hideFindPanel()
{
    Q_D(MobileWebViewBackend);
    d->hideFindPanelImpl();
}

void MobileWebViewBackend::componentComplete()
{
    QQuickItem::componentComplete();
    ensureSnapshotImageProviderRegistered(qmlEngine(this));
}

void MobileWebViewBackend::geometryChange(const QRectF &newGeometry, const QRectF &oldGeometry)
{
    QQuickItem::geometryChange(newGeometry, oldGeometry);

    if (newGeometry != oldGeometry) {
        Q_D(MobileWebViewBackend);
        d->updateNativeGeometry(newGeometry);
    }
}

void MobileWebViewBackend::itemChange(ItemChange change, const ItemChangeData &value)
{
    QQuickItem::itemChange(change, value);
    Q_D(MobileWebViewBackend);

    switch (change) {
    case ItemSceneChange:
        QObject::disconnect(d->m_afterAnimatingConnection);
        if (value.window) {
            ensureSnapshotImageProviderRegistered(qmlEngine(this));

            QQuickWindow *window = value.window;
            QPointer<MobileWebViewBackend> guard(this);
            MobileWebViewBackendPrivate *backend = d;
            d->m_afterAnimatingConnection = QObject::connect(
                window,
                &QQuickWindow::afterAnimating,
                this,
                [guard, backend]() {
                    if (!guard) {
                        return;
                    }
                    backend->syncNativeGeometryFromScene();
                });

            QMetaObject::invokeMethod(this, [this, d]() {
                d->setupNativeViewImpl();
                // Trigger geometry sync now that m_nativeViewSetup is true.
                polish();
            }, Qt::QueuedConnection);
        } else {
            d->detachNativeViewFromScene();
        }
        break;

    case ItemVisibleHasChanged:
        d->updateNativeVisibility(value.boolValue);
        if (value.boolValue) {
            d->updateNativeGeometry(QRectF(0, 0, width(), height()));
        }
        break;

    case ItemParentHasChanged:
        polish();
        break;

    default:
        break;
    }
}

void MobileWebViewBackend::updatePolish()
{
    Q_D(MobileWebViewBackend);
    d->updateNativeGeometry(QRectF(0, 0, width(), height()));
}
