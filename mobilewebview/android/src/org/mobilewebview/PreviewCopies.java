package org.mobilewebview;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Private copies a PDF preview renders from when its provider hands over a
 * stream. Android kills a process without notice, so the first copy in a
 * process clears what earlier runs left.
 */
final class PreviewCopies {
    static final String DIR = "mobilewebview-pdf";
    /** A larger stream is not copied, and its preview shows as unavailable. */
    static final long LIMIT_BYTES = 200L * 1024 * 1024;

    private boolean mSwept;

    /** A new empty file for a copy in cacheDir. */
    synchronized File create(File cacheDir) throws IOException {
        File dir = new File(cacheDir, DIR);
        if (!mSwept) {
            File[] stale = dir.listFiles();
            if (stale != null) {
                for (File file : stale) {
                    file.delete();
                }
            }
            mSwept = true;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        return File.createTempFile("preview", ".pdf", dir);
    }

    /** The head already read from the stream, then the rest of it, up to limit bytes in all. */
    static void copy(byte[] head, int headLength, InputStream in, OutputStream out, long limit)
            throws IOException {
        long total = headLength;
        if (total > limit) {
            throw new IOException("the PDF is larger than a preview copies");
        }
        out.write(head, 0, headLength);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > limit) {
                throw new IOException("the PDF is larger than a preview copies");
            }
            out.write(buf, 0, n);
        }
    }
}
