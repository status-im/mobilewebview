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

    private static void resetState() {
        ProfileStore.reset();
        WebViewCompat.reset();
        CookieManager.resetFlushCount();
        WebViewFeature.setMultiProfileSupported(true);
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
