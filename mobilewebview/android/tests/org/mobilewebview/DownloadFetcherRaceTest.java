package org.mobilewebview;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Pause→resume race: worker A parks in a blocking read, the user pauses and
 * quickly resumes (worker B), then A's connection dies. A stale A must neither
 * delete the file B wrote, nor remove the session, nor report a second finish.
 */
public final class DownloadFetcherRaceTest {
    private static final byte[] PAYLOAD = "0123456789".getBytes();
    private static final int FIRST_CHUNK = 4;

    private static final class RecordedFinish {
        final boolean ok;
        final String error;

        RecordedFinish(boolean ok, String error) {
            this.ok = ok;
            this.error = error;
        }
    }

    public static void main(String[] args) throws Exception {
        pausedThenResumedDownloadSurvivesStaleWorkerDeath();
        System.out.println("DownloadFetcherRaceTest passed");
    }

    private static void pausedThenResumedDownloadSurvivesStaleWorkerDeath() throws Exception {
        File dest = File.createTempFile("mwv-race-", ".bin");
        //noinspection ResultOfMethodCallIgnored
        dest.delete();

        ConcurrentLinkedQueue<HttpExchange> parked = new ConcurrentLinkedQueue<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/file", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range == null) {
                // Worker A: first chunk, then hold the connection open.
                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                exchange.sendResponseHeaders(200, PAYLOAD.length);
                OutputStream out = exchange.getResponseBody();
                out.write(PAYLOAD, 0, FIRST_CHUNK);
                out.flush();
                parked.add(exchange); // intentionally not closed
                return;
            }
            // Worker B: honour "bytes=N-" with a 206 and the remainder.
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

        CountDownLatch firstProgress = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        List<RecordedFinish> finishes = new CopyOnWriteArrayList<>();

        DownloadFetcher fetcher = new DownloadFetcher(new DownloadFetcher.Callbacks() {
            @Override
            public void onProgress(long downloadId, long receivedBytes, long totalBytes) {
                if (receivedBytes >= FIRST_CHUNK) {
                    firstProgress.countDown();
                }
            }

            @Override
            public void onFinished(long downloadId, boolean ok, String error) {
                finishes.add(new RecordedFinish(ok, error));
                finished.countDown();
            }
        });

        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/file";
            fetcher.start(1L, url, dest.getAbsolutePath(), "UA-test", true, null);

            TestAssert.assertTrue("worker A never reported progress",
                    firstProgress.await(10, TimeUnit.SECONDS));

            fetcher.pause(1L);
            // Give the partial write a moment to land before resume computes its offset.
            waitForFileLength(dest, FIRST_CHUNK);

            fetcher.resume(1L, "UA-test", true, null);
            TestAssert.assertTrue("worker B never finished",
                    finished.await(10, TimeUnit.SECONDS));

            // Kill worker A's parked connection after B is done: its interrupt/EOF
            // must not be treated as this download's outcome.
            for (HttpExchange exchange : parked) {
                exchange.close();
            }
            Thread.sleep(500);

            TestAssert.assertEquals(1, finishes.size());
            TestAssert.assertTrue("resumed download must finish ok, got: "
                    + finishes.get(0).error, finishes.get(0).ok);
            TestAssert.assertTrue("file must survive the stale worker", dest.exists());
            byte[] written = Files.readAllBytes(dest.toPath());
            TestAssert.assertEquals(PAYLOAD.length, written.length);
            for (int i = 0; i < PAYLOAD.length; ++i) {
                TestAssert.assertEquals(PAYLOAD[i], written[i]);
            }
        } finally {
            server.stop(0);
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
        }
    }

    private static void waitForFileLength(File file, long atLeast) throws InterruptedException {
        for (int i = 0; i < 100; ++i) {
            if (file.length() >= atLeast) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("partial file never reached " + atLeast + " bytes");
    }
}
