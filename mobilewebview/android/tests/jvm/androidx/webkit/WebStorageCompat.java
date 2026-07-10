package androidx.webkit;

import android.webkit.WebStorage;
import java.util.concurrent.Executor;

/** Fake WebStorageCompat for JVM unit tests; not used on device. */
public final class WebStorageCompat {
    private static int sDeleteBrowsingDataForSiteCount = 0;
    private static String sLastSite = null;
    private static boolean sLastCallbackRan = false;

    private WebStorageCompat() {}

    public static String deleteBrowsingDataForSite(WebStorage instance, String site,
                                                  Runnable doneCallback) {
        ++sDeleteBrowsingDataForSiteCount;
        sLastSite = site;
        sLastCallbackRan = false;
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

    public static boolean lastCallbackRan() {
        return sLastCallbackRan;
    }

    public static void reset() {
        sDeleteBrowsingDataForSiteCount = 0;
        sLastSite = null;
        sLastCallbackRan = false;
    }
}
