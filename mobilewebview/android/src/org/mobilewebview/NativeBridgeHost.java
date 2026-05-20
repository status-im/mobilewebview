package org.mobilewebview;

import android.webkit.WebView;

import java.util.List;

interface NativeBridgeHost {
    long nativePtr();
    String currentMainFrameOrigin();
    List<String> allowedOriginsSnapshot();
    WebView webView();
    void onWebMessage(String message, String origin);
}
