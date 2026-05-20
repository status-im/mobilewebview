package androidx.webkit;

import android.webkit.WebView;

import java.util.Set;

public final class WebViewCompat {
    private WebViewCompat() {}

    public static Object addDocumentStartJavaScript(
            WebView webView, String script, Set<String> allowedOriginRules) {
        return webView.addDocumentStartJavaScript(script);
    }
}
