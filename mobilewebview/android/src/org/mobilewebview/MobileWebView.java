package org.mobilewebview;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * MobileWebView - Native Android WebView wrapper for Qt integration, the class the native
 * backend talks to. The WebView itself is a {@link WebViewCore} in this process, or a
 * {@link RemoteWebView} in the browser process when {@link #setRemoteProcess} is on.
 */
public class MobileWebView implements WebViewSink {
    private static final String TAG = "MobileWebView";

    private volatile long mNativePtr;  // Pointer to C++ AndroidWebViewBackend
    private final WebViewEngine mEngine;
    private final WebViewCore mLocal;

    private static volatile boolean sRemote = false;

    /**
     * Host each WebView in its own panel window instead of the activity's content view. For
     * activities whose window draws no views (e.g. it took its surface); set before creating
     * WebViews.
     */
    public static void setOwnWindows(boolean ownWindows) {
        WebViewCore.setOwnWindows(ownWindows);
    }

    /**
     * Run the WebViews in the browser process ({@link BrowserProcessService}) and show them
     * through a SurfaceView here; this process then never loads the WebView. API 30+. Set before
     * creating WebViews.
     */
    public static void setRemoteProcess(boolean remote) {
        sRemote = remote && android.os.Build.VERSION.SDK_INT >= 30;
        DataClearManager.setClientProcess(sRemote);
    }

    static boolean isRemoteProcess() {
        return sRemote;
    }

    /** WebSettings.getDefaultUserAgent, or the browser process's last answer when remote. */
    public static String defaultUserAgent(Context context) {
        if (sRemote) {
            return BrowserProcessClient.cachedDefaultUserAgent(context);
        }
        try {
            final String agent = WebSettings.getDefaultUserAgent(context);
            return agent != null ? agent : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    public MobileWebView(Context context, long nativePtr, View rootView,
                         String storageName, boolean offTheRecord) {
        mNativePtr = nativePtr;
        if (sRemote) {
            mLocal = null;
            mEngine = new RemoteWebView(context, this, rootView, storageName, offTheRecord);
        } else {
            mLocal = new WebViewCore(context, this, rootView, storageName, offTheRecord);
            mEngine = mLocal;
        }
    }

    public void installMessageBridge(String namespace, String[] allowedOrigins,
                                     String invokeKey, String[] userScripts,
                                     String bootstrapPageScript, String bootstrapBridgeScript,
                                     String inlineDownloadScript) {
        mEngine.installMessageBridge(namespace, allowedOrigins, invokeKey, userScripts,
                bootstrapPageScript, bootstrapBridgeScript, inlineDownloadScript);
    }

    public void updateAllowedOrigins(String[] origins) { mEngine.updateAllowedOrigins(origins); }
    public void loadUrl(String url) { mEngine.loadUrl(url); }
    public void loadHtml(String html, String baseUrl) { mEngine.loadHtml(html, baseUrl); }
    public void goBack() { mEngine.goBack(); }
    public void goForward() { mEngine.goForward(); }
    public void goBackOrForward(int offset) { mEngine.goBackOrForward(offset); }
    public void reload() { mEngine.reload(); }
    public void stop() { mEngine.stop(); }
    public void clearHistory() { mEngine.clearHistory(); }
    public void clearHttpCache(long requestId) { mEngine.clearHttpCache(requestId); }
    public void deleteAllCookies(long requestId) { mEngine.deleteAllCookies(requestId); }
    public void clearDomStorage(long requestId) { mEngine.clearDomStorage(requestId); }
    public boolean isClearSiteDataSupported() { return mEngine.isClearSiteDataSupported(); }
    public void clearSiteData(String site, long requestId) { mEngine.clearSiteData(site, requestId); }
    public void reloadAndBypassCache() { mEngine.reloadAndBypassCache(); }
    public void setZoomFactor(float factor) { mEngine.setZoomFactor(factor); }
    public void setHttpUserAgent(String userAgent) { mEngine.setHttpUserAgent(userAgent); }
    public void findText(String text, int flags) { mEngine.findText(text, flags); }
    public void stopFind() { mEngine.stopFind(); }
    public void evaluateJavaScript(String script) { mEngine.evaluateJavaScript(script); }
    public void postMessageToJavaScript(String json) { mEngine.postMessageToJavaScript(json); }
    public void setGeometry(int x, int y, int width, int height) { mEngine.setGeometry(x, y, width, height); }
    public void setVisible(boolean visible, boolean grabFocus) { mEngine.setVisible(visible, grabFocus); }
    public void setVisible(boolean visible) { mEngine.setVisible(visible, true); }
    public void captureSnapshotForFreeze(long requestId) { mEngine.captureSnapshotForFreeze(requestId); }
    public void setInteractionEnabled(boolean enabled) { mEngine.setInteractionEnabled(enabled); }
    public void probeDownload(String url, String token) { mEngine.probeDownload(url, token); }
    public void startDownload(long downloadId, String url, String destination) { mEngine.startDownload(downloadId, url, destination); }
    public void cancelDownload(long downloadId) { mEngine.cancelDownload(downloadId); }
    public void pauseDownload(long downloadId) { mEngine.pauseDownload(downloadId); }
    public void resumeDownload(long downloadId) { mEngine.resumeDownload(downloadId); }

    /**
     * Publishes an Inline Download (blob:/data:, written by Qt) into
     * MediaStore.Downloads. Fetched downloads take the same step inside
     * DownloadFetcher; this is only the JNI door for the ones Qt writes.
     * The caller filters Incognito out.
     */
    public static void publishInlineDownload(String path, Context context, String mimeType) {
        DownloadMediaStore.registerCompleted(path, context, mimeType);
    }

    public void destroy() {
        mNativePtr = 0;  // zero out immediately so JNI callbacks are ignored
        mEngine.destroy();
    }

    /** The local WebView; null for remote WebViews. */
    public WebView getWebView() {
        return mLocal != null ? mLocal.getWebView() : null;
    }

    // --- WebViewSink: to the native backend ---

    @Override public void onWebMessageReceived(String message, String origin, boolean isMainFrame) {
        final long ptr = mNativePtr;
        if (ptr != 0) nativeOnWebMessageReceived(ptr, message, origin, isMainFrame);
    }
    @Override public void onNavigationStarted(String url) {
        final long ptr = mNativePtr;
        if (ptr != 0) nativeOnNavigationStarted(ptr, url);
    }
    @Override public void onNavigationFinished(String url) {
        final long ptr = mNativePtr;
        if (ptr != 0) nativeOnNavigationFinished(ptr, url);
    }
    @Override public void onNavigationFailed() {
        final long ptr = mNativePtr;
        if (ptr != 0) nativeOnNavigationFailed(ptr);
    }
    @Override public void onJavaScriptResult(String result, String error) {
        final long ptr = mNativePtr;
        if (ptr != 0) nativeOnJavaScriptResult(ptr, result, error);
    }
    @Override public void onTitleChanged(String title) {
        final long ptr = mNativePtr;
        if (ptr != 0) nativeOnTitleChanged(ptr, title);
    }
    @Override public void onNavigationStateChanged(boolean canGoBack, boolean canGoForward) {
        final long ptr = mNativePtr;
        if (ptr != 0) nativeOnNavigationStateChanged(ptr, canGoBack, canGoForward);
    }
    @Override public void onBackRequested(boolean pressed) {
        final long ptr = mNativePtr;
        if (ptr != 0) nativeOnBackRequested(ptr, pressed);
    }
    @Override public void onHistoryChanged(String[] urls, String[] titles, int currentHistoryIndex) {
        final long ptr = mNativePtr;
        if (ptr != 0) nativeOnHistoryChanged(ptr, urls, titles, currentHistoryIndex);
    }
    @Override public void onNewWindowRequested(String url, boolean userInitiated) {
        final long ptr = mNativePtr;
        if (ptr != 0) nativeOnNewWindowRequested(ptr, url, userInitiated);
    }
    @Override public void onLoadProgressChanged(int progress) {
        final long ptr = mNativePtr;
        if (ptr != 0) nativeOnLoadProgressChanged(ptr, progress);
    }
    @Override public void onFaviconReceived(String faviconUrl) {
        final long ptr = mNativePtr;
        if (ptr != 0) nativeOnFaviconReceived(ptr, faviconUrl);
    }
    @Override public void onFindResultChanged(int activeMatchIndex, int matchCount) {
        final long ptr = mNativePtr;
        if (ptr != 0) nativeOnFindResultChanged(ptr, activeMatchIndex, matchCount);
    }
    @Override public void onFreezeSnapshotReady(long requestId, Bitmap bitmap) {
        final long ptr = mNativePtr;
        if (bitmap == null) {
            if (ptr != 0) nativeOnFreezeSnapshotReady(ptr, requestId, 0, 0, null);
            return;
        }
        final int w = bitmap.getWidth();
        final int h = bitmap.getHeight();
        byte[] pixels = null;
        try {
            final ByteBuffer buffer = ByteBuffer.allocate(w * h * 4);
            buffer.order(ByteOrder.nativeOrder());
            bitmap.copyPixelsToBuffer(buffer);
            pixels = buffer.array();
        } catch (RuntimeException e) {
            Log.e(TAG, "deliverFreezeSnapshot failed", e);
        } finally {
            bitmap.recycle();
        }
        if (ptr == 0) return;
        if (pixels == null) {
            nativeOnFreezeSnapshotReady(ptr, requestId, 0, 0, null);
        } else {
            nativeOnFreezeSnapshotReady(ptr, requestId, w, h, pixels);
        }
    }
    @Override public void onClearHttpCacheCompleted(long requestId) {
        final long ptr = mNativePtr;
        if (ptr != 0) nativeOnClearHttpCacheCompleted(ptr, requestId);
    }
    @Override public void onDeleteAllCookiesCompleted(long requestId) {
        final long ptr = mNativePtr;
        if (ptr != 0) nativeOnDeleteAllCookiesCompleted(ptr, requestId);
    }
    @Override public void onClearDomStorageCompleted(long requestId) {
        final long ptr = mNativePtr;
        if (ptr != 0) nativeOnClearDomStorageCompleted(ptr, requestId);
    }
    @Override public void onClearSiteDataCompleted(long requestId) {
        final long ptr = mNativePtr;
        if (ptr != 0) nativeOnClearSiteDataCompleted(ptr, requestId);
    }
    @Override public void onDownloadDetected(String url, String contentDisposition, String mimeType,
                                             long contentLength, String userAgent, String token) {
        final long ptr = mNativePtr;
        if (ptr != 0) nativeOnDownloadDetected(ptr, url, contentDisposition, mimeType, contentLength,
                userAgent, token);
    }
    @Override public void onDownloadProgress(long downloadId, long receivedBytes, long totalBytes) {
        final long ptr = mNativePtr;
        if (ptr != 0) nativeOnDownloadProgress(ptr, downloadId, receivedBytes, totalBytes);
    }
    @Override public void onDownloadFinished(long downloadId, boolean ok, String error) {
        final long ptr = mNativePtr;
        if (ptr != 0) nativeOnDownloadFinished(ptr, downloadId, ok, error);
    }
    @Override public void onLinkLongPressed(String linkUrl, String imageUrl, float x, float y) {
        final long ptr = mNativePtr;
        if (ptr != 0) nativeOnLinkLongPressed(ptr, linkUrl, imageUrl, x, y);
    }
    @Override public void onTouchDown() {}

    /** The browser process told us its default User-Agent (remote WebViews). */
    void onDefaultUserAgent(String userAgent) {
        final long ptr = mNativePtr;
        if (ptr != 0) nativeOnDefaultUserAgent(ptr, userAgent);
    }

    // Native callback methods (implemented in C++)
    private native void nativeOnWebMessageReceived(long nativePtr, String message,
                                                   String origin, boolean isMainFrame);
    private native void nativeOnNavigationStarted(long nativePtr, String url);
    private native void nativeOnNavigationFinished(long nativePtr, String url);
    private native void nativeOnNavigationFailed(long nativePtr);
    private native void nativeOnJavaScriptResult(long nativePtr, String result, String error);
    private native void nativeOnTitleChanged(long nativePtr, String title);
    private native void nativeOnNavigationStateChanged(long nativePtr, boolean canGoBack, boolean canGoForward);
    private native void nativeOnBackRequested(long nativePtr, boolean pressed);
    private native void nativeOnHistoryChanged(long nativePtr, String[] urls, String[] titles, int currentHistoryIndex);
    private native void nativeOnNewWindowRequested(long nativePtr, String url, boolean userInitiated);
    private native void nativeOnLoadProgressChanged(long nativePtr, int progress);
    private native void nativeOnFaviconReceived(long nativePtr, String faviconUrl);
    private native void nativeOnFindResultChanged(long nativePtr, int activeMatchIndex, int matchCount);
    private native void nativeOnFreezeSnapshotReady(long nativePtr, long requestId, int width, int height,
                                                    byte[] pixels);
    private native void nativeOnClearHttpCacheCompleted(long nativePtr, long requestId);
    private native void nativeOnDeleteAllCookiesCompleted(long nativePtr, long requestId);
    private native void nativeOnClearDomStorageCompleted(long nativePtr, long requestId);
    private native void nativeOnClearSiteDataCompleted(long nativePtr, long requestId);
    private native void nativeOnDownloadDetected(long nativePtr, String url, String contentDisposition,
                                                 String mimeType, long contentLength, String userAgent,
                                                 String token);
    private native void nativeOnDownloadProgress(long nativePtr, long downloadId,
                                                 long receivedBytes, long totalBytes);
    private native void nativeOnDownloadFinished(long nativePtr, long downloadId,
                                                 boolean ok, String error);
    private native void nativeOnLinkLongPressed(long nativePtr, String linkUrl, String imageUrl,
                                                float x, float y);
    private native void nativeOnDefaultUserAgent(long nativePtr, String userAgent);
}
