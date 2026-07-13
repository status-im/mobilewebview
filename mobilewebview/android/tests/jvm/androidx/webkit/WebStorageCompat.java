package androidx.webkit;

import android.webkit.WebStorage;
import java.util.concurrent.Executor;

/** Fake WebStorageCompat for JVM unit tests; not used on device. */
public final class WebStorageCompat {
    private static int sDeleteBrowsingDataForSiteCount = 0;
    private static String sLastSite = null;
    private static WebStorage sLastWebStorage = null;
    private static boolean sLastCallbackRan = false;
    private static boolean sDeferCallbacks = false;
    private static Runnable sPendingCallback = null;

    private WebStorageCompat() {}

    public static String deleteBrowsingDataForSite(WebStorage instance, String site,
                                                  Runnable doneCallback) {
        ++sDeleteBrowsingDataForSiteCount;
        sLastSite = site;
        sLastWebStorage = instance;
        sLastCallbackRan = false;
        if (sDeferCallbacks) {
            sPendingCallback = doneCallback;
            return site;
        }
        if (doneCallback != null) {
            doneCallback.run();
            sLastCallbackRan = true;
        }
        // Mirror the real API: return the top-level domain used for deletion.
        return site;
    }

    public static String deleteBrowsingDataForSite(WebStorage instance, String site,
                                                  Executor executor, Runnable doneCallback) {
        return deleteBrowsingDataForSite(instance, site, doneCallback);
    }

    public static int deleteBrowsingDataForSiteCount() {
        return sDeleteBrowsingDataForSiteCount;
    }

    public static String lastSite() {
        return sLastSite;
    }

    public static WebStorage lastWebStorage() {
        return sLastWebStorage;
    }

    public static boolean lastCallbackRan() {
        return sLastCallbackRan;
    }

    public static void setDeferCallbacks(boolean defer) {
        sDeferCallbacks = defer;
    }

    public static Runnable pendingCallback() {
        return sPendingCallback;
    }

    public static void runPendingCallback() {
        Runnable callback = sPendingCallback;
        sPendingCallback = null;
        if (callback != null) {
            callback.run();
            sLastCallbackRan = true;
        }
    }

    public static void reset() {
        sDeleteBrowsingDataForSiteCount = 0;
        sLastSite = null;
        sLastWebStorage = null;
        sLastCallbackRan = false;
        sDeferCallbacks = false;
        sPendingCallback = null;
    }
}
