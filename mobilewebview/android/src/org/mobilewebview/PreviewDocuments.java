package org.mobilewebview;

import android.content.Context;
import android.content.pm.ProviderInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
    public List<String> providerAuthorities() {
        List<String> authorities = new ArrayList<>();
        for (ProviderInfo provider : mContext.getPackageManager().queryContentProviders(null, 0, 0)) {
            authorities.add(provider.authority);
        }
        return authorities;
    }
}
