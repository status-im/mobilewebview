package org.mobilewebview;

import android.webkit.WebSettings;
import android.webkit.WebView;

public final class ProbeRequestTest {
    public static void main(String[] args) {
        shouldPreferHostUserAgentWithoutTouchingSettings();
        shouldOmitCookiesOffTheRecord();
        shouldFallBackToEmptyUserAgentWhenWebViewIsDead();
        shouldOmitCookiesWhenCookieAccessFails();
        System.out.println("ProbeRequestTest passed");
    }

    private static void shouldPreferHostUserAgentWithoutTouchingSettings() {
        ProbeRequest request = ProbeRequest.resolve("host-agent", deadWebView(), false,
                "https://example.org/file.bin");
        assertEquals("host-agent", request.userAgent);
    }

    private static void shouldOmitCookiesOffTheRecord() {
        ProbeRequest request = ProbeRequest.resolve("host-agent", null, true,
                "https://example.org/file.bin");
        assertEquals(null, request.cookieHeader);
    }

    // A download can be re-issued against a WebView the platform already tore
    // down; probing must degrade to an anonymous request, never throw.
    private static void shouldFallBackToEmptyUserAgentWhenWebViewIsDead() {
        ProbeRequest request = ProbeRequest.resolve("", deadWebView(), false,
                "https://example.org/file.bin");
        assertEquals("", request.userAgent);
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

    private static WebView deadWebView() {
        return new WebView(null) {
            @Override
            public WebSettings getSettings() {
                throw new IllegalStateException("WebView is destroyed");
            }
        };
    }

    private static void assertEquals(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }
}
