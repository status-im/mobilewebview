package org.mobilewebview;

import android.content.Intent;

/**
 * Hardening for {@link android.webkit.WebViewClient#shouldOverrideUrlLoading} when starting activities.
 */
final class UrlLoadingHelper {
    private UrlLoadingHelper() { }

    /**
     * From unknown web content: clear explicit component, strip dangerous URI flags, browsable, new task.
     */
    static void applyWebViewSecurityPolicy(Intent intent) {
        if (intent == null) {
            return;
        }
        intent.setComponent(null);
        intent.setSelector(null);
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int f = intent.getFlags();
        f &= ~Intent.FLAG_GRANT_READ_URI_PERMISSION;
        f &= ~Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        f &= ~Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
        f &= ~Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
        intent.setFlags(f);
    }
}
