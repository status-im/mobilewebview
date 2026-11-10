package org.mobilewebview;

import android.graphics.Bitmap;
import android.view.View;
import android.webkit.WebChromeClient;

interface ChromeHost {
    void onTitleChanged(String title);
    void onProgressChanged(int progress);
    void onFavicon(Bitmap icon);
    void onNewWindowRequested(String url, boolean userGesture);
    void onShowFullscreen(View view, WebChromeClient.CustomViewCallback callback);
    void onHideFullscreen();
}
