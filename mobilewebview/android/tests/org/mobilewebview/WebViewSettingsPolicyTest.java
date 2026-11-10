package org.mobilewebview;

import android.webkit.WebSettings;

public final class WebViewSettingsPolicyTest {
    public static void main(String[] args) {
        shouldEnableTheWebPlatformFeaturesThePagesNeed();
        shouldGiveABrowsingViewNoLocalAccess();
        shouldOpenOnlyTheSchemeAPreviewGrantNeeds();
        shouldFitContentToTheScreenAndAllowPinchZoom();
        System.out.println("WebViewSettingsPolicyTest passed");
    }

    private static WebSettings applied() {
        WebSettings settings = new WebSettings();
        WebViewSettingsPolicy.apply(settings);
        return settings;
    }

    private static void shouldEnableTheWebPlatformFeaturesThePagesNeed() {
        WebSettings settings = applied();
        assertEquals(Boolean.TRUE, settings.javaScriptEnabled());
        assertEquals(Boolean.TRUE, settings.domStorageEnabled());
        assertEquals(Boolean.TRUE, settings.databaseEnabled());
        assertEquals(Integer.valueOf(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE),
                settings.mixedContentMode());
    }

    // A browsing view reaches no file:// and no content:// at all: the app's own
    // files (account data, logs) sit one file:// URL away.
    private static void shouldGiveABrowsingViewNoLocalAccess() {
        WebSettings settings = applied();
        assertEquals(Boolean.FALSE, settings.allowFileAccess());
        assertEquals(Boolean.FALSE, settings.allowContentAccess());
        assertEquals(Boolean.FALSE, settings.allowFileAccessFromFileURLs());
        assertEquals(Boolean.FALSE, settings.allowUniversalAccessFromFileURLs());
    }

    // A preview reaches local content only over the scheme of its Read Grant;
    // LocalReadGrant enforces the one file within it.
    private static void shouldOpenOnlyTheSchemeAPreviewGrantNeeds() {
        WebSettings settings = applied();
        WebViewSettingsPolicy.applyReadGrant(settings, LocalReadGrant.forPreview(
                "file:///storage/emulated/0/Download/x.pdf", ""));
        assertEquals(Boolean.TRUE, settings.allowFileAccess());
        assertEquals(Boolean.FALSE, settings.allowContentAccess());

        WebViewSettingsPolicy.applyReadGrant(settings, LocalReadGrant.forPreview(
                "content://com.example.provider/1", ""));
        assertEquals(Boolean.FALSE, settings.allowFileAccess());
        assertEquals(Boolean.TRUE, settings.allowContentAccess());

        WebViewSettingsPolicy.applyReadGrant(settings, LocalReadGrant.NONE);
        assertEquals(Boolean.FALSE, settings.allowFileAccess());
        assertEquals(Boolean.FALSE, settings.allowContentAccess());
        // A file origin never reads other local files, granted or not.
        assertEquals(Boolean.FALSE, settings.allowFileAccessFromFileURLs());
        assertEquals(Boolean.FALSE, settings.allowUniversalAccessFromFileURLs());
    }

    // The WebView defaults draw a page (or a local image) at its natural size
    // with pinch zoom off; a browser fits it and lets the user zoom.
    private static void shouldFitContentToTheScreenAndAllowPinchZoom() {
        WebSettings settings = applied();
        assertEquals(Boolean.TRUE, settings.useWideViewPort());
        assertEquals(Boolean.TRUE, settings.loadWithOverviewMode());
        assertEquals(Boolean.TRUE, settings.builtInZoomControls());
        // Pinch only: the on-screen +/- buttons are long gone from mobile browsers.
        assertEquals(Boolean.FALSE, settings.displayZoomControls());
    }

    private static void assertEquals(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }
}
