package org.mobilewebview;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Destination open/cleanup for self-fetch downloads (file path or content: URI).
 */
final class DownloadIo {
    private static final String TAG = "MobileWebView";

    private DownloadIo() {}

    static OutputStream openDestination(String destination, Context context, long offset)
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

    static void cleanupPartial(String destination, Context context) {
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
}
