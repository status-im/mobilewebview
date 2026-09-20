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

    public void setJavaScriptEnabled(boolean flag) {
        mJavaScriptEnabled = flag;
    }

    public void setDomStorageEnabled(boolean flag) {
        mDomStorageEnabled = flag;
    }

    public void setDatabaseEnabled(boolean flag) {
        mDatabaseEnabled = flag;
    }

    public void setAllowFileAccess(boolean flag) {
        mAllowFileAccess = flag;
    }

    public void setAllowContentAccess(boolean flag) {
        mAllowContentAccess = flag;
    }

    public void setAllowFileAccessFromFileURLs(boolean flag) {
        mAllowFileAccessFromFileURLs = flag;
    }

    public void setAllowUniversalAccessFromFileURLs(boolean flag) {
        mAllowUniversalAccessFromFileURLs = flag;
    }

    public void setMixedContentMode(int mode) {
        mMixedContentMode = mode;
    }

    public void setUseWideViewPort(boolean flag) {
        mUseWideViewPort = flag;
    }

    public void setLoadWithOverviewMode(boolean flag) {
        mLoadWithOverviewMode = flag;
    }

    public void setBuiltInZoomControls(boolean flag) {
        mBuiltInZoomControls = flag;
    }

    public void setDisplayZoomControls(boolean flag) {
        mDisplayZoomControls = flag;
    }

    public void setCacheMode(int mode) {
        mCacheMode = mode;
    }

    public int getCacheMode() {
        return mCacheMode;
    }

    public String getUserAgentString() {
        return "fake-settings-agent";
    }

    // Recorded for WebViewSettingsPolicyTest; unset means "never called".
    private Boolean mJavaScriptEnabled;
    private Boolean mDomStorageEnabled;
    private Boolean mDatabaseEnabled;
    private Boolean mAllowFileAccess;
    private Boolean mAllowContentAccess;
    private Boolean mAllowFileAccessFromFileURLs;
    private Boolean mAllowUniversalAccessFromFileURLs;
    private Integer mMixedContentMode;
    private Boolean mUseWideViewPort;
    private Boolean mLoadWithOverviewMode;
    private Boolean mBuiltInZoomControls;
    private Boolean mDisplayZoomControls;

    public Boolean javaScriptEnabled() { return mJavaScriptEnabled; }
    public Boolean domStorageEnabled() { return mDomStorageEnabled; }
    public Boolean databaseEnabled() { return mDatabaseEnabled; }
    public Boolean allowFileAccess() { return mAllowFileAccess; }
    public Boolean allowContentAccess() { return mAllowContentAccess; }
    public Boolean allowFileAccessFromFileURLs() { return mAllowFileAccessFromFileURLs; }
    public Boolean allowUniversalAccessFromFileURLs() { return mAllowUniversalAccessFromFileURLs; }
    public Integer mixedContentMode() { return mMixedContentMode; }
    public Boolean useWideViewPort() { return mUseWideViewPort; }
    public Boolean loadWithOverviewMode() { return mLoadWithOverviewMode; }
    public Boolean builtInZoomControls() { return mBuiltInZoomControls; }
    public Boolean displayZoomControls() { return mDisplayZoomControls; }
}
