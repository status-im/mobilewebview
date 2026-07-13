package org.mobilewebview;

import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;

import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
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

        // Bugbot RED repros — expected to fail until GREEN fixes land.
        profileScopedClearsUseProfileManagersNotSingletons();
        deleteAllCookiesDoneFiresOnlyAfterAsyncCallback();
        clearSiteDataDoneFiresOnlyAfterDeferredCallback();
        clearHttpCacheInvokesDoneAfterClear();
        clearDomStorageInvokesDoneAfterDelete();
        overlappingDeleteAllCookiesBothDonesFire();
        overlappingClearSiteDataBothDonesFire();

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

    /**
     * Bugbot #2: clears must hit the active Profile's CookieManager / WebStorage,
     * not the process-wide singletons.
     */
    private static void profileScopedClearsUseProfileManagersNotSingletons() {
        resetState();
        WebViewFeature.setDeleteBrowsingDataSupported(true);
        Profile profile = ProfileStore.getInstance().getOrCreateProfile("Profile_A");
        DataClearManager manager = new DataClearManager();

        final int globalCookiesBefore = CookieManager.removeAllCookiesCount();
        final int globalStorageBefore = WebStorage.deleteAllDataCount();

        manager.deleteAllCookies(profile, null);
        manager.clearDomStorage(profile);
        manager.clearSiteData(profile, "https://example.com", null);

        TestAssert.assertEquals(
                "profile CookieManager must receive deleteAllCookies",
                1, profile.getCookieManager().instanceRemoveAllCookiesCount());
        TestAssert.assertEquals(
                "global CookieManager must not be used for profile-scoped clear",
                globalCookiesBefore, CookieManager.removeAllCookiesCount());

        TestAssert.assertEquals(
                "profile WebStorage must receive clearDomStorage",
                1, profile.getWebStorage().instanceDeleteAllDataCount());
        TestAssert.assertEquals(
                "global WebStorage must not be used for profile-scoped clearDomStorage",
                globalStorageBefore, WebStorage.deleteAllDataCount());

        TestAssert.assertTrue(
                "clearSiteData must pass the profile WebStorage to WebStorageCompat",
                profile.getWebStorage() == WebStorageCompat.lastWebStorage());
        TestAssert.assertTrue(
                "clearSiteData must not pass the process-wide WebStorage singleton",
                WebStorage.getInstance() != WebStorageCompat.lastWebStorage());
    }

    /**
     * Bugbot #3: deleteAllCookies(done) must not run done until removeAllCookies'
     * ValueCallback fires.
     */
    private static void deleteAllCookiesDoneFiresOnlyAfterAsyncCallback() {
        resetState();
        CookieManager.setDeferCallbacks(true);
        DataClearManager manager = new DataClearManager();
        final boolean[] doneRan = {false};

        manager.deleteAllCookies(() -> doneRan[0] = true);

        TestAssert.assertTrue(
                "done must not run before CookieManager callback",
                !doneRan[0]);
        TestAssert.assertTrue(
                "removeAllCookies must receive a non-null ValueCallback",
                CookieManager.pendingCallback() != null);

        CookieManager.runPendingCallback();

        TestAssert.assertTrue("done must run after CookieManager callback", doneRan[0]);
    }

    /**
     * Bugbot #1: clearSiteData(done) must not run done until
     * WebStorageCompat.deleteBrowsingDataForSite's doneCallback fires.
     */
    private static void clearSiteDataDoneFiresOnlyAfterDeferredCallback() {
        resetState();
        WebViewFeature.setDeleteBrowsingDataSupported(true);
        WebStorageCompat.setDeferCallbacks(true);
        DataClearManager manager = new DataClearManager();
        final boolean[] doneRan = {false};

        manager.clearSiteData("https://example.com", () -> doneRan[0] = true);

        TestAssert.assertTrue(
                "done must not run before WebStorageCompat callback",
                !doneRan[0]);
        TestAssert.assertTrue(
                "deleteBrowsingDataForSite must capture the done callback",
                WebStorageCompat.pendingCallback() != null);

        WebStorageCompat.runPendingCallback();

        TestAssert.assertTrue("done must run after WebStorageCompat callback", doneRan[0]);
    }

    /** Darwin-like: sync cache clear still reports done after the work. */
    private static void clearHttpCacheInvokesDoneAfterClear() {
        resetState();
        DataClearManager manager = new DataClearManager();
        WebView webView = new WebView(null);
        final boolean[] doneRan = {false};

        manager.clearHttpCache(webView, () -> doneRan[0] = true);

        TestAssert.assertEquals(1, WebView.clearCacheCount());
        TestAssert.assertTrue("done must run after clearCache", doneRan[0]);
    }

    /** Darwin-like: sync DOM storage clear still reports done after the work. */
    private static void clearDomStorageInvokesDoneAfterDelete() {
        resetState();
        DataClearManager manager = new DataClearManager();
        final boolean[] doneRan = {false};

        manager.clearDomStorage(null, () -> doneRan[0] = true);

        TestAssert.assertEquals(1, WebStorage.deleteAllDataCount());
        TestAssert.assertTrue("done must run after deleteAllData", doneRan[0]);
    }

    /** Overlapping deleteAllCookies must each get their own done (no single-slot clobber). */
    private static void overlappingDeleteAllCookiesBothDonesFire() {
        resetState();
        CookieManager.setDeferCallbacks(true);
        DataClearManager manager = new DataClearManager();
        final boolean[] doneA = {false};
        final boolean[] doneB = {false};

        manager.deleteAllCookies(() -> doneA[0] = true);
        manager.deleteAllCookies(() -> doneB[0] = true);

        TestAssert.assertEquals(2, CookieManager.pendingCallbackCount());
        TestAssert.assertTrue("first done must wait", !doneA[0]);
        TestAssert.assertTrue("second done must wait", !doneB[0]);

        CookieManager.runPendingCallback();
        TestAssert.assertTrue("first done after first callback", doneA[0]);
        TestAssert.assertTrue("second done still pending", !doneB[0]);

        CookieManager.runPendingCallback();
        TestAssert.assertTrue("second done after second callback", doneB[0]);
    }

    /** Overlapping clearSiteData must each get their own done (no single-slot clobber). */
    private static void overlappingClearSiteDataBothDonesFire() {
        resetState();
        WebViewFeature.setDeleteBrowsingDataSupported(true);
        WebStorageCompat.setDeferCallbacks(true);
        DataClearManager manager = new DataClearManager();
        final boolean[] doneA = {false};
        final boolean[] doneB = {false};

        manager.clearSiteData("https://a.example", () -> doneA[0] = true);
        manager.clearSiteData("https://b.example", () -> doneB[0] = true);

        TestAssert.assertEquals(2, WebStorageCompat.pendingCallbackCount());
        TestAssert.assertTrue("first done must wait", !doneA[0]);
        TestAssert.assertTrue("second done must wait", !doneB[0]);

        WebStorageCompat.runPendingCallback();
        TestAssert.assertTrue("first done after first callback", doneA[0]);
        TestAssert.assertTrue("second done still pending", !doneB[0]);

        WebStorageCompat.runPendingCallback();
        TestAssert.assertTrue("second done after second callback", doneB[0]);
    }

    private static void resetState() {
        WebView.resetDataClearCounters();
        CookieManager.resetRemoveAllCookiesCount();
        CookieManager.resetFlushCount();
        WebStorage.reset();
        WebStorageCompat.reset();
        ProfileStore.reset();
        WebViewFeature.setDeleteBrowsingDataSupported(true);
    }
}
