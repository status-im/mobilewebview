package org.mobilewebview;

import android.webkit.WebView;

interface BridgeInjectorHost {
    WebView webView();
    BridgeState snapshot();
    String currentOrigin();
}
