package org.mobilewebview;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.CookieManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
        cleanupPartial(session.destination, null);
    }

    void cancelAll() {
        for (Long id : mSessions.keySet()) {
            cancel(id);
        }
    }

    private void startFetch(long downloadId, Session session, Context context) {
        session.state = State.RUNNING;
        Future<?> future = mExecutor.submit(() -> {
            try {
                fetch(downloadId, session, context);
            } finally {
                if (session.future != null) {
                    session.future = null;
                }
            }
        });
        session.future = future;
    }

    private void fetch(long downloadId, Session session, Context context) {
        HttpURLConnection conn = null;
        OutputStream out = null;
        try {
            URL current = new URL(session.url);
            int redirects = 0;
            while (true) {
                if (session.state != State.RUNNING || Thread.interrupted()) {
                    if (session.shouldCleanupPartial()) {
                        cleanupPartial(session.destination, context);
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
                    cleanupPartial(session.destination, context);
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

            out = openDestination(session.destination, context, session.offset);
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[BUFFER_SIZE];
                long received = session.offset;
                long lastNotify = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (session.state != State.RUNNING || Thread.interrupted()) {
                        if (session.shouldCleanupPartial()) {
                            cleanupPartial(session.destination, context);
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
                mCallbacks.onProgress(downloadId, received, total >= 0 ? total : received);
            }

            if (!session.offTheRecord) {
                registerInSystemDownloads(session.destination, context, conn.getContentType());
            }
            mSessions.remove(downloadId);
            mCallbacks.onFinished(downloadId, true, null);
        } catch (Exception e) {
            if (session.state == State.PAUSING) {
                return;
            }
            if (session.state == State.CANCELLING || Thread.interrupted()) {
                cleanupPartial(session.destination, context);
                return;
            }
            Log.e(TAG, "Download failed: " + session.url, e);
            cleanupPartial(session.destination, context);
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

    private static OutputStream openDestination(String destination, Context context, long offset)
            throws Exception {
        if (destination != null && destination.startsWith("content:")) {
            Uri uri = Uri.parse(destination);
            String mode = RangeFetchPolicy.shouldAppend(offset) ? "wa" : "w";
            OutputStream stream = context.getContentResolver().openOutputStream(uri, mode);
            if (stream == null) {
                throw new IllegalStateException("Cannot open content URI");
            }
            return stream;
        }
        File file = new File(destination);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create destination directory");
        }
        return new FileOutputStream(file, RangeFetchPolicy.shouldAppend(offset));
    }

    private static void cleanupPartial(String destination, Context context) {
        try {
            if (destination != null && destination.startsWith("content:")) {
                if (context != null) {
                    context.getContentResolver().delete(Uri.parse(destination), null, null);
                }
            } else if (destination != null) {
                //noinspection ResultOfMethodCallIgnored
                new File(destination).delete();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to cleanup partial download", e);
        }
    }

    private static void registerInSystemDownloads(String destination, Context context, String mime) {
        if (context == null || destination == null || destination.startsWith("content:")) {
            return;
        }
        try {
            File file = new File(destination);
            if (!file.exists()) {
                return;
            }
            // App-private paths aren't MediaStore-indexable. Q+: copy into
            // MediaStore.Downloads for the system UI (host still opens its file).
            // Pre-Q: scan only — public write needs WRITE_EXTERNAL_STORAGE.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishToMediaStoreDownloads(file, context, mime);
                return;
            }
            MediaScannerConnection.scanFile(
                    context,
                    new String[] { file.getAbsolutePath() },
                    mime != null ? new String[] { mime } : null,
                    null);
        } catch (Exception e) {
            Log.w(TAG, "Failed to register download in system UI", e);
        }
    }

    private static void publishToMediaStoreDownloads(File file, Context context, String mime)
            throws Exception {
        final ContentResolver resolver = context.getContentResolver();

        final ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, file.getName());
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        if (mime != null && !mime.isEmpty()) {
            values.put(MediaStore.Downloads.MIME_TYPE, mime);
        }

        // MediaStore uniquifies DISPLAY_NAME itself ("name (1).ext") on collision.
        final Uri collection =
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        final Uri item = resolver.insert(collection, values);
        if (item == null) {
            Log.w(TAG, "MediaStore insert failed for " + file.getName());
            return;
        }

        try (OutputStream out = resolver.openOutputStream(item);
             InputStream in = new FileInputStream(file)) {
            if (out == null) {
                throw new IllegalStateException("openOutputStream returned null");
            }
            final byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        } catch (Exception e) {
            resolver.delete(item, null, null);
            throw e;
        }

        final ContentValues publish = new ContentValues();
        publish.put(MediaStore.Downloads.IS_PENDING, 0);
        resolver.update(item, publish, null, null);
    }
}
