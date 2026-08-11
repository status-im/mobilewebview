package org.mobilewebview;

import android.webkit.CookieManager;
import android.webkit.WebView;

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

    static ProbeRequest resolve(String httpUserAgent, WebView webView,
                                boolean offTheRecord, String url) {
        String agent = httpUserAgent;
        if ((agent == null || agent.isEmpty()) && webView != null) {
            // The platform may have torn the WebView down already; probe
            // anonymously rather than dropping the download request.
            try {
                agent = webView.getSettings().getUserAgentString();
            } catch (RuntimeException ignored) {
                agent = "";
            }
        }
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
