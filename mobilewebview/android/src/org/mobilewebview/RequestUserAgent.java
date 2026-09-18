package org.mobilewebview;

import android.content.Context;
import android.webkit.WebSettings;

/**
 * User agent for requests the library sends itself (download probe, self-fetch)
 * (ADR 0006). Needs no WebView, so it is safe on any thread.
 */
final class RequestUserAgent {
    private RequestUserAgent() {}

    /** The host override, else the platform default, else "" (anonymous request). */
    static String resolve(String httpUserAgent, Context context) {
        if (httpUserAgent != null && !httpUserAgent.isEmpty()) {
            return httpUserAgent;
        }
        if (context == null) {
            return "";
        }
        // Throws on devices with no WebView provider.
        try {
            String agent = WebSettings.getDefaultUserAgent(context);
            return agent != null ? agent : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }
}
