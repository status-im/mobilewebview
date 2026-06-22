package androidx.webkit;

public final class WebViewFeature {
    public static final String DOCUMENT_START_SCRIPT = "DOCUMENT_START_SCRIPT";
    public static final String MULTI_PROFILE = "MULTI_PROFILE";

    private static boolean sMultiProfileSupported = true;

    private WebViewFeature() {}

    public static boolean isFeatureSupported(String feature) {
        if (MULTI_PROFILE.equals(feature)) {
            return sMultiProfileSupported;
        }
        return DOCUMENT_START_SCRIPT.equals(feature);
    }

    public static void setMultiProfileSupported(boolean supported) {
        sMultiProfileSupported = supported;
    }
}
