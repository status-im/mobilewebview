package android.provider;

import android.net.Uri;

/** Minimal JVM-test stub; not used on device. */
public final class MediaStore {
    public static final String VOLUME_EXTERNAL_PRIMARY = "external_primary";

    public static final class Downloads {
        public static final String DISPLAY_NAME = "_display_name";
        public static final String RELATIVE_PATH = "relative_path";
        public static final String IS_PENDING = "is_pending";
        public static final String MIME_TYPE = "mime_type";

        public static Uri getContentUri(String volumeName) {
            return Uri.parse("content://media/" + volumeName + "/downloads");
        }
    }

    private MediaStore() {
    }
}
