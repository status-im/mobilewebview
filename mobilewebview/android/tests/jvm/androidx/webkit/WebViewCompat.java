package androidx.webkit;

import android.webkit.WebView;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

public final class WebViewCompat {
    private static String sLastProfileName;
    private static final Map<WebView, String> sProfilesByView = new IdentityHashMap<>();

    private WebViewCompat() {}

    public static Object addDocumentStartJavaScript(
            WebView webView, String script, Set<String> allowedOriginRules) {
        return webView.addDocumentStartJavaScript(script);
    }

    public static void setProfile(WebView webView, String profileName) {
        sLastProfileName = profileName;
        if (webView != null) {
            sProfilesByView.put(webView, profileName);
        }
    }

    public static Profile getProfile(WebView webView) {
        if (webView == null) {
            return null;
        }
        String name = sProfilesByView.get(webView);
        if (name == null) {
            return null;
        }
        return ProfileStore.getInstance().getOrCreateProfile(name);
    }

    public static String lastProfileName() {
        return sLastProfileName;
    }

    public static void reset() {
        sLastProfileName = null;
        sProfilesByView.clear();
    }
}
