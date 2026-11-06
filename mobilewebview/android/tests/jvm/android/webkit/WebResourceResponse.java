package android.webkit;

import java.io.InputStream;
import java.util.Map;

/** Minimal JVM-test stub; not used on device. */
public class WebResourceResponse {
    private final String mMimeType;
    private final int mStatusCode;
    private final Map<String, String> mResponseHeaders;
    private final InputStream mData;

    public WebResourceResponse(String mimeType, String encoding, InputStream data) {
        this(mimeType, encoding, 200, "OK", null, data);
    }

    public WebResourceResponse(String mimeType, String encoding, int statusCode,
                               String reasonPhrase, Map<String, String> responseHeaders,
                               InputStream data) {
        mMimeType = mimeType;
        mStatusCode = statusCode;
        mResponseHeaders = responseHeaders;
        mData = data;
    }

    public String getMimeType() {
        return mMimeType;
    }

    public int getStatusCode() {
        return mStatusCode;
    }

    public Map<String, String> getResponseHeaders() {
        return mResponseHeaders;
    }

    public InputStream getData() {
        return mData;
    }
}
