#include "MobileWebView/mobilewebviewcapabilities.h"

#if defined(Q_OS_ANDROID)

bool MobileWebViewCapabilities::isInPageMediaPlaybackSupported()
{
    // The system WebView plays WebM/VP8/VP9 and the other open formats in a
    // page across the whole range this library supports (WebView 113+), so
    // there is no runtime gate to apply.
    return true;
}

#endif // Q_OS_ANDROID
