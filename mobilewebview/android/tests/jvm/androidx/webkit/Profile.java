package androidx.webkit;

import android.webkit.CookieManager;
import android.webkit.WebStorage;

public final class Profile {
    private final String mName;
    private final CookieManager mCookieManager = new CookieManager();
    private final WebStorage mWebStorage = new WebStorage();

    Profile(String name) {
        mName = name;
    }

    public String getName() {
        return mName;
    }

    public CookieManager getCookieManager() {
        return mCookieManager;
    }

    public WebStorage getWebStorage() {
        return mWebStorage;
    }
}
