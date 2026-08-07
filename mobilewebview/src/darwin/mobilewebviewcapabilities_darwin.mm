#include "MobileWebView/mobilewebviewcapabilities.h"

#if defined(Q_OS_MACOS) || defined(Q_OS_IOS)

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
