package org.mobilewebview;

import java.io.File;

/**
 * Pure helpers for HTTP Range resume of self-fetch downloads (ADR 0005).
 * No Android framework dependencies — unit-tested on the JVM.
 */
final class RangeFetchPolicy {
    private RangeFetchPolicy() {}

    /** {@code bytes=N-} request header value, or null when a full GET is needed. */
    static String rangeHeader(long offsetBytes) {
        if (offsetBytes <= 0) {
            return null;
        }
        return "bytes=" + offsetBytes + "-";
    }

    static long existingFileLength(File file) {
        if (file == null || !file.isFile()) {
            return 0L;
        }
        long length = file.length();
        return length > 0 ? length : 0L;
    }

    static boolean isPartialContent(int responseCode) {
        return responseCode == 206;
    }

    /**
     * Parse total size from {@code Content-Range: bytes start-end/total}.
     * Returns {@code fallbackTotal} when the header is missing or uses {@code *}.
     */
    static long totalFromContentRange(String contentRange, long fallbackTotal) {
        if (contentRange == null || contentRange.isEmpty()) {
            return fallbackTotal;
        }
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0 || slash + 1 >= contentRange.length()) {
            return fallbackTotal;
        }
        String totalPart = contentRange.substring(slash + 1).trim();
        if ("*".equals(totalPart)) {
            return fallbackTotal;
        }
        try {
            long total = Long.parseLong(totalPart);
            return total >= 0 ? total : fallbackTotal;
        } catch (NumberFormatException e) {
            return fallbackTotal;
        }
    }

    /** Open mode for FileOutputStream / ContentResolver: append when resuming. */
    static boolean shouldAppend(long offsetBytes) {
        return offsetBytes > 0;
    }
}
