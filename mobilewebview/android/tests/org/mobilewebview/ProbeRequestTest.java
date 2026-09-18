package org.mobilewebview;

import android.content.Context;
import android.webkit.WebSettings;

public final class ProbeRequestTest {
    public static void main(String[] args) {
        shouldPreferHostUserAgentWithoutTouchingSettings();
        shouldOmitCookiesOffTheRecord();
        shouldUsePlatformDefaultUserAgentWhenHostSetsNone();
        shouldOmitCookiesWhenCookieAccessFails();
        System.out.println("ProbeRequestTest passed");
    }

    private static void shouldPreferHostUserAgentWithoutTouchingSettings() {
        ProbeRequest request = ProbeRequest.resolve("host-agent", new Context(), false,
                "https://example.org/file.bin");
        assertEquals("host-agent", request.userAgent);
    }

    private static void shouldOmitCookiesOffTheRecord() {
        ProbeRequest request = ProbeRequest.resolve("host-agent", null, true,
                "https://example.org/file.bin");
        assertEquals(null, request.cookieHeader);
    }

    // Probes run from the Qt thread: an empty host agent resolves to the
    // platform default without touching the WebView.
    private static void shouldUsePlatformDefaultUserAgentWhenHostSetsNone() {
        WebSettings.resetDefaultUserAgent();
        ProbeRequest request = ProbeRequest.resolve("", new Context(), false,
                "https://example.org/file.bin");
        assertEquals("fake-default-agent", request.userAgent);
    }

    // CookieManager.getInstance() dies on devices with no WebView provider;
    // the probe must go on without cookies, never throw.
    private static void shouldOmitCookiesWhenCookieAccessFails() {
        android.webkit.CookieManager.failGetCookieWith(
                new IllegalStateException("no WebView provider"));
        try {
            ProbeRequest request = ProbeRequest.resolve("host-agent", null, false,
                    "https://example.org/file.bin");
            assertEquals(null, request.cookieHeader);
            assertEquals("host-agent", request.userAgent);
        } finally {
            android.webkit.CookieManager.resetGetCookieFailure();
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }
}
