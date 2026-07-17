package org.mobilewebview;

/**
 * Pure helpers for download URL / filename policy (ADR 0005).
 * Kept free of Android framework types so JVM unit tests can cover it.
 */
final class DownloadUrlPolicy {
    private DownloadUrlPolicy() {}

    /** blob: and data: are out of scope for v1. */
    static boolean isSupportedDownloadUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String lower = url.toLowerCase();
        return !lower.startsWith("blob:") && !lower.startsWith("data:");
    }

    /**
     * Best-effort filename from Content-Disposition / URL path.
     * Mirrors the common cases of android.webkit.URLUtil.guessFileName without
     * depending on the Android SDK in unit tests.
     */
    static String guessFileName(String url, String contentDisposition, String mimeType) {
        String fromDisposition = filenameFromContentDisposition(contentDisposition);
        if (fromDisposition != null && !fromDisposition.isEmpty()) {
            return sanitizeFileName(fromDisposition);
        }

        String fromUrl = filenameFromUrl(url);
        if (fromUrl != null && !fromUrl.isEmpty()) {
            return sanitizeFileName(fromUrl);
        }

        String ext = extensionForMime(mimeType);
        return ext.isEmpty() ? "download" : ("download." + ext);
    }

    static String filenameFromContentDisposition(String contentDisposition) {
        if (contentDisposition == null || contentDisposition.isEmpty()) {
            return null;
        }
        // filename*=UTF-8''...
        int star = indexOfIgnoreCase(contentDisposition, "filename*=");
        if (star >= 0) {
            String rest = contentDisposition.substring(star + "filename*=".length()).trim();
            int end = rest.indexOf(';');
            if (end >= 0) {
                rest = rest.substring(0, end);
            }
            rest = trimQuotes(rest);
            int tick = rest.lastIndexOf("''");
            if (tick >= 0 && tick + 2 < rest.length()) {
                return rest.substring(tick + 2);
            }
            return rest;
        }
        int idx = indexOfIgnoreCase(contentDisposition, "filename=");
        if (idx < 0) {
            return null;
        }
        String rest = contentDisposition.substring(idx + "filename=".length()).trim();
        int end = rest.indexOf(';');
        if (end >= 0) {
            rest = rest.substring(0, end);
        }
        return trimQuotes(rest);
    }

    private static String filenameFromUrl(String url) {
        if (url == null) {
            return null;
        }
        String path = url;
        int scheme = path.indexOf("://");
        if (scheme >= 0) {
            path = path.substring(scheme + 3);
            int slash = path.indexOf('/');
            path = slash >= 0 ? path.substring(slash + 1) : "";
        }
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int hash = path.indexOf('#');
        if (hash >= 0) {
            path = path.substring(0, hash);
        }
        int slash = path.lastIndexOf('/');
        if (slash >= 0) {
            path = path.substring(slash + 1);
        }
        return path.isEmpty() ? null : path;
    }

    private static String extensionForMime(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        String mime = mimeType.toLowerCase();
        if (mime.contains("pdf")) return "pdf";
        if (mime.contains("zip")) return "zip";
        if (mime.contains("png")) return "png";
        if (mime.contains("jpeg") || mime.contains("jpg")) return "jpg";
        if (mime.contains("octet-stream")) return "bin";
        if (mime.startsWith("text/")) return "txt";
        return "";
    }

    private static String sanitizeFileName(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "download" : cleaned;
    }

    private static String trimQuotes(String value) {
        String v = value.trim();
        if (v.length() >= 2) {
            if ((v.startsWith("\"") && v.endsWith("\""))
                    || (v.startsWith("'") && v.endsWith("'"))) {
                return v.substring(1, v.length() - 1);
            }
        }
        return v;
    }

    private static int indexOfIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase().indexOf(needle.toLowerCase());
    }
}
