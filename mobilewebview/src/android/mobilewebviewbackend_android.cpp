#include "MobileWebView/mobilewebviewbackend.h"
#include "MobileWebView/mobilewebviewcapabilities.h"
#include "../common/mobilewebviewbackend_p.h"
#include "../common/inlinedownloadcodec.h"
#include "../common/inlinedownloadmessage.h"
#include "../common/origin_utils.h"
#include "../common/userscript_utils.h"
#include "../common/android_js_result.h"

#ifdef Q_OS_ANDROID

#include <QDebug>
#include <QQuickWindow>
#include <QKeyEvent>
#include <QCoreApplication>
#include <QFile>
#include <QGuiApplication>
#include <QHash>
#include <QJniObject>
#include <QJniEnvironment>
#include <QtMath>
#include <QVariantMap>
#include <QImage>
#include <QByteArray>
#include <QPointer>
#include <optional>
#include <functional>
#include <utility>

// =============================================================================
// AndroidWebViewPrivate - Android-specific implementation
// =============================================================================

class AndroidWebViewPrivate : public MobileWebViewBackendPrivate
{
public:
    explicit AndroidWebViewPrivate(MobileWebViewBackend *q);
    ~AndroidWebViewPrivate() override;
    
    // Platform-specific implementations
    bool initNativeView() override;
    void destroyNativeView() override;
    void loadUrlImpl(const QUrl &url) override;
    void loadHtmlImpl(const QString &html, const QUrl &baseUrl) override;
    void goBackImpl() override;
    void goForwardImpl() override;
    void goBackOrForwardImpl(int offset) override;
    void reloadImpl() override;
    void reloadAndBypassCacheImpl() override;
    void stopImpl() override;
    void clearHistoryImpl() override;
    void clearHttpCacheImpl(std::function<void()> completion) override;
    void deleteAllCookiesImpl(std::function<void()> completion) override;
    void clearDomStorageImpl(std::function<void()> completion) override;
    void clearSiteDataImpl(const QString &origin, std::function<void()> completion) override;
    bool clearSiteDataSupportedImpl() const override;
    void evaluateJavaScript(const QString &script) override;
    void updateNativeGeometry(const QRectF &rect) override;
    void updateNativeVisibility(bool visible) override;
    bool installBridgeImpl(const QString &ns, const QStringList &origins, 
                          const QString &invokeKey, const QString &webChannelScriptPath) override;
    void postMessageToJavaScript(const QString &json) override;
    void setupNativeViewImpl() override;
    void updateAllowedOriginsImpl(const QStringList &origins) override;
    void updateInteractionEnabled(bool enabled) override;
    void setZoomFactorImpl(qreal factor) override;
    void setHttpUserAgentImpl(const QString &userAgent) override;
    void findTextImpl(const QString &text, int flags) override;
    void stopFindImpl() override;
    bool findSupportedImpl() const override;
    bool hasNativeFindPanelImpl() const override;
    // showFindPanelImpl/hideFindPanelImpl: base-class no-ops — the QML find
    // panel is used on Android.
    bool inPageMediaPlaybackSupportedImpl() const override;
    void captureSnapshotImpl(quint64 requestId) override;
    void detachNativeViewFromSceneImpl() override;
    void startDownloadImpl(quint64 downloadId, const QUrl &url,
                           const QString &destinationPath) override;
    void cancelDownloadImpl(quint64 downloadId) override;
    void pauseDownloadImpl(quint64 downloadId) override;
    void resumeDownloadImpl(quint64 downloadId) override;

    // JNI helper methods
    void cleanupJni();
    jobject createWebView();
    void destroyWebView();
    void callSimpleVoidMethod(jmethodID method);
    bool callLongVoidMethod(jmethodID method, jlong requestId);
    quint64 storeClearCompletion(std::function<void()> completion);
    void completeClearRequest(quint64 requestId);
    void finishAllPendingClears();
    bool clearJniExceptionIfAny(QJniEnvironment &env);
    jobjectArray createJavaStringArray(QJniEnvironment &env, const QStringList &values);
    
    // Callback handlers (called from JNI)
    void onWebMessageReceived(const QString &message, const QString &origin, bool isMainFrame);
    void onLinkLongPressedPx(const QUrl &linkUrl, const QUrl &imageUrl, QPointF posPx);
    void requestUrlDownloadImpl(const QUrl &url, const QString &suggestedFileName) override;
    void onNavigationStarted(const QString &url);
    void onNavigationFinished(const QString &url);
    void onNavigationFailed();
    void onTitleChanged(const QString &title);
    void onNavigationStateChanged(bool canGoBack, bool canGoForward);
    void onHistoryChanged(const QVariantList &historyItems, int currentHistoryIndex);
    void onNewWindowRequested(const QString &url, bool userInitiated);
    void onJavaScriptResult(const QString &result, const QString &error);
    void onLoadProgressChanged(int progress);
    void onFaviconReceived(const QString &faviconUrl);
    void onFindResultChanged(int activeMatchIndex, int matchCount);
    void onClearCompleted(quint64 requestId);
    
private:
    jobject m_webViewObject = nullptr;  // Global reference to Java MobileWebView
    jclass m_webViewClass = nullptr;
    
    // JNI method IDs (cached for performance)
    jmethodID m_loadUrlMethod = nullptr;
    jmethodID m_loadHtmlMethod = nullptr;
    jmethodID m_goBackMethod = nullptr;
    jmethodID m_goForwardMethod = nullptr;
    jmethodID m_goBackOrForwardMethod = nullptr;
    jmethodID m_reloadMethod = nullptr;
    jmethodID m_stopMethod = nullptr;
    jmethodID m_evaluateJavaScriptMethod = nullptr;
    jmethodID m_setGeometryMethod = nullptr;
    jmethodID m_setVisibleMethod = nullptr;
    jmethodID m_destroyMethod = nullptr;
    jmethodID m_updateAllowedOriginsMethod = nullptr;
    jmethodID m_setInteractionEnabledMethod = nullptr;
    jmethodID m_clearHistoryMethod = nullptr;
    jmethodID m_clearHttpCacheMethod = nullptr;
    jmethodID m_deleteAllCookiesMethod = nullptr;
    jmethodID m_clearDomStorageMethod = nullptr;
    jmethodID m_clearSiteDataMethod = nullptr;
    jmethodID m_reloadAndBypassCacheMethod = nullptr;
    jmethodID m_setZoomFactorMethod = nullptr;
    jmethodID m_setHttpUserAgentMethod = nullptr;
    jmethodID m_findTextMethod = nullptr;
    jmethodID m_stopFindMethod = nullptr;
    jmethodID m_captureSnapshotForFreezeMethod = nullptr;
    jmethodID m_startDownloadMethod = nullptr;
    jmethodID m_probeDownloadMethod = nullptr;
    jmethodID m_cancelDownloadMethod = nullptr;
    jmethodID m_pauseDownloadMethod = nullptr;
    jmethodID m_resumeDownloadMethod = nullptr;

    bool m_jniInitialized = false;
    QMutex m_jniMutex;  // Protect JNI calls

    std::optional<QRect> m_lastGeometry;
    quint64 m_nextClearRequestId = 0;
    QHash<quint64, std::function<void()>> m_pendingClearCompletions;
};

AndroidWebViewPrivate::AndroidWebViewPrivate(MobileWebViewBackend *q)
    : MobileWebViewBackendPrivate(q)
{
}

AndroidWebViewPrivate::~AndroidWebViewPrivate()
{
    cleanupJni();
}

void AndroidWebViewPrivate::destroyNativeView()
{
    if (m_webViewObject) {
        destroyWebView();
    }
    finishAllPendingClears();
}

bool AndroidWebViewPrivate::initNativeView()
{
    QJniEnvironment env;
    if (!env.isValid()) {
        qWarning() << "AndroidWebViewPrivate: Invalid JNI environment";
        return false;
    }

    if (m_jniInitialized && m_webViewClass) {
        m_webViewObject = createWebView();
        if (!m_webViewObject) {
            qWarning() << "AndroidWebViewPrivate: Failed to recreate WebView object";
            return false;
        }
        return true;
    }

    // Load MobileWebView class
    jclass localClass = env->FindClass("org/mobilewebview/MobileWebView");
    if (!localClass) {
        qWarning() << "AndroidWebViewPrivate: Failed to find MobileWebView class";
        env->ExceptionDescribe();
        env->ExceptionClear();
        return false;
    }

    m_webViewClass = static_cast<jclass>(env->NewGlobalRef(localClass));
    env->DeleteLocalRef(localClass);

    if (!m_webViewClass) {
        qWarning() << "AndroidWebViewPrivate: Failed to create global ref for MobileWebView class";
        return false;
    }

    // Create WebView object
    m_webViewObject = createWebView();
    if (!m_webViewObject) {
        qWarning() << "AndroidWebViewPrivate: Failed to create WebView object";
        return false;
    }

    // Cache method IDs
    m_loadUrlMethod = env->GetMethodID(m_webViewClass, "loadUrl", "(Ljava/lang/String;)V");
    m_loadHtmlMethod = env->GetMethodID(m_webViewClass, "loadHtml", "(Ljava/lang/String;Ljava/lang/String;)V");
    m_goBackMethod = env->GetMethodID(m_webViewClass, "goBack", "()V");
    m_goForwardMethod = env->GetMethodID(m_webViewClass, "goForward", "()V");
    m_goBackOrForwardMethod = env->GetMethodID(m_webViewClass, "goBackOrForward", "(I)V");
    m_reloadMethod = env->GetMethodID(m_webViewClass, "reload", "()V");
    m_stopMethod = env->GetMethodID(m_webViewClass, "stop", "()V");
    m_evaluateJavaScriptMethod = env->GetMethodID(m_webViewClass, "evaluateJavaScript", "(Ljava/lang/String;)V");
    m_setGeometryMethod = env->GetMethodID(m_webViewClass, "setGeometry", "(IIII)V");
    m_setVisibleMethod = env->GetMethodID(m_webViewClass, "setVisible", "(Z)V");
    m_destroyMethod = env->GetMethodID(m_webViewClass, "destroy", "()V");
    m_updateAllowedOriginsMethod = env->GetMethodID(m_webViewClass, "updateAllowedOrigins", "([Ljava/lang/String;)V");
    m_setInteractionEnabledMethod = env->GetMethodID(m_webViewClass, "setInteractionEnabled", "(Z)V");
    m_clearHistoryMethod = env->GetMethodID(m_webViewClass, "clearHistory", "()V");
    m_clearHttpCacheMethod = env->GetMethodID(m_webViewClass, "clearHttpCache", "(J)V");
    m_deleteAllCookiesMethod = env->GetMethodID(m_webViewClass, "deleteAllCookies", "(J)V");
    m_clearDomStorageMethod = env->GetMethodID(m_webViewClass, "clearDomStorage", "(J)V");
    m_clearSiteDataMethod = env->GetMethodID(m_webViewClass, "clearSiteData", "(Ljava/lang/String;J)V");
    m_reloadAndBypassCacheMethod = env->GetMethodID(m_webViewClass, "reloadAndBypassCache", "()V");
    m_setZoomFactorMethod = env->GetMethodID(m_webViewClass, "setZoomFactor", "(F)V");
    m_setHttpUserAgentMethod = env->GetMethodID(m_webViewClass, "setHttpUserAgent", "(Ljava/lang/String;)V");
    m_findTextMethod = env->GetMethodID(m_webViewClass, "findText", "(Ljava/lang/String;I)V");
    m_stopFindMethod = env->GetMethodID(m_webViewClass, "stopFind", "()V");
    m_captureSnapshotForFreezeMethod = env->GetMethodID(m_webViewClass, "captureSnapshotForFreeze", "(J)V");
    m_startDownloadMethod = env->GetMethodID(m_webViewClass, "startDownload",
        "(JLjava/lang/String;Ljava/lang/String;)V");
    m_probeDownloadMethod = env->GetMethodID(m_webViewClass, "probeDownload",
        "(Ljava/lang/String;)V");
    m_cancelDownloadMethod = env->GetMethodID(m_webViewClass, "cancelDownload", "(J)V");
    m_pauseDownloadMethod = env->GetMethodID(m_webViewClass, "pauseDownload", "(J)V");
    m_resumeDownloadMethod = env->GetMethodID(m_webViewClass, "resumeDownload", "(J)V");

    m_jniInitialized = true;

    m_viewStoreOffTheRecord = m_offTheRecord;
    m_viewStoreName = m_storageName;

    // Apply before any load that setupNativeViewImpl may kick off on first create.
    setHttpUserAgentImpl(m_httpUserAgent);

    return true;
}

void AndroidWebViewPrivate::cleanupJni()
{
    QMutexLocker locker(&m_jniMutex);
    
    if (m_webViewObject) {
        destroyWebView();
    }

    QJniEnvironment env;
    if (env.isValid()) {
        if (m_webViewClass) {
            env->DeleteGlobalRef(m_webViewClass);
            m_webViewClass = nullptr;
        }
    }

    m_jniInitialized = false;
    locker.unlock();
    finishAllPendingClears();
}

jobject AndroidWebViewPrivate::createWebView()
{
    QJniEnvironment env;
    if (!env.isValid()) {
        return nullptr;
    }

    // Get Android context
    QJniObject activity = QNativeInterface::QAndroidApplication::context();
    if (!activity.isValid()) {
        qWarning() << "AndroidWebViewPrivate: Failed to get Android context";
        return nullptr;
    }

    // Prefer android.R.id.content for stable content coordinates/insets.
    constexpr jint kAndroidContentViewId = 0x01020002; // android.R.id.content
    QJniObject contentView = activity.callObjectMethod("findViewById",
        "(I)Landroid/view/View;", kAndroidContentViewId);
    jobject rootView = contentView.object();
    if (!rootView) {
        QJniObject window = activity.callObjectMethod("getWindow", "()Landroid/view/Window;");
        if (window.isValid()) {
            QJniObject decorView = window.callObjectMethod("getDecorView", "()Landroid/view/View;");
            rootView = decorView.object();
        }
    }

    // Create MobileWebView instance
    jmethodID constructor = env->GetMethodID(m_webViewClass, "<init>",
        "(Landroid/content/Context;JLandroid/view/View;Ljava/lang/String;Z)V");
    
    if (!constructor) {
        qWarning() << "AndroidWebViewPrivate: Failed to find constructor";
        env->ExceptionDescribe();
        env->ExceptionClear();
        return nullptr;
    }

    const jstring jStorageName = env->NewStringUTF(m_storageName.toUtf8().constData());
    const jboolean jOffTheRecord = m_offTheRecord ? JNI_TRUE : JNI_FALSE;

    jobject localObj = env->NewObject(m_webViewClass, constructor, 
                                      activity.object(), 
                                      reinterpret_cast<jlong>(this),
                                      rootView,
                                      jStorageName,
                                      jOffTheRecord);
    
    env->DeleteLocalRef(jStorageName);
    
    if (!localObj) {
        qWarning() << "AndroidWebViewPrivate: Failed to create MobileWebView instance";
        env->ExceptionDescribe();
        env->ExceptionClear();
        return nullptr;
    }

    // Create global reference
    jobject globalObj = env->NewGlobalRef(localObj);
    env->DeleteLocalRef(localObj);

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }

    return globalObj;
}

void AndroidWebViewPrivate::destroyWebView()
{
    QJniEnvironment env;
    if (!env.isValid() || !m_webViewObject) {
        return;
    }

    if (m_destroyMethod) {
        env->CallVoidMethod(m_webViewObject, m_destroyMethod);
    }

    env->DeleteGlobalRef(m_webViewObject);
    m_webViewObject = nullptr;
    m_lastGeometry.reset();

    clearJniExceptionIfAny(env);
}

void AndroidWebViewPrivate::callSimpleVoidMethod(jmethodID method)
{
    QMutexLocker locker(&m_jniMutex);
    if (!m_jniInitialized) {
        return;
    }

    QJniEnvironment env;
    if (!env.isValid() || !m_webViewObject || !method) {
        return;
    }

    env->CallVoidMethod(m_webViewObject, method);
    clearJniExceptionIfAny(env);
}

bool AndroidWebViewPrivate::callLongVoidMethod(jmethodID method, jlong requestId)
{
    QMutexLocker locker(&m_jniMutex);
    if (!m_jniInitialized) {
        return false;
    }

    QJniEnvironment env;
    if (!env.isValid() || !m_webViewObject || !method) {
        return false;
    }

    env->CallVoidMethod(m_webViewObject, method, requestId);
    return !clearJniExceptionIfAny(env);
}

quint64 AndroidWebViewPrivate::storeClearCompletion(std::function<void()> completion)
{
    const quint64 requestId = ++m_nextClearRequestId;
    if (completion) {
        m_pendingClearCompletions.insert(requestId, std::move(completion));
    }
    return requestId;
}

void AndroidWebViewPrivate::completeClearRequest(quint64 requestId)
{
    const auto completion = m_pendingClearCompletions.take(requestId);
    if (completion) {
        completion();
    }
}

void AndroidWebViewPrivate::finishAllPendingClears()
{
    QHash<quint64, std::function<void()>> pending = std::move(m_pendingClearCompletions);
    m_pendingClearCompletions.clear();
    for (auto &completion : pending) {
        if (completion) {
            completion();
        }
    }
}

bool AndroidWebViewPrivate::clearJniExceptionIfAny(QJniEnvironment &env)
{
    if (!env->ExceptionCheck()) {
        return false;
    }

    env->ExceptionDescribe();
    env->ExceptionClear();
    return true;
}

jobjectArray AndroidWebViewPrivate::createJavaStringArray(QJniEnvironment &env, const QStringList &values)
{
    jclass stringClass = env->FindClass("java/lang/String");
    if (!stringClass) {
        clearJniExceptionIfAny(env);
        return nullptr;
    }

    jobjectArray array = env->NewObjectArray(values.size(), stringClass, nullptr);
    env->DeleteLocalRef(stringClass);
    if (!array) {
        clearJniExceptionIfAny(env);
        return nullptr;
    }

    for (int i = 0; i < values.size(); ++i) {
        jstring jstr = env->NewStringUTF(values[i].toUtf8().constData());
        if (!jstr) {
            env->DeleteLocalRef(array);
            clearJniExceptionIfAny(env);
            return nullptr;
        }
        env->SetObjectArrayElement(array, i, jstr);
        env->DeleteLocalRef(jstr);
        if (clearJniExceptionIfAny(env)) {
            env->DeleteLocalRef(array);
            return nullptr;
        }
    }

    return array;
}

// loadFileUrlImpl is deliberately not overridden here: Android WebView loads a
// top-level file:// through this very path (MobileWebView.setupWebView enables
// setAllowFileAccess, and WebViewUrlPolicy lists "file" as supported), and it
// has no read-access-directory concept to honour. The base default forwards to
// loadUrlImpl, which is exactly what this platform needs.
void AndroidWebViewPrivate::loadUrlImpl(const QUrl &url)
{
    QMutexLocker locker(&m_jniMutex);
    
    if (!m_jniInitialized) {
        qWarning() << "AndroidWebViewPrivate: JNI not initialized";
        return;
    }
    
    QJniEnvironment env;
    if (!env.isValid() || !m_webViewObject || !m_loadUrlMethod) {
        return;
    }

    jstring jUrl = env->NewStringUTF(url.toString().toUtf8().constData());
    env->CallVoidMethod(m_webViewObject, m_loadUrlMethod, jUrl);
    env->DeleteLocalRef(jUrl);

    clearJniExceptionIfAny(env);
}

void AndroidWebViewPrivate::loadHtmlImpl(const QString &html, const QUrl &baseUrl)
{
    QMutexLocker locker(&m_jniMutex);
    
    if (!m_jniInitialized) {
        qWarning() << "AndroidWebViewPrivate: JNI not initialized";
        return;
    }
    
    QJniEnvironment env;
    if (!env.isValid() || !m_webViewObject || !m_loadHtmlMethod) {
        return;
    }

    jstring jHtml = env->NewStringUTF(html.toUtf8().constData());
    jstring jBaseUrl = env->NewStringUTF(baseUrl.toString().toUtf8().constData());
    env->CallVoidMethod(m_webViewObject, m_loadHtmlMethod, jHtml, jBaseUrl);
    env->DeleteLocalRef(jHtml);
    env->DeleteLocalRef(jBaseUrl);

    clearJniExceptionIfAny(env);
}

void AndroidWebViewPrivate::goBackImpl()
{
    callSimpleVoidMethod(m_goBackMethod);
}

void AndroidWebViewPrivate::goForwardImpl()
{
    callSimpleVoidMethod(m_goForwardMethod);
}

void AndroidWebViewPrivate::goBackOrForwardImpl(int offset)
{
    QMutexLocker locker(&m_jniMutex);

    if (!m_jniInitialized) {
        qWarning() << "AndroidWebViewPrivate: JNI not initialized";
        return;
    }

    QJniEnvironment env;
    if (!env.isValid() || !m_webViewObject || !m_goBackOrForwardMethod) {
        return;
    }

    env->CallVoidMethod(m_webViewObject, m_goBackOrForwardMethod, static_cast<jint>(offset));
    clearJniExceptionIfAny(env);
}

void AndroidWebViewPrivate::reloadImpl()
{
    callSimpleVoidMethod(m_reloadMethod);
}

void AndroidWebViewPrivate::reloadAndBypassCacheImpl()
{
    callSimpleVoidMethod(m_reloadAndBypassCacheMethod);
}

void AndroidWebViewPrivate::stopImpl()
{
    callSimpleVoidMethod(m_stopMethod);
}

void AndroidWebViewPrivate::clearHistoryImpl()
{
    callSimpleVoidMethod(m_clearHistoryMethod);
}

void AndroidWebViewPrivate::clearHttpCacheImpl(std::function<void()> completion)
{
    const quint64 requestId = storeClearCompletion(std::move(completion));
    if (!callLongVoidMethod(m_clearHttpCacheMethod, static_cast<jlong>(requestId))) {
        completeClearRequest(requestId);
    }
}

void AndroidWebViewPrivate::deleteAllCookiesImpl(std::function<void()> completion)
{
    const quint64 requestId = storeClearCompletion(std::move(completion));
    if (!callLongVoidMethod(m_deleteAllCookiesMethod, static_cast<jlong>(requestId))) {
        completeClearRequest(requestId);
    }
}

void AndroidWebViewPrivate::clearDomStorageImpl(std::function<void()> completion)
{
    const quint64 requestId = storeClearCompletion(std::move(completion));
    if (!callLongVoidMethod(m_clearDomStorageMethod, static_cast<jlong>(requestId))) {
        completeClearRequest(requestId);
    }
}

void AndroidWebViewPrivate::clearSiteDataImpl(const QString &origin, std::function<void()> completion)
{
    const quint64 requestId = storeClearCompletion(std::move(completion));

    QMutexLocker locker(&m_jniMutex);

    if (!m_jniInitialized) {
        qWarning() << "AndroidWebViewPrivate: JNI not initialized";
        locker.unlock();
        completeClearRequest(requestId);
        return;
    }

    QJniEnvironment env;
    if (!env.isValid() || !m_webViewObject || !m_clearSiteDataMethod) {
        locker.unlock();
        completeClearRequest(requestId);
        return;
    }

    jstring jOrigin = env->NewStringUTF(origin.toUtf8().constData());
    env->CallVoidMethod(m_webViewObject, m_clearSiteDataMethod, jOrigin, static_cast<jlong>(requestId));
    env->DeleteLocalRef(jOrigin);

    if (clearJniExceptionIfAny(env)) {
        locker.unlock();
        completeClearRequest(requestId);
    }
}

bool AndroidWebViewPrivate::clearSiteDataSupportedImpl() const
{
    return MobileWebViewCapabilities::isClearSiteDataSupported();
}

void AndroidWebViewPrivate::evaluateJavaScript(const QString &script)
{
    QMutexLocker locker(&m_jniMutex);
    
    if (!m_jniInitialized) {
        qWarning() << "AndroidWebViewPrivate: JNI not initialized";
        return;
    }
    
    QJniEnvironment env;
    if (!env.isValid() || !m_webViewObject || !m_evaluateJavaScriptMethod) {
        return;
    }

    jstring jScript = env->NewStringUTF(script.toUtf8().constData());
    env->CallVoidMethod(m_webViewObject, m_evaluateJavaScriptMethod, jScript);
    env->DeleteLocalRef(jScript);

    clearJniExceptionIfAny(env);
}

void AndroidWebViewPrivate::updateNativeGeometry(const QRectF &rect)
{
    QMutexLocker locker(&m_jniMutex);
    
    if (!m_jniInitialized || !m_nativeViewSetup) {
        return;
    }

    QQuickWindow *win = q_ptr->window();
    if (!win) {
        return;
    }

    QPointF scenePos = q_ptr->mapToScene(QPointF(0, 0));
    qreal itemWidth = rect.width();
    qreal itemHeight = rect.height();

    if (itemWidth <= 0 || itemHeight <= 0) {
        return;
    }
    
    QJniEnvironment env;
    if (!env.isValid() || !m_webViewObject || !m_setGeometryMethod) {
        return;
    }

    // Qt Quick geometry is in device-independent units; Android View expects physical pixels.
    const qreal dpr = win->devicePixelRatio();
    const jint xPx = static_cast<jint>(qRound(scenePos.x() * dpr));
    const jint yPx = static_cast<jint>(qRound(scenePos.y() * dpr));
    const jint wPx = static_cast<jint>(qRound(itemWidth * dpr));
    const jint hPx = static_cast<jint>(qRound(itemHeight * dpr));

    const QRect geometry(xPx, yPx, wPx, hPx);
    if (m_lastGeometry == geometry) {
        return;
    }

    env->CallVoidMethod(m_webViewObject, m_setGeometryMethod, xPx, yPx, wPx, hPx);

    m_lastGeometry = geometry;

    clearJniExceptionIfAny(env);
}

void AndroidWebViewPrivate::updateNativeVisibility(bool visible)
{
    QMutexLocker locker(&m_jniMutex);
    
    if (!m_jniInitialized) {
        return;
    }
    
    QJniEnvironment env;
    if (!env.isValid() || !m_webViewObject || !m_setVisibleMethod) {
        return;
    }

    const bool shouldBeVisible = shouldShowNativeWebView(visible);
    env->CallVoidMethod(m_webViewObject, m_setVisibleMethod, shouldBeVisible ? JNI_TRUE : JNI_FALSE);

    clearJniExceptionIfAny(env);
}

void AndroidWebViewPrivate::detachNativeViewFromSceneImpl()
{
    m_lastGeometry.reset();
}

bool AndroidWebViewPrivate::installBridgeImpl(const QString &ns, const QStringList &origins, 
                                               const QString &invokeKey, const QString &)
{
    if (!m_jniInitialized) {
        qWarning() << "AndroidWebViewPrivate: JNI not initialized";
        return false;
    }

    // Load bootstrap scripts from Qt resources
    QFile bootstrapPageFile(QStringLiteral(":/CustomWebView/js/bootstrap_page.js"));
    QString bootstrapPageScript;
    if (bootstrapPageFile.open(QIODevice::ReadOnly | QIODevice::Text)) {
        bootstrapPageScript = QString::fromUtf8(bootstrapPageFile.readAll());
        bootstrapPageScript.replace(QStringLiteral("%NS%"), ns);
        bootstrapPageFile.close();
    } else {
        qWarning() << "AndroidWebViewPrivate: Failed to load bootstrap_page.js";
    }

    QFile bootstrapBridgeFile(QStringLiteral(":/CustomWebView/js/bootstrap_bridge_android.js"));
    QString bootstrapBridgeScript;
    if (bootstrapBridgeFile.open(QIODevice::ReadOnly | QIODevice::Text)) {
        bootstrapBridgeScript = QString::fromUtf8(bootstrapBridgeFile.readAll());
        bootstrapBridgeScript.replace(QStringLiteral("%INVOKE_KEY%"), invokeKey);
        bootstrapBridgeFile.close();
    } else {
        qWarning() << "AndroidWebViewPrivate: Failed to load bootstrap_bridge_android.js";
    }

    QFile inlineDownloadFile(QStringLiteral(":/CustomWebView/js/inline_download_interceptor.js"));
    QString inlineDownloadScript;
    if (inlineDownloadFile.open(QIODevice::ReadOnly | QIODevice::Text)) {
        inlineDownloadScript = QString::fromUtf8(inlineDownloadFile.readAll());
        inlineDownloadScript.replace(
            QStringLiteral("%MAX_INLINE_BYTES%"),
            QString::number(MobileWebView::InlineDownloadCodec::kMaxDecodedBytes));
        inlineDownloadFile.close();
    } else {
        qWarning() << "AndroidWebViewPrivate: Failed to load inline_download_interceptor.js";
    }

    // Load user scripts content
    QStringList scriptContents;
    for (const QVariant &scriptVariant : m_userScripts) {
        const QString scriptPath = extractUserScriptPath(scriptVariant);

        if (!scriptPath.isEmpty()) {
            QFile file(scriptPath);
            if (file.open(QIODevice::ReadOnly | QIODevice::Text)) {
                scriptContents.append(QString::fromUtf8(file.readAll()));
                file.close();
            } else {
                qWarning() << "AndroidWebViewPrivate: Failed to read user script"
                           << "path=" << scriptPath
                           << "error=" << file.errorString();
            }
        }
    }

    qInfo() << "AndroidWebViewPrivate: Installing bridge"
            << "namespace=" << ns
            << "origins=" << origins
            << "userScriptsLoaded=" << scriptContents.size();

    // Call Java method to install bridge
    QJniEnvironment env;
    if (!env.isValid() || !m_webViewObject) {
        qWarning() << "AndroidWebViewPrivate: Invalid JNI environment or webview object";
        return false;
    }

    if (origins.isEmpty()) {
        qWarning() << "AndroidWebViewPrivate: allowed origins list is empty;"
                   << "JS->native messages will be rejected";
    }

    jobjectArray jAllowedOrigins = createJavaStringArray(env, origins);
    if (!jAllowedOrigins) {
        qWarning() << "AndroidWebViewPrivate: Failed to convert allowed origins to Java array";
        return false;
    }

    jobjectArray jUserScripts = createJavaStringArray(env, scriptContents);
    if (!jUserScripts) {
        qWarning() << "AndroidWebViewPrivate: Failed to convert user scripts to Java array";
        env->DeleteLocalRef(jAllowedOrigins);
        return false;
    }

    jstring jNamespace = env->NewStringUTF(ns.toUtf8().constData());
    jstring jInvokeKey = env->NewStringUTF(invokeKey.toUtf8().constData());
    jstring jBootstrapPage = env->NewStringUTF(bootstrapPageScript.toUtf8().constData());
    jstring jBootstrapBridge = env->NewStringUTF(bootstrapBridgeScript.toUtf8().constData());
    jstring jInlineDownload = env->NewStringUTF(inlineDownloadScript.toUtf8().constData());

    jmethodID installMethod = env->GetMethodID(m_webViewClass, "installMessageBridge",
        "(Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;"
        "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    
    bool success = false;
    if (installMethod) {
        env->CallVoidMethod(m_webViewObject, installMethod, jNamespace, 
                           jAllowedOrigins, jInvokeKey, jUserScripts,
                           jBootstrapPage, jBootstrapBridge, jInlineDownload);
        success = true;
    }

    env->DeleteLocalRef(jNamespace);
    env->DeleteLocalRef(jInvokeKey);
    env->DeleteLocalRef(jBootstrapPage);
    env->DeleteLocalRef(jBootstrapBridge);
    env->DeleteLocalRef(jInlineDownload);
    env->DeleteLocalRef(jAllowedOrigins);
    env->DeleteLocalRef(jUserScripts);

    if (clearJniExceptionIfAny(env)) {
        return false;
    }

    return success;
}

void AndroidWebViewPrivate::postMessageToJavaScript(const QString &json)
{
    if (!m_jniInitialized) {
        qWarning() << "AndroidWebViewPrivate: JNI not initialized";
        return;
    }

    QJniEnvironment env;
    if (!env.isValid() || !m_webViewObject) {
        return;
    }

    jstring jJson = env->NewStringUTF(json.toUtf8().constData());
    jmethodID method = env->GetMethodID(m_webViewClass, "postMessageToJavaScript",
        "(Ljava/lang/String;)V");
    
    if (method) {
        env->CallVoidMethod(m_webViewObject, method, jJson);
    }

    env->DeleteLocalRef(jJson);

    clearJniExceptionIfAny(env);
}

void AndroidWebViewPrivate::setupNativeViewImpl()
{
    const bool createdNow = !m_jniInitialized;
    if (!m_jniInitialized && !initNativeView()) {
        qWarning() << "AndroidWebViewPrivate::setupNativeViewImpl: initNativeView failed";
        return;
    }

    QQuickWindow *win = q_ptr->window();
    if (!win) {
        qWarning() << "AndroidWebViewPrivate::setupNativeViewImpl: no window";
        return;
    }

    m_nativeViewSetup = true;
    updateNativeVisibility(q_ptr->isVisible());
    updateNativeGeometry(QRectF(0, 0, q_ptr->width(), q_ptr->height()));

    if (createdNow) {
        ensureBridgeInstalled();
        if (m_hasLastHtml) {
            loadHtmlImpl(m_lastHtml, m_lastHtmlBaseUrl);
        } else if (m_hasLastFileUrl) {
            loadFileUrlImpl(m_lastFileUrl, m_lastFileReadAccessUrl);
        } else if (m_url.isValid() && !m_url.isEmpty()) {
            loadUrlImpl(m_url);
        }
    }
}

void AndroidWebViewPrivate::updateAllowedOriginsImpl(const QStringList &origins)
{
    QMutexLocker locker(&m_jniMutex);

    if (!m_jniInitialized) {
        return;
    }

    QJniEnvironment env;
    if (!env.isValid() || !m_webViewObject || !m_updateAllowedOriginsMethod) {
        return;
    }

    jobjectArray jOrigins = createJavaStringArray(env, origins);
    if (!jOrigins) {
        return;
    }

    env->CallVoidMethod(m_webViewObject, m_updateAllowedOriginsMethod, jOrigins);
    env->DeleteLocalRef(jOrigins);
    clearJniExceptionIfAny(env);
}

void AndroidWebViewPrivate::updateInteractionEnabled(bool enabled)
{
    QMutexLocker locker(&m_jniMutex);

    if (!m_jniInitialized) {
        return;
    }

    QJniEnvironment env;
    if (!env.isValid() || !m_webViewObject || !m_setInteractionEnabledMethod) {
        return;
    }

    env->CallVoidMethod(m_webViewObject, m_setInteractionEnabledMethod,
                        enabled ? JNI_TRUE : JNI_FALSE);
    clearJniExceptionIfAny(env);
}

void AndroidWebViewPrivate::setZoomFactorImpl(qreal factor)
{
    QMutexLocker locker(&m_jniMutex);

    if (!m_jniInitialized) {
        return;
    }

    QJniEnvironment env;
    if (!env.isValid() || !m_webViewObject || !m_setZoomFactorMethod) {
        return;
    }

    env->CallVoidMethod(m_webViewObject, m_setZoomFactorMethod,
                        static_cast<jfloat>(factor));
    clearJniExceptionIfAny(env);
}

void AndroidWebViewPrivate::setHttpUserAgentImpl(const QString &userAgent)
{
    QMutexLocker locker(&m_jniMutex);

    if (!m_jniInitialized) {
        return;
    }

    QJniEnvironment env;
    if (!env.isValid() || !m_webViewObject || !m_setHttpUserAgentMethod) {
        return;
    }

    const jstring jUserAgent = userAgent.isEmpty()
        ? nullptr
        : env->NewStringUTF(userAgent.toUtf8().constData());
    env->CallVoidMethod(m_webViewObject, m_setHttpUserAgentMethod, jUserAgent);
    if (jUserAgent) {
        env->DeleteLocalRef(jUserAgent);
    }
    clearJniExceptionIfAny(env);
}

void AndroidWebViewPrivate::requestUrlDownloadImpl(const QUrl &url,
                                                   const QString &suggestedFileName)
{
    // Named: detect immediately. Unnamed: HEAD-probe first (JNI miss → plain path).
    if (!suggestedFileName.isEmpty()) {
        MobileWebViewBackendPrivate::requestUrlDownloadImpl(url, suggestedFileName);
        return;
    }

    {
        QMutexLocker locker(&m_jniMutex);
        if (m_jniInitialized && m_webViewObject && m_probeDownloadMethod) {
            QJniEnvironment env;
            if (env.isValid()) {
                jstring jUrl = env->NewStringUTF(url.toString().toUtf8().constData());
                env->CallVoidMethod(m_webViewObject, m_probeDownloadMethod, jUrl);
                if (jUrl)
                    env->DeleteLocalRef(jUrl);
                clearJniExceptionIfAny(env);
                return;
            }
        }
    }

    MobileWebViewBackendPrivate::requestUrlDownloadImpl(url, suggestedFileName);
}

void AndroidWebViewPrivate::startDownloadImpl(quint64 downloadId, const QUrl &url,
                                              const QString &destinationPath)
{
    QMutexLocker locker(&m_jniMutex);

    if (!m_jniInitialized || !m_webViewObject || !m_startDownloadMethod) {
        QMetaObject::invokeMethod(q_ptr, [this, downloadId]() {
            onDownloadFinished(downloadId, false, QStringLiteral("Download unavailable"));
        }, Qt::QueuedConnection);
        return;
    }

    QJniEnvironment env;
    if (!env.isValid()) {
        QMetaObject::invokeMethod(q_ptr, [this, downloadId]() {
            onDownloadFinished(downloadId, false, QStringLiteral("Download unavailable"));
        }, Qt::QueuedConnection);
        return;
    }

    jstring jUrl = env->NewStringUTF(url.toString().toUtf8().constData());
    jstring jDest = env->NewStringUTF(destinationPath.toUtf8().constData());
    env->CallVoidMethod(m_webViewObject, m_startDownloadMethod,
                        static_cast<jlong>(downloadId), jUrl, jDest);
    if (jUrl)
        env->DeleteLocalRef(jUrl);
    if (jDest)
        env->DeleteLocalRef(jDest);
    clearJniExceptionIfAny(env);
}

void AndroidWebViewPrivate::cancelDownloadImpl(quint64 downloadId)
{
    QMutexLocker locker(&m_jniMutex);

    if (!m_jniInitialized || !m_webViewObject || !m_cancelDownloadMethod)
        return;

    QJniEnvironment env;
    if (!env.isValid())
        return;

    env->CallVoidMethod(m_webViewObject, m_cancelDownloadMethod, static_cast<jlong>(downloadId));
    clearJniExceptionIfAny(env);
}

void AndroidWebViewPrivate::pauseDownloadImpl(quint64 downloadId)
{
    QMutexLocker locker(&m_jniMutex);

    if (!m_jniInitialized || !m_webViewObject || !m_pauseDownloadMethod)
        return;

    QJniEnvironment env;
    if (!env.isValid())
        return;

    env->CallVoidMethod(m_webViewObject, m_pauseDownloadMethod, static_cast<jlong>(downloadId));
    clearJniExceptionIfAny(env);
}

void AndroidWebViewPrivate::resumeDownloadImpl(quint64 downloadId)
{
    QMutexLocker locker(&m_jniMutex);

    if (!m_jniInitialized || !m_webViewObject || !m_resumeDownloadMethod) {
        QMetaObject::invokeMethod(q_ptr, [this, downloadId]() {
            onDownloadFinished(downloadId, false, QStringLiteral("Resume data unavailable"));
        }, Qt::QueuedConnection);
        return;
    }

    QJniEnvironment env;
    if (!env.isValid()) {
        QMetaObject::invokeMethod(q_ptr, [this, downloadId]() {
            onDownloadFinished(downloadId, false, QStringLiteral("Resume data unavailable"));
        }, Qt::QueuedConnection);
        return;
    }

    env->CallVoidMethod(m_webViewObject, m_resumeDownloadMethod, static_cast<jlong>(downloadId));
    clearJniExceptionIfAny(env);
}

void AndroidWebViewPrivate::findTextImpl(const QString &text, int flags)
{
    QMutexLocker locker(&m_jniMutex);

    if (!m_jniInitialized) {
        return;
    }

    QJniEnvironment env;
    if (!env.isValid() || !m_webViewObject || !m_findTextMethod) {
        return;
    }

    jstring jText = env->NewStringUTF(text.toUtf8().constData());
    env->CallVoidMethod(m_webViewObject, m_findTextMethod, jText, static_cast<jint>(flags));
    env->DeleteLocalRef(jText);
    clearJniExceptionIfAny(env);
}

void AndroidWebViewPrivate::stopFindImpl()
{
    callSimpleVoidMethod(m_stopFindMethod);
}

bool AndroidWebViewPrivate::findSupportedImpl() const
{
    return MobileWebViewCapabilities::isFindSupported();
}

bool AndroidWebViewPrivate::hasNativeFindPanelImpl() const
{
    return MobileWebViewCapabilities::hasNativeFindPanel();
}

bool AndroidWebViewPrivate::inPageMediaPlaybackSupportedImpl() const
{
    return MobileWebViewCapabilities::isInPageMediaPlaybackSupported();
}

void AndroidWebViewPrivate::captureSnapshotImpl(quint64 requestId)
{
    QMutexLocker locker(&m_jniMutex);

    QPointer<MobileWebViewBackend> guard(q_ptr);

    if (!m_jniInitialized || !m_webViewObject || !m_captureSnapshotForFreezeMethod) {
        QMetaObject::invokeMethod(q_ptr, [guard, this, requestId]() {
            if (!guard) {
                return;
            }
            notifySnapshotReady(requestId, QImage());
        }, Qt::QueuedConnection);
        return;
    }

    QJniEnvironment env;
    if (!env.isValid()) {
        QMetaObject::invokeMethod(q_ptr, [guard, this, requestId]() {
            if (!guard) {
                return;
            }
            notifySnapshotReady(requestId, QImage());
        }, Qt::QueuedConnection);
        return;
    }

    env->CallVoidMethod(m_webViewObject, m_captureSnapshotForFreezeMethod, static_cast<jlong>(requestId));
    clearJniExceptionIfAny(env);
}

void AndroidWebViewPrivate::onFindResultChanged(int activeMatchIndex, int matchCount)
{
    emit q_ptr->findTextResult(activeMatchIndex, matchCount);
}

void AndroidWebViewPrivate::onClearCompleted(quint64 requestId)
{
    completeClearRequest(requestId);
}

// Callback handlers
void AndroidWebViewPrivate::onWebMessageReceived(const QString &message, const QString &origin, bool isMainFrame)
{
    if (MobileWebView::tryHandleInlineDownloadMessage(q_ptr, message))
        return;
    emit q_ptr->webMessageReceived(message, origin, isMainFrame);
}

void AndroidWebViewPrivate::onLinkLongPressedPx(const QUrl &linkUrl, const QUrl &imageUrl,
                                                QPointF posPx)
{
    // Physical WebView px → logical (view matches item geometry; ÷ dpr).
    qreal dpr = 1.0;
    if (QQuickWindow *win = q_ptr->window())
        dpr = win->devicePixelRatio();
    if (dpr <= 0)
        dpr = 1.0;
    emitLinkLongPressed(linkUrl, imageUrl, posPx / dpr);
}

void AndroidWebViewPrivate::onNavigationStarted(const QString &url)
{
    // Update origins before user scripts / QWebChannel handshake on the next pass.
    if (!url.isEmpty()) {
        updateUrlState(QUrl(url));
    }
    setLoading(true);
    setLoaded(false);
    setLoadProgress(0);
    setFavicon(QString());
}

void AndroidWebViewPrivate::onNavigationFinished(const QString &url)
{
    setLoading(false);
    setLoaded(true);
    setLoadProgress(100);
    updateUrlState(QUrl(url));
}

void AndroidWebViewPrivate::onNavigationFailed()
{
    setLoading(false);
    setLoaded(false);
}

void AndroidWebViewPrivate::onTitleChanged(const QString &title)
{
    setTitle(title);
}

void AndroidWebViewPrivate::onNavigationStateChanged(bool canGoBack, bool canGoForward)
{
    setCanGoBack(canGoBack);
    setCanGoForward(canGoForward);
}

void AndroidWebViewPrivate::onHistoryChanged(const QVariantList &historyItems, int currentHistoryIndex)
{
    setHistoryState(historyItems, currentHistoryIndex);
}

void AndroidWebViewPrivate::onNewWindowRequested(const QString &url, bool userInitiated)
{
    q_ptr->emitNewWindowRequested(QUrl(url), userInitiated);
}

void AndroidWebViewPrivate::onJavaScriptResult(const QString &result, const QString &error)
{
    QVariant qResult;
    if (error.isEmpty()) {
        // Android's evaluateJavascript JSON-encodes every result; decode it so the
        // javaScriptResult signal carries the same value types as the Apple backend.
        qResult = decodeAndroidEvaluateJsResult(result);
    }
    emit q_ptr->javaScriptResult(qResult, error);
}

void AndroidWebViewPrivate::onLoadProgressChanged(int progress)
{
    setLoadProgress(progress);
}

void AndroidWebViewPrivate::onFaviconReceived(const QString &faviconUrl)
{
    setFavicon(faviconUrl);
}

// =============================================================================
// Factory function for Android
// =============================================================================

MobileWebViewBackendPrivate *createPlatformBackend(MobileWebViewBackend *q)
{
    return new AndroidWebViewPrivate(q);
}

// =============================================================================
// JNI callback implementations
// =============================================================================

namespace {

// Inbound JNI marshalling helpers.
//
// Threading contract: every nativeOn* entry point runs on a JNI (Java) thread.
// All JNIEnv/jstring access must happen there, BEFORE the queued hop to the Qt
// thread; the hop lambda may only capture already-converted values by value.

QString toQString(JNIEnv *env, jstring value)
{
    if (!value) {
        return {};
    }
    const char *chars = env->GetStringUTFChars(value, nullptr);
    QString out = QString::fromUtf8(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

// Guard the native pointer, cast it, and queue `fn(backend)` onto the Qt
// thread (context object = the backend's public QObject, so the invocation is
// dropped if it is destroyed before delivery).
template <typename Fn>
void dispatchToBackend(jlong nativePtr, Fn &&fn)
{
    if (nativePtr == 0) {
        return;
    }
    auto *backend = reinterpret_cast<AndroidWebViewPrivate *>(nativePtr);
    QMetaObject::invokeMethod(backend->q_ptr,
                              [backend, fn = std::forward<Fn>(fn)]() { fn(backend); },
                              Qt::QueuedConnection);
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnWebMessageReceived(JNIEnv *env, jobject,
                                                         jlong nativePtr, jstring message,
                                                         jstring origin, jboolean isMainFrame)
{
    const QString qMessage = toQString(env, message);
    const QString qOrigin = toQString(env, origin);
    const bool mainFrame = isMainFrame == JNI_TRUE;
    dispatchToBackend(nativePtr, [qMessage, qOrigin, mainFrame](AndroidWebViewPrivate *backend) {
        backend->onWebMessageReceived(qMessage, qOrigin, mainFrame);
    });
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnNavigationStarted(JNIEnv *env, jobject,
                                                               jlong nativePtr, jstring url)
{
    const QString qUrl = toQString(env, url);
    dispatchToBackend(nativePtr, [qUrl](AndroidWebViewPrivate *backend) {
        backend->onNavigationStarted(qUrl);
    });
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnNavigationFinished(JNIEnv *env, jobject,
                                                        jlong nativePtr, jstring url)
{
    const QString qUrl = toQString(env, url);
    dispatchToBackend(nativePtr, [qUrl](AndroidWebViewPrivate *backend) {
        backend->onNavigationFinished(qUrl);
    });
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnNavigationFailed(JNIEnv *, jobject, jlong nativePtr)
{
    dispatchToBackend(nativePtr, [](AndroidWebViewPrivate *backend) {
        backend->onNavigationFailed();
    });
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnJavaScriptResult(JNIEnv *env, jobject,
                                                      jlong nativePtr, jstring result, jstring error)
{
    const QString qResult = toQString(env, result);
    const QString qError = toQString(env, error);
    dispatchToBackend(nativePtr, [qResult, qError](AndroidWebViewPrivate *backend) {
        backend->onJavaScriptResult(qResult, qError);
    });
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnTitleChanged(JNIEnv *env, jobject,
                                                          jlong nativePtr, jstring title)
{
    const QString qTitle = toQString(env, title);
    dispatchToBackend(nativePtr, [qTitle](AndroidWebViewPrivate *backend) {
        backend->onTitleChanged(qTitle);
    });
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnNavigationStateChanged(JNIEnv *, jobject,
                                                                    jlong nativePtr, jboolean canGoBack,
                                                                    jboolean canGoForward)
{
    const bool back = canGoBack == JNI_TRUE;
    const bool forward = canGoForward == JNI_TRUE;
    dispatchToBackend(nativePtr, [back, forward](AndroidWebViewPrivate *backend) {
        backend->onNavigationStateChanged(back, forward);
    });
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnBackRequested(JNIEnv *, jobject, jlong nativePtr,
                                                           jboolean pressed)
{
    const QEvent::Type type = (pressed == JNI_TRUE) ? QEvent::KeyPress : QEvent::KeyRelease;
    dispatchToBackend(nativePtr, [type](AndroidWebViewPrivate *backend) {
        QQuickWindow *win = backend->q_ptr->window();
        if (!win) return;
        QCoreApplication::postEvent(win, new QKeyEvent(type, Qt::Key_Back, Qt::NoModifier));
    });
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnHistoryChanged(JNIEnv *env, jobject,
                                                            jlong nativePtr, jobjectArray urls,
                                                            jobjectArray titles, jint currentHistoryIndex)
{
    QVariantList historyItems;

    const jsize urlCount = urls ? env->GetArrayLength(urls) : 0;
    const jsize titleCount = titles ? env->GetArrayLength(titles) : 0;
    historyItems.reserve(urlCount);

    for (jsize i = 0; i < urlCount; ++i) {
        auto *urlString = static_cast<jstring>(env->GetObjectArrayElement(urls, i));
        auto *titleString = i < titleCount ? static_cast<jstring>(env->GetObjectArrayElement(titles, i)) : nullptr;

        QVariantMap item;
        item.insert(QStringLiteral("url"), toQString(env, urlString));
        item.insert(QStringLiteral("title"), toQString(env, titleString));
        historyItems.append(item);

        if (urlString) {
            env->DeleteLocalRef(urlString);
        }
        if (titleString) {
            env->DeleteLocalRef(titleString);
        }
    }

    const int index = static_cast<int>(currentHistoryIndex);
    dispatchToBackend(nativePtr, [historyItems, index](AndroidWebViewPrivate *backend) {
        backend->onHistoryChanged(historyItems, index);
    });
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnNewWindowRequested(JNIEnv *env, jobject,
                                                                jlong nativePtr, jstring url,
                                                                jboolean userInitiated)
{
    const QString qUrl = toQString(env, url);
    const bool user = userInitiated == JNI_TRUE;
    dispatchToBackend(nativePtr, [qUrl, user](AndroidWebViewPrivate *backend) {
        backend->onNewWindowRequested(qUrl, user);
    });
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnLoadProgressChanged(JNIEnv *, jobject,
                                                                  jlong nativePtr, jint progress)
{
    const int qProgress = static_cast<int>(progress);
    dispatchToBackend(nativePtr, [qProgress](AndroidWebViewPrivate *backend) {
        backend->onLoadProgressChanged(qProgress);
    });
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnFaviconReceived(JNIEnv *env, jobject,
                                                              jlong nativePtr, jstring faviconUrl)
{
    const QString qUrl = toQString(env, faviconUrl);
    dispatchToBackend(nativePtr, [qUrl](AndroidWebViewPrivate *backend) {
        backend->onFaviconReceived(qUrl);
    });
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnFindResultChanged(JNIEnv *, jobject,
                                                                jlong nativePtr,
                                                                jint activeMatchIndex,
                                                                jint matchCount)
{
    const int active = static_cast<int>(activeMatchIndex);
    const int count = static_cast<int>(matchCount);
    dispatchToBackend(nativePtr, [active, count](AndroidWebViewPrivate *backend) {
        backend->onFindResultChanged(active, count);
    });
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnFreezeSnapshotReady(JNIEnv *env, jobject,
                                                                 jlong nativePtr,
                                                                 jlong requestId,
                                                                 jint width,
                                                                 jint height,
                                                                 jbyteArray jdata)
{
    // Copy the pixel buffer on the JNI thread; the QImage is built on the Qt thread.
    QByteArray pixels;
    if (jdata && width > 0 && height > 0) {
        const jsize len = env->GetArrayLength(jdata);
        const jsize expected = static_cast<jsize>(width) * static_cast<jsize>(height) * 4;
        if (len == expected) {
            jbyte *bytes = env->GetByteArrayElements(jdata, nullptr);
            if (bytes) {
                pixels = QByteArray(reinterpret_cast<const char *>(bytes), len);
                env->ReleaseByteArrayElements(jdata, bytes, JNI_ABORT);
            }
        }
    }

    const quint64 rid = static_cast<quint64>(requestId);
    const int w = static_cast<int>(width);
    const int h = static_cast<int>(height);

    dispatchToBackend(nativePtr, [rid, w, h, pixels](AndroidWebViewPrivate *backend) {
        QImage img;
        if (!pixels.isEmpty() && w > 0 && h > 0) {
            img = QImage(reinterpret_cast<const uchar *>(pixels.constData()),
                         w, h, w * 4, QImage::Format_RGBA8888)
                      .copy();
        }
        backend->notifySnapshotReady(rid, img);
    });
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnClearHttpCacheCompleted(JNIEnv *, jobject,
                                                                      jlong nativePtr,
                                                                      jlong requestId)
{
    const quint64 rid = static_cast<quint64>(requestId);
    dispatchToBackend(nativePtr, [rid](AndroidWebViewPrivate *backend) {
        backend->onClearCompleted(rid);
    });
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnDeleteAllCookiesCompleted(JNIEnv *, jobject,
                                                                        jlong nativePtr,
                                                                        jlong requestId)
{
    const quint64 rid = static_cast<quint64>(requestId);
    dispatchToBackend(nativePtr, [rid](AndroidWebViewPrivate *backend) {
        backend->onClearCompleted(rid);
    });
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnClearDomStorageCompleted(JNIEnv *, jobject,
                                                                       jlong nativePtr,
                                                                       jlong requestId)
{
    const quint64 rid = static_cast<quint64>(requestId);
    dispatchToBackend(nativePtr, [rid](AndroidWebViewPrivate *backend) {
        backend->onClearCompleted(rid);
    });
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnClearSiteDataCompleted(JNIEnv *, jobject,
                                                                    jlong nativePtr,
                                                                    jlong requestId)
{
    const quint64 rid = static_cast<quint64>(requestId);
    dispatchToBackend(nativePtr, [rid](AndroidWebViewPrivate *backend) {
        backend->onClearCompleted(rid);
    });
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnDownloadDetected(JNIEnv *env, jobject,
        jlong nativePtr, jstring url, jstring contentDisposition, jstring mimeType,
        jlong contentLength, jstring /*userAgent*/)
{
    const QString qUrl = toQString(env, url);
    const QString qDisposition = toQString(env, contentDisposition);
    const QString qMime = toQString(env, mimeType);
    const qint64 total = contentLength > 0 ? static_cast<qint64>(contentLength) : -1;

    dispatchToBackend(nativePtr, [qUrl, qDisposition, qMime, total](AndroidWebViewPrivate *backend) {
        backend->onDownloadDetected(QUrl(qUrl), QString(), qDisposition, qMime, total);
    });
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnDownloadProgress(JNIEnv *, jobject,
        jlong nativePtr, jlong downloadId, jlong receivedBytes, jlong totalBytes)
{
    const quint64 id = static_cast<quint64>(downloadId);
    const qint64 received = static_cast<qint64>(receivedBytes);
    const qint64 total = totalBytes >= 0 ? static_cast<qint64>(totalBytes) : -1;
    dispatchToBackend(nativePtr, [id, received, total](AndroidWebViewPrivate *backend) {
        backend->onDownloadProgress(id, received, total);
    });
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnLinkLongPressed(JNIEnv *env, jobject,
        jlong nativePtr, jstring linkUrl, jstring imageUrl, jfloat x, jfloat y)
{
    const QString qLink = toQString(env, linkUrl);
    const QString qImage = toQString(env, imageUrl);
    const QPointF posPx(x, y);
    dispatchToBackend(nativePtr, [qLink, qImage, posPx](AndroidWebViewPrivate *backend) {
        backend->onLinkLongPressedPx(QUrl(qLink), QUrl(qImage), posPx);
    });
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnDownloadFinished(JNIEnv *env, jobject,
        jlong nativePtr, jlong downloadId, jboolean ok, jstring error)
{
    const QString qError = toQString(env, error);
    const quint64 id = static_cast<quint64>(downloadId);
    const bool success = ok == JNI_TRUE;
    dispatchToBackend(nativePtr, [id, success, qError](AndroidWebViewPrivate *backend) {
        backend->onDownloadFinished(id, success, qError);
    });
}

} // extern "C"

#endif // Q_OS_ANDROID
