package org.mobilewebview;

import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;

/**
 * Native data-clearing helpers for MobileWebView.
 * See docs/adr/0004-data-clearing-and-force-reload.md.
 */
public final class DataClearManager {
    private boolean mRestoreDefaultCacheModeOnNextPageFinish;

    public void clearHttpCache(WebView webView) {
        if (webView != null) {
            webView.clearCache(true);
        }
    }

    public void deleteAllCookies() {
        CookieManager.getInstance().removeAllCookies(null);
    }

    public void clearDomStorage() {
        WebStorage.getInstance().deleteAllData();
    }

    public void clearDomStorage(String origin) {
        if (origin == null || origin.isEmpty()) {
            return;
        }
        WebStorage.getInstance().deleteOrigin(origin);
    }

    public void reloadAndBypassCache(WebView webView) {
        if (webView == null) {
            return;
        }
        WebSettings settings = webView.getSettings();
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        mRestoreDefaultCacheModeOnNextPageFinish = true;
        webView.reload();
    }

    public void onPageFinished(WebView webView) {
        if (!mRestoreDefaultCacheModeOnNextPageFinish || webView == null) {
            return;
        }
        webView.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
        mRestoreDefaultCacheModeOnNextPageFinish = false;
    }
}
