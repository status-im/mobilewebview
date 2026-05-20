package org.mobilewebview;

import android.graphics.Bitmap;

interface ChromeHost {
    void onTitleChanged(String title);
    void onProgressChanged(int progress);
    void onFavicon(Bitmap icon);
    void onNewWindowRequested(String url, boolean userGesture);
}
