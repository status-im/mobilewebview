package org.mobilewebview;

import android.util.Log;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The local content one view may show: its Read Grant (ADR 0007) and the PDF
 * whose pages it serves (ADR 0008). A preview is prepared off the UI thread
 * and applied on it right before the load; the WebView asks about requests
 * from its own threads. Pure Java behind Documents, so the JVM tests state the
 * routing.
 */
final class LocalPreview {
    private static final String TAG = "LocalPreview";
    private static final Map<String, String> NO_SCRIPT = Collections.singletonMap(
            "Content-Security-Policy", MediaPage.NO_SCRIPT_POLICY);

    /** What a preview reads from the platform. */
    interface Documents {
        /** The PDF at url, or null when the content is not one; throws for a PDF that cannot be opened. */
        PdfDocument openIfPdf(String url) throws IOException;

        String displayName(String url);

        /** The content's MIME type, or "" when it is not known. */
        String mimeType(String url);

        /** The content itself, for a preview's own page. */
        InputStream open(String url) throws IOException;

    }

    /** An open PDF whose pages a preview serves. */
    interface PdfDocument {
        String token();

        String viewerHtml(String script);

        byte[] renderPng(int index, int width);

        byte[] textJson(int index);

        void close();
    }

    /** A preview ready to load: apply() it on the UI thread, or discard() it. */
    static final class Prepared {
        final String url;
        final LocalReadGrant grant;
        /** The page to load in place of url (the PDF viewer, a media player), or null to load url itself. */
        final String html;
        private final PdfDocument mPdf;

        private Prepared(String url, LocalReadGrant grant, String html, PdfDocument pdf) {
            this.url = url;
            this.grant = grant;
            this.html = html;
            mPdf = pdf;
        }
    }

    private final Documents mDocuments;
    private final Object mLock = new Object();
    private volatile LocalReadGrant mGrant = LocalReadGrant.NONE;
    private Prepared mPending;
    private PdfDocument mPdf;
    // The page of the applied preview that is the file itself (not one of ours).
    private String mOwnPageUrl;
    private volatile String mViewerScript = "";
    private boolean mClosed = false;

    LocalPreview(Documents documents) {
        mDocuments = documents;
    }

    /**
     * Resolves the grant and opens the PDF for a preview of url, which the host
     * passes as it was named. Null when it must not load: the grant does not
     * cover it. Waits on the provider.
     */
    Prepared prepare(String url, String readAccessUrl) {
        LocalReadGrant grant = LocalReadGrant.forPreview(url, readAccessUrl);
        if (grant == LocalReadGrant.NONE) {
            Log.w(TAG, "The read-access URL does not cover the file; not loaded");
            return null;
        }
        Prepared prepared;
        try {
            PdfDocument pdf = mDocuments.openIfPdf(url);
            prepared = pdf != null ? new Prepared(url, grant, pdf.viewerHtml(mViewerScript), pdf)
                                   : new Prepared(url, grant, pageHtml(url), null);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "PDF preview cannot be opened", e);
            prepared = new Prepared(url, grant,
                    PdfViewer.unavailableHtml(mDocuments.displayName(url)), null);
        }
        synchronized (mLock) {
            if (mClosed) {
                closePdf(prepared.mPdf);
                return null;
            }
            if (mPending != null) {
                closePdf(mPending.mPdf);
            }
            mPending = prepared;
        }
        return prepared;
    }

    /** pdf_viewer.js, read by the host from its resources. */
    void setViewerScript(String script) {
        mViewerScript = script != null ? script : "";
    }

    /** Makes prepared the view's preview; false when a later one replaced it. */
    boolean apply(Prepared prepared) {
        synchronized (mLock) {
            if (prepared == null || prepared != mPending) {
                return false;
            }
            mPending = null;
            closePdf(mPdf);
            mPdf = prepared.mPdf;
            mGrant = prepared.grant;
            mOwnPageUrl = prepared.html == null ? prepared.url : null;
            return true;
        }
    }

    /** Drops a prepared preview the view will not load. */
    void discard(Prepared prepared) {
        synchronized (mLock) {
            if (prepared != null && prepared == mPending) {
                mPending = null;
                closePdf(prepared.mPdf);
            }
        }
    }

    void close() {
        synchronized (mLock) {
            mClosed = true;
            if (mPending != null) {
                closePdf(mPending.mPdf);
                mPending = null;
            }
            closePdf(mPdf);
            mPdf = null;
            mGrant = LocalReadGrant.NONE;
            mOwnPageUrl = null;
        }
    }

    /** False for a local URL outside the Read Grant: the navigation is cancelled. */
    boolean permitsNavigation(String url) {
        try {
            return mGrant.permits(url);
        } catch (RuntimeException e) {
            Log.w(TAG, "Refused a navigation the Read Grant cannot judge", e);
            return false;
        }
    }

    /**
     * A response the view gives itself (a PDF page) or a refusal (local content
     * outside the Read Grant); null lets the WebView load the request.
     */
    WebResourceResponse interceptRequest(String url, boolean mainFrame) {
        try {
            if (url != null && url.startsWith(PdfViewer.PAGE_ORIGIN)) {
                return pdfPage(url);
            }
            if (mainFrame && url != null && url.equals(ownPageUrl())) {
                return ownPage(url);
            }
            if (LocalReadGrant.isLocal(url) && !mGrant.permits(url)) {
                Log.w(TAG, "Refused local " + (mainFrame ? "navigation" : "subresource")
                        + " outside the Read Grant");
                return emptyResponse("text/plain", 403, "Forbidden");
            }
            return null;
        } catch (RuntimeException e) {
            Log.w(TAG, "Refused a request that failed to resolve", e);
            return emptyResponse("text/plain", 403, "Forbidden");
        }
    }

    private String ownPageUrl() {
        synchronized (mLock) {
            return mOwnPageUrl;
        }
    }

    // The file's own page, served here so it runs no script: a provider can
    // serve text/html under any name. The WebView drops the headers of a
    // content:// response but keeps its type, so a type that could run script
    // shows as text; the policy header covers the rest.
    private WebResourceResponse ownPage(String url) {
        try {
            return new WebResourceResponse(shownType(mDocuments.mimeType(url)), null, 200, "OK",
                    NO_SCRIPT, mDocuments.open(url));
        } catch (IOException e) {
            Log.w(TAG, "The previewed file does not open");
            return emptyResponse("text/plain", 404, "Not Found");
        }
    }

    /** The type a file's own page shows as: its own when that runs no script, else text. */
    static String shownType(String type) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        int params = t.indexOf(';');
        if (params >= 0) {
            t = t.substring(0, params).trim();
        }
        boolean scriptFree = t.equals("application/json")
                || (t.startsWith("image/") && !t.contains("svg"))
                || (t.startsWith("text/") && !t.equals("text/html") && !t.contains("xml")
                        && !t.equals("text/xsl"));
        return scriptFree ? t : "text/plain";
    }

    private WebResourceResponse pdfPage(String url) {
        PdfDocument pdf;
        synchronized (mLock) {
            pdf = mPdf;
        }
        PdfViewer.PageRequest page = pdf != null ? PdfViewer.parsePageRequest(url, pdf.token()) : null;
        if (page == null) {
            return emptyResponse("image/png", 404, "Not Found");
        }
        if (page.text) {
            byte[] json = pdf.textJson(page.index);
            if (json == null) {
                return emptyResponse("application/json", 404, "Not Found");
            }
            // The viewer page fetches it from its own file:// or content:// origin.
            return new WebResourceResponse("application/json", "utf-8", 200, "OK",
                    Collections.singletonMap("Access-Control-Allow-Origin", "*"),
                    new ByteArrayInputStream(json));
        }
        byte[] png = pdf.renderPng(page.index, page.width);
        if (png == null) {
            return emptyResponse("image/png", 404, "Not Found");
        }
        return new WebResourceResponse("image/png", null, new ByteArrayInputStream(png));
    }

    // The page of ours a file shows in (a player, an image), or null for its own.
    private String pageHtml(String url) {
        try {
            String element = MediaPage.element(mDocuments.mimeType(url));
            return element == null ? null : MediaPage.html(element, url, mDocuments.displayName(url));
        } catch (RuntimeException e) {
            Log.w(TAG, "No page for the preview; the file shows as itself", e);
            return null;
        }
    }

    private static void closePdf(PdfDocument pdf) {
        if (pdf != null) {
            pdf.close();
        }
    }

    private static WebResourceResponse emptyResponse(String mimeType, int status, String reason) {
        return new WebResourceResponse(mimeType, null, status, reason,
                Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }
}
