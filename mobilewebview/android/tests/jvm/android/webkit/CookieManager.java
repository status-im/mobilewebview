package android.webkit;

public final class CookieManager {
    private static final CookieManager sInstance = new CookieManager();
    private static int sFlushCount = 0;

    private CookieManager() {}

    public static CookieManager getInstance() {
        return sInstance;
    }

    public void flush() {
        ++sFlushCount;
    }

    public static int flushCount() {
        return sFlushCount;
    }

    public static void resetFlushCount() {
        sFlushCount = 0;
    }
}
