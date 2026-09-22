package org.mobilewebview;

public final class DownloadMediaStoreTest {
    public static void main(String[] args) {
        shouldDropAMimeThatContradictsTheExtension();
        shouldKeepTheMimeWhenTheNameCarriesNoExtension();
        shouldHandleMissingInput();
        System.out.println("DownloadMediaStoreTest passed");
    }

    // A site that names a PNG "photo.jpg" must not make the insert fail.
    private static void shouldDropAMimeThatContradictsTheExtension() {
        assertEquals(null, DownloadMediaStore.mimeForPublish("photo.jpg", "image/png"));
        assertEquals(null, DownloadMediaStore.mimeForPublish("photo.jpg", "image/jpeg"));
        assertEquals(null, DownloadMediaStore.mimeForPublish("archive.tar.gz", "application/gzip"));
    }

    private static void shouldKeepTheMimeWhenTheNameCarriesNoExtension() {
        assertEquals("application/pdf", DownloadMediaStore.mimeForPublish("report", "application/pdf"));
        assertEquals("image/png", DownloadMediaStore.mimeForPublish(".hidden", "image/png"));
        assertEquals("image/png", DownloadMediaStore.mimeForPublish("trailing.", "image/png"));
    }

    private static void shouldHandleMissingInput() {
        assertEquals(null, DownloadMediaStore.mimeForPublish("report", null));
        assertEquals(null, DownloadMediaStore.mimeForPublish("report", ""));
        assertEquals("image/png", DownloadMediaStore.mimeForPublish(null, "image/png"));
    }

    private static void assertEquals(String expected, String actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }
}
