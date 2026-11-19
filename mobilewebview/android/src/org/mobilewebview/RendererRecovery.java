package org.mobilewebview;

/**
 * What brings a WebView back after its renderer died, and when.
 *
 * Content: the saved back/forward history while it is at least as new as the
 * last navigation, else the last loadHtml() content or URL. Timing: a kill
 * under a shown view revives on its own once per user action (show, load,
 * reload), so a renderer the system keeps killing never loops; a crash, or a
 * hidden view, waits for the next show, load or reload.
 */
final class RendererRecovery {
    enum Revive { NOT_GONE, BLANK, RESTORE_STATE, LOAD_URL, LOAD_HTML }

    private boolean mGone = false;
    private volatile boolean mDestroyed = false;
    private boolean mAutoRevived = false;
    private boolean mAutoReviveScheduled = false;
    private byte[] mState = null;
    private boolean mStateFresh = false;
    private String mUrl = "";
    private String mHtml = null;
    private String mHtmlBaseUrl = "";

    void onStateSaved(byte[] state) {
        if (state != null && state.length > 0) {
            mState = state;
            mStateFresh = true;
        }
    }

    void clearState() {
        mState = null;
        mStateFresh = false;
    }

    /** A navigation started. */
    void onUrl(String url) {
        final String next = url != null ? url : "";
        if (!next.equals(mUrl)) {
            mStateFresh = false;
        }
        mUrl = next;
        if (mHtml != null && !next.equals(mHtmlBaseUrl) && !next.equals("about:blank")
                && !next.startsWith("data:")) {
            mHtml = null;
        }
    }

    void onHtml(String html, String baseUrl) {
        mHtml = html != null ? html : "";
        mHtmlBaseUrl = baseUrl != null ? baseUrl : "";
    }

    void onLoadUrl() {
        mHtml = null;
    }

    void onGone() {
        mGone = true;
    }

    void onDestroyed() {
        mDestroyed = true;
    }

    boolean isGone() {
        return mGone && !mDestroyed;
    }

    boolean shouldAutoRevive(boolean crashed, boolean shown) {
        return !mDestroyed && !crashed && shown && !mAutoRevived;
    }

    /** An auto-revive waits for the window to come to the front. */
    void scheduleAutoRevive() {
        mAutoReviveScheduled = true;
    }

    /** Whether a scheduled auto-revive may still run; consumed. */
    boolean takeScheduledAutoRevive() {
        final boolean run = mAutoReviveScheduled && isGone();
        mAutoReviveScheduled = false;
        return run;
    }

    void onAutoRevived() {
        mAutoRevived = true;
    }

    void onUserAction() {
        mAutoRevived = false;
    }

    static boolean visibleAfterRevive(boolean shown) {
        return shown;
    }

    Revive takeRevive() {
        if (!isGone()) {
            return Revive.NOT_GONE;
        }
        mGone = false;
        mAutoReviveScheduled = false;
        // History of a loadHtml() page would only bring back its base URL.
        if (mHtml != null && onHtmlDocument()) {
            return Revive.LOAD_HTML;
        }
        if (mState != null && mStateFresh) {
            return Revive.RESTORE_STATE;
        }
        if (mHtml != null) {
            return Revive.LOAD_HTML;
        }
        if (!mUrl.isEmpty()) {
            return Revive.LOAD_URL;
        }
        return mState != null ? Revive.RESTORE_STATE : Revive.BLANK;
    }

    private boolean onHtmlDocument() {
        return mUrl.isEmpty() || mUrl.equals(mHtmlBaseUrl) || mUrl.equals("about:blank")
                || mUrl.startsWith("data:");
    }

    byte[] state() {
        return mState;
    }

    String url() {
        return mUrl;
    }

    String html() {
        return mHtml;
    }

    String htmlBaseUrl() {
        return mHtmlBaseUrl;
    }
}
