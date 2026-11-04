package org.mobilewebview;

public final class LocalReadGrantTest {
    private static final String APP_FILES = "file:///data/user/0/app.example/files";

    public static void main(String[] args) {
        shouldGiveABrowsingViewNoLocalUrl();
        shouldGrantAPreviewOnlyItsOwnFileWhenTheFileIsTheAccessUrl();
        shouldGrantTheDirectoryWhenTheAccessUrlIsOne();
        shouldDefaultTheGrantToTheFilesOwnDirectory();
        shouldNotLetDotSegmentsOrLookalikeDirectoriesEscape();
        shouldMatchFileUrlsWhateverTheirPercentEncoding();
        shouldGrantAContentPreviewOnlyItsOwnUri();
        shouldRefuseAGrantThatDoesNotCoverTheFile();
        shouldRefuseAGrantForAWebUrl();
        System.out.println("LocalReadGrantTest passed");
    }

    private static void shouldGiveABrowsingViewNoLocalUrl() {
        LocalReadGrant none = LocalReadGrant.NONE;
        assertFalse(none.allowsFileScheme());
        assertFalse(none.allowsContentScheme());
        assertFalse(none.permits(APP_FILES + "/opened-file-log.jsonl"));
        assertFalse(none.permits("FILE:///sdcard/Download/x.pdf"));
        assertFalse(none.permits("content://com.example.provider/1"));
        // Not local: the grant has nothing to say about it.
        assertTrue(none.permits("https://example.com/"));
        assertTrue(none.permits("about:blank"));
        assertTrue(none.permits("data:text/plain,hi"));
    }

    private static void shouldGrantAPreviewOnlyItsOwnFileWhenTheFileIsTheAccessUrl() {
        String file = "file:///storage/emulated/0/Download/report.pdf";
        LocalReadGrant grant = LocalReadGrant.forPreview(file, file);
        assertTrue(grant.allowsFileScheme());
        assertFalse(grant.allowsContentScheme());
        assertTrue(grant.permits(file));
        assertTrue(grant.permits(file + "#page=2"));
        assertFalse(grant.permits("file:///storage/emulated/0/Download/other.pdf"));
        assertFalse(grant.permits(APP_FILES + "/opened-file-log.jsonl"));
        assertFalse(grant.permits("content://com.example.provider/1"));
    }

    private static void shouldGrantTheDirectoryWhenTheAccessUrlIsOne() {
        LocalReadGrant grant = LocalReadGrant.forPreview(
                "file:///storage/emulated/0/Download/page.html",
                "file:///storage/emulated/0/Download");
        assertTrue(grant.permits("file:///storage/emulated/0/Download/page.html"));
        assertTrue(grant.permits("file:///storage/emulated/0/Download/img/logo.png"));
        assertFalse(grant.permits("file:///storage/emulated/0/DCIM/photo.jpg"));
    }

    private static void shouldDefaultTheGrantToTheFilesOwnDirectory() {
        LocalReadGrant grant = LocalReadGrant.forPreview(
                "file:///storage/emulated/0/Download/clip.mp4", "");
        assertTrue(grant.permits("file:///storage/emulated/0/Download/clip.mp4"));
        assertTrue(grant.permits("file:///storage/emulated/0/Download/sibling.txt"));
        assertFalse(grant.permits("file:///storage/emulated/0/Music/song.mp3"));
    }

    private static void shouldNotLetDotSegmentsOrLookalikeDirectoriesEscape() {
        LocalReadGrant grant = LocalReadGrant.forPreview(
                "file:///storage/emulated/0/Download/clip.mp4",
                "file:///storage/emulated/0/Download");
        assertFalse(grant.permits(
                "file:///storage/emulated/0/Download/../../../data/user/0/app.example/files/db"));
        assertFalse(grant.permits(
                "file:///storage/emulated/0/Download/%2E%2E/%2E%2E/x"));
        assertFalse(grant.permits("file:///storage/emulated/0/Downloads-evil/x.pdf"));
    }

    // The host hands over Qt's pretty-decoded URL (literal spaces, Cyrillic);
    // the WebView asks for the fully encoded one. Both name the same file.
    private static void shouldMatchFileUrlsWhateverTheirPercentEncoding() {
        String pretty = "file:///storage/emulated/0/Download/Open with sample — пробел.pdf";
        String encoded = "file:///storage/emulated/0/Download/"
                + "Open%20with%20sample%20%E2%80%94%20%D0%BF%D1%80%D0%BE%D0%B1%D0%B5%D0%BB.pdf";
        LocalReadGrant grant = LocalReadGrant.forPreview(pretty, pretty);
        assertTrue(grant.permits(encoded));
    }

    private static void shouldGrantAContentPreviewOnlyItsOwnUri() {
        String uri = "content://com.google.android.gm.sapi/me%40example.com/"
                + "message_attachment_external/%23thread-f%3A1/0.1?account_type=com.google";
        LocalReadGrant grant = LocalReadGrant.forPreview(uri, "");
        assertTrue(grant.allowsContentScheme());
        assertFalse(grant.allowsFileScheme());
        assertTrue(grant.permits(uri));
        // The WebView may spell the same URI with different escapes.
        assertTrue(grant.permits("content://com.google.android.gm.sapi/me@example.com/"
                + "message_attachment_external/%23thread-f:1/0.1?account_type=com.google"));
        assertFalse(grant.permits("content://com.google.android.gm.sapi/me%40example.com/"
                + "message_attachment_external/%23thread-f%3A2/0.1?account_type=com.google"));
        assertFalse(grant.permits("content://com.example.other/1"));
        assertFalse(grant.permits(APP_FILES + "/db"));
    }

    private static void shouldRefuseAGrantThatDoesNotCoverTheFile() {
        assertSame(LocalReadGrant.NONE, LocalReadGrant.forPreview(
                "file:///storage/emulated/0/Download/x.pdf", APP_FILES));
        assertSame(LocalReadGrant.NONE, LocalReadGrant.forPreview(
                "file:///storage/emulated/0/Download/x.pdf", "https://example.com/"));
    }

    private static void shouldRefuseAGrantForAWebUrl() {
        assertSame(LocalReadGrant.NONE, LocalReadGrant.forPreview("https://example.com/x.pdf", ""));
        assertSame(LocalReadGrant.NONE, LocalReadGrant.forPreview("", ""));
        assertSame(LocalReadGrant.NONE, LocalReadGrant.forPreview(null, null));
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

    private static void assertSame(Object expected, Object actual) {
        if (expected != actual) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }
}
