package org.mobilewebview;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MobileWebViewNativeBridgeTest {
    public static void main(String[] args) {
        shouldDeliverMessageFromAllowedOrigin();
        shouldRejectMessageFromDisallowedOrigin();
        shouldRejectMessageBeforeOriginIsKnown();
        shouldIgnoreMessageAfterNativeSideIsGone();
        shouldForwardMediaPlaybackFromAnyFrame();
        shouldIgnoreMediaPlaybackAfterNativeSideIsGone();
        shouldIgnoreMediaPlaybackWithoutADocumentId();
        System.out.println("MobileWebViewNativeBridgeTest passed");
    }

    private static final class FakeHost implements NativeBridgeHost {
        long nativePtr = 1;
        String origin = "";
        List<String> allowed = new ArrayList<>();
        final List<String> delivered = new ArrayList<>();
        final List<String> mediaReports = new ArrayList<>();

        @Override public long nativePtr() { return nativePtr; }
        @Override public String currentMainFrameOrigin() { return origin; }
        @Override public List<String> allowedOriginsSnapshot() { return allowed; }
        @Override public void onWebMessage(String message, String messageOrigin) {
            delivered.add(messageOrigin + " " + message);
        }
        @Override public void onMediaPlaybackChanged(String documentId, boolean playing) {
            mediaReports.add(documentId + " " + playing);
        }
    }

    private static void shouldDeliverMessageFromAllowedOrigin() {
        FakeHost host = new FakeHost();
        host.origin = "https://status.app";
        host.allowed = Arrays.asList("https://status.app");

        new MobileWebViewNativeBridge(host).postMessage("hello");

        assertEquals(Arrays.asList("https://status.app hello"), host.delivered);
    }

    private static void shouldRejectMessageFromDisallowedOrigin() {
        FakeHost host = new FakeHost();
        host.origin = "https://evil.example";
        host.allowed = Arrays.asList("https://status.app");

        new MobileWebViewNativeBridge(host).postMessage("hello");

        assertEquals(new ArrayList<String>(), host.delivered);
    }

    // postMessage runs on the JavaBridge thread: with no main-frame origin yet it
    // must reject the message, never ask the WebView (that throws off its UI thread).
    private static void shouldRejectMessageBeforeOriginIsKnown() {
        FakeHost host = new FakeHost();
        host.origin = "";
        host.allowed = Arrays.asList("*");

        new MobileWebViewNativeBridge(host).postMessage("hello");

        assertEquals(new ArrayList<String>(), host.delivered);
    }

    private static void shouldIgnoreMessageAfterNativeSideIsGone() {
        FakeHost host = new FakeHost();
        host.nativePtr = 0;
        host.origin = "https://status.app";
        host.allowed = Arrays.asList("https://status.app");

        new MobileWebViewNativeBridge(host).postMessage("hello");

        assertEquals(new ArrayList<String>(), host.delivered);
    }

    // Media state carries no page data, so frames of any origin may report it.
    private static void shouldForwardMediaPlaybackFromAnyFrame() {
        FakeHost host = new FakeHost();
        host.origin = "";

        MobileWebViewNativeBridge bridge = new MobileWebViewNativeBridge(host);
        bridge.mediaPlaybackChanged("d1", true);
        bridge.mediaPlaybackChanged("d1", false);

        assertEquals(Arrays.asList("d1 true", "d1 false"), host.mediaReports);
    }

    private static void shouldIgnoreMediaPlaybackAfterNativeSideIsGone() {
        FakeHost host = new FakeHost();
        host.nativePtr = 0;

        new MobileWebViewNativeBridge(host).mediaPlaybackChanged("d1", true);

        assertEquals(new ArrayList<String>(), host.mediaReports);
    }

    private static void shouldIgnoreMediaPlaybackWithoutADocumentId() {
        FakeHost host = new FakeHost();

        new MobileWebViewNativeBridge(host).mediaPlaybackChanged(null, true);
        new MobileWebViewNativeBridge(host).mediaPlaybackChanged("", true);

        assertEquals(new ArrayList<String>(), host.mediaReports);
    }

    private static void assertEquals(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }
}
