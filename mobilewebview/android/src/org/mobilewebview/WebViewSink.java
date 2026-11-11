package org.mobilewebview;

import android.graphics.Bitmap;

/**
 * What a {@link WebViewCore} reports. In the UI process {@link MobileWebView} hands it to the
 * native backend; in the browser process it is sent back over Binder.
 */
interface WebViewSink {
    void onWebMessageReceived(String message, String origin, boolean isMainFrame);
    void onNavigationStarted(String url);
    void onNavigationFinished(String url);
    void onNavigationFailed();
    void onJavaScriptResult(String result, String error);
    void onTitleChanged(String title);
    void onNavigationStateChanged(boolean canGoBack, boolean canGoForward);
    void onBackRequested(boolean pressed);
    void onHistoryChanged(String[] urls, String[] titles, int currentHistoryIndex);
    void onNewWindowRequested(String url, boolean userInitiated);
    void onLoadProgressChanged(int progress);
    void onFaviconReceived(String faviconUrl);
    void onFindResultChanged(int activeMatchIndex, int matchCount);
    /** The sink owns {@code bitmap}; null when the snapshot failed. */
    void onFreezeSnapshotReady(long requestId, Bitmap bitmap);
    void onClearHttpCacheCompleted(long requestId);
    void onDeleteAllCookiesCompleted(long requestId);
    void onClearDomStorageCompleted(long requestId);
    void onClearSiteDataCompleted(long requestId);
    void onDownloadDetected(String url, String contentDisposition, String mimeType,
                            long contentLength, String userAgent, String token);
    void onDownloadProgress(long downloadId, long receivedBytes, long totalBytes);
    void onDownloadFinished(long downloadId, boolean ok, String error);
    void onLinkLongPressed(String linkUrl, String imageUrl, float x, float y);
    /** A touch started on the WebView. */
    void onTouchDown();
}
