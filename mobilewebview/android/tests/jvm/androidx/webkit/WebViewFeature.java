package androidx.webkit;

public final class WebViewFeature {
    public static final String DOCUMENT_START_SCRIPT = "DOCUMENT_START_SCRIPT";
    public static final String MULTI_PROFILE = "MULTI_PROFILE";
    public static final String DELETE_BROWSING_DATA = "DELETE_BROWSING_DATA";

    private static boolean sMultiProfileSupported = true;
    private static boolean sDeleteBrowsingDataSupported = true;

    private WebViewFeature() {}

    public static boolean isFeatureSupported(String feature) {
        if (MULTI_PROFILE.equals(feature)) {
            return sMultiProfileSupported;
        }
        if (DELETE_BROWSING_DATA.equals(feature)) {
            return sDeleteBrowsingDataSupported;
        }
        return DOCUMENT_START_SCRIPT.equals(feature);
    }

    public static void setMultiProfileSupported(boolean supported) {
        sMultiProfileSupported = supported;
    }

    public static void setDeleteBrowsingDataSupported(boolean supported) {
        sDeleteBrowsingDataSupported = supported;
    }
}
