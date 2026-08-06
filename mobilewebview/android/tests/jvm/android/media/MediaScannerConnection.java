package android.media;

import android.content.Context;
import android.net.Uri;

/** Minimal JVM-test stub; not used on device. */
public final class MediaScannerConnection {
    public interface OnScanCompletedListener {
        void onScanCompleted(String path, Uri uri);
    }

    private MediaScannerConnection() {
    }

    public static void scanFile(Context context,
                                String[] paths,
                                String[] mimeTypes,
                                OnScanCompletedListener callback) {
    }
}
