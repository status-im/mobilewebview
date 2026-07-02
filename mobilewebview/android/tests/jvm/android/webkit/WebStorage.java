package android.webkit;

/** Fake WebStorage for JVM unit tests; not used on device. */
public final class WebStorage {
    private static final WebStorage sInstance = new WebStorage();
    private static int sDeleteAllDataCount = 0;
    private static int sDeleteOriginCount = 0;
    private static String sLastDeleteOrigin = null;

    private WebStorage() {}

    public static WebStorage getInstance() {
        return sInstance;
    }

    public void deleteAllData() {
        ++sDeleteAllDataCount;
    }

    public void deleteOrigin(String origin) {
        ++sDeleteOriginCount;
        sLastDeleteOrigin = origin;
    }

    public static int deleteAllDataCount() {
        return sDeleteAllDataCount;
    }

    public static int deleteOriginCount() {
        return sDeleteOriginCount;
    }

    public static String lastDeleteOrigin() {
        return sLastDeleteOrigin;
    }

    public static void reset() {
        sDeleteAllDataCount = 0;
        sDeleteOriginCount = 0;
        sLastDeleteOrigin = null;
    }
}
