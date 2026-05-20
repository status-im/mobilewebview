package org.mobilewebview;

final class TestAssert {
    private TestAssert() { }

    static void assertTrue(boolean value) {
        if (!value) {
            throw new AssertionError("Expected true");
        }
    }

    static void assertFalse(boolean value) {
        if (value) {
            throw new AssertionError("Expected false");
        }
    }

    static void assertEquals(boolean expected, boolean actual) {
        if (expected != actual) {
            throw new AssertionError("Expected [" + expected + "], got [" + actual + "]");
        }
    }

    static void assertEquals(int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError("Expected [" + expected + "], got [" + actual + "]");
        }
    }

    static void assertEquals(long expected, long actual) {
        if (expected != actual) {
            throw new AssertionError("Expected [" + expected + "], got [" + actual + "]");
        }
    }

    static void assertEquals(String expected, String actual) {
        if (expected == null && actual == null) {
            return;
        }
        if (expected == null || !expected.equals(actual)) {
            throw new AssertionError("Expected [" + expected + "], got [" + actual + "]");
        }
    }

    static void assertEquals(Object expected, Object actual) {
        if (expected == null && actual == null) {
            return;
        }
        if (expected == null || !expected.equals(actual)) {
            throw new AssertionError("Expected [" + expected + "], got [" + actual + "]");
        }
    }

    static void assertNull(Object value) {
        if (value != null) {
            throw new AssertionError("Expected null, got [" + value + "]");
        }
    }

    static void assertNotNull(Object value) {
        if (value == null) {
            throw new AssertionError("Expected non-null");
        }
    }

    static void assertContains(String haystack, String needle) {
        if (haystack == null || !haystack.contains(needle)) {
            throw new AssertionError("Expected to contain [" + needle + "] in [" + haystack + "]");
        }
    }
}
