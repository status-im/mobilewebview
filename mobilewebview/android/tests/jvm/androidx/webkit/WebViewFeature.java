package androidx.webkit;

public final class WebViewFeature {
    public static final String DOCUMENT_START_SCRIPT = "DOCUMENT_START_SCRIPT";

    private WebViewFeature() {}

    public static boolean isFeatureSupported(String feature) {
        return DOCUMENT_START_SCRIPT.equals(feature);
    }
}
