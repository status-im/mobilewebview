package org.mobilewebview;

public final class BridgeScriptBuilderTest {
    public static void main(String[] args) {
        shouldQuoteJsLiteral();
        shouldBuildDeliverScript();
        System.out.println("BridgeScriptBuilderTest passed");
    }

    private static void shouldQuoteJsLiteral() {
        String quoted = BridgeScriptBuilder.quoteJsLiteral("a\"b\\c\n\r\t");
        assertEquals("\"a\\\"b\\\\c\\n\\r\\t\"", quoted);
    }

    private static void shouldBuildDeliverScript() {
        String script = BridgeScriptBuilder.buildDeliverScript("qt", "{\"k\":\"v\"}");
        assertContains(script, "window[ns] && window[ns].__deliverMessage");
        assertContains(script, "})(\"qt\", \"{\\\"k\\\":\\\"v\\\"}\");");
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected [" + expected + "], got [" + actual + "]");
        }
    }

    private static void assertContains(String actual, String needle) {
        if (!actual.contains(needle)) {
            throw new AssertionError("Expected substring [" + needle + "] in [" + actual + "]");
        }
    }
}
