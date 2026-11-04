package org.mobilewebview.testapp;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.OpenableColumns;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Opened file probes: queues "Open with" intents and measures how their URIs
 * can be read — in place, seekable, through a private copy, by PdfRenderer.
 * Every probe returns a JSON object so the C++ side stays a thin JNI shim.
 */
public final class OpenedFileProbe {
    private static Context sContext;
    private static JSONArray sPending = new JSONArray();
    private static int sSeq = 0;

    private OpenedFileProbe() { }

    static synchronized void setContext(Context context) {
        sContext = context;
    }

    private static synchronized Context context() {
        return sContext;
    }

    static synchronized void enqueue(Intent intent, String referrer, String via) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
            return;
        }
        JSONObject o = new JSONObject();
        put(o, "seq", ++sSeq);
        put(o, "via", via);
        put(o, "action", intent.getAction());
        put(o, "data", intent.getData() != null ? intent.getData().toString() : "");
        put(o, "type", String.valueOf(intent.getType()));
        put(o, "flags", "0x" + Integer.toHexString(intent.getFlags()));
        put(o, "grantRead", (intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        put(o, "grantPersistable",
                (intent.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0);
        put(o, "referrer", referrer);
        JSONArray clip = new JSONArray();
        ClipData clipData = intent.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                Uri uri = clipData.getItemAt(i).getUri();
                clip.put(uri != null ? uri.toString() : "");
            }
        }
        put(o, "clipUris", clip);
        put(o, "receivedAtMs", System.currentTimeMillis());
        sPending.put(o);
    }

    public static synchronized String drain() {
        String out = sPending.toString();
        sPending = new JSONArray();
        return out;
    }

    public static String describe(String uriString) {
        JSONObject o = new JSONObject();
        try {
            Context ctx = context();
            Uri uri = Uri.parse(uriString);
            ContentResolver resolver = ctx.getContentResolver();
            put(o, "scheme", String.valueOf(uri.getScheme()));
            put(o, "authority", String.valueOf(uri.getAuthority()));
            put(o, "resolverType", String.valueOf(resolver.getType(uri)));
            int permission = ctx.checkUriPermission(uri, Process.myPid(), Process.myUid(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            put(o, "uriPermissionGranted", permission == PackageManager.PERMISSION_GRANTED);
            if ("content".equals(uri.getScheme())) {
                try (Cursor c = resolver.query(uri,
                        new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                        null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        int name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        int size = c.getColumnIndex(OpenableColumns.SIZE);
                        put(o, "displayName", name >= 0 ? String.valueOf(c.getString(name)) : "");
                        put(o, "size", size >= 0 && !c.isNull(size) ? c.getLong(size) : -1);
                    }
                }
            } else if ("file".equals(uri.getScheme())) {
                File f = new File(uri.getPath());
                put(o, "displayName", f.getName());
                put(o, "size", f.length());
                put(o, "exists", f.exists());
                put(o, "canRead", f.canRead());
            }
            put(o, "ok", true);
        } catch (Exception e) {
            fail(o, e);
        }
        return o.toString();
    }

    public static String read(String uriString) {
        JSONObject o = new JSONObject();
        long start = System.nanoTime();
        Uri uri = Uri.parse(uriString);
        try (ParcelFileDescriptor pfd = context().getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) {
                put(o, "ok", false);
                put(o, "error", "openFileDescriptor returned null");
            } else {
                FileDescriptor fd = pfd.getFileDescriptor();
                StructStat st = Os.fstat(fd);
                long size = pfd.getStatSize();
                put(o, "statSize", size);
                put(o, "regularFile", OsConstants.S_ISREG(st.st_mode));
                byte[] head = new byte[8];
                int n = Os.read(fd, head, 0, head.length);
                put(o, "head", hex(head, n));
                try {
                    long mid = Math.max(0, size / 2);
                    Os.lseek(fd, mid, OsConstants.SEEK_SET);
                    byte[] probe = new byte[8];
                    put(o, "seekable", Os.read(fd, probe, 0, probe.length) >= 0);
                    put(o, "midOffset", mid);
                } catch (Exception e) {
                    put(o, "seekable", false);
                    put(o, "seekError", e.toString());
                }
                put(o, "ok", n > 0);
            }
        } catch (Exception e) {
            fail(o, e);
            readAsStream(o, uri);
        }
        put(o, "ms", (System.nanoTime() - start) / 1_000_000);
        return o.toString();
    }

    // Some providers serve only a stream (a pipe), never a file descriptor.
    private static void readAsStream(JSONObject o, Uri uri) {
        try (InputStream in = context().getContentResolver().openInputStream(uri)) {
            byte[] head = new byte[8];
            int n = in != null ? in.read(head) : -1;
            put(o, "streamHead", hex(head, n));
            put(o, "streamReadable", n > 0);
        } catch (Exception e) {
            put(o, "streamError", e.toString());
        }
    }

    public static String copyToCache(String uriString) {
        JSONObject o = new JSONObject();
        long start = System.nanoTime();
        try {
            Context ctx = context();
            Uri uri = Uri.parse(uriString);
            File dir = new File(ctx.getCacheDir(), "opened/" + System.currentTimeMillis());
            if (!dir.mkdirs()) {
                throw new IllegalStateException("cannot create " + dir);
            }
            File out = new File(dir, safeName(displayName(ctx, uri)));
            long total = 0;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri);
                 OutputStream os = new FileOutputStream(out)) {
                if (in == null) {
                    throw new IllegalStateException("openInputStream returned null");
                }
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    total += n;
                }
            }
            put(o, "ok", true);
            put(o, "path", out.getAbsolutePath());
            put(o, "url", Uri.fromFile(out).toString());
            put(o, "bytes", total);
        } catch (Exception e) {
            fail(o, e);
        }
        put(o, "ms", (System.nanoTime() - start) / 1_000_000);
        return o.toString();
    }

    public static String pdfPages(String uriString) {
        JSONObject o = new JSONObject();
        long start = System.nanoTime();
        PdfRenderer renderer = null;
        try {
            ParcelFileDescriptor pfd = context().getContentResolver()
                    .openFileDescriptor(Uri.parse(uriString), "r");
            if (pfd == null) {
                throw new IllegalStateException("openFileDescriptor returned null");
            }
            // Takes ownership of pfd and closes it with the renderer.
            renderer = new PdfRenderer(pfd);
            put(o, "pageCount", renderer.getPageCount());
            try (PdfRenderer.Page page = renderer.openPage(0)) {
                put(o, "page0", page.getWidth() + "x" + page.getHeight());
            }
            put(o, "ok", true);
        } catch (Exception e) {
            fail(o, e);
        } finally {
            if (renderer != null) {
                renderer.close();
            }
        }
        put(o, "ms", (System.nanoTime() - start) / 1_000_000);
        return o.toString();
    }

    private static String displayName(Context ctx, Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor c = ctx.getContentResolver().query(uri,
                    new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst() && !c.isNull(0)) {
                    return c.getString(0);
                }
            } catch (Exception ignored) {
                // Fall back to the last path segment.
            }
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "opened-file";
    }

    private static String safeName(String name) {
        String cleaned = name.replace('/', '_').replace('\\', '_');
        return cleaned.isEmpty() ? "opened-file" : cleaned;
    }

    private static String hex(byte[] bytes, int length) {
        if (length <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(String.format("%02x", bytes[i]));
        }
        return sb.toString();
    }

    private static void fail(JSONObject o, Exception e) {
        put(o, "ok", false);
        put(o, "error", e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    private static void put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (JSONException ignored) {
            // Keys are literals and values are JSON-safe.
        }
    }
}
