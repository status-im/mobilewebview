package org.mobilewebview;

import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;

import java.lang.reflect.Method;

/**
 * Native data-clearing helpers for MobileWebView.
 * See docs/adr/0004-data-clearing-and-force-reload.md.
 */
public final class DataClearManager {
    private static final String TAG = "DataClearManager";
    private static final String DELETE_BROWSING_DATA = "DELETE_BROWSING_DATA";

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

    /**
     * Full per-site wipe (cookies + network cache + JS storage + service workers).
     * Requires WebViewFeature.DELETE_BROWSING_DATA. Invokes {@code done} when finished
     * (or immediately on failure / unsupported).
     */
    public void clearSiteData(String site, Runnable done) {
        if (site == null || site.isEmpty()) {
            if (done != null) {
                done.run();
            }
            return;
        }
        if (!isClearSiteDataSupported()) {
            Log.w(TAG, "clearSiteData: DELETE_BROWSING_DATA not supported; ignoring");
            if (done != null) {
                done.run();
            }
            return;
        }
        try {
            Class<?> compatClass = Class.forName("androidx.webkit.WebStorageCompat");
            Method method = compatClass.getMethod(
                    "deleteBrowsingDataForSite",
                    WebStorage.class,
                    String.class,
                    Runnable.class);
            method.invoke(null, WebStorage.getInstance(), site, done);
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "clearSiteData failed", e);
            if (done != null) {
                done.run();
            }
        }
    }

    public static boolean isClearSiteDataSupported() {
        try {
            Class<?> featureClass = Class.forName("androidx.webkit.WebViewFeature");
            Method isSupported = featureClass.getMethod("isFeatureSupported", String.class);
            Object result = isSupported.invoke(null, DELETE_BROWSING_DATA);
            return result instanceof Boolean && (Boolean) result;
        } catch (ReflectiveOperationException e) {
            return false;
        }
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
