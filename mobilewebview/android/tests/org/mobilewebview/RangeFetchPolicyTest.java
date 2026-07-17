package org.mobilewebview;

import java.io.File;
import java.io.FileOutputStream;

public final class RangeFetchPolicyTest {
    public static void main(String[] args) throws Exception {
        rangeHeader();
        existingFileLength();
        isPartialContent();
        totalFromContentRange();
        shouldAppend();
        System.out.println("RangeFetchPolicyTest passed");
    }

    private static void rangeHeader() {
        TestAssert.assertNull(RangeFetchPolicy.rangeHeader(0));
        TestAssert.assertNull(RangeFetchPolicy.rangeHeader(-1));
        TestAssert.assertEquals("bytes=100-", RangeFetchPolicy.rangeHeader(100));
    }

    private static void existingFileLength() throws Exception {
        TestAssert.assertEquals(0L, RangeFetchPolicy.existingFileLength(null));
        File missing = new File("/tmp/mwv-range-missing-" + System.nanoTime());
        TestAssert.assertEquals(0L, RangeFetchPolicy.existingFileLength(missing));

        File part = File.createTempFile("mwv-range-", ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(new byte[] {1, 2, 3, 4, 5});
        }
        try {
            TestAssert.assertEquals(5L, RangeFetchPolicy.existingFileLength(part));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
    }

    private static void isPartialContent() {
        TestAssert.assertTrue(RangeFetchPolicy.isPartialContent(206));
        TestAssert.assertFalse(RangeFetchPolicy.isPartialContent(200));
        TestAssert.assertFalse(RangeFetchPolicy.isPartialContent(416));
    }

    private static void totalFromContentRange() {
        TestAssert.assertEquals(1000L,
            RangeFetchPolicy.totalFromContentRange("bytes 100-999/1000", -1));
        TestAssert.assertEquals(42L,
            RangeFetchPolicy.totalFromContentRange("bytes 0-41/*", 42));
        TestAssert.assertEquals(-1L,
            RangeFetchPolicy.totalFromContentRange(null, -1));
        TestAssert.assertEquals(7L,
            RangeFetchPolicy.totalFromContentRange("garbage", 7));
    }

    private static void shouldAppend() {
        TestAssert.assertFalse(RangeFetchPolicy.shouldAppend(0));
        TestAssert.assertTrue(RangeFetchPolicy.shouldAppend(1));
    }
}
