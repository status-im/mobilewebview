package org.mobilewebview;

import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LocalPreviewTest {
    private static final String DOWNLOADS = "file:///storage/emulated/0/Download";

    public static void main(String[] args) throws IOException {
        shouldGiveABrowsingViewNoLocalContent();
        shouldServeThePagesOfTheAppliedPdfOnly();
        shouldLoadContentThatIsNotAPdfAsItIs();
        shouldPlayAVideoInAPageWithItsControls();
        shouldPlayAudioInAPageWithItsControls();
        shouldShowABitmapTheWebViewCannotNameInAnImagePage();
        shouldServeAFilesOwnPageWithoutScript();
        shouldShowOnlyTypesThatRunNoScriptAsThemselves();
        shouldKeepScriptOutOfThePlayerAndImagePages();
        shouldShowAFileWhosePageFailsAsItself();
        shouldEscapeTheMediaUrlAndName();
        shouldShowAPdfTheRendererRefusesAsUnavailable();
        shouldRefuseAPreviewItsGrantDoesNotCover();
        shouldOpenAContentUriAsItWasNamed();
        shouldCloseEveryPdfThePreviewNoLongerShows();
        shouldAnswerARequestThatFailsWithoutThrowing();
        System.out.println("LocalPreviewTest passed");
    }

    private static void shouldGiveABrowsingViewNoLocalContent() {
        LocalPreview preview = new LocalPreview(new FakeDocuments());
        assertEquals(403, preview.interceptRequest(DOWNLOADS + "/a.pdf", false).getStatusCode());
        assertFalse(preview.permitsNavigation(DOWNLOADS + "/a.pdf"));
        assertEquals(null, preview.interceptRequest("https://example.com/", true));
        assertTrue(preview.permitsNavigation("https://example.com/"));
        assertEquals(404, preview.interceptRequest(PdfViewer.PAGE_ORIGIN + "/t/0?w=100", false).getStatusCode());
    }

    private static void shouldServeThePagesOfTheAppliedPdfOnly() {
        FakeDocuments documents = new FakeDocuments();
        FakePdf pdf = documents.pdf(DOWNLOADS + "/a.pdf", "tokenA");
        LocalPreview preview = new LocalPreview(documents);

        LocalPreview.Prepared prepared = preview.prepare(DOWNLOADS + "/a.pdf", "");
        assertEquals("viewer tokenA", prepared.html);
        // Not shown yet: neither its pages nor its file.
        assertEquals(404, preview.interceptRequest(pageUrl("tokenA", "0?w=100"), false).getStatusCode());
        assertFalse(preview.permitsNavigation(DOWNLOADS + "/a.pdf"));

        assertTrue(preview.apply(prepared));
        WebResourceResponse image = preview.interceptRequest(pageUrl("tokenA", "0?w=600"), false);
        assertEquals(200, image.getStatusCode());
        assertEquals("image/png", image.getMimeType());
        assertEquals("0@600", pdf.lastRender);
        WebResourceResponse text = preview.interceptRequest(pageUrl("tokenA", "1/text"), false);
        assertEquals(200, text.getStatusCode());
        assertEquals("application/json", text.getMimeType());
        assertEquals("*", text.getResponseHeaders().get("Access-Control-Allow-Origin"));
        assertEquals(404, preview.interceptRequest(pageUrl("other", "0?w=100"), false).getStatusCode());

        assertTrue(preview.permitsNavigation(DOWNLOADS + "/a.pdf"));
        assertEquals(null, preview.interceptRequest(DOWNLOADS + "/a.pdf", true));
        assertEquals(403, preview.interceptRequest("file:///data/user/0/app/files/x", false).getStatusCode());
    }

    private static void shouldLoadContentThatIsNotAPdfAsItIs() {
        LocalPreview preview = new LocalPreview(new FakeDocuments());
        LocalPreview.Prepared prepared = preview.prepare(DOWNLOADS + "/notes.txt", "");
        assertEquals(null, prepared.html);
        assertEquals(DOWNLOADS + "/notes.txt", prepared.url);
        assertTrue(preview.apply(prepared));
        assertTrue(preview.permitsNavigation(DOWNLOADS + "/notes.txt"));
    }

    // The WebView's own media document starts playing and hides its controls.
    private static void shouldPlayAVideoInAPageWithItsControls() {
        LocalPreview preview = new LocalPreview(new FakeDocuments());
        LocalPreview.Prepared prepared = preview.prepare(DOWNLOADS + "/clip.mp4", "");
        assertEquals(DOWNLOADS + "/clip.mp4", prepared.url);
        assertTrue(prepared.html.contains("<video src=\"" + DOWNLOADS + "/clip.mp4\" controls"));
        assertTrue(prepared.html.contains("<title>clip.mp4</title>"));
        assertFalse(prepared.html.contains("autoplay"));
        assertTrue(preview.apply(prepared));
        assertEquals(null, preview.interceptRequest(DOWNLOADS + "/clip.mp4", false));
    }

    private static void shouldPlayAudioInAPageWithItsControls() {
        LocalPreview.Prepared prepared =
                new LocalPreview(new FakeDocuments()).prepare(DOWNLOADS + "/song.mp3", "");
        assertTrue(prepared.html.contains("<audio src=\"" + DOWNLOADS + "/song.mp3\" controls"));
        assertFalse(prepared.html.contains("autoplay"));
    }

    // The WebView shows an image/x-ms-bmp document blank; an <img> decodes it by its bytes.
    private static void shouldShowABitmapTheWebViewCannotNameInAnImagePage() {
        String url = DOWNLOADS + "/pic.bmp";
        FakeDocuments documents = new FakeDocuments();
        documents.types.put(url, "image/x-ms-bmp");
        LocalPreview.Prepared prepared = new LocalPreview(documents).prepare(url, "");
        assertTrue(prepared.html.contains("<img src=\"" + url + "\""));
        assertTrue(prepared.html.contains("<title>pic.bmp</title>"));
        assertFalse(prepared.html.contains("controls"));

        String png = DOWNLOADS + "/pic.png";
        documents.types.put(png, "image/png");
        assertEquals(null, new LocalPreview(documents).prepare(png, "").html);
    }

    // A provider can serve text/html under any name. The page carries its ban on
    // script with it, through history and the back-forward cache.
    private static void shouldServeAFilesOwnPageWithoutScript() throws IOException {
        String url = "content://com.example.files/doc/7";
        FakeDocuments documents = new FakeDocuments();
        documents.types.put(url, "text/html");
        documents.contents.put(url, "<script>alert(1)</script>");
        LocalPreview preview = new LocalPreview(documents);
        LocalPreview.Prepared prepared = preview.prepare(url, url);
        assertEquals(null, prepared.html);
        // Not shown yet: refused like any local content outside the grant.
        assertEquals(403, preview.interceptRequest(url, true).getStatusCode());

        assertTrue(preview.apply(prepared));
        WebResourceResponse page = preview.interceptRequest(url, true);
        assertEquals(200, page.getStatusCode());
        // The WebView drops a content:// response's headers but keeps its type.
        assertEquals("text/plain", page.getMimeType());
        assertTrue(page.getResponseHeaders().get("Content-Security-Policy").contains("script-src 'none'"));
        assertEquals("<script>alert(1)</script>",
                new String(page.getData().readAllBytes(), StandardCharsets.UTF_8));
        // Its subresources and other pages load as usual.
        assertEquals(null, preview.interceptRequest(url, false));
        assertEquals(null, preview.interceptRequest("https://example.com/", true));

        // A type nobody named shows as text; content that does not open is not shown.
        String unnamed = DOWNLOADS + "/blob";
        documents.contents.put(unnamed, "x");
        preview.apply(preview.prepare(unnamed, ""));
        assertEquals("text/plain", preview.interceptRequest(unnamed, true).getMimeType());
        String gone = DOWNLOADS + "/gone.txt";
        preview.apply(preview.prepare(gone, ""));
        assertEquals(404, preview.interceptRequest(gone, true).getStatusCode());
    }

    // Only a PDF that does not open gets "This PDF can't be shown".
    private static void shouldShowAFileWhosePageFailsAsItself() {
        FakeDocuments documents = new FakeDocuments();
        String url = DOWNLOADS + "/song.mp3";
        documents.brokenTypes.add(url);
        LocalPreview.Prepared prepared = new LocalPreview(documents).prepare(url, "");
        assertEquals(url, prepared.url);
        assertEquals(null, prepared.html);
    }

    private static void shouldShowOnlyTypesThatRunNoScriptAsThemselves() {
        assertEquals("application/json", LocalPreview.shownType("application/json"));
        assertEquals("image/png", LocalPreview.shownType("image/png"));
        assertEquals("text/plain", LocalPreview.shownType("text/plain"));
        assertEquals("text/csv", LocalPreview.shownType("Text/CSV; charset=utf-8"));
        for (String scripted : new String[] {"text/html", "application/xhtml+xml", "image/svg+xml",
                "text/xml", "application/xml", "text/xsl", "multipart/related", "", null}) {
            assertEquals("text/plain", LocalPreview.shownType(scripted));
        }
    }

    private static void shouldKeepScriptOutOfThePlayerAndImagePages() {
        FakeDocuments documents = new FakeDocuments();
        documents.types.put(DOWNLOADS + "/pic.bmp", "image/x-ms-bmp");
        LocalPreview preview = new LocalPreview(documents);
        for (String name : new String[] {"clip.mp4", "song.mp3", "pic.bmp"}) {
            String html = preview.prepare(DOWNLOADS + "/" + name, "").html;
            assertTrue(html.contains(
                    "<meta http-equiv=\"Content-Security-Policy\" content=\"script-src 'none'\">"));
        }
    }

    private static void shouldEscapeTheMediaUrlAndName() {
        String url = "content://com.example.files/doc/1?a=1&b=\"2\"";
        FakeDocuments documents = new FakeDocuments();
        documents.types.put(url, "video/mp4");
        documents.names.put(url, "<b>clip</b>.mp4");
        LocalPreview.Prepared prepared = new LocalPreview(documents).prepare(url, url);
        assertTrue(prepared.html.contains("src=\"content://com.example.files/doc/1?a=1&amp;b=&quot;2&quot;\""));
        assertTrue(prepared.html.contains("<title>&lt;b&gt;clip&lt;/b&gt;.mp4</title>"));
    }

    private static void shouldShowAPdfTheRendererRefusesAsUnavailable() {
        FakeDocuments documents = new FakeDocuments();
        documents.refused.add(DOWNLOADS + "/locked.pdf");
        LocalPreview.Prepared prepared =
                new LocalPreview(documents).prepare(DOWNLOADS + "/locked.pdf", "");
        assertEquals(PdfViewer.unavailableHtml("locked.pdf"), prepared.html);
    }

    private static void shouldRefuseAPreviewItsGrantDoesNotCover() {
        FakeDocuments documents = new FakeDocuments();
        documents.pdf(DOWNLOADS + "/a.pdf", "tokenA");
        assertEquals(null, new LocalPreview(documents).prepare(DOWNLOADS + "/a.pdf", "file:///sdcard/Other"));
        assertEquals(0, documents.opened);
    }

    // A provider's authority is case-sensitive; the host hands the URI over as it was named.
    private static void shouldOpenAContentUriAsItWasNamed() {
        LocalPreview.Prepared prepared =
                new LocalPreview(new FakeDocuments()).prepare("content://com.Example.Docs/1", "");
        assertEquals("content://com.Example.Docs/1", prepared.url);
    }

    private static void shouldCloseEveryPdfThePreviewNoLongerShows() {
        FakeDocuments documents = new FakeDocuments();
        FakePdf a = documents.pdf(DOWNLOADS + "/a.pdf", "a");
        FakePdf b = documents.pdf(DOWNLOADS + "/b.pdf", "b");
        FakePdf c = documents.pdf(DOWNLOADS + "/c.pdf", "c");
        FakePdf d = documents.pdf(DOWNLOADS + "/d.pdf", "d");
        FakePdf e = documents.pdf(DOWNLOADS + "/e.pdf", "e");
        LocalPreview preview = new LocalPreview(documents);

        preview.apply(preview.prepare(DOWNLOADS + "/a.pdf", ""));
        LocalPreview.Prepared second = preview.prepare(DOWNLOADS + "/b.pdf", "");
        assertFalse(a.closed); // still on screen until b replaces it
        assertTrue(preview.apply(second));
        assertTrue(a.closed);

        // A preview prepared and then replaced before it loaded never shows.
        LocalPreview.Prepared third = preview.prepare(DOWNLOADS + "/c.pdf", "");
        LocalPreview.Prepared fourth = preview.prepare(DOWNLOADS + "/d.pdf", "");
        assertTrue(c.closed);
        assertFalse(preview.apply(third));
        preview.discard(fourth);
        assertTrue(d.closed);
        assertFalse(b.closed);

        preview.close();
        assertTrue(b.closed);
        assertEquals(null, preview.prepare(DOWNLOADS + "/e.pdf", ""));
        assertTrue(e.closed);
    }

    private static void shouldAnswerARequestThatFailsWithoutThrowing() {
        FakeDocuments documents = new FakeDocuments();
        FakePdf pdf = documents.pdf(DOWNLOADS + "/a.pdf", "tokenA");
        pdf.failRender = true;
        LocalPreview preview = new LocalPreview(documents);
        preview.apply(preview.prepare(DOWNLOADS + "/a.pdf", ""));

        assertEquals(403, preview.interceptRequest(pageUrl("tokenA", "0?w=100"), false).getStatusCode());
        assertEquals(403, preview.interceptRequest(DOWNLOADS + "/%00", false).getStatusCode());
        assertFalse(preview.permitsNavigation(DOWNLOADS + "/a%00.html"));
    }

    private static String pageUrl(String token, String rest) {
        return PdfViewer.PAGE_ORIGIN + "/" + token + "/" + rest;
    }

    private static final class FakeDocuments implements LocalPreview.Documents {
        final Map<String, FakePdf> pdfs = new HashMap<>();
        final List<String> refused = new ArrayList<>();
        final Map<String, String> types = new HashMap<>();
        final Map<String, String> names = new HashMap<>();
        final Map<String, String> contents = new HashMap<>();
        final List<String> brokenTypes = new ArrayList<>();
        int opened = 0;

        FakePdf pdf(String url, String token) {
            FakePdf pdf = new FakePdf(token);
            pdfs.put(url, pdf);
            return pdf;
        }

        @Override
        public LocalPreview.PdfDocument openIfPdf(String url) throws IOException {
            ++opened;
            if (refused.contains(url)) {
                throw new IOException("password-protected");
            }
            return pdfs.get(url);
        }

        @Override
        public InputStream open(String url) throws IOException {
            String content = contents.get(url);
            if (content == null) {
                throw new IOException("gone");
            }
            return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String displayName(String url) {
            return names.getOrDefault(url, url.substring(url.lastIndexOf('/') + 1));
        }

        @Override
        public String mimeType(String url) {
            if (brokenTypes.contains(url)) {
                throw new IllegalStateException("provider went away");
            }
            if (types.containsKey(url)) {
                return types.get(url);
            }
            if (url.endsWith(".mp4")) {
                return "video/mp4";
            }
            return url.endsWith(".mp3") ? "audio/mpeg" : "";
        }
    }

    private static final class FakePdf implements LocalPreview.PdfDocument {
        private final String mToken;
        boolean closed = false;
        boolean failRender = false;
        String lastRender = null;

        FakePdf(String token) {
            mToken = token;
        }

        @Override
        public String token() {
            return mToken;
        }

        @Override
        public String viewerHtml(String script) {
            return "viewer " + mToken;
        }

        @Override
        public byte[] renderPng(int index, int width) {
            if (failRender) {
                throw new IllegalStateException("pdfium");
            }
            lastRender = index + "@" + width;
            return new byte[] {1};
        }

        @Override
        public byte[] textJson(int index) {
            return "[]".getBytes();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static void assertTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError("Expected true");
        }
    }

    private static void assertFalse(boolean condition) {
        if (condition) {
            throw new AssertionError("Expected false");
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }
}
