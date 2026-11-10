package org.mobilewebview;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.graphics.pdf.content.PdfPageTextContent;
import android.graphics.pdf.models.selection.PageSelection;
import android.graphics.pdf.models.selection.SelectionBoundary;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.Os;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One PDF open for a preview: PdfRenderer on the document's own descriptor,
 * or on a private copy when the provider hands out a stream that cannot seek.
 * PdfRenderer opens one page at a time, so rendering is serialized.
 */
final class PdfDocumentSession implements LocalPreview.PdfDocument {
    private static final PreviewCopies COPIES = new PreviewCopies();
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
     * content is not a PDF or cannot be read here: the WebView then loads it as
     * it is. Throws when it is a PDF PdfRenderer cannot open.
     */
    static PdfDocumentSession openIfPdf(Context context, String url) throws IOException {
        ParcelFileDescriptor fd;
        byte[] head = new byte[PdfViewer.SIGNATURE_WINDOW];
        boolean seekable;
        int n;
        try {
            fd = openDescriptor(context, url);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        try {
            seekable = fd.getStatSize() >= 0;
            n = seekable
                    ? Os.pread(fd.getFileDescriptor(), head, 0, head.length, 0)
                    : readHead(fd, head);
            if (!PdfViewer.isPdf(head, n)) {
                fd.close();
                return null;
            }
        } catch (Exception e) {
            closeQuietly(fd);
            return null;
        }
        File copy = null;
        try {
            if (!seekable) {
                // A pipe: PdfRenderer needs to seek, so render a private copy.
                copy = copyToCache(context, head, n, fd);
                fd.close();
                fd = ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY);
            }
            PdfRenderer renderer = new PdfRenderer(fd);
            fd = null; // the renderer owns it now and closes it
            try {
                return new PdfDocumentSession(PreviewDocuments.displayName(context, url), renderer, copy);
            } catch (RuntimeException e) {
                renderer.close();
                throw e;
            }
        } catch (IOException | RuntimeException e) {
            closeQuietly(fd);
            deleteCopy(copy);
            throw e;
        }
    }

    /** Names this document's page requests; see PdfViewer.pageBase. */
    @Override
    public String token() {
        return mToken;
    }

    @Override
    public String viewerHtml(String script) {
        return PdfViewer.html(mTitle, mWidths, mHeights, mToken, readsText(), script);
    }

    /** PdfRenderer reads page text from Android 15. */
    static boolean readsText() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM;
    }

    /** The page as PNG bytes at the given width, or null when it does not exist or cannot be drawn. */
    @Override
    public synchronized byte[] renderPng(int index, int width) {
        if (mClosed || index < 0 || index >= mWidths.length) {
            return null;
        }
        int[] size = PdfViewer.renderSize(mWidths[index], mHeights[index], width);
        Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            // Up to 2048 x 8192 pixels: without the memory the page stays blank.
            return null;
        }
        try (PdfRenderer.Page page = mRenderer.openPage(index)) {
            // Pages are transparent where they draw nothing.
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, png);
            return png.toByteArray();
        } catch (RuntimeException e) {
            // A page pdfium cannot draw stays blank.
            return null;
        } finally {
            bitmap.recycle();
        }
    }

    /** The page's text layer as JSON (see PdfTextLayer.json), or null when there is none. */
    @Override
    public synchronized byte[] textJson(int index) {
        if (mClosed || index < 0 || index >= mWidths.length || !readsText()) {
            return null;
        }
        try (PdfRenderer.Page page = mRenderer.openPage(index)) {
            StringBuilder text = new StringBuilder();
            for (PdfPageTextContent content : page.getTextContents()) {
                text.append(content.getText());
            }
            List<PdfTextLayer.Run> runs = PdfTextLayer.runs(text.toString(),
                    (start, end) -> bounds(page, start, end));
            return PdfTextLayer.json(runs).getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            // A page pdfium cannot read stays an image.
            return null;
        }
    }

    // Selecting by index answers with the text's rectangles, one per row.
    @TargetApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private static List<float[]> bounds(PdfRenderer.Page page, int start, int end) {
        List<float[]> rects = new ArrayList<>();
        PageSelection selection = page.selectContent(
                new SelectionBoundary(start), new SelectionBoundary(end));
        if (selection == null) {
            return rects;
        }
        for (PdfPageTextContent content : selection.getSelectedTextContents()) {
            for (RectF r : content.getBounds()) {
                rects.add(new float[] {r.left, r.top, r.right, r.bottom});
            }
        }
        return rects;
    }

    @Override
    public synchronized void close() {
        if (mClosed) {
            return;
        }
        mClosed = true;
        mRenderer.close();
        deleteCopy(mCopy);
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
        File copy = COPIES.create(context.getCacheDir());
        try (InputStream in = new FileInputStream(fd.getFileDescriptor());
             OutputStream out = new FileOutputStream(copy)) {
            PreviewCopies.copy(head, headLength, in, out, PreviewCopies.LIMIT_BYTES);
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
