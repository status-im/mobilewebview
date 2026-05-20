package org.mobilewebview;

import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Locale;

final class MobileWebViewClient extends WebViewClient {
    private static final String TAG = "MobileWebView";

    private final NavigationHost mHost;

    MobileWebViewClient(NavigationHost host) {
        mHost = host;
    }

    @Override
    public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
        Log.d(TAG, "onPageStarted: " + url);
        mHost.onNavigationStarted(url);
        mHost.onNavigationLifecycle(view, url, true, ScriptInjectionPhase.ON_PAGE_STARTED);
    }

    @Override
    public void onPageCommitVisible(WebView view, String url) {
        mHost.onNavigationLifecycle(view, url, false, ScriptInjectionPhase.ON_PAGE_COMMIT_VISIBLE);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        Log.d(TAG, "onPageFinished: " + url);
        mHost.onNavigationLifecycle(view, url, false, ScriptInjectionPhase.NONE);
        mHost.onNavigationFinished(url);
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request,
                               WebResourceError error) {
        if (request.isForMainFrame()) {
            Log.e(TAG, "onReceivedError: " + error.getDescription());
            mHost.onMainFrameError();
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        if (url == null) {
            return false;
        }
        // Pre-24 only; no isForMainFrame / hasGesture — stricter only on API 24+.
        return mHost.handleCustomScheme(view, Uri.parse(url));
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        if (request == null || !request.isForMainFrame() || request.getUrl() == null) {
            return false;
        }
        Uri u = request.getUrl();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            String sch = u.getScheme();
            String sl = sch != null ? sch.toLowerCase(Locale.ROOT) : "";
            if (!"intent".equals(sl) && !request.hasGesture()) {
                return false;
            }
        }
        return mHost.handleCustomScheme(view, u);
    }
}
