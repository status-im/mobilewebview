package org.mobilewebview;

import android.util.Log;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * The local content one view may show: its Read Grant (ADR 0007) and the PDF
 * whose pages it serves (ADR 0008). A preview is prepared off the UI thread
 * and applied on it right before the load; the WebView asks about requests
 * from its own threads. Pure Java behind Documents, so the JVM tests state the
 * routing.
 */
final class LocalPreview {
    private static final String TAG = "LocalPreview";

    /** What a preview reads from the platform. */
    interface Documents {
        /** The PDF at url, or null when the content is not one; throws for a PDF that cannot be opened. */
        PdfDocument openIfPdf(String url) throws IOException;

        String displayName(String url);

        /** The content's MIME type, or "" when it is not known. */
        String mimeType(String url);

        /** The visible content providers' authorities; an entry may list several, separated by ';'. */
        List<String> providerAuthorities();
    }

    /** An open PDF whose pages a preview serves. */
    interface PdfDocument {
        String token();

        String viewerHtml();

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

        /** Only the PDF viewer page runs script; a file's own page never does. */
        boolean runsScript() {
            return mPdf != null;
        }
    }

    private final Documents mDocuments;
    private final Object mLock = new Object();
    private volatile LocalReadGrant mGrant = LocalReadGrant.NONE;
    private Prepared mPending;
    private PdfDocument mPdf;
    private boolean mClosed = false;

    LocalPreview(Documents documents) {
        mDocuments = documents;
    }

    /**
     * Resolves the grant and opens the PDF for a preview of requestedUrl. Null
     * when it must not load: the grant does not cover it, or its content://
     * authority could name another provider. Waits on the provider.
     */
    Prepared prepare(String requestedUrl, String readAccessUrl) {
        String url = withProviderAuthority(requestedUrl);
        if (url == null) {
            Log.w(TAG, "The content:// authority could name another provider; not loaded");
            return null;
        }
        LocalReadGrant grant = LocalReadGrant.forPreview(url, readAccessUrl);
        if (grant == LocalReadGrant.NONE) {
            Log.w(TAG, "The read-access URL does not cover the file; not loaded");
            return null;
        }
        Prepared prepared;
        try {
            PdfDocument pdf = mDocuments.openIfPdf(url);
            String html = pdf != null ? pdf.viewerHtml() : mediaHtml(url);
            prepared = new Prepared(url, grant, html, pdf);
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

    private String mediaHtml(String url) {
        String element = MediaPage.element(mDocuments.mimeType(url));
        return element == null ? null : MediaPage.html(element, url, mDocuments.displayName(url));
    }

    // The host's QUrl lowercases a content:// authority; providers match it
    // case-sensitively. The provider that granted the URI is visible to us.
    // Null when the authority cannot be told apart from another provider's.
    private String withProviderAuthority(String url) {
        if (url == null || !url.regionMatches(true, 0, "content:", 0, 8)) {
            return url;
        }
        try {
            return ContentAuthorities.restoreCase(url, mDocuments.providerAuthorities());
        } catch (RuntimeException e) {
            Log.w(TAG, "Provider lookup failed", e);
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
