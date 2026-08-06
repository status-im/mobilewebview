package android.content;

import android.net.Uri;

import java.io.OutputStream;

/** Minimal JVM-test stub; not used on device. */
public class ContentResolver {
    public OutputStream openOutputStream(Uri uri) {
        return null;
    }

    public OutputStream openOutputStream(Uri uri, String mode) {
        return null;
    }

    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    public int delete(Uri uri, String where, String[] selectionArgs) {
        return 0;
    }

    public int update(Uri uri, ContentValues values, String where, String[] selectionArgs) {
        return 0;
    }
}
