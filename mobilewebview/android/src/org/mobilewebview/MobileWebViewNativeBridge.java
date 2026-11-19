package org.mobilewebview;

import android.util.Log;
import android.webkit.JavascriptInterface;

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

        // Runs on the JavaBridge thread, so the origin comes from navigation tracking,
        // never from the WebView. No origin yet means the message is rejected.
        final String origin = mHost.currentMainFrameOrigin();

        final List<String> allowedOrigins = mHost.allowedOriginsSnapshot();
        if (!OriginUtils.isOriginAllowed(origin, allowedOrigins)) {
            Log.w(TAG, "Rejected message from disallowed origin: " + origin);
            return;
        }

        mHost.onWebMessage(message, origin);
    }

    /**
     * Called from every document's media_playback_watcher.js when it starts or
     * stops playing audible media. Carries no page data, so any origin may
     * report; the worst a page can do is keep its own view alive.
     */
    @JavascriptInterface
    public void mediaPlaybackChanged(String documentId, boolean playing) {
        if (mHost.nativePtr() == 0 || documentId == null || documentId.isEmpty()) {
            return;
        }
        mHost.onMediaPlaybackChanged(documentId, playing);
    }
}
