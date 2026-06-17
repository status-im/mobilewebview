package androidx.webkit;

import android.webkit.WebView;

import java.util.Set;

public final class WebViewCompat {
    private static String sLastProfileName;

    private WebViewCompat() {}

    public static Object addDocumentStartJavaScript(
            WebView webView, String script, Set<String> allowedOriginRules) {
        return webView.addDocumentStartJavaScript(script);
    }

    public static void setProfile(WebView webView, Profile profile) {
        sLastProfileName = profile != null ? profile.getName() : null;
    }

    public static String lastProfileName() {
        return sLastProfileName;
    }

    public static void reset() {
        sLastProfileName = null;
    }
}
