package org.mobilewebview;

import android.webkit.WebResourceResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LocalPreviewTest {
    private static final String DOWNLOADS = "file:///storage/emulated/0/Download";

    public static void main(String[] args) {
        shouldGiveABrowsingViewNoLocalContent();
        shouldServeThePagesOfTheAppliedPdfOnly();
        shouldLoadContentThatIsNotAPdfAsItIs();
        shouldPlayAVideoInAPageWithItsControls();
        shouldPlayAudioInAPageWithItsControls();
        shouldEscapeTheMediaUrlAndName();
        shouldShowAPdfTheRendererRefusesAsUnavailable();
        shouldRefuseAPreviewItsGrantDoesNotCover();
        shouldRefuseAContentUriTwoProvidersCouldName();
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

    // Another app can declare the lowercase spelling of a provider's authority.
    private static void shouldRefuseAContentUriTwoProvidersCouldName() {
        FakeDocuments documents = new FakeDocuments();
        documents.providers = Arrays.asList("com.Example.Docs", "com.example.docs");
        assertEquals(null, new LocalPreview(documents).prepare("content://com.example.docs/1", ""));

        documents.providers = Collections.singletonList("com.Example.Docs");
        LocalPreview.Prepared prepared =
                new LocalPreview(documents).prepare("content://com.example.docs/1", "");
        assertEquals("content://com.Example.Docs/1", prepared.url);

        documents.failLookup = true;
        assertEquals(null, new LocalPreview(documents).prepare("content://com.example.docs/1", ""));
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
        List<String> providers = new ArrayList<>();
        boolean failLookup = false;
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
        public String displayName(String url) {
            return names.getOrDefault(url, url.substring(url.lastIndexOf('/') + 1));
        }

        @Override
        public String mimeType(String url) {
            if (types.containsKey(url)) {
                return types.get(url);
            }
            if (url.endsWith(".mp4")) {
                return "video/mp4";
            }
            return url.endsWith(".mp3") ? "audio/mpeg" : "";
        }

        @Override
        public List<String> providerAuthorities() {
            if (failLookup) {
                throw new SecurityException("no package visibility");
            }
            return providers;
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
        public String viewerHtml() {
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
