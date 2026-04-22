package org.mobilewebview;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * WebView navigation policy: which schemes must stay in WebView, and safe http(s) fallbacks.
 * No Android types — can be unit-tested on the JVM in CI.
 */
public final class WebViewUrlPolicy {
    private static final Set<String> SCHEMES_LEFT_TO_WEBVIEW = new HashSet<>();

    static {
        for (String s : new String[] {
                "http", "https", "about", "javascript", "data", "file", "blob", "content"
        }) {
            SCHEMES_LEFT_TO_WEBVIEW.add(s);
        }
    }

    private WebViewUrlPolicy() { }

    /**
     * @return true for normal in-WebView handling (http, about, data, etc.)
     */
    public static boolean isSchemeLeftToWebView(String scheme) {
        if (scheme == null) {
            return false;
        }
        return SCHEMES_LEFT_TO_WEBVIEW.contains(scheme.toLowerCase(Locale.ROOT));
    }

    /**
     * {@code browser_fallback_url} must not load javascript: / intent: in WebView
     */
    public static boolean isHttpOrHttpsUrlForFallback(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        try {
            URI u = new URI(url);
            String s = u.getScheme();
            if (s == null) {
                return false;
            }
            return "http".equalsIgnoreCase(s) || "https".equalsIgnoreCase(s);
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
