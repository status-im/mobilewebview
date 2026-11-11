package org.mobilewebview;

/**
 * The calls {@link MobileWebView} takes from the native backend: served by a {@link WebViewCore}
 * in this process or by a {@link RemoteWebView} in the browser process.
 */
interface WebViewEngine {
    void installMessageBridge(String namespace, String[] allowedOrigins, String invokeKey,
                              String[] userScripts, String bootstrapPageScript,
                              String bootstrapBridgeScript, String inlineDownloadScript);
    void updateAllowedOrigins(String[] origins);
    void loadUrl(String url);
    void loadHtml(String html, String baseUrl);
    void goBack();
    void goForward();
    void goBackOrForward(int offset);
    void reload();
    void stop();
    void clearHistory();
    void clearHttpCache(long requestId);
    void deleteAllCookies(long requestId);
    void clearDomStorage(long requestId);
    boolean isClearSiteDataSupported();
    void clearSiteData(String site, long requestId);
    void reloadAndBypassCache();
    void setZoomFactor(float factor);
    void setHttpUserAgent(String userAgent);
    void findText(String text, int flags);
    void stopFind();
    void evaluateJavaScript(String script);
    void postMessageToJavaScript(String json);
    void setGeometry(int x, int y, int width, int height);
    void setVisible(boolean visible, boolean grabFocus);
    void captureSnapshotForFreeze(long requestId);
    void setInteractionEnabled(boolean enabled);
    void probeDownload(String url, String token);
    void startDownload(long downloadId, String url, String destination);
    void cancelDownload(long downloadId);
    void pauseDownload(long downloadId);
    void resumeDownload(long downloadId);
    void destroy();
}
