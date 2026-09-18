package org.mobilewebview;

import android.content.Context;
import android.webkit.WebSettings;

public final class RequestUserAgentTest {
    public static void main(String[] args) {
        shouldPreferHostUserAgentWithoutAskingThePlatform();
        shouldUsePlatformDefaultWhenHostSetsNone();
        shouldFallBackToEmptyWithoutContext();
        shouldFallBackToEmptyWhenPlatformDefaultFails();
        System.out.println("RequestUserAgentTest passed");
    }

    private static void shouldPreferHostUserAgentWithoutAskingThePlatform() {
        WebSettings.resetDefaultUserAgent();
        assertEquals("host-agent", RequestUserAgent.resolve("host-agent", new Context()));
        assertEquals(0, WebSettings.defaultUserAgentCalls());
    }

    // Empty means "platform default": resolved without a WebView, so it is safe
    // off the WebView's UI thread (the download worker, the Qt thread).
    private static void shouldUsePlatformDefaultWhenHostSetsNone() {
        WebSettings.resetDefaultUserAgent();
        assertEquals("fake-default-agent", RequestUserAgent.resolve("", new Context()));
        assertEquals("fake-default-agent", RequestUserAgent.resolve(null, new Context()));
    }

    private static void shouldFallBackToEmptyWithoutContext() {
        WebSettings.resetDefaultUserAgent();
        assertEquals("", RequestUserAgent.resolve("", null));
    }

    // No WebView provider on the device: send the request anonymously, never throw.
    private static void shouldFallBackToEmptyWhenPlatformDefaultFails() {
        WebSettings.failGetDefaultUserAgentWith(new IllegalStateException("no WebView provider"));
        try {
            assertEquals("", RequestUserAgent.resolve("", new Context()));
        } finally {
            WebSettings.resetDefaultUserAgent();
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }
}
