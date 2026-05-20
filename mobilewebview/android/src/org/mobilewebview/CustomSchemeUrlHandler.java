package org.mobilewebview;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.webkit.WebView;

import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Handles custom URL schemes (intent, tel, mailto, geo, market, etc.).
 */
final class CustomSchemeUrlHandler {
    private static final String TAG = "MobileWebView";

    private CustomSchemeUrlHandler() { }

    /**
     * @return true if navigation was handled or cancelled; false to let WebView load
     */
    static boolean handle(WebView view, Uri uri) {
        if (uri == null) {
            return true;
        }
        String rawScheme = uri.getScheme();
        if (rawScheme == null) {
            return true;
        }
        if (WebViewUrlPolicy.isSchemeLeftToWebView(rawScheme)) {
            return false;
        }
        String schemeLower = rawScheme.toLowerCase(Locale.ROOT);
        Context ctx = view.getContext();

        if ("intent".equals(schemeLower)) {
            final Intent intent;
            try {
                intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
            } catch (URISyntaxException e) {
                Log.w(TAG, "Invalid intent URL", e);
                return true;
            }
            UrlLoadingHelper.applyWebViewSecurityPolicy(intent);
            if (intent.resolveActivity(ctx.getPackageManager()) != null) {
                try {
                    ctx.startActivity(intent);
                    return true;
                } catch (ActivityNotFoundException e) {
                    // try browser_fallback_url
                } catch (SecurityException e) {
                    Log.w(TAG, "Refused to start activity from intent URL", e);
                }
            }
            String fallback = intent.getStringExtra("browser_fallback_url");
            if (fallback != null && WebViewUrlPolicy.isHttpOrHttpsUrlForFallback(fallback)) {
                view.loadUrl(fallback);
            }
            return true;
        }

        Intent appIntent = new Intent(Intent.ACTION_VIEW, uri);
        UrlLoadingHelper.applyWebViewSecurityPolicy(appIntent);
        if (appIntent.resolveActivity(ctx.getPackageManager()) == null) {
            return true;
        }
        try {
            ctx.startActivity(appIntent);
        } catch (ActivityNotFoundException ignored) {
        } catch (SecurityException e) {
            Log.w(TAG, "Refused to start view intent", e);
        }
        return true;
    }
}
