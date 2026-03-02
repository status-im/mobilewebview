package org.mobilewebview;

import java.util.Arrays;
import java.util.Collections;

public final class OriginUtilsTest {
    public static void main(String[] args) {
        shouldExtractOrigin();
        shouldMatchAllowedOrigins();
        System.out.println("OriginUtilsTest passed");
    }

    private static void shouldExtractOrigin() {
        assertEquals("https://example.com",
            OriginUtils.extractOrigin("https://example.com/path"));
        assertEquals("http://example.com:8080",
            OriginUtils.extractOrigin("http://example.com:8080/path"));
        assertEquals("http://example.com",
            OriginUtils.extractOrigin("http://example.com:80/path"));
        assertEquals("",
            OriginUtils.extractOrigin("invalid-url"));
        assertEquals("",
            OriginUtils.extractOrigin(""));
        assertEquals("",
            OriginUtils.extractOrigin(null));
    }

    private static void shouldMatchAllowedOrigins() {
        assertTrue(OriginUtils.isOriginAllowed("https://example.com",
            Arrays.asList("https://example.com")));
        assertTrue(OriginUtils.isOriginAllowed("https://api.example.com",
            Arrays.asList("https://*.example.com")));
        assertTrue(OriginUtils.isOriginAllowed("https://anything.com",
            Arrays.asList("*")));
        assertFalse(OriginUtils.isOriginAllowed("https://example.org",
            Arrays.asList("https://*.example.com")));
        assertFalse(OriginUtils.isOriginAllowed("",
            Arrays.asList("*")));
        assertFalse(OriginUtils.isOriginAllowed("https://example.com",
            Collections.emptyList()));
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected [" + expected + "], got [" + actual + "]");
        }
    }

    private static void assertTrue(boolean value) {
        if (!value) {
            throw new AssertionError("Expected true");
        }
    }

    private static void assertFalse(boolean value) {
        if (value) {
            throw new AssertionError("Expected false");
        }
    }
}
