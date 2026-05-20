package org.mobilewebview;

import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.util.List;

final class MobileWebViewNativeBridge {
    private static final String TAG = "MobileWebView";

    private final NativeBridgeHost mHost;

    MobileWebViewNativeBridge(NativeBridgeHost host) {
        mHost = host;
    }

    /**
     * Called from JavaScript via NativeBridge.postMessage()
     */
    @JavascriptInterface
    public void postMessage(String message) {
        if (mHost.nativePtr() == 0) {
            return;
        }

        // Prefer tracked main-frame origin to avoid transient URL mismatches during redirects.
        String resolvedOrigin = mHost.currentMainFrameOrigin();
        if (resolvedOrigin == null || resolvedOrigin.isEmpty()) {
            WebView webView = mHost.webView();
            String currentUrl = webView != null ? webView.getUrl() : null;
            resolvedOrigin = OriginUtils.extractOrigin(currentUrl);
        }
        final String origin = resolvedOrigin;

        final List<String> allowedOrigins = mHost.allowedOriginsSnapshot();
        if (!OriginUtils.isOriginAllowed(origin, allowedOrigins)) {
            Log.w(TAG, "Rejected message from disallowed origin: " + origin);
            return;
        }

        mHost.onWebMessage(message, origin);
    }
}
