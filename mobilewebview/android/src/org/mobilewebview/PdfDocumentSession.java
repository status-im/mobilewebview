package org.mobilewebview;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.system.Os;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * One PDF open for a preview: PdfRenderer on the document's own descriptor,
 * or on a private copy when the provider hands out a stream that cannot seek.
 * PdfRenderer opens one page at a time, so rendering is serialized.
 */
final class PdfDocumentSession {
    private final String mToken = PdfViewer.newToken();
    private final String mTitle;
    private final PdfRenderer mRenderer;
    private final float[] mWidths;
    private final float[] mHeights;
    private final File mCopy;
    private boolean mClosed = false;

    private PdfDocumentSession(String title, PdfRenderer renderer, File copy) {
        mTitle = title;
        mRenderer = renderer;
        mCopy = copy;
        int count = renderer.getPageCount();
        mWidths = new float[count];
        mHeights = new float[count];
        for (int i = 0; i < count; i++) {
            try (PdfRenderer.Page page = renderer.openPage(i)) {
                mWidths[i] = page.getWidth();
                mHeights[i] = page.getHeight();
            }
        }
    }

    /**
     * The PDF behind {@code url} (as the WebView requests it), or null when the
     * content is not a PDF. Throws when it is one PdfRenderer cannot open.
     */
    static PdfDocumentSession openIfPdf(Context context, String url) throws IOException {
        ParcelFileDescriptor fd = openDescriptor(context, url);
        File copy = null;
        try {
            byte[] head = new byte[PdfViewer.SIGNATURE_WINDOW];
            boolean seekable = fd.getStatSize() >= 0;
            int n = seekable
                    ? Os.pread(fd.getFileDescriptor(), head, 0, head.length, 0)
                    : readHead(fd, head);
            if (!PdfViewer.isPdf(head, n)) {
                fd.close();
                return null;
            }
            if (!seekable) {
                // A pipe: PdfRenderer needs to seek, so render a private copy.
                copy = copyToCache(context, head, n, fd);
                fd.close();
                fd = ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY);
            }
            PdfRenderer renderer = new PdfRenderer(fd);
            fd = null; // the renderer owns it now and closes it
            try {
                return new PdfDocumentSession(displayName(context, url), renderer, copy);
            } catch (RuntimeException e) {
                renderer.close();
                throw e;
            }
        } catch (IOException | RuntimeException e) {
            closeQuietly(fd);
            deleteCopy(copy);
            throw e;
        } catch (Exception e) {
            closeQuietly(fd);
            deleteCopy(copy);
            throw new IOException(e);
        }
    }

    /** Names this document's page requests; see PdfViewer.pageBase. */
    String token() {
        return mToken;
    }

    String viewerHtml() {
        return PdfViewer.html(mTitle, mWidths, mHeights, mToken);
    }

    /** The page as PNG bytes at the given width, or null when it does not exist. */
    synchronized byte[] renderPng(int index, int width) {
        if (mClosed || index < 0 || index >= mWidths.length) {
            return null;
        }
        int[] size = PdfViewer.renderSize(mWidths[index], mHeights[index], width);
        Bitmap bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
        try (PdfRenderer.Page page = mRenderer.openPage(index)) {
            // Pages are transparent where they draw nothing.
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, png);
            return png.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    synchronized void close() {
        if (mClosed) {
            return;
        }
        mClosed = true;
        mRenderer.close();
        deleteCopy(mCopy);
    }

    static String displayName(Context context, String url) {
        String path = LocalReadGrant.filePath(url);
        if (path != null) {
            return new File(path).getName();
        }
        Uri uri = Uri.parse(url);
        try (Cursor c = context.getContentResolver().query(
                uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getString(0);
            }
        } catch (RuntimeException ignored) {
            // Fall back to the last path segment.
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "PDF";
    }

    private static ParcelFileDescriptor openDescriptor(Context context, String url)
            throws IOException {
        String path = LocalReadGrant.filePath(url);
        ParcelFileDescriptor fd = path != null
                ? ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                : context.getContentResolver().openFileDescriptor(Uri.parse(url), "r");
        if (fd == null) {
            throw new IOException("no descriptor for the preview");
        }
        return fd;
    }

    // Reads up to head.length bytes from the start of a stream.
    private static int readHead(ParcelFileDescriptor fd, byte[] head) throws IOException {
        InputStream in = new FileInputStream(fd.getFileDescriptor());
        int total = 0;
        int n;
        while (total < head.length && (n = in.read(head, total, head.length - total)) > 0) {
            total += n;
        }
        return total;
    }

    // The head already read from the stream, then the rest of it.
    private static File copyToCache(Context context, byte[] head, int headLength,
                                    ParcelFileDescriptor fd) throws IOException {
        File dir = new File(context.getCacheDir(), "mobilewebview-pdf");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        File copy = File.createTempFile("preview", ".pdf", dir);
        try (InputStream in = new FileInputStream(fd.getFileDescriptor());
             OutputStream out = new FileOutputStream(copy)) {
            out.write(head, 0, headLength);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            deleteCopy(copy);
            throw e;
        }
        return copy;
    }

    private static void deleteCopy(File copy) {
        if (copy != null && !copy.delete()) {
            copy.deleteOnExit();
        }
    }

    private static void closeQuietly(ParcelFileDescriptor fd) {
        if (fd == null) {
            return;
        }
        try {
            fd.close();
        } catch (IOException | RuntimeException ignored) {
            // Already closed or owned by a renderer that failed to open.
        }
    }
}
