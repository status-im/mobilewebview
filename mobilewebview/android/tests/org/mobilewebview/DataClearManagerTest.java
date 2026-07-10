package org.mobilewebview;

import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;

import androidx.webkit.WebStorageCompat;
import androidx.webkit.WebViewFeature;

public final class DataClearManagerTest {
    public static void main(String[] args) {
        clearHttpCacheCallsWebViewClearCache();
        deleteAllCookiesCallsCookieManager();
        clearDomStorageCallsDeleteAllData();
        clearSiteDataCallsDeleteBrowsingDataForSite();
        clearSiteDataSupportedReflectsFeature();
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

    private static void clearSiteDataCallsDeleteBrowsingDataForSite() {
        resetState();
        WebViewFeature.setDeleteBrowsingDataSupported(true);
        DataClearManager manager = new DataClearManager();
        final boolean[] doneRan = {false};

        manager.clearSiteData("https://example.com", () -> doneRan[0] = true);

        TestAssert.assertEquals(1, WebStorageCompat.deleteBrowsingDataForSiteCount());
        TestAssert.assertEquals("https://example.com", WebStorageCompat.lastSite());
        TestAssert.assertTrue(WebStorageCompat.lastCallbackRan());
        TestAssert.assertTrue(doneRan[0]);
    }

    private static void clearSiteDataSupportedReflectsFeature() {
        resetState();
        WebViewFeature.setDeleteBrowsingDataSupported(true);
        TestAssert.assertTrue(DataClearManager.isClearSiteDataSupported());

        WebViewFeature.setDeleteBrowsingDataSupported(false);
        TestAssert.assertTrue(!DataClearManager.isClearSiteDataSupported());

        // Unsupported: clearSiteData must still invoke done and not call compat.
        DataClearManager manager = new DataClearManager();
        final boolean[] doneRan = {false};
        manager.clearSiteData("https://example.com", () -> doneRan[0] = true);
        TestAssert.assertEquals(0, WebStorageCompat.deleteBrowsingDataForSiteCount());
        TestAssert.assertTrue(doneRan[0]);
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
        WebStorageCompat.reset();
        WebViewFeature.setDeleteBrowsingDataSupported(true);
    }
}
