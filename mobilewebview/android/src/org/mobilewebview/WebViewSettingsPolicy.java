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

        // No local content until a preview is granted some (applyReadGrant);
        // a file origin never reads other local files.
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
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

    /** Script for a page load, and for the PDF viewer; not for a preview of a file itself. */
    static void allowScripts(WebSettings settings, boolean allowed) {
        if (settings == null) {
            return;
        }
        settings.setJavaScriptEnabled(allowed);
    }

    /** Opens the one scheme a preview's Read Grant needs; NONE closes both. */
    static void applyReadGrant(WebSettings settings, LocalReadGrant grant) {
        if (settings == null) {
            return;
        }
        settings.setAllowFileAccess(grant.allowsFileScheme());
        settings.setAllowContentAccess(grant.allowsContentScheme());
    }
}
