package org.mobilewebview;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.util.Log;
import android.webkit.CookieManager;

import java.io.File;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Self-fetch download worker (ADR 0005). Reuses WebView cookies + User-Agent.
 * Registers completed files with the system Downloads UI in Standard mode only.
 */
final class DownloadFetcher {
    private static final String TAG = "MobileWebView";
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long PROGRESS_THROTTLE_MS = 100;

    interface Callbacks {
        void onProgress(long downloadId, long receivedBytes, long totalBytes);
        void onFinished(long downloadId, boolean ok, String error);
    }

    private final ExecutorService mExecutor = Executors.newCachedThreadPool();
    private final Map<Long, Future<?>> mActive = new ConcurrentHashMap<>();
    private final Map<Long, AtomicBoolean> mCancelled = new ConcurrentHashMap<>();
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
        AtomicBoolean cancelled = new AtomicBoolean(false);
        mCancelled.put(downloadId, cancelled);

        Future<?> future = mExecutor.submit(() -> {
            try {
                fetch(downloadId, url, destination, userAgent, offTheRecord, context, cancelled);
            } finally {
                mActive.remove(downloadId);
                mCancelled.remove(downloadId);
            }
        });
        mActive.put(downloadId, future);
    }

    void cancel(long downloadId) {
        AtomicBoolean flag = mCancelled.get(downloadId);
        if (flag != null) {
            flag.set(true);
        }
        Future<?> future = mActive.remove(downloadId);
        if (future != null) {
            future.cancel(true);
        }
    }

    void cancelAll() {
        for (Long id : mActive.keySet()) {
            cancel(id);
        }
    }

    private void fetch(long downloadId,
                       String url,
                       String destination,
                       String userAgent,
                       boolean offTheRecord,
                       Context context,
                       AtomicBoolean cancelled) {
        HttpURLConnection conn = null;
        OutputStream out = null;
        try {
            if (!DownloadUrlPolicy.isSupportedDownloadUrl(url)) {
                mCallbacks.onFinished(downloadId, false, "Unsupported download URL scheme");
                return;
            }

            URL current = new URL(url);
            int redirects = 0;
            while (true) {
                if (cancelled.get() || Thread.interrupted()) {
                    cleanupPartial(destination, context);
                    return;
                }

                conn = (HttpURLConnection) current.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(60_000);
                if (userAgent != null && !userAgent.isEmpty()) {
                    conn.setRequestProperty("User-Agent", userAgent);
                }
                String cookies = CookieManager.getInstance().getCookie(current.toString());
                if (cookies != null && !cookies.isEmpty()) {
                    conn.setRequestProperty("Cookie", cookies);
                }

                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (location == null || location.isEmpty() || ++redirects > 10) {
                        mCallbacks.onFinished(downloadId, false, "Too many redirects");
                        return;
                    }
                    current = new URL(current, location);
                    continue;
                }
                if (code < 200 || code >= 300) {
                    mCallbacks.onFinished(downloadId, false, "HTTP " + code);
                    return;
                }
                break;
            }

            long total = conn.getContentLengthLong();
            out = openDestination(destination, context);
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[BUFFER_SIZE];
                long received = 0;
                long lastNotify = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancelled.get() || Thread.interrupted()) {
                        cleanupPartial(destination, context);
                        return;
                    }
                    out.write(buf, 0, n);
                    received += n;
                    long now = System.currentTimeMillis();
                    if (now - lastNotify >= PROGRESS_THROTTLE_MS) {
                        mCallbacks.onProgress(downloadId, received, total);
                        lastNotify = now;
                    }
                }
                mCallbacks.onProgress(downloadId, received, total >= 0 ? total : received);
            }

            if (!offTheRecord) {
                registerInSystemDownloads(destination, context, conn.getContentType());
            }
            mCallbacks.onFinished(downloadId, true, null);
        } catch (Exception e) {
            if (cancelled.get() || Thread.interrupted()) {
                cleanupPartial(destination, context);
                return;
            }
            Log.e(TAG, "Download failed: " + url, e);
            cleanupPartial(destination, context);
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

    private static OutputStream openDestination(String destination, Context context)
            throws Exception {
        if (destination != null && destination.startsWith("content:")) {
            Uri uri = Uri.parse(destination);
            OutputStream stream = context.getContentResolver().openOutputStream(uri, "w");
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
        return new FileOutputStream(file);
    }

    private static void cleanupPartial(String destination, Context context) {
        try {
            if (destination != null && destination.startsWith("content:")) {
                context.getContentResolver().delete(Uri.parse(destination), null, null);
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
            // Scan the host-written file into the media store / Downloads UI.
            MediaScannerConnection.scanFile(
                    context,
                    new String[] { file.getAbsolutePath() },
                    mime != null ? new String[] { mime } : null,
                    null);
        } catch (Exception e) {
            Log.w(TAG, "Failed to register download in system UI", e);
        }
    }
}
