package org.mobilewebview;

import android.graphics.Bitmap;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

final class MobileWebChromeClient extends WebChromeClient {
    private static final String TAG = "MobileWebView";

    private final ChromeHost mHost;

    MobileWebChromeClient(ChromeHost host) {
        mHost = host;
    }

    @Override
    public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                  android.os.Message resultMsg) {
        WebView.HitTestResult hitTestResult = view.getHitTestResult();
        String requestedUrl = hitTestResult != null ? hitTestResult.getExtra() : null;

        if (requestedUrl != null && !requestedUrl.isEmpty()) {
            mHost.onNewWindowRequested(requestedUrl, isUserGesture);
            return false;
        }

        return false;
    }

    @Override
    public void onReceivedTitle(WebView view, String title) {
        mHost.onTitleChanged(title != null ? title : "");
    }

    @Override
    public void onProgressChanged(WebView view, int newProgress) {
        mHost.onProgressChanged(newProgress);
    }

    @Override
    public void onReceivedIcon(WebView view, Bitmap icon) {
        mHost.onFavicon(icon);
    }

    @Override
    public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
        Log.d(TAG, "Console [" + consoleMessage.messageLevel() + "]: " +
                   consoleMessage.message() + " -- From line " +
                   consoleMessage.lineNumber() + " of " +
                   consoleMessage.sourceId());
        return true;
    }
}
