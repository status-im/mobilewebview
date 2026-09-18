package android.webkit;

import android.content.Context;

/** Fake WebSettings for JVM unit tests; not used on device. */
public class WebSettings {
    public static final int LOAD_DEFAULT = -1;
    public static final int LOAD_CACHE_ELSE_NETWORK = 1;
    public static final int LOAD_NO_CACHE = 2;
    public static final int LOAD_CACHE_ONLY = 3;
    public static final int MIXED_CONTENT_COMPATIBILITY_MODE = 2;

    private int mCacheMode = LOAD_DEFAULT;

    private static RuntimeException sDefaultUserAgentFailure = null;
    private static int sDefaultUserAgentCalls = 0;

    public static String getDefaultUserAgent(Context context) {
        ++sDefaultUserAgentCalls;
        if (sDefaultUserAgentFailure != null) {
            throw sDefaultUserAgentFailure;
        }
        return "fake-default-agent";
    }

    public static void failGetDefaultUserAgentWith(RuntimeException failure) {
        sDefaultUserAgentFailure = failure;
    }

    public static int defaultUserAgentCalls() {
        return sDefaultUserAgentCalls;
    }

    public static void resetDefaultUserAgent() {
        sDefaultUserAgentFailure = null;
        sDefaultUserAgentCalls = 0;
    }

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
