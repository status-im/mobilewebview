package org.mobilewebview;

import java.util.Locale;

/**
 * The page a local audio or video file plays in. Loaded directly, the WebView
 * shows its own media document, which starts playing and hides its controls;
 * this page shows them and waits for the user. Also the page for a bitmap the
 * WebView shows blank by its type (image/x-ms-bmp), which an img decodes.
 */
final class MediaPage {
    private MediaPage() {}

    /** "video", "audio" or "img" for a file of mimeType, null for anything else. */
    static String element(String mimeType) {
        String type = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (type.equals("image/x-ms-bmp")) {
            return "img";
        }
        return type.startsWith("video/") ? "video" : type.startsWith("audio/") ? "audio" : null;
    }

    static String html(String element, String url, String title) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + PdfViewer.escapeHtml(title) + "</title><style>"
                + "html,body{margin:0;height:100%;background:#000}"
                + "body{display:flex;align-items:center;justify-content:center}"
                + "video,img{max-width:100%;max-height:100%}audio{width:90%}"
                + "</style></head><body>" + tag(element, PdfViewer.escapeHtml(url)) + "</body></html>";
    }

    private static String tag(String element, String src) {
        if (element.equals("img")) {
            return "<img src=\"" + src + "\" alt=\"\">";
        }
        return "<" + element + " src=\"" + src + "\" controls playsinline preload=\"metadata\"></"
                + element + ">";
    }
}
