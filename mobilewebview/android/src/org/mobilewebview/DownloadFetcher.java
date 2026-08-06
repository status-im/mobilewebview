package org.mobilewebview;

import android.content.Context;
import android.util.Log;
import android.webkit.CookieManager;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Self-fetch download worker (ADR 0005). Reuses WebView cookies + User-Agent.
 * Registers completed files with the system Downloads UI in Standard mode only.
 * Pause keeps the partial file; resume uses HTTP Range + append.
 */
final class DownloadFetcher {
    private static final String TAG = "MobileWebView";
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long PROGRESS_THROTTLE_MS = 100;

    interface Callbacks {
        void onProgress(long downloadId, long receivedBytes, long totalBytes);
        void onFinished(long downloadId, boolean ok, String error);
    }

    private enum State {
        RUNNING,
        PAUSING,
        CANCELLING
    }

    private static final class Session {
        final String url;
        final String destination;
        volatile String userAgent;
        volatile boolean offTheRecord;
        volatile State state = State.RUNNING;
        volatile long offset = 0L;
        volatile Future<?> future;
        // Bumped by every startFetch; a worker whose generation is stale has been
        // superseded (pause→resume race) and must neither clean up nor report.
        volatile long generation = 0L;

        Session(String url, String destination, String userAgent, boolean offTheRecord) {
            this.url = url;
            this.destination = destination;
            this.userAgent = userAgent;
            this.offTheRecord = offTheRecord;
        }

        boolean shouldCleanupPartial() {
            return state == State.CANCELLING;
        }
    }

    private final ExecutorService mExecutor = Executors.newCachedThreadPool();
    private final Map<Long, Session> mSessions = new ConcurrentHashMap<>();
    private final Callbacks mCallbacks;

    DownloadFetcher(Callbacks callbacks) {
        mCallbacks = callbacks;
    }

    void start(long downloadId,
               String url,
               String destination,
               String userAgent,
               boolean offTheRecord,
               Context context) {
        cancel(downloadId);
        Session session = new Session(url, destination, userAgent, offTheRecord);
        mSessions.put(downloadId, session);
        startFetch(downloadId, session, context);
    }

    void pause(long downloadId) {
        Session session = mSessions.get(downloadId);
        if (session == null) {
            return;
        }
        session.state = State.PAUSING;
        Future<?> future = session.future;
        session.future = null;
        if (future != null) {
            future.cancel(true);
        }
        // Keep mSessions + partial file for resume. Do not call onFinished.
    }

    void resume(long downloadId, String userAgent, boolean offTheRecord, Context context) {
        Session session = mSessions.get(downloadId);
        if (session == null) {
            mCallbacks.onFinished(downloadId, false, "Resume data unavailable");
            return;
        }
        if (userAgent != null && !userAgent.isEmpty()) {
            session.userAgent = userAgent;
        }
        session.offTheRecord = offTheRecord;

        long offset = 0L;
        if (session.destination != null && !session.destination.startsWith("content:")) {
            offset = RangeFetchPolicy.existingFileLength(new File(session.destination));
        }
        session.offset = offset;
        startFetch(downloadId, session, context);
    }

    void cancel(long downloadId) {
        Session session = mSessions.get(downloadId);
        if (session == null) {
            return;
        }
        session.state = State.CANCELLING;
        Future<?> future = session.future;
        session.future = null;
        if (future != null) {
            future.cancel(true);
        }
        mSessions.remove(downloadId);
        DownloadIo.cleanupPartial(session.destination, null);
    }

    void cancelAll() {
        for (Long id : mSessions.keySet()) {
            cancel(id);
        }
    }

    private void startFetch(long downloadId, Session session, Context context) {
        session.state = State.RUNNING;
        final long generation = ++session.generation;
        Future<?> future = mExecutor.submit(() -> {
            try {
                fetch(downloadId, session, generation, context);
            } finally {
                if (session.future != null) {
                    session.future = null;
                }
            }
        });
        session.future = future;
    }

    private void fetch(long downloadId, Session session, long generation, Context context) {
        HttpURLConnection conn = null;
        OutputStream out = null;
        try {
            URL current = new URL(session.url);
            int redirects = 0;
            while (true) {
                if (session.generation != generation) {
                    return; // superseded — the newer worker owns the session and the file
                }
                if (session.state != State.RUNNING || Thread.interrupted()) {
                    if (session.shouldCleanupPartial()) {
                        DownloadIo.cleanupPartial(session.destination, context);
                    }
                    return;
                }

                conn = (HttpURLConnection) current.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(60_000);
                if (session.userAgent != null && !session.userAgent.isEmpty()) {
                    conn.setRequestProperty("User-Agent", session.userAgent);
                }
                String cookies = CookieManager.getInstance().getCookie(current.toString());
                if (cookies != null && !cookies.isEmpty()) {
                    conn.setRequestProperty("Cookie", cookies);
                }
                String range = RangeFetchPolicy.rangeHeader(session.offset);
                if (range != null) {
                    conn.setRequestProperty("Range", range);
                }

                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (location == null || location.isEmpty() || ++redirects > 10) {
                        mSessions.remove(downloadId);
                        mCallbacks.onFinished(downloadId, false, "Too many redirects");
                        return;
                    }
                    current = new URL(current, location);
                    continue;
                }
                if (session.offset > 0 && code == 200) {
                    // Server ignored Range — discard partial and retry without Range.
                    conn.disconnect();
                    session.offset = 0;
                    DownloadIo.cleanupPartial(session.destination, context);
                    continue;
                }
                if (code < 200 || code >= 300) {
                    mSessions.remove(downloadId);
                    mCallbacks.onFinished(downloadId, false, "HTTP " + code);
                    return;
                }
                break;
            }

            long total = conn.getContentLengthLong();
            if (RangeFetchPolicy.isPartialContent(conn.getResponseCode())) {
                total = RangeFetchPolicy.totalFromContentRange(
                    conn.getHeaderField("Content-Range"),
                    total >= 0 ? total + session.offset : -1);
            }

            out = DownloadIo.openDestination(session.destination, context, session.offset);
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[BUFFER_SIZE];
                long received = session.offset;
                long lastNotify = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (session.generation != generation) {
                        return; // superseded — hands off the session and the file
                    }
                    if (session.state != State.RUNNING || Thread.interrupted()) {
                        if (session.shouldCleanupPartial()) {
                            DownloadIo.cleanupPartial(session.destination, context);
                        }
                        return;
                    }
                    out.write(buf, 0, n);
                    received += n;
                    session.offset = received;
                    long now = System.currentTimeMillis();
                    if (now - lastNotify >= PROGRESS_THROTTLE_MS) {
                        mCallbacks.onProgress(downloadId, received, total);
                        lastNotify = now;
                    }
                }
                if (session.generation != generation) {
                    return; // superseded on clean EOF — no stale progress or completion
                }
                mCallbacks.onProgress(downloadId, received, total >= 0 ? total : received);
            }

            if (session.generation != generation) {
                return; // superseded mid-write — the newer worker reports for this id
            }
            if (!session.offTheRecord) {
                DownloadMediaStore.registerCompleted(
                        session.destination, context, conn.getContentType());
            }
            mSessions.remove(downloadId);
            mCallbacks.onFinished(downloadId, true, null);
        } catch (Exception e) {
            if (session.generation != generation) {
                // Pause→resume race: our interrupt exception must not delete the
                // partial file or report failure for the download the new worker owns.
                return;
            }
            if (session.state == State.PAUSING) {
                return;
            }
            if (session.state == State.CANCELLING || Thread.interrupted()) {
                DownloadIo.cleanupPartial(session.destination, context);
                return;
            }
            Log.e(TAG, "Download failed: " + session.url, e);
            DownloadIo.cleanupPartial(session.destination, context);
            mSessions.remove(downloadId);
            mCallbacks.onFinished(downloadId, false,
                    e.getMessage() != null ? e.getMessage() : "Download failed");
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
