package android.webkit;

public final class CookieManager {
    private static final CookieManager sInstance = new CookieManager();
    private static int sFlushCount = 0;
    private static int sRemoveAllCookiesCount = 0;

    private CookieManager() {}

    public static CookieManager getInstance() {
        return sInstance;
    }

    public void flush() {
        ++sFlushCount;
    }

    public void removeAllCookies(ValueCallback<Boolean> callback) {
        ++sRemoveAllCookiesCount;
        if (callback != null) {
            callback.onReceiveValue(Boolean.TRUE);
        }
    }

    public static int flushCount() {
        return sFlushCount;
    }

    public static int removeAllCookiesCount() {
        return sRemoveAllCookiesCount;
    }

    public static void resetFlushCount() {
        sFlushCount = 0;
    }

    public static void resetRemoveAllCookiesCount() {
        sRemoveAllCookiesCount = 0;
    }
}
