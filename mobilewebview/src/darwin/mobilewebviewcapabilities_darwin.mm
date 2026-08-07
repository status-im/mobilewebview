#include "MobileWebView/mobilewebviewcapabilities.h"

#if defined(Q_OS_MACOS) || defined(Q_OS_IOS)

bool MobileWebViewCapabilities::isFindSupported()
{
    // WKWebView's find interaction is iOS-only; the macOS build drives find
    // through no native API here, so it reports unsupported.
#ifdef Q_OS_IOS
    return true;
#else
    return false;
#endif
}

bool MobileWebViewCapabilities::hasNativeFindPanel()
{
    // Where find works on Darwin it is the system find navigator, so the host
    // must not draw one of its own.
#ifdef Q_OS_IOS
    return true;
#else
    return false;
#endif
}

bool MobileWebViewCapabilities::isClearSiteDataSupported()
{
    // WKWebsiteDataStore removes records per data type and display name on
    // every Darwin version this library supports.
    return true;
}

bool MobileWebViewCapabilities::isInPageMediaPlaybackSupported()
{
#ifdef Q_OS_IOS
    // WebKit gained WebM/VP8/VP9 decoding in iOS 17.4
    // (https://webkit.org/blog/15063/webkit-features-in-safari-17-4/). Below
    // that, a downloaded WebM handed to an in-page <video> fails to decode and
    // the user is left on a dead player.
    //
    // Checked at runtime rather than against the deployment target: the target
    // is the host app's to pin, and a binary built with a 17.4 floor still has
    // to answer honestly if it is ever run somewhere older.
    if (@available(iOS 17.4, *)) {
        return true;
    }
    return false;
#else
    // macOS WebKit has played WebM since well before the 14.0 floor this
    // library requires, so there is no version to test for.
    return true;
#endif
}

#endif // Q_OS_MACOS || Q_OS_IOS
