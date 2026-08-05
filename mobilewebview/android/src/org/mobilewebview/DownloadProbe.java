package org.mobilewebview;

import android.util.Log;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * HEAD-probe helpers for unnamed downloadUrl() (ADR 0005).
 * Returns Disposition/Type/Length; empty metadata on failure.
 */
final class DownloadProbe {
    private static final String TAG = "MobileWebView";
    private static final int TIMEOUT_MS = 10_000;

    private DownloadProbe() {}

    static final class Result {
        final String contentDisposition;
        final String mimeType;
        final long contentLength;

        Result(String contentDisposition, String mimeType, long contentLength) {
            this.contentDisposition = contentDisposition != null ? contentDisposition : "";
            this.mimeType = mimeType != null ? mimeType : "";
            this.contentLength = contentLength;
        }

        static Result empty() {
            return new Result("", "", -1);
        }
    }

    /**
     * Synchronous HEAD probe. Pass cookie header value or null/empty to omit.
     * Caller owns threading and CookieManager access.
     */
    static Result probeHeaders(String url, String userAgent, String cookieHeader) {
        if (url == null || url.isEmpty()) {
            return Result.empty();
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            if (userAgent != null && !userAgent.isEmpty()) {
                conn.setRequestProperty("User-Agent", userAgent);
            }
            if (cookieHeader != null && !cookieHeader.isEmpty()) {
                conn.setRequestProperty("Cookie", cookieHeader);
            }
            conn.connect();
            final int code = conn.getResponseCode();
            if (code < 200 || code >= 400) {
                return Result.empty();
            }
            return new Result(
                    conn.getHeaderField("Content-Disposition"),
                    conn.getContentType(),
                    conn.getContentLengthLong());
        } catch (Exception e) {
            Log.w(TAG, "probeDownload failed for " + url, e);
            return Result.empty();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
