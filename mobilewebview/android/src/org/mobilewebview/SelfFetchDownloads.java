package org.mobilewebview;

import android.content.Context;

/**
 * Self-fetch downloads of one view (ADR 0005): probe, start, pause, resume, cancel.
 * Called on the Qt thread, so it holds no WebView; the User-Agent comes from
 * RequestUserAgent instead.
 */
final class SelfFetchDownloads {
    interface Host {
        String httpUserAgent();
        boolean offTheRecord();
        Context context();
    }

    private final Host mHost;
    private final DownloadFetcher mFetcher;

    SelfFetchDownloads(Host host, DownloadFetcher.Callbacks callbacks) {
        mHost = host;
        mFetcher = new DownloadFetcher(callbacks);
    }

    ProbeRequest probeRequest(String url) {
        return ProbeRequest.resolve(mHost.httpUserAgent(), mHost.context(),
                mHost.offTheRecord(), url);
    }

    void start(long downloadId, String url, String destination) {
        mFetcher.start(downloadId, url, destination, userAgent(), mHost.offTheRecord(),
                mHost.context());
    }

    void pause(long downloadId) {
        mFetcher.pause(downloadId);
    }

    void resume(long downloadId) {
        mFetcher.resume(downloadId, userAgent(), mHost.offTheRecord(), mHost.context());
    }

    void cancel(long downloadId) {
        mFetcher.cancel(downloadId);
    }

    void cancelAll() {
        mFetcher.cancelAll();
    }

    private String userAgent() {
        return RequestUserAgent.resolve(mHost.httpUserAgent(), mHost.context());
    }
}
