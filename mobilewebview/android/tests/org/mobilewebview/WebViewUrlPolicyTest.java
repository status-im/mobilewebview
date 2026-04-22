package org.mobilewebview;

public final class WebViewUrlPolicyTest {
    public static void main(String[] args) {
        schemeWhitelist();
        httpFallback();
        System.out.println("WebViewUrlPolicyTest passed");
    }

    private static void schemeWhitelist() {
        assertTrue(WebViewUrlPolicy.isSchemeLeftToWebView("https"));
        assertTrue(WebViewUrlPolicy.isSchemeLeftToWebView("HTTP"));
        assertTrue(WebViewUrlPolicy.isSchemeLeftToWebView("about"));
        assertFalse(WebViewUrlPolicy.isSchemeLeftToWebView("tel"));
        assertFalse(WebViewUrlPolicy.isSchemeLeftToWebView("intent"));
        assertFalse(WebViewUrlPolicy.isSchemeLeftToWebView(null));
    }

    private static void httpFallback() {
        assertTrue(WebViewUrlPolicy.isHttpOrHttpsUrlForFallback("https://maps.google.com/x"));
        assertTrue(WebViewUrlPolicy.isHttpOrHttpsUrlForFallback("http://example.com"));
        assertFalse(WebViewUrlPolicy.isHttpOrHttpsUrlForFallback("javascript:alert(1)"));
        assertFalse(WebViewUrlPolicy.isHttpOrHttpsUrlForFallback("intent://x"));
        assertFalse(WebViewUrlPolicy.isHttpOrHttpsUrlForFallback(null));
        assertFalse(WebViewUrlPolicy.isHttpOrHttpsUrlForFallback(""));
    }

    private static void assertTrue(boolean value) {
        if (!value) {
            throw new AssertionError("Expected true");
        }
    }

    private static void assertFalse(boolean value) {
        if (value) {
            throw new AssertionError("Expected false");
        }
    }
}
