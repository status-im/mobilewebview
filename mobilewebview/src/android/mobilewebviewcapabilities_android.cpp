#include "MobileWebView/mobilewebviewcapabilities.h"

#if defined(Q_OS_ANDROID)

#include <QJniEnvironment>

bool MobileWebViewCapabilities::isFindSupported()
{
    // WebView.findAllAsync/clearMatches exist across the whole WebView 113+
    // range this library supports.
    return true;
}

bool MobileWebViewCapabilities::hasNativeFindPanel()
{
    // Android WebView reports matches but draws no find UI, so the host's QML
    // find panel is the only one.
    return false;
}

bool MobileWebViewCapabilities::isClearSiteDataSupported()
{
    // Asked of the Java side rather than assumed: DataClearManager gates this
    // on the per-origin WebStorage/CookieManager APIs it can actually reach.
    // Touches only the JNI environment, so no WebView instance is needed.
    QJniEnvironment env;
    if (!env.isValid()) {
        return false;
    }
    jclass managerClass = env->FindClass("org/mobilewebview/DataClearManager");
    if (!managerClass) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        return false;
    }
    jmethodID method = env->GetStaticMethodID(managerClass, "isClearSiteDataSupported", "()Z");
    if (!method) {
        env->DeleteLocalRef(managerClass);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        return false;
    }
    const jboolean supported = env->CallStaticBooleanMethod(managerClass, method);
    env->DeleteLocalRef(managerClass);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return supported == JNI_TRUE;
}

bool MobileWebViewCapabilities::isInPageMediaPlaybackSupported()
{
    // The system WebView plays WebM/VP8/VP9 and the other open formats in a
    // page across the whole range this library supports (WebView 113+), so
    // there is no runtime gate to apply.
    return true;
}

#endif // Q_OS_ANDROID
