package org.mobilewebview;

public final class DownloadProbeTest {
    public static void main(String[] args) {
        emptyResult();
        emptyUrl();
        System.out.println("DownloadProbeTest passed");
    }

    private static void emptyResult() {
        DownloadProbe.Result r = DownloadProbe.Result.empty();
        TestAssert.assertEquals("", r.contentDisposition);
        TestAssert.assertEquals("", r.mimeType);
        TestAssert.assertEquals(-1L, r.contentLength);
    }

    private static void emptyUrl() {
        DownloadProbe.Result r = DownloadProbe.probeHeaders("", "UA", null);
        TestAssert.assertEquals("", r.contentDisposition);
        TestAssert.assertEquals("", r.mimeType);
        TestAssert.assertEquals(-1L, r.contentLength);

        r = DownloadProbe.probeHeaders(null, "UA", "a=b");
        TestAssert.assertEquals(-1L, r.contentLength);
    }
}
