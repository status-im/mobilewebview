package org.mobilewebview;

import android.webkit.WebSettings;

public final class WebViewSettingsPolicyTest {
    public static void main(String[] args) {
        shouldEnableTheWebPlatformFeaturesThePagesNeed();
        shouldAllowTopLevelFilesWithoutLettingFileOriginsReadOthers();
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

    // Downloads open as top-level file:// pages, but a file origin must not read
    // other local files.
    private static void shouldAllowTopLevelFilesWithoutLettingFileOriginsReadOthers() {
        WebSettings settings = applied();
        assertEquals(Boolean.TRUE, settings.allowFileAccess());
        assertEquals(Boolean.TRUE, settings.allowContentAccess());
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
