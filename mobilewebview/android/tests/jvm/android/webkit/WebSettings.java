package android.webkit;

/** Fake WebSettings for JVM unit tests; not used on device. */
public class WebSettings {
    public static final int LOAD_DEFAULT = -1;
    public static final int LOAD_CACHE_ELSE_NETWORK = 1;
    public static final int LOAD_NO_CACHE = 2;
    public static final int LOAD_CACHE_ONLY = 3;
    public static final int MIXED_CONTENT_COMPATIBILITY_MODE = 2;

    private int mCacheMode = LOAD_DEFAULT;

    public void setJavaScriptEnabled(boolean flag) {}

    public void setDomStorageEnabled(boolean flag) {}

    public void setDatabaseEnabled(boolean flag) {}

    public void setAllowFileAccess(boolean flag) {}

    public void setAllowContentAccess(boolean flag) {}

    public void setAllowFileAccessFromFileURLs(boolean flag) {}

    public void setAllowUniversalAccessFromFileURLs(boolean flag) {}

    public void setMixedContentMode(int mode) {}

    public void setCacheMode(int mode) {
        mCacheMode = mode;
    }

    public int getCacheMode() {
        return mCacheMode;
    }

    public String getUserAgentString() {
        return "fake-settings-agent";
    }
}
