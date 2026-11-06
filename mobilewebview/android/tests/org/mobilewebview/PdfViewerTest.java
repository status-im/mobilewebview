package org.mobilewebview;

import java.nio.charset.StandardCharsets;

public final class PdfViewerTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    public static void main(String[] args) {
        shouldAskForPagesOnTheSessionsOwnPath();
        shouldServeOnlyThisSessionsPageRequests();
        shouldServeAPagesTextBesideItsImage();
        shouldBoundTheRenderWidth();
        shouldKeepThePageShapeWithinTheBitmapLimits();
        shouldListEveryPageWithItsShape();
        shouldLayTextOverThePagesOnlyWhenAndroidReadsIt();
        shouldEscapeTheTitle();
        shouldRecognisePdfBySignature();
        shouldSayWhenAPdfCannotBeShown();
        shouldMintAFreshUnguessableTokenPerSession();
        System.out.println("PdfViewerTest passed");
    }

    // Page images come from a host no network serves: the WebView asks
    // shouldInterceptRequest for it whatever the viewer page's own scheme
    // (a content:// page may not load content:// subresources at all).
    private static void shouldAskForPagesOnTheSessionsOwnPath() {
        assertEquals("https://pdf.mobilewebview.invalid/" + TOKEN + "/", PdfViewer.pageBase(TOKEN));
    }

    private static void shouldServeOnlyThisSessionsPageRequests() {
        String base = PdfViewer.pageBase(TOKEN);
        PdfViewer.PageRequest page = PdfViewer.parsePageRequest(base + "2?w=1080", TOKEN);
        assertTrue(page != null);
        assertEquals(2, page.index);
        assertEquals(1080, page.width);
        assertFalse(page.text);

        // Another session, a non-page path, and junk are not page requests.
        assertTrue(PdfViewer.parsePageRequest(
                PdfViewer.pageBase("ffffffffffffffffffffffffffffffff") + "2?w=1080", TOKEN) == null);
        assertTrue(PdfViewer.parsePageRequest(base, TOKEN) == null);
        assertTrue(PdfViewer.parsePageRequest(base + "-1?w=900", TOKEN) == null);
        assertTrue(PdfViewer.parsePageRequest(base + "x?w=900", TOKEN) == null);
        assertTrue(PdfViewer.parsePageRequest(base + "1?w=900&extra=1", TOKEN) == null);
        assertTrue(PdfViewer.parsePageRequest(base + "1", TOKEN) == null);
        assertTrue(PdfViewer.parsePageRequest("https://example.com/" + TOKEN + "/1?w=900", TOKEN) == null);
        assertTrue(PdfViewer.parsePageRequest(null, TOKEN) == null);
        assertTrue(PdfViewer.parsePageRequest(base + "1?w=900", null) == null);
    }

    private static void shouldServeAPagesTextBesideItsImage() {
        String base = PdfViewer.pageBase(TOKEN);
        PdfViewer.PageRequest text = PdfViewer.parsePageRequest(base + "2/text", TOKEN);
        assertTrue(text != null);
        assertEquals(2, text.index);
        assertTrue(text.text);

        assertTrue(PdfViewer.parsePageRequest(base + "/text", TOKEN) == null);
        assertTrue(PdfViewer.parsePageRequest(base + "x/text", TOKEN) == null);
        assertTrue(PdfViewer.parsePageRequest(base + "2/text?w=900", TOKEN) == null);
        assertTrue(PdfViewer.parsePageRequest(
                PdfViewer.pageBase("ffffffffffffffffffffffffffffffff") + "2/text", TOKEN) == null);
    }

    private static void shouldBoundTheRenderWidth() {
        String base = PdfViewer.pageBase(TOKEN);
        assertEquals(PdfViewer.MAX_RENDER_WIDTH, PdfViewer.parsePageRequest(base + "0?w=99999", TOKEN).width);
        assertEquals(PdfViewer.MIN_RENDER_WIDTH, PdfViewer.parsePageRequest(base + "0?w=1", TOKEN).width);
    }

    private static void shouldKeepThePageShapeWithinTheBitmapLimits() {
        int[] letter = PdfViewer.renderSize(612f, 792f, 1000);
        assertEquals(1000, letter[0]);
        assertEquals(1294, letter[1]);

        // A very long page is scaled down rather than allocated at full height.
        int[] receipt = PdfViewer.renderSize(200f, 20000f, 2000);
        assertEquals(PdfViewer.MAX_RENDER_HEIGHT, receipt[1]);
        assertEquals(82, receipt[0]);

        int[] broken = PdfViewer.renderSize(0f, 0f, 1000);
        assertTrue(broken[0] >= 1 && broken[1] >= 1);
    }

    private static void shouldListEveryPageWithItsShape() {
        String html = PdfViewer.html("report.pdf",
                new float[] {612f, 842f}, new float[] {792f, 595f}, TOKEN, true);
        assertEquals(2, count(html, "data-page=\""));
        assertTrue(html.contains("data-page=\"0\" data-w=\"612\" data-h=\"792\""));
        assertTrue(html.contains("data-page=\"1\" data-w=\"842\" data-h=\"595\""));
        assertTrue(html.contains("width=\"612\" height=\"792\""));
        assertTrue(html.contains("<meta charset=\"utf-8\">"));
        assertTrue(html.contains("<title>report.pdf</title>"));
        assertTrue(html.contains(PdfViewer.pageBase(TOKEN)));
    }

    // Selection and find-in-page work on a transparent text layer over each
    // page image, filled with the page's text where Android can read it.
    private static void shouldLayTextOverThePagesOnlyWhenAndroidReadsIt() {
        String withText = PdfViewer.html("a.pdf", new float[] {612f}, new float[] {792f}, TOKEN, true);
        assertEquals(1, count(withText, "<div class=\"text\"></div>"));
        assertTrue(withText.contains("'/text'"));

        String imagesOnly = PdfViewer.html("a.pdf", new float[] {612f}, new float[] {792f}, TOKEN, false);
        assertFalse(imagesOnly.contains("class=\"text\""));
        assertTrue(imagesOnly.contains("withText=false"));
    }

    private static void shouldEscapeTheTitle() {
        String html = PdfViewer.html("<b>\"x\" & y</b>.pdf",
                new float[] {612f}, new float[] {792f}, TOKEN, false);
        assertTrue(html.contains("<title>&lt;b&gt;&quot;x&quot; &amp; y&lt;/b&gt;.pdf</title>"));
    }

    private static void shouldRecognisePdfBySignature() {
        assertTrue(PdfViewer.isPdf(bytes("%PDF-1.7\n..."), 12));
        // Some writers put a few bytes of junk before the header.
        assertTrue(PdfViewer.isPdf(bytes("\r\n  %PDF-1.4"), 11));
        assertFalse(PdfViewer.isPdf(bytes("<html>%PDF-"), 4));
        assertFalse(PdfViewer.isPdf(bytes("ID3\u0004"), 4));
        assertFalse(PdfViewer.isPdf(new byte[0], 0));
    }

    // A PDF that is corrupt or password-protected stays in the view as a page
    // that says so, not as a download of a local file.
    private static void shouldSayWhenAPdfCannotBeShown() {
        String html = PdfViewer.unavailableHtml("<secret>.pdf");
        assertTrue(html.contains("<title>&lt;secret&gt;.pdf</title>"));
        assertTrue(html.contains("can't be shown"));
        assertFalse(html.contains("<img"));
    }

    private static void shouldMintAFreshUnguessableTokenPerSession() {
        String a = PdfViewer.newToken();
        String b = PdfViewer.newToken();
        assertEquals(32, a.length());
        assertTrue(a.matches("[0-9a-f]{32}"));
        assertFalse(a.equals(b));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
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
