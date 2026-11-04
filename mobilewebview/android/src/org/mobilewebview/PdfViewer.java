package org.mobilewebview;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * The page a PDF preview shows instead of the bytes the Android WebView cannot
 * render: one image per page, each a request the view answers with a
 * PdfRenderer bitmap, under a transparent layer of the page's text (see
 * PdfTextLayer) where Android can read it. Pages come from a host no network
 * serves, under a token per open document, so shouldInterceptRequest sees them
 * whatever the viewer page's scheme and no other page can guess them. Pure
 * Java, so the JVM tests state the page and its requests.
 */
final class PdfViewer {
    static final String PAGE_ORIGIN = "https://pdf.mobilewebview.invalid";
    private static final String WIDTH_QUERY = "?w=";
    private static final String TEXT_PATH = "/text";
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
        // The page's text layer rather than its image.
        final boolean text;

        PageRequest(int index, int width, boolean text) {
            this.index = index;
            this.width = width;
            this.text = text;
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
        if (rest.endsWith(TEXT_PATH)) {
            int index = parseNonNegative(rest.substring(0, rest.length() - TEXT_PATH.length()));
            return index < 0 ? null : new PageRequest(index, 0, true);
        }
        int query = rest.indexOf(WIDTH_QUERY);
        if (query <= 0) {
            return null;
        }
        int index = parseNonNegative(rest.substring(0, query));
        int width = parseNonNegative(rest.substring(query + WIDTH_QUERY.length()));
        if (index < 0 || width < 0) {
            return null;
        }
        return new PageRequest(index, Math.max(MIN_RENDER_WIDTH, Math.min(MAX_RENDER_WIDTH, width)), false);
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

    /**
     * The viewer page: every page as a lazily loaded image of its own shape,
     * under the page's text layer when {@code withText}.
     */
    static String html(String title, float[] pageWidths, float[] pageHeights, String token,
                       boolean withText) {
        StringBuilder sb = new StringBuilder(2048 + pageWidths.length * 160);
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
          .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1,")
          .append(" minimum-scale=1, maximum-scale=8\">")
          .append("<title>").append(escapeHtml(title)).append("</title><style>")
          .append("html,body{margin:0;background:#525659}")
          .append("body{display:flex;flex-direction:column;align-items:center;gap:8px;padding:8px 0;")
          .append("user-select:none}")
          .append(".page{position:relative;width:calc(100% - 16px);container-type:inline-size;")
          .append("background:#fff;box-shadow:0 1px 3px rgba(0,0,0,.4)}")
          .append(".page img{display:block;width:100%;height:auto}")
          .append(".text{position:absolute;inset:0;overflow:hidden;line-height:1;user-select:text}")
          .append(".text span{position:absolute;white-space:pre;color:transparent;")
          .append("font-family:sans-serif;transform-origin:0 0}")
          .append(".text span::selection{background:rgba(0,100,255,.3)}")
          // Find-in-page matches tint the page's own glyphs instead of covering them.
          .append(".text span::search-text{color:transparent;background:rgba(255,213,0,.45)}")
          .append(".text span::search-text:current{background:rgba(255,128,0,.55)}")
          .append("</style></head><body>");
        for (int i = 0; i < pageWidths.length; i++) {
            String w = number(pageWidths[i]);
            String h = number(pageHeights[i]);
            sb.append("<div class=\"page\" data-page=\"").append(i)
              .append("\" data-w=\"").append(w).append("\" data-h=\"").append(h)
              .append("\"><img alt=\"Page ").append(i + 1)
              .append("\" width=\"").append(Math.round(pageWidths[i]))
              .append("\" height=\"").append(Math.round(pageHeights[i]))
              .append("\" loading=\"lazy\">");
            if (withText) {
                sb.append("<div class=\"text\"></div>");
            }
            sb.append("</div>");
        }
        sb.append("<script>(function(){var base=").append(jsString(pageBase(token)))
          .append(",maxWidth=").append(MAX_RENDER_WIDTH)
          .append(",withText=").append(withText).append(';')
          .append(VIEWER_SCRIPT).append("})();</script></body></html>");
        return sb.toString();
    }

    // Images render at the device's pixel width and scale with pinch zoom.
    // Each text run is a transparent span over its place on the page, in units
    // of the page's width, stretched to the width the page draws it at; line
    // breaks between runs keep copied text in lines. The text of every page
    // loads, visible pages first, so find-in-page covers the whole document.
    private static final String VIEWER_SCRIPT =
            "var pages=document.querySelectorAll('.page');"
            + "var w=Math.round(Math.min(Math.max(document.documentElement.clientWidth,1)"
            + "*(window.devicePixelRatio||1),maxWidth));"
            + "for(var i=0;i<pages.length;i++)pages[i].querySelector('img').src=base+i+'?w='+w;"
            + "if(!withText)return;"
            + "var ctx=document.createElement('canvas').getContext('2d');"
            + "function lay(page,runs){"
            + "var pw=+page.getAttribute('data-w'),ph=+page.getAttribute('data-h'),"
            + "f=document.createDocumentFragment();"
            + "for(var j=0;j<runs.length;j++){"
            + "var r=runs[j],h=r[3]-r[1];if(h<=0||r[2]<=r[0])continue;"
            + "ctx.font=h+'px sans-serif';"
            + "var m=ctx.measureText(r[4]).width,s=document.createElement('span');"
            + "s.textContent=r[4]+(r[5]===' '?' ':'');"
            + "s.style.left=r[0]*100/pw+'%';s.style.top=r[1]*100/ph+'%';"
            + "s.style.fontSize='calc('+h+'*100cqw/'+pw+')';"
            + "if(m>0)s.style.transform='scaleX('+(r[2]-r[0])/m+')';"
            + "f.appendChild(s);if(r[5]==='\\n')f.appendChild(document.createElement('br'));}"
            + "f.appendChild(document.createElement('br'));"
            + "page.querySelector('.text').appendChild(f);}"
            + "var queue=[],busy=0;for(i=0;i<pages.length;i++)queue.push(i);"
            + "var seen=new IntersectionObserver(function(es){es.forEach(function(e){"
            + "var k=queue.indexOf(+e.target.getAttribute('data-page'));"
            + "if(e.isIntersecting&&k>0)queue.unshift(queue.splice(k,1)[0]);});});"
            + "for(i=0;i<pages.length;i++)seen.observe(pages[i]);"
            + "function load(n){busy++;"
            + "fetch(base+n+'/text').then(function(r){return r.ok?r.json():[];})"
            + ".then(function(runs){lay(pages[n],runs);},function(){})"
            + ".then(function(){busy--;pump();});}"
            + "function pump(){while(busy<2&&queue.length)load(queue.shift());"
            + "if(!queue.length)seen.disconnect();}"
            + "pump();";

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

    /** A coordinate with at most two decimals and no trailing zeros. */
    static String number(float value) {
        String s = String.format(Locale.ROOT, "%.2f", value);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    // A JS string literal safe inside a <script> element, and a JSON string.
    static String jsString(String s) {
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
