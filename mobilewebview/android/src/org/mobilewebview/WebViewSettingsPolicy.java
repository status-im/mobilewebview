package org.mobilewebview;

import android.webkit.WebSettings;

/**
 * The WebSettings every MobileWebView runs with, in one place (ADR 0001).
 * Pure: it only writes settings, so the JVM tests state the whole policy.
 */
final class WebViewSettingsPolicy {
    private WebViewSettingsPolicy() {}

    static void apply(WebSettings settings) {
        if (settings == null) {
            return;
        }

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        // Top-level file:// for opening Downloads / local media. Web→file stays
        // blocked; flags below keep file-origin JS from reading other local files.
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        // The WebView defaults lay a page out at desktop width and draw a local
        // image at its natural size, with pinch zoom off. A browser fits both to
        // the screen and lets the user zoom.
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
    }
}
