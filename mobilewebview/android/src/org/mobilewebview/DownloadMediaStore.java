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

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Publish completed downloads to the system Downloads UI.
 * App-private paths aren't MediaStore-indexable; Q+ copies into MediaStore.Downloads.
 */
final class DownloadMediaStore {
    private static final String TAG = "MobileWebView";
    private static final int BUFFER_SIZE = 64 * 1024;

    private DownloadMediaStore() {}

    static void registerCompleted(String destination, Context context, String mime) {
        if (context == null || destination == null || destination.startsWith("content:")) {
            return;
        }
        try {
            File file = new File(destination);
            if (!file.exists()) {
                return;
            }
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

    /**
     * The MIME to publish with, or null to let MediaStore derive it from the name.
     * A name with an extension decides the type: AOSP silently appends the MIME's
     * own extension when the two disagree ("photo.jpg.png"), and vendor providers
     * can reject the insert instead, leaving the file unpublished.
     */
    static String mimeForPublish(String displayName, String mime) {
        if (mime == null || mime.isEmpty()) {
            return null;
        }
        final String name = displayName == null ? "" : displayName;
        final int dot = name.lastIndexOf('.');
        final boolean hasExtension = dot > 0 && dot < name.length() - 1;
        return hasExtension ? null : mime;
    }

    private static void publishToMediaStoreDownloads(File file, Context context, String mime)
            throws Exception {
        final ContentResolver resolver = context.getContentResolver();

        final ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, file.getName());
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        final String publishMime = mimeForPublish(file.getName(), mime);
        if (publishMime != null && !publishMime.isEmpty()) {
            values.put(MediaStore.Downloads.MIME_TYPE, publishMime);
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
