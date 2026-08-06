package android.webkit;

import java.util.ArrayDeque;

public final class CookieManager {
    private static final CookieManager sInstance = new CookieManager();
    private static int sFlushCount = 0;
    private static int sRemoveAllCookiesCount = 0;
    private static boolean sDeferCallbacks = false;
    private static final ArrayDeque<ValueCallback<Boolean>> sPendingCallbacks = new ArrayDeque<>();

    private int mRemoveAllCookiesCount = 0;

    public CookieManager() {}

    public static CookieManager getInstance() {
        return sInstance;
    }

    public String getCookie(String url) {
        return null;
    }

    public void flush() {
        ++sFlushCount;
    }

    public void removeAllCookies(ValueCallback<Boolean> callback) {
        ++mRemoveAllCookiesCount;
        if (this == sInstance) {
            ++sRemoveAllCookiesCount;
        }
        if (sDeferCallbacks) {
            sPendingCallbacks.addLast(callback);
            return;
        }
        if (callback != null) {
            callback.onReceiveValue(Boolean.TRUE);
        }
    }

    public int instanceRemoveAllCookiesCount() {
        return mRemoveAllCookiesCount;
    }

    public static int flushCount() {
        return sFlushCount;
    }

    public static int removeAllCookiesCount() {
        return sRemoveAllCookiesCount;
    }

    public static void setDeferCallbacks(boolean defer) {
        sDeferCallbacks = defer;
    }

    public static ValueCallback<Boolean> pendingCallback() {
        return sPendingCallbacks.peekFirst();
    }

    public static int pendingCallbackCount() {
        return sPendingCallbacks.size();
    }

    public static void runPendingCallback() {
        ValueCallback<Boolean> callback = sPendingCallbacks.pollFirst();
        if (callback != null) {
            callback.onReceiveValue(Boolean.TRUE);
        }
    }

    public static void resetFlushCount() {
        sFlushCount = 0;
    }

    public static void resetRemoveAllCookiesCount() {
        sRemoveAllCookiesCount = 0;
        sInstance.mRemoveAllCookiesCount = 0;
        sDeferCallbacks = false;
        sPendingCallbacks.clear();
    }
}
