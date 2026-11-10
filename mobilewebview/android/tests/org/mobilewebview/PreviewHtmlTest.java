package org.mobilewebview;

import static org.mobilewebview.TestAssert.assertEquals;

public final class PreviewHtmlTest {
    public static void main(String[] args) {
        shouldEscapeEverythingThatCouldLeaveText();
        System.out.println("PreviewHtmlTest passed");
    }

    private static void shouldEscapeEverythingThatCouldLeaveText() {
        assertEquals("&lt;b&gt;&quot;a&quot; &amp; &#39;b&#39;&lt;/b&gt;",
                PreviewHtml.escape("<b>\"a\" & 'b'</b>"));
    }
}
