#include "MobileWebView/mobilewebviewbackend.h"
#include "../common/mobilewebviewbackend_p.h"
#include "../common/origin_utils.h"
#include "../common/userscript_utils.h"

#ifdef Q_OS_ANDROID

#include <QDebug>
#include <QQuickWindow>
#include <QFile>
#include <QGuiApplication>
#include <QJniObject>
#include <QJniEnvironment>
#include <QtMath>

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
    void loadUrlImpl(const QUrl &url) override;
    void loadHtmlImpl(const QString &html, const QUrl &baseUrl) override;
    void goBackImpl() override;
    void goForwardImpl() override;
    void reloadImpl() override;
    void stopImpl() override;
    void clearHistoryImpl() override;
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
    void findTextImpl(const QString &text, int flags) override;
    void stopFindImpl() override;
    bool findSupportedImpl() const override;
    bool hasNativeFindPanelImpl() const override;
    void showFindPanelImpl() override;
    void hideFindPanelImpl() override;
    
    // JNI helper methods
    void cleanupJni();
    jobject createWebView();
    void destroyWebView();
    void callSimpleVoidMethod(jmethodID method);
    bool clearJniExceptionIfAny(QJniEnvironment &env);
    jobjectArray createJavaStringArray(QJniEnvironment &env, const QStringList &values);
    
    // Callback handlers (called from JNI)
    void onWebMessageReceived(const QString &message, const QString &origin, bool isMainFrame);
    void onNavigationStarted();
    void onNavigationFinished(const QString &url);
    void onNavigationFailed();
    void onTitleChanged(const QString &title);
    void onNavigationStateChanged(bool canGoBack, bool canGoForward);
    void onNewWindowRequested(const QString &url, bool userInitiated);
    void onJavaScriptResult(const QString &result, const QString &error);
    void onLoadProgressChanged(int progress);
    void onFaviconReceived(const QString &faviconUrl);
    void onFindResultChanged(int activeMatchIndex, int matchCount);
    
private:
    jobject m_webViewObject = nullptr;  // Global reference to Java MobileWebView
    jclass m_webViewClass = nullptr;
    
    // JNI method IDs (cached for performance)
    jmethodID m_loadUrlMethod = nullptr;
    jmethodID m_loadHtmlMethod = nullptr;
    jmethodID m_goBackMethod = nullptr;
    jmethodID m_goForwardMethod = nullptr;
    jmethodID m_reloadMethod = nullptr;
    jmethodID m_stopMethod = nullptr;
    jmethodID m_evaluateJavaScriptMethod = nullptr;
    jmethodID m_setGeometryMethod = nullptr;
    jmethodID m_setVisibleMethod = nullptr;
    jmethodID m_destroyMethod = nullptr;
    jmethodID m_updateAllowedOriginsMethod = nullptr;
    jmethodID m_setInteractionEnabledMethod = nullptr;
    jmethodID m_clearHistoryMethod = nullptr;
    jmethodID m_setZoomFactorMethod = nullptr;
    jmethodID m_findTextMethod = nullptr;
    jmethodID m_stopFindMethod = nullptr;
    
    bool m_jniInitialized = false;
    QMutex m_jniMutex;  // Protect JNI calls
};

AndroidWebViewPrivate::AndroidWebViewPrivate(MobileWebViewBackend *q)
    : MobileWebViewBackendPrivate(q)
{
    initNativeView();
}

AndroidWebViewPrivate::~AndroidWebViewPrivate()
{
    cleanupJni();
}

bool AndroidWebViewPrivate::initNativeView()
{
    QJniEnvironment env;
    if (!env.isValid()) {
        qWarning() << "AndroidWebViewPrivate: Invalid JNI environment";
        return false;
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
    m_reloadMethod = env->GetMethodID(m_webViewClass, "reload", "()V");
    m_stopMethod = env->GetMethodID(m_webViewClass, "stop", "()V");
    m_evaluateJavaScriptMethod = env->GetMethodID(m_webViewClass, "evaluateJavaScript", "(Ljava/lang/String;)V");
    m_setGeometryMethod = env->GetMethodID(m_webViewClass, "setGeometry", "(IIII)V");
    m_setVisibleMethod = env->GetMethodID(m_webViewClass, "setVisible", "(Z)V");
    m_destroyMethod = env->GetMethodID(m_webViewClass, "destroy", "()V");
    m_updateAllowedOriginsMethod = env->GetMethodID(m_webViewClass, "updateAllowedOrigins", "([Ljava/lang/String;)V");
    m_setInteractionEnabledMethod = env->GetMethodID(m_webViewClass, "setInteractionEnabled", "(Z)V");
    m_clearHistoryMethod = env->GetMethodID(m_webViewClass, "clearHistory", "()V");
    m_setZoomFactorMethod = env->GetMethodID(m_webViewClass, "setZoomFactor", "(F)V");
    m_findTextMethod = env->GetMethodID(m_webViewClass, "findText", "(Ljava/lang/String;I)V");
    m_stopFindMethod = env->GetMethodID(m_webViewClass, "stopFind", "()V");

    m_jniInitialized = true;
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
        "(Landroid/content/Context;JLandroid/view/View;)V");
    
    if (!constructor) {
        qWarning() << "AndroidWebViewPrivate: Failed to find constructor";
        env->ExceptionDescribe();
        env->ExceptionClear();
        return nullptr;
    }

    jobject localObj = env->NewObject(m_webViewClass, constructor, 
                                      activity.object(), 
                                      reinterpret_cast<jlong>(this),
                                      rootView);
    
    if (!localObj) {
        qWarning() << "AndroidWebViewPrivate: Failed to create MobileWebView instance";
        env->ExceptionDescribe();
        env->ExceptionClear();
        return nullptr;
    }

    // Create global reference
    jobject globalObj = env->NewGlobalRef(localObj);
    env->DeleteLocalRef(localObj);

    // Get WebView once to verify Java object setup.
    // View attachment is performed in Java on Android UI thread.
    jmethodID getWebViewMethod = env->GetMethodID(m_webViewClass, "getWebView",
        "()Landroid/webkit/WebView;");
    if (getWebViewMethod) {
        jobject webView = env->CallObjectMethod(globalObj, getWebViewMethod);
        if (webView) {
            env->DeleteLocalRef(webView);
        }
    }

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

void AndroidWebViewPrivate::reloadImpl()
{
    callSimpleVoidMethod(m_reloadMethod);
}

void AndroidWebViewPrivate::stopImpl()
{
    callSimpleVoidMethod(m_stopMethod);
}

void AndroidWebViewPrivate::clearHistoryImpl()
{
    callSimpleVoidMethod(m_clearHistoryMethod);
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

    env->CallVoidMethod(m_webViewObject, m_setGeometryMethod, xPx, yPx, wPx, hPx);

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

    bool shouldBeVisible = visible && m_nativeViewSetup;
    env->CallVoidMethod(m_webViewObject, m_setVisibleMethod, shouldBeVisible ? JNI_TRUE : JNI_FALSE);

    clearJniExceptionIfAny(env);
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

    jmethodID installMethod = env->GetMethodID(m_webViewClass, "installMessageBridge",
        "(Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    
    bool success = false;
    if (installMethod) {
        env->CallVoidMethod(m_webViewObject, installMethod, jNamespace, 
                           jAllowedOrigins, jInvokeKey, jUserScripts,
                           jBootstrapPage, jBootstrapBridge);
        success = true;
    }

    env->DeleteLocalRef(jNamespace);
    env->DeleteLocalRef(jInvokeKey);
    env->DeleteLocalRef(jBootstrapPage);
    env->DeleteLocalRef(jBootstrapBridge);
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
    if (!m_jniInitialized) {
        return;
    }

    QQuickWindow *win = q_ptr->window();
    if (!win) {
        qWarning() << "AndroidWebViewPrivate::setupNativeViewImpl: no window";
        return;
    }

    // WebView is already created in initNativeView, just mark as setup
    m_nativeViewSetup = true;
    updateNativeVisibility(q_ptr->isVisible());
    updateNativeGeometry(QRectF(0, 0, q_ptr->width(), q_ptr->height()));
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
    return true;
}

bool AndroidWebViewPrivate::hasNativeFindPanelImpl() const
{
    return false;
}

void AndroidWebViewPrivate::showFindPanelImpl()
{
    // No-op on Android: QML find panel is used instead
}

void AndroidWebViewPrivate::hideFindPanelImpl()
{
    // No-op on Android: QML find panel is used instead
}

void AndroidWebViewPrivate::onFindResultChanged(int activeMatchIndex, int matchCount)
{
    emit q_ptr->findTextResult(activeMatchIndex, matchCount);
}

// Callback handlers
void AndroidWebViewPrivate::onWebMessageReceived(const QString &message, const QString &origin, bool isMainFrame)
{
    emit q_ptr->webMessageReceived(message, origin, isMainFrame);
}

void AndroidWebViewPrivate::onNavigationStarted()
{
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

void AndroidWebViewPrivate::onNewWindowRequested(const QString &url, bool userInitiated)
{
    q_ptr->emitNewWindowRequested(QUrl(url), userInitiated);
}

void AndroidWebViewPrivate::onJavaScriptResult(const QString &result, const QString &error)
{
    QVariant qResult;
    if (error.isEmpty() && !result.isEmpty()) {
        qResult = result;
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

extern "C" {

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnWebMessageReceived(JNIEnv *env, jobject obj, 
                                                         jlong nativePtr, jstring message, 
                                                         jstring origin, jboolean isMainFrame)
{
    if (nativePtr == 0) return;
    
    AndroidWebViewPrivate *backend = reinterpret_cast<AndroidWebViewPrivate*>(nativePtr);
    const char *msgChars = env->GetStringUTFChars(message, nullptr);
    const char *originChars = env->GetStringUTFChars(origin, nullptr);
    
    QString qMessage = QString::fromUtf8(msgChars);
    QString qOrigin = QString::fromUtf8(originChars);
    
    env->ReleaseStringUTFChars(message, msgChars);
    env->ReleaseStringUTFChars(origin, originChars);
    
    QMetaObject::invokeMethod(backend->q_ptr, [backend, qMessage, qOrigin, isMainFrame]() {
        backend->onWebMessageReceived(qMessage, qOrigin, isMainFrame == JNI_TRUE);
    }, Qt::QueuedConnection);
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnNavigationStarted(JNIEnv *env, jobject obj, jlong nativePtr)
{
    if (nativePtr == 0) return;
    
    AndroidWebViewPrivate *backend = reinterpret_cast<AndroidWebViewPrivate*>(nativePtr);
    QMetaObject::invokeMethod(backend->q_ptr, [backend]() {
        backend->onNavigationStarted();
    }, Qt::QueuedConnection);
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnNavigationFinished(JNIEnv *env, jobject obj, 
                                                        jlong nativePtr, jstring url)
{
    if (nativePtr == 0) return;
    
    AndroidWebViewPrivate *backend = reinterpret_cast<AndroidWebViewPrivate*>(nativePtr);
    const char *urlChars = env->GetStringUTFChars(url, nullptr);
    QString qUrl = QString::fromUtf8(urlChars);
    env->ReleaseStringUTFChars(url, urlChars);
    
    QMetaObject::invokeMethod(backend->q_ptr, [backend, qUrl]() {
        backend->onNavigationFinished(qUrl);
    }, Qt::QueuedConnection);
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnNavigationFailed(JNIEnv *env, jobject obj, jlong nativePtr)
{
    if (nativePtr == 0) return;
    
    AndroidWebViewPrivate *backend = reinterpret_cast<AndroidWebViewPrivate*>(nativePtr);
    QMetaObject::invokeMethod(backend->q_ptr, [backend]() {
        backend->onNavigationFailed();
    }, Qt::QueuedConnection);
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnJavaScriptResult(JNIEnv *env, jobject obj, 
                                                      jlong nativePtr, jstring result, jstring error)
{
    if (nativePtr == 0) return;
    
    AndroidWebViewPrivate *backend = reinterpret_cast<AndroidWebViewPrivate*>(nativePtr);
    const char *resultChars = env->GetStringUTFChars(result, nullptr);
    const char *errorChars = env->GetStringUTFChars(error, nullptr);
    
    QString qResult = QString::fromUtf8(resultChars);
    QString qError = QString::fromUtf8(errorChars);
    
    env->ReleaseStringUTFChars(result, resultChars);
    env->ReleaseStringUTFChars(error, errorChars);
    
    QMetaObject::invokeMethod(backend->q_ptr, [backend, qResult, qError]() {
        backend->onJavaScriptResult(qResult, qError);
    }, Qt::QueuedConnection);
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnTitleChanged(JNIEnv *env, jobject obj,
                                                          jlong nativePtr, jstring title)
{
    if (nativePtr == 0) return;

    AndroidWebViewPrivate *backend = reinterpret_cast<AndroidWebViewPrivate*>(nativePtr);
    const char *titleChars = env->GetStringUTFChars(title, nullptr);
    QString qTitle = QString::fromUtf8(titleChars);
    env->ReleaseStringUTFChars(title, titleChars);

    QMetaObject::invokeMethod(backend->q_ptr, [backend, qTitle]() {
        backend->onTitleChanged(qTitle);
    }, Qt::QueuedConnection);
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnNavigationStateChanged(JNIEnv *env, jobject obj,
                                                                    jlong nativePtr, jboolean canGoBack,
                                                                    jboolean canGoForward)
{
    if (nativePtr == 0) return;

    AndroidWebViewPrivate *backend = reinterpret_cast<AndroidWebViewPrivate*>(nativePtr);
    QMetaObject::invokeMethod(backend->q_ptr, [backend, canGoBack, canGoForward]() {
        backend->onNavigationStateChanged(canGoBack == JNI_TRUE, canGoForward == JNI_TRUE);
    }, Qt::QueuedConnection);
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnNewWindowRequested(JNIEnv *env, jobject obj,
                                                                jlong nativePtr, jstring url,
                                                                jboolean userInitiated)
{
    if (nativePtr == 0) return;

    AndroidWebViewPrivate *backend = reinterpret_cast<AndroidWebViewPrivate*>(nativePtr);
    const char *urlChars = env->GetStringUTFChars(url, nullptr);
    QString qUrl = QString::fromUtf8(urlChars);
    env->ReleaseStringUTFChars(url, urlChars);

    QMetaObject::invokeMethod(backend->q_ptr, [backend, qUrl, userInitiated]() {
        backend->onNewWindowRequested(qUrl, userInitiated == JNI_TRUE);
    }, Qt::QueuedConnection);
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnLoadProgressChanged(JNIEnv *env, jobject obj,
                                                                  jlong nativePtr, jint progress)
{
    if (nativePtr == 0) return;

    AndroidWebViewPrivate *backend = reinterpret_cast<AndroidWebViewPrivate*>(nativePtr);
    QMetaObject::invokeMethod(backend->q_ptr, [backend, progress]() {
        backend->onLoadProgressChanged(static_cast<int>(progress));
    }, Qt::QueuedConnection);
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnFaviconReceived(JNIEnv *env, jobject obj,
                                                              jlong nativePtr, jstring faviconUrl)
{
    if (nativePtr == 0) return;

    AndroidWebViewPrivate *backend = reinterpret_cast<AndroidWebViewPrivate*>(nativePtr);
    const char *urlChars = env->GetStringUTFChars(faviconUrl, nullptr);
    QString qUrl = QString::fromUtf8(urlChars);
    env->ReleaseStringUTFChars(faviconUrl, urlChars);

    QMetaObject::invokeMethod(backend->q_ptr, [backend, qUrl]() {
        backend->onFaviconReceived(qUrl);
    }, Qt::QueuedConnection);
}

JNIEXPORT void JNICALL
Java_org_mobilewebview_MobileWebView_nativeOnFindResultChanged(JNIEnv *env, jobject obj,
                                                                jlong nativePtr,
                                                                jint activeMatchIndex,
                                                                jint matchCount)
{
    if (nativePtr == 0) return;

    AndroidWebViewPrivate *backend = reinterpret_cast<AndroidWebViewPrivate*>(nativePtr);
    QMetaObject::invokeMethod(backend->q_ptr, [backend, activeMatchIndex, matchCount]() {
        backend->onFindResultChanged(static_cast<int>(activeMatchIndex),
                                     static_cast<int>(matchCount));
    }, Qt::QueuedConnection);
}

} // extern "C"

#endif // Q_OS_ANDROID
