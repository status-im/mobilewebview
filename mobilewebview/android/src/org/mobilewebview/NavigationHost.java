package org.mobilewebview;

import android.net.Uri;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

interface NavigationHost {
    /** False for a local URL outside this view's Read Grant: the navigation is cancelled. */
    boolean permitsNavigation(String url);
    /**
     * A response this view gives itself (the PDF viewer) or a refusal (local
     * content outside the Read Grant); null lets the WebView load the request.
     * Called off the UI thread.
     */
    WebResourceResponse interceptRequest(String url, boolean mainFrame);
    void onNavigationStarted(String url);
    void onNavigationFinished(String url);
    void onMainFrameError();
    void onNavigationLifecycle(WebView view, String url,
                               boolean warnIfBridgeMissing,
                               ScriptInjectionPhase phase);
    boolean handleCustomScheme(WebView view, Uri uri);
}
