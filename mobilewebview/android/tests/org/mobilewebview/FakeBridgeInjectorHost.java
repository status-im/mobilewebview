package org.mobilewebview;

import android.webkit.WebView;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class FakeBridgeInjectorHost implements BridgeInjectorHost {
    WebView webView;
    String currentOrigin = "https://app.example";
    BridgeState state = new BridgeState(
        true,
        Collections.singletonList("window.user=true;"),
        "window.bootstrapPage=true;",
        "window.bootstrapBridge=true;",
        Arrays.asList("https://app.example", "https://other.example"));

    @Override
    public WebView webView() {
        return webView;
    }

    @Override
    public BridgeState snapshot() {
        return state;
    }

    @Override
    public String currentOrigin() {
        return currentOrigin;
    }
}
