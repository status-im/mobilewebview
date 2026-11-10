package org.mobilewebview;

import android.content.Context;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** What a LocalPreview reads on Android: PDFs through PdfRenderer, everything through its provider or file. */
final class PreviewDocuments implements LocalPreview.Documents {
    private final Context mContext;

    PreviewDocuments(Context context) {
        mContext = context;
    }

    @Override
    public LocalPreview.PdfDocument openIfPdf(String url) throws IOException {
        return PdfDocumentSession.openIfPdf(mContext, url);
    }

    @Override
    public String displayName(String url) {
        return displayName(mContext, url);
    }

    /** The provider's name for the file, else its URL's last segment. */
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
        return last != null ? last : "file";
    }

    @Override
    public String mimeType(String url) {
        String path = LocalReadGrant.filePath(url);
        if (path != null) {
            String name = new File(path).getName();
            int dot = name.lastIndexOf('.');
            String type = dot < 0 ? null : MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
            return type == null ? "" : type;
        }
        try {
            String type = mContext.getContentResolver().getType(Uri.parse(url));
            return type == null ? "" : type;
        } catch (RuntimeException e) {
            return "";
        }
    }

    @Override
    public InputStream open(String url) throws IOException {
        String path = LocalReadGrant.filePath(url);
        InputStream in = path != null ? new FileInputStream(path)
                : mContext.getContentResolver().openInputStream(Uri.parse(url));
        if (in == null) {
            throw new IOException("no stream for the preview");
        }
        return in;
    }

}
