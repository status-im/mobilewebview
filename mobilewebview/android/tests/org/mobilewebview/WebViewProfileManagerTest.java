package org.mobilewebview;

import android.webkit.CookieManager;
import android.webkit.WebView;

import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

public final class WebViewProfileManagerTest {
    public static void main(String[] args) {
        standardModeUsesNamedProfile();
        incognitoUsesDeletableProfileAndSweepRemovesOrphans();
        incognitoDestroyDeletesProfile();
        standardModeFlushesCookiesOnNavigationFinish();
        incognitoDoesNotFlushCookies();
        clearsReachTheLiveViewsProfile();
        clearsReachTheNamedProfileWhileTheViewIsGone();
        clearsSkipAnIncognitoProfileGoneWithItsView();
        clearsUseTheDefaultWithoutAProfile();
        anIncognitoProfileStillInUseIsDeletedOnceReleased();
        anIncognitoProfileNeverReleasedIsLeftToTheSweep();
        System.out.println("WebViewProfileManagerTest passed");
    }

    private static void standardModeUsesNamedProfile() {
        resetState();
        WebViewProfileManager manager = new WebViewProfileManager();
        WebView webView = new WebView(null);

        String profileName = manager.configureProfile(webView, "Profile_A", false);

        assertEquals("Profile_A", profileName);
        assertEquals("Profile_A", WebViewCompat.lastProfileName());
    }

    private static void incognitoUsesDeletableProfileAndSweepRemovesOrphans() {
        resetState();
        WebViewProfileManager manager = new WebViewProfileManager();
        WebView webView = new WebView(null);

        String profileName = manager.configureProfile(webView, "Profile_A", true);

        assertTrue(profileName != null && profileName.startsWith("Incognito_"));
        ProfileStore.getInstance().getOrCreateProfile("Incognito_orphan");

        WebViewProfileManager.sweepOrphanedIncognitoProfiles();

        assertFalse(ProfileStore.getInstance().getAllProfileNames().contains("Incognito_orphan"));
    }

    private static void incognitoDestroyDeletesProfile() {
        resetState();
        WebViewProfileManager manager = new WebViewProfileManager();
        WebView webView = new WebView(null);

        String profileName = manager.configureProfile(webView, "Profile_A", true);
        assertTrue(profileName != null);
        assertTrue(ProfileStore.getInstance().getAllProfileNames().contains(profileName));

        manager.destroyProfile(profileName, true);

        assertFalse(ProfileStore.getInstance().getAllProfileNames().contains(profileName));
    }

    private static void standardModeFlushesCookiesOnNavigationFinish() {
        resetState();
        WebViewProfileManager manager = new WebViewProfileManager();

        manager.flushCookiesIfPersistent(false);

        assertEquals(1, CookieManager.flushCount());
    }

    private static void incognitoDoesNotFlushCookies() {
        resetState();
        WebViewProfileManager manager = new WebViewProfileManager();

        manager.flushCookiesIfPersistent(true);

        assertEquals(0, CookieManager.flushCount());
    }

    private static void clearsReachTheLiveViewsProfile() {
        resetState();
        WebViewProfileManager manager = new WebViewProfileManager();
        WebView webView = new WebView(null);
        manager.configureProfile(webView, "Profile_A", false);

        WebViewProfileManager.ClearTarget target = manager.clearTarget(webView, "Profile_A", false);

        assertFalse(target.skip);
        assertEquals("Profile_A", target.profile.getName());
    }

    // The renderer is gone, the view with it: clears must not fall to the default profile.
    private static void clearsReachTheNamedProfileWhileTheViewIsGone() {
        resetState();
        WebViewProfileManager manager = new WebViewProfileManager();
        manager.configureProfile(new WebView(null), "Profile_A", false);

        WebViewProfileManager.ClearTarget target = manager.clearTarget(null, "Profile_A", false);

        assertFalse(target.skip);
        assertEquals("Profile_A", target.profile.getName());
    }

    private static void clearsSkipAnIncognitoProfileGoneWithItsView() {
        resetState();
        WebViewProfileManager manager = new WebViewProfileManager();

        assertTrue(manager.clearTarget(null, null, true).skip);
    }

    private static void clearsUseTheDefaultWithoutAProfile() {
        resetState();
        WebViewProfileManager manager = new WebViewProfileManager();

        WebViewProfileManager.ClearTarget target = manager.clearTarget(null, null, false);

        assertFalse(target.skip);
        assertTrue(target.profile == null);
    }

    // WebView releases a destroyed view's profile only later: the delete is retried.
    private static void anIncognitoProfileStillInUseIsDeletedOnceReleased() {
        resetState();
        final java.util.List<Runnable> retries = new java.util.ArrayList<>();
        WebViewProfileManager.setRetryScheduler((task, delayMs) -> retries.add(task));
        WebViewProfileManager manager = new WebViewProfileManager();
        String name = manager.configureProfile(new WebView(null), "Profile_A", true);
        ProfileStore.setInUse(name, true);

        manager.destroyProfile(name, true);
        assertTrue(ProfileStore.getInstance().getAllProfileNames().contains(name));
        assertTrue(retries.size() == 1);

        ProfileStore.setInUse(name, false);
        retries.remove(0).run();

        assertFalse(ProfileStore.getInstance().getAllProfileNames().contains(name));
        assertTrue(retries.isEmpty());
    }

    private static void anIncognitoProfileNeverReleasedIsLeftToTheSweep() {
        resetState();
        final java.util.List<Runnable> retries = new java.util.ArrayList<>();
        WebViewProfileManager.setRetryScheduler((task, delayMs) -> retries.add(task));
        WebViewProfileManager manager = new WebViewProfileManager();
        String name = manager.configureProfile(new WebView(null), "Profile_A", true);
        ProfileStore.setInUse(name, true);

        manager.destroyProfile(name, true);
        int runs = 0;
        while (!retries.isEmpty() && runs < 100) {
            retries.remove(0).run();
            ++runs;
        }

        assertTrue("retries stop", runs < 100);
        assertTrue(ProfileStore.getInstance().getAllProfileNames().contains(name));
        ProfileStore.setInUse(name, false);
        WebViewProfileManager.sweepOrphanedIncognitoProfiles();
        assertFalse(ProfileStore.getInstance().getAllProfileNames().contains(name));
    }

    private static void resetState() {
        ProfileStore.reset();
        WebViewCompat.reset();
        CookieManager.resetFlushCount();
        WebViewFeature.setMultiProfileSupported(true);
        WebViewProfileManager.setRetryScheduler((task, delayMs) -> { });
    }

    private static void assertTrue(String message, boolean condition) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError("Expected true");
        }
    }

    private static void assertFalse(boolean condition) {
        if (condition) {
            throw new AssertionError("Expected false");
        }
    }

    private static void assertEquals(String expected, String actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Expected [" + expected + "], got [" + actual + "]");
        }
    }

    private static void assertEquals(int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError("Expected [" + expected + "], got [" + actual + "]");
        }
    }
}
