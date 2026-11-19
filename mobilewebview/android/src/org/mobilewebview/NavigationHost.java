package org.mobilewebview;

import android.net.Uri;
import android.webkit.WebView;

interface NavigationHost {
    void onNavigationStarted(String url);
    void onNavigationFinished(String url);
    void onMainFrameError();
    void onNavigationLifecycle(WebView view, String url,
                               boolean warnIfBridgeMissing,
                               ScriptInjectionPhase phase);
    boolean handleCustomScheme(WebView view, Uri uri);
    /** Must return true, or the app process is killed with the renderer. */
    boolean onRenderProcessGone(WebView view, boolean crashed);
}
