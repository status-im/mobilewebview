package org.mobilewebview;

public class DownloadUrlPolicyTest {
    public static void main(String[] args) {
        TestAssert.assertTrue("https ok", DownloadUrlPolicy.isSupportedDownloadUrl("https://a/b.pdf"));
        TestAssert.assertTrue("http ok", DownloadUrlPolicy.isSupportedDownloadUrl("http://a/b.pdf"));
        TestAssert.assertFalse(DownloadUrlPolicy.isSupportedDownloadUrl("blob:https://a/uuid"));
        TestAssert.assertFalse(DownloadUrlPolicy.isSupportedDownloadUrl("data:text/plain,hi"));
        TestAssert.assertFalse(DownloadUrlPolicy.isSupportedDownloadUrl(null));

        TestAssert.assertEquals(
                "report.pdf",
                DownloadUrlPolicy.guessFileName(
                        "https://example.com/x",
                        "attachment; filename=\"report.pdf\"",
                        "application/pdf"));

        TestAssert.assertEquals(
                "file.bin",
                DownloadUrlPolicy.guessFileName(
                        "https://example.com/path/file.bin?token=1",
                        null,
                        "application/octet-stream"));

        TestAssert.assertEquals(
                "download.pdf",
                DownloadUrlPolicy.guessFileName("https://example.com/", null, "application/pdf"));

        System.out.println("DownloadUrlPolicyTest: OK");
    }
}
