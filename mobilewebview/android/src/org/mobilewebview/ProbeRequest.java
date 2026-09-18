package org.mobilewebview;

import android.content.Context;
import android.webkit.CookieManager;

/**
 * Request context (user agent, cookie header) for a HEAD probe (ADR 0005).
 * Resolution happens on the caller's thread before the probe thread spawns.
 */
final class ProbeRequest {
    final String userAgent;
    final String cookieHeader;

    private ProbeRequest(String userAgent, String cookieHeader) {
        this.userAgent = userAgent != null ? userAgent : "";
        this.cookieHeader = cookieHeader;
    }

    static ProbeRequest resolve(String httpUserAgent, Context context,
                                boolean offTheRecord, String url) {
        String agent = RequestUserAgent.resolve(httpUserAgent, context);
        String cookies = null;
        if (!offTheRecord) {
            // getInstance() dies on devices with no WebView provider — probe
            // without cookies then.
            try {
                cookies = CookieManager.getInstance().getCookie(url);
            } catch (RuntimeException ignored) {
            }
        }
        return new ProbeRequest(agent, cookies);
    }
}
