package org.mobilewebview;

import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;

import androidx.webkit.Profile;

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
        clearHttpCache(webView, null);
    }

    /**
     * Evicts the HTTP cache. {@code done} runs after {@link WebView#clearCache} returns
     * (sync API — no OS completion callback).
     */
    public void clearHttpCache(WebView webView, Runnable done) {
        if (webView != null) {
            webView.clearCache(true);
        }
        if (done != null) {
            done.run();
        }
    }

    public void deleteAllCookies() {
        deleteAllCookies((Profile) null, null);
    }

    public void deleteAllCookies(Runnable done) {
        deleteAllCookies(null, done);
    }

    /**
     * Clears cookies for {@code profile} when non-null; otherwise the process-wide
     * {@link CookieManager}. {@code done} runs only after {@code removeAllCookies}
     * completes.
     */
    public void deleteAllCookies(Profile profile, Runnable done) {
        CookieManager cookieManager = profile != null
                ? profile.getCookieManager()
                : CookieManager.getInstance();
        cookieManager.removeAllCookies(new ValueCallback<Boolean>() {
            @Override
            public void onReceiveValue(Boolean value) {
                if (done != null) {
                    done.run();
                }
            }
        });
    }

    public void clearDomStorage() {
        clearDomStorage(null, null);
    }

    public void clearDomStorage(Profile profile) {
        clearDomStorage(profile, null);
    }

    /**
     * Clears DOM storage for {@code profile} when non-null; otherwise the
     * process-wide {@link WebStorage}. {@code done} runs after
     * {@link WebStorage#deleteAllData} returns (sync API).
     */
    public void clearDomStorage(Profile profile, Runnable done) {
        WebStorage webStorage = profile != null
                ? profile.getWebStorage()
                : WebStorage.getInstance();
        webStorage.deleteAllData();
        if (done != null) {
            done.run();
        }
    }

    /**
     * Full per-site wipe (cookies + network cache + JS storage + service workers).
     * Requires WebViewFeature.DELETE_BROWSING_DATA. Invokes {@code done} when finished
     * (or immediately on failure / unsupported).
     */
    public void clearSiteData(String site, Runnable done) {
        clearSiteData(null, site, done);
    }

    /**
     * Per-site wipe using {@code profile}'s {@link WebStorage} when non-null.
     * {@code done} runs only from WebStorageCompat's completion callback.
     */
    public void clearSiteData(Profile profile, String site, Runnable done) {
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
            WebStorage webStorage = profile != null
                    ? profile.getWebStorage()
                    : WebStorage.getInstance();
            method.invoke(null, webStorage, site, done);
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
