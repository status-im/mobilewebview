package org.mobilewebview;

import android.content.Context;
import android.webkit.WebSettings;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The User-Agent a self-fetch download actually sends, for start and resume.
 * Both run on the Qt thread, so they must not depend on the WebView.
 */
public final class SelfFetchDownloadsTest {
    private static final byte[] PAYLOAD = "0123456789".getBytes();
    private static final int FIRST_CHUNK = 4;

    public static void main(String[] args) throws Exception {
        startSendsPlatformDefaultWhenHostSetsNone();
        startSendsHostUserAgent();
        resumeSendsPlatformDefaultWhenHostSetsNone();
        System.out.println("SelfFetchDownloadsTest passed");
        // DownloadFetcher pool threads idle for 60s before the JVM may exit.
        System.exit(0);
    }

    private static final class FakeHost implements SelfFetchDownloads.Host {
        final String userAgent;

        FakeHost(String userAgent) {
            this.userAgent = userAgent;
        }

        @Override public String httpUserAgent() { return userAgent; }
        // Off the record: nothing is registered with the MediaStore.
        @Override public boolean offTheRecord() { return true; }
        @Override public Context context() { return new Context(); }
    }

    private static final class Finishes implements DownloadFetcher.Callbacks {
        final CountDownLatch firstProgress = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        volatile boolean ok;

        @Override public void onProgress(long downloadId, long receivedBytes, long totalBytes) {
            firstProgress.countDown();
        }

        @Override public void onFinished(long downloadId, boolean ok, String error) {
            this.ok = ok;
            finished.countDown();
        }
    }

    private static void startSendsPlatformDefaultWhenHostSetsNone() throws Exception {
        assertEquals("fake-default-agent", userAgentSentByStart(""));
    }

    private static void startSendsHostUserAgent() throws Exception {
        assertEquals("host-agent", userAgentSentByStart("host-agent"));
    }

    private static String userAgentSentByStart(String hostUserAgent) throws Exception {
        WebSettings.resetDefaultUserAgent();
        List<String> agents = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/file", exchange -> {
            agents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
            exchange.sendResponseHeaders(200, PAYLOAD.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(PAYLOAD);
            }
        });
        server.start();
        File dest = tempDestination();
        Finishes finishes = new Finishes();
        try {
            SelfFetchDownloads downloads =
                    new SelfFetchDownloads(new FakeHost(hostUserAgent), finishes);
            downloads.start(1L, url(server), dest.getPath());

            check(finishes.finished.await(10, TimeUnit.SECONDS), "download did not finish");
            check(finishes.ok, "download failed");
            assertEquals(1, agents.size());
            return agents.get(0);
        } finally {
            server.stop(0);
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
        }
    }

    // Pause mid-transfer, then resume: the Range request carries the resolved agent too.
    private static void resumeSendsPlatformDefaultWhenHostSetsNone() throws Exception {
        WebSettings.resetDefaultUserAgent();
        List<String> resumeAgents = new CopyOnWriteArrayList<>();
        ConcurrentLinkedQueue<HttpExchange> parked = new ConcurrentLinkedQueue<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/file", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range == null) {
                exchange.sendResponseHeaders(200, PAYLOAD.length);
                OutputStream out = exchange.getResponseBody();
                out.write(PAYLOAD, 0, FIRST_CHUNK);
                out.flush();
                parked.add(exchange); // held open until the test pauses
                return;
            }
            resumeAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
            long offset = Long.parseLong(range.substring("bytes=".length(), range.length() - 1));
            int remaining = PAYLOAD.length - (int) offset;
            exchange.getResponseHeaders().set("Content-Range",
                    "bytes " + offset + "-" + (PAYLOAD.length - 1) + "/" + PAYLOAD.length);
            exchange.sendResponseHeaders(206, remaining);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(PAYLOAD, (int) offset, remaining);
            }
        });
        server.start();
        File dest = tempDestination();
        Finishes finishes = new Finishes();
        try {
            SelfFetchDownloads downloads = new SelfFetchDownloads(new FakeHost(""), finishes);
            downloads.start(2L, url(server), dest.getPath());
            check(finishes.firstProgress.await(10, TimeUnit.SECONDS), "no first chunk");

            downloads.pause(2L);
            downloads.resume(2L);

            check(finishes.finished.await(10, TimeUnit.SECONDS), "resumed download did not finish");
            check(finishes.ok, "resumed download failed");
            assertEquals(1, resumeAgents.size());
            assertEquals("fake-default-agent", resumeAgents.get(0));
        } finally {
            for (HttpExchange exchange : parked) {
                exchange.close();
            }
            server.stop(0);
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
        }
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/file";
    }

    private static File tempDestination() throws Exception {
        File dest = File.createTempFile("mwv-self-fetch-", ".bin");
        //noinspection ResultOfMethodCallIgnored
        dest.delete();
        return dest;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }
}
