package org.mobilewebview;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** What a LocalPreview reads on Android: PDFs through PdfRenderer, providers through PackageManager. */
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
        return PdfDocumentSession.displayName(mContext, url);
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
    public List<String> providerAuthorities() {
        List<String> authorities = new ArrayList<>();
        for (ProviderInfo provider : mContext.getPackageManager().queryContentProviders(null, 0, 0)) {
            authorities.add(provider.authority);
        }
        return authorities;
    }
}
