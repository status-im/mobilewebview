package org.mobilewebview;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * The page a PDF preview shows instead of the bytes the Android WebView cannot
 * render: one image per page, each a request the view answers with a
 * PdfRenderer bitmap. Page images come from a host no network serves, under a
 * token per open document, so shouldInterceptRequest sees them whatever the
 * viewer page's scheme and no other page can guess them. Pure Java, so the
 * JVM tests state the page and its requests.
 */
final class PdfViewer {
    static final String PAGE_ORIGIN = "https://pdf.mobilewebview.invalid";
    private static final String WIDTH_QUERY = "?w=";
    private static final SecureRandom RANDOM = new SecureRandom();
    static final int MIN_RENDER_WIDTH = 200;
    static final int MAX_RENDER_WIDTH = 2048;
    static final int MAX_RENDER_HEIGHT = 8192;
    // How far into the file a "%PDF-" header may start.
    static final int SIGNATURE_WINDOW = 1024;

    private PdfViewer() { }

    static final class PageRequest {
        final int index;
        final int width;

        PageRequest(int index, int width) {
            this.index = index;
            this.width = width;
        }
    }

    /** A fresh token for one open document: 128 random bits as hex. */
    static String newToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return sb.toString();
    }

    /** Where the pages of the document with this token are requested. */
    static String pageBase(String token) {
        return PAGE_ORIGIN + "/" + token + "/";
    }

    /** The page {@code requestUrl} asks for, or null when it is not one of this document's. */
    static PageRequest parsePageRequest(String requestUrl, String token) {
        if (requestUrl == null || token == null) {
            return null;
        }
        String base = pageBase(token);
        if (!requestUrl.startsWith(base)) {
            return null;
        }
        String rest = requestUrl.substring(base.length());
        int query = rest.indexOf(WIDTH_QUERY);
        if (query <= 0) {
            return null;
        }
        int index = parseNonNegative(rest.substring(0, query));
        int width = parseNonNegative(rest.substring(query + WIDTH_QUERY.length()));
        if (index < 0 || width < 0) {
            return null;
        }
        return new PageRequest(index, Math.max(MIN_RENDER_WIDTH, Math.min(MAX_RENDER_WIDTH, width)));
    }

    /** Bitmap size for a page of the given shape: the requested width, height within bounds. */
    static int[] renderSize(float pageWidth, float pageHeight, int width) {
        if (pageWidth <= 0f || pageHeight <= 0f) {
            return new int[] {Math.max(1, width), Math.max(1, width)};
        }
        int w = Math.max(1, width);
        int h = Math.round(w * pageHeight / pageWidth);
        if (h > MAX_RENDER_HEIGHT) {
            h = MAX_RENDER_HEIGHT;
            w = Math.max(1, Math.round(h * pageWidth / pageHeight));
        }
        return new int[] {w, Math.max(1, h)};
    }

    static boolean isPdf(byte[] head, int length) {
        byte[] signature = "%PDF-".getBytes(StandardCharsets.US_ASCII);
        int limit = Math.min(Math.min(length, head.length), SIGNATURE_WINDOW) - signature.length;
        for (int i = 0; i <= limit; i++) {
            boolean match = true;
            for (int j = 0; j < signature.length; j++) {
                if (head[i + j] != signature[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    /** The viewer page: every page as a lazily loaded image of its own shape. */
    static String html(String title, float[] pageWidths, float[] pageHeights, String token) {
        StringBuilder sb = new StringBuilder(512 + pageWidths.length * 96);
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
          .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1,")
          .append(" minimum-scale=1, maximum-scale=8\">")
          .append("<title>").append(escapeHtml(title)).append("</title><style>")
          .append("html,body{margin:0;background:#525659}")
          .append("body{display:flex;flex-direction:column;align-items:center;gap:8px;padding:8px 0}")
          .append("img{display:block;width:calc(100% - 16px);height:auto;background:#fff;")
          .append("box-shadow:0 1px 3px rgba(0,0,0,.4)}")
          .append("</style></head><body>");
        for (int i = 0; i < pageWidths.length; i++) {
            sb.append("<img alt=\"Page ").append(i + 1)
              .append("\" data-page=\"").append(i)
              .append("\" width=\"").append(Math.round(pageWidths[i]))
              .append("\" height=\"").append(Math.round(pageHeights[i]))
              .append("\" loading=\"lazy\">");
        }
        // Rendered at the device's pixel width; the images scale with pinch zoom.
        sb.append("<script>(function(){var base=").append(jsString(pageBase(token)))
          .append(";var w=Math.round(Math.min(Math.max(document.documentElement.clientWidth,1)")
          .append("*(window.devicePixelRatio||1),").append(MAX_RENDER_WIDTH).append("));")
          .append("var pages=document.querySelectorAll('img[data-page]');")
          .append("for(var i=0;i<pages.length;i++){pages[i].src=base+")
          .append("pages[i].getAttribute('data-page')+'").append(WIDTH_QUERY)
          .append("'+w;}})();</script></body></html>");
        return sb.toString();
    }

    /** The page for a PDF PdfRenderer cannot open (corrupt, password-protected). */
    static String unavailableHtml(String title) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + escapeHtml(title) + "</title><style>"
                + "html,body{margin:0;height:100%;background:#525659;color:#fff;"
                + "font:16px sans-serif}body{display:flex;align-items:center;"
                + "justify-content:center;text-align:center;padding:0 24px}"
                + "</style></head><body><p>This PDF can't be shown.</p></body></html>";
    }

    private static int parseNonNegative(String digits) {
        if (digits.isEmpty() || digits.length() > 9) {
            return -1;
        }
        for (int i = 0; i < digits.length(); i++) {
            if (!Character.isDigit(digits.charAt(i))) {
                return -1;
            }
        }
        return Integer.parseInt(digits);
    }

    private static String escapeHtml(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    // A JS string literal safe inside a <script> element.
    private static String jsString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\' || c == '<' || c == '>' || c == '\'' || c < 0x20
                    || c == ' ' || c == ' ') {
                sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
