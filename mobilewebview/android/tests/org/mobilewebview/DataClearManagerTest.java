package org.mobilewebview;

import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;

public final class DataClearManagerTest {
    public static void main(String[] args) {
        clearHttpCacheCallsWebViewClearCache();
        deleteAllCookiesCallsCookieManager();
        clearDomStorageCallsDeleteAllData();
        clearDomStorageOriginCallsDeleteOrigin();
        reloadAndBypassCacheSetsNoCacheThenRestoresOnPageFinished();
        System.out.println("DataClearManagerTest passed");
    }

    private static void clearHttpCacheCallsWebViewClearCache() {
        resetState();
        DataClearManager manager = new DataClearManager();
        WebView webView = new WebView(null);

        manager.clearHttpCache(webView);

        TestAssert.assertEquals(1, WebView.clearCacheCount());
        TestAssert.assertTrue(WebView.lastClearCacheIncludeDiskFiles());
    }

    private static void deleteAllCookiesCallsCookieManager() {
        resetState();
        DataClearManager manager = new DataClearManager();

        manager.deleteAllCookies();

        TestAssert.assertEquals(1, CookieManager.removeAllCookiesCount());
    }

    private static void clearDomStorageCallsDeleteAllData() {
        resetState();
        DataClearManager manager = new DataClearManager();

        manager.clearDomStorage();

        TestAssert.assertEquals(1, WebStorage.deleteAllDataCount());
    }

    private static void clearDomStorageOriginCallsDeleteOrigin() {
        resetState();
        DataClearManager manager = new DataClearManager();

        manager.clearDomStorage("https://example.com");

        TestAssert.assertEquals(1, WebStorage.deleteOriginCount());
        TestAssert.assertEquals("https://example.com", WebStorage.lastDeleteOrigin());
    }

    private static void reloadAndBypassCacheSetsNoCacheThenRestoresOnPageFinished() {
        resetState();
        DataClearManager manager = new DataClearManager();
        WebView webView = new WebView(null);

        manager.reloadAndBypassCache(webView);

        TestAssert.assertEquals(WebSettings.LOAD_NO_CACHE, webView.getSettings().getCacheMode());
        TestAssert.assertEquals(1, WebView.reloadCount());

        manager.onPageFinished(webView);

        TestAssert.assertEquals(WebSettings.LOAD_DEFAULT, webView.getSettings().getCacheMode());
    }

    private static void resetState() {
        WebView.resetDataClearCounters();
        CookieManager.resetRemoveAllCookiesCount();
        WebStorage.reset();
    }
}
