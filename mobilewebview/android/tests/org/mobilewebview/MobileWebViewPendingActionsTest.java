package org.mobilewebview;

import java.util.ArrayList;
import java.util.List;

public final class MobileWebViewPendingActionsTest {
    public static void main(String[] args) {
        shouldReplayPendingActionsInOrderWhenQueueBecomesReady();
        shouldRunActionImmediatelyAfterReady();
        shouldIgnoreSecondMarkReadyCall();
        System.out.println("MobileWebViewPendingActionsTest passed");
    }

    private static void shouldReplayPendingActionsInOrderWhenQueueBecomesReady() {
        PendingActionQueue queue = new PendingActionQueue();
        List<String> events = new ArrayList<>();

        assertTrue(queue.enqueueIfNotReady(() -> events.add("first")));
        assertTrue(queue.enqueueIfNotReady(() -> events.add("second")));

        queue.markReady();

        assertEquals(2, events.size());
        assertEquals("first", events.get(0));
        assertEquals("second", events.get(1));
    }

    private static void shouldRunActionImmediatelyAfterReady() {
        PendingActionQueue queue = new PendingActionQueue();
        List<String> events = new ArrayList<>();

        queue.markReady();
        assertFalse(queue.enqueueIfNotReady(() -> events.add("queued")));

        events.add("immediate");
        assertEquals(1, events.size());
        assertEquals("immediate", events.get(0));
    }

    private static void shouldIgnoreSecondMarkReadyCall() {
        PendingActionQueue queue = new PendingActionQueue();
        List<String> events = new ArrayList<>();

        assertTrue(queue.enqueueIfNotReady(() -> events.add("once")));
        queue.markReady();
        queue.markReady();

        assertEquals(1, events.size());
        assertEquals("once", events.get(0));
    }

    private static void assertTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError("Expected true");
        }
    }

    private static void assertFalse(boolean condition) {
        if (condition) {
            throw new AssertionError("Expected false");
        }
    }

    private static void assertEquals(int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError("Expected [" + expected + "], got [" + actual + "]");
        }
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected [" + expected + "], got [" + actual + "]");
        }
    }
}
