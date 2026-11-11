package org.mobilewebview;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A WebView that runs in the browser process ({@link BrowserProcessService}) and is shown here by
 * a SurfaceView, in its own panel window ({@link WebViewWindow}) placed like the QML item. The
 * browser process sends a SurfacePackage of its SurfaceControlViewHost; touches on the page go
 * straight to that window.
 *
 * Keeps what it needs to re-create the WebView after the browser process was let go: the bridge
 * setup, settings, geometry and the saved history.
 */
final class RemoteWebView implements WebViewEngine {
    private static final String TAG = "MobileWebView";
    private static final AtomicInteger sNextId = new AtomicInteger(1);
    // A capture this recent before a hide means the hide is a freeze (QML popup over the page).
    private static final long FREEZE_HIDE_WINDOW_MS = 2_000;

    final int mId = sNextId.getAndIncrement();
    private final MobileWebView mOwner;
    private final BrowserProcessClient mClient;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Activity mActivity;
    private final ViewGroup mHostContent;

    // Replayed into a new browser process (guarded by this).
    private final String mStorageName;
    private final boolean mOffTheRecord;
    private Bundle mBridge;
    private String[] mAllowedOrigins;
    private String mUserAgent;
    private Float mZoom;
    private Boolean mInteraction;
    private int mWidth = 1, mHeight = 1;
    private boolean mVisible = false;
    private boolean mFrozen = false;
    private long mCaptureMs = 0;
    private String mLastUrl;
    private Bundle mSavedState;
    private boolean mEverLive = false;
    private int mGeneration = -1;
    private IBinder mHostToken;
    private int mDisplayId;
    private ArrayList<Rect> mPassThrough = new ArrayList<>();

    // Main thread.
    private SurfaceView mSurfaceView;
    private WebViewWindow mWindow;
    private boolean mDestroyed = false;

    RemoteWebView(Context context, MobileWebView owner, View rootView,
                  String storageName, boolean offTheRecord) {
        mOwner = owner;
        mClient = BrowserProcessClient.get(context);
        mActivity = findActivity(context);
        mHostContent = rootView instanceof ViewGroup ? (ViewGroup) rootView : null;
        mStorageName = storageName != null ? storageName : "";
        mOffTheRecord = offTheRecord;
        mClient.register(this);
        mMain.post(this::createWindow);
    }

    private static Activity findActivity(Context context) {
        Context c = context;
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    private void createWindow() {
        if (mDestroyed || mActivity == null || mHostContent == null) {
            Log.w(TAG, "RemoteWebView: no activity to show the WebView in");
            return;
        }
        mSurfaceView = new SurfaceView(mActivity);
        // Above the panel: the embedded window then gets the touches on the page.
        mSurfaceView.setZOrderOnTop(true);
        mSurfaceView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                final IBinder token = mSurfaceView.getHostToken();
                final int display = mActivity.getDisplay() != null ? mActivity.getDisplay().getDisplayId() : 0;
                synchronized (RemoteWebView.this) {
                    mHostToken = token;
                    mDisplayId = display;
                }
                mClient.callIfLive(RemoteWebView.this, "attach", attachArgs());
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                synchronized (RemoteWebView.this) {
                    mHostToken = null;
                }
            }
        });
        mWindow = new WebViewWindow(mActivity, mHostContent, mSurfaceView, new WebViewWindow.Content() {
            @Override
            public boolean onBack(KeyEvent event) {
                final int action = event.getAction();
                if (action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP) {
                    mOwner.onBackRequested(action == KeyEvent.ACTION_DOWN);
                    return true;
                }
                return false;
            }

            @Override
            public void onFocusableChanged(boolean focusable) {
                // The embedded window can take the focus only through a focused host window.
                mSurfaceView.setFocusable(focusable);
                mSurfaceView.setFocusableInTouchMode(focusable);
                if (!focusable) mSurfaceView.clearFocus();
            }

            @Override
            public void onWindowFocusChanged(boolean hasFocus) {
                Log.i(TAG, "RemoteWebView " + mId + " panel focus=" + hasFocus);
                if (hasFocus && mWindow.isFocusable()) {
                    mSurfaceView.clearFocus();
                    mSurfaceView.requestFocus();
                }
            }
        });
        final int w, h, x, y;
        final boolean visible;
        synchronized (this) {
            w = mWidth;
            h = mHeight;
            visible = mVisible;
        }
        mWindow.setGeometry(mX, mY, w, h);
        mWindow.setVisible(visible, false);
        // The edge strip and other host views over the page move without a geometry change here.
        mHostContent.getViewTreeObserver().addOnGlobalLayoutListener(this::updatePassThrough);
    }

    private int mX, mY;  // main thread

    synchronized boolean isLive(int generation) {
        return mGeneration == generation;
    }

    /** Shown, or hidden only for a freeze: the browser process must stay. */
    synchronized boolean keepsProcess() {
        return mVisible || mFrozen;
    }

    // Under the client lock: create the WebView in a (new) browser process and bring it back to
    // where it was.
    synchronized void replayInto(IBinder host, int generation) {
        mGeneration = generation;
        final Bundle create = new Bundle();
        create.putString("storageName", mStorageName);
        create.putBoolean("offTheRecord", mOffTheRecord);
        send(host, "create", create);
        if (mBridge != null) send(host, "installMessageBridge", new Bundle(mBridge));
        if (mAllowedOrigins != null) {
            final Bundle b = new Bundle();
            b.putStringArray("origins", mAllowedOrigins);
            send(host, "updateAllowedOrigins", b);
        }
        if (mUserAgent != null) {
            final Bundle b = new Bundle();
            b.putString("userAgent", mUserAgent);
            send(host, "setHttpUserAgent", b);
        }
        if (mZoom != null) {
            final Bundle b = new Bundle();
            b.putFloat("factor", mZoom);
            send(host, "setZoomFactor", b);
        }
        if (mInteraction != null) {
            final Bundle b = new Bundle();
            b.putBoolean("enabled", mInteraction);
            send(host, "setInteractionEnabled", b);
        }
        if (mEverLive) {
            if (mSavedState != null) {
                final Bundle b = new Bundle();
                b.putBundle("state", mSavedState);
                send(host, "restoreState", b);
                Log.i(TAG, "RemoteWebView " + mId + " restoring saved history");
            } else if (mLastUrl != null) {
                final Bundle b = new Bundle();
                b.putString("url", mLastUrl);
                send(host, "loadUrl", b);
                Log.i(TAG, "RemoteWebView " + mId + " reloading " + mLastUrl);
            }
        }
        mEverLive = true;
        final Bundle vis = new Bundle();
        vis.putBoolean("visible", mVisible);
        send(host, "setVisible", vis);
        if (mHostToken != null) send(host, "attach", attachArgs());
        final Bundle pt = new Bundle();
        pt.putParcelableArrayList("rects", new ArrayList<>(mPassThrough));
        send(host, "passThrough", pt);
    }

    private void send(IBinder host, String method, Bundle args) {
        args.putInt("id", mId);
        args.putString("m", method);
        BrowserIpc.send(host, "call", args, false);
    }

    private synchronized Bundle attachArgs() {
        final Bundle b = new Bundle();
        b.putBinder("hostToken", mHostToken);
        b.putInt("displayId", mDisplayId);
        b.putInt("width", mWidth);
        b.putInt("height", mHeight);
        return b;
    }

    // --- callbacks from the browser process (binder thread) ---

    void onCallback(String method, Bundle a) {
        switch (method) {
            case "surface": {
                final SurfaceControlViewHost.SurfacePackage pkg = a.getParcelable("package");
                mMain.post(() -> {
                    if (mSurfaceView == null || mDestroyed) {
                        if (pkg != null) pkg.release();
                        return;
                    }
                    mSurfaceView.setChildSurfacePackage(pkg);
                    Log.i(TAG, "RemoteWebView " + mId + " surface package set");
                });
                return;
            }
            case "state":
                synchronized (this) {
                    mSavedState = a.getBundle("state");
                }
                return;
            case "onTouchDown":
                Log.i(TAG, "RemoteWebView " + mId + " touch down in the browser process");
                mMain.post(() -> {
                    if (mWindow != null) mWindow.contentTouched();
                });
                return;
            case "onWebMessageReceived":
                mOwner.onWebMessageReceived(a.getString("message"), a.getString("origin"), a.getBoolean("isMainFrame"));
                return;
            case "onNavigationStarted":
                synchronized (this) {
                    mLastUrl = a.getString("url");
                }
                mOwner.onNavigationStarted(a.getString("url"));
                return;
            case "onNavigationFinished":
                synchronized (this) {
                    mLastUrl = a.getString("url");
                }
                mOwner.onNavigationFinished(a.getString("url"));
                return;
            case "onNavigationFailed":
                mOwner.onNavigationFailed();
                return;
            case "onJavaScriptResult":
                mOwner.onJavaScriptResult(a.getString("result"), a.getString("error"));
                return;
            case "onTitleChanged":
                mOwner.onTitleChanged(a.getString("title"));
                return;
            case "onNavigationStateChanged":
                mOwner.onNavigationStateChanged(a.getBoolean("canGoBack"), a.getBoolean("canGoForward"));
                return;
            case "onBackRequested":
                mOwner.onBackRequested(a.getBoolean("pressed"));
                return;
            case "onHistoryChanged":
                mOwner.onHistoryChanged(a.getStringArray("urls"), a.getStringArray("titles"), a.getInt("index"));
                return;
            case "onNewWindowRequested":
                mOwner.onNewWindowRequested(a.getString("url"), a.getBoolean("userInitiated"));
                return;
            case "onLoadProgressChanged":
                mOwner.onLoadProgressChanged(a.getInt("progress"));
                return;
            case "onFaviconReceived":
                mOwner.onFaviconReceived(a.getString("faviconUrl"));
                return;
            case "onFindResultChanged":
                mOwner.onFindResultChanged(a.getInt("active"), a.getInt("count"));
                return;
            case "onFreezeSnapshotReady":
                mOwner.onFreezeSnapshotReady(a.getLong("requestId"), (Bitmap) a.getParcelable("bitmap"));
                return;
            case "onClearHttpCacheCompleted":
                mOwner.onClearHttpCacheCompleted(a.getLong("requestId"));
                return;
            case "onDeleteAllCookiesCompleted":
                mOwner.onDeleteAllCookiesCompleted(a.getLong("requestId"));
                return;
            case "onClearDomStorageCompleted":
                mOwner.onClearDomStorageCompleted(a.getLong("requestId"));
                return;
            case "onClearSiteDataCompleted":
                mOwner.onClearSiteDataCompleted(a.getLong("requestId"));
                return;
            case "onDownloadDetected":
                mOwner.onDownloadDetected(a.getString("url"), a.getString("contentDisposition"),
                        a.getString("mimeType"), a.getLong("contentLength"), a.getString("userAgent"),
                        a.getString("token"));
                return;
            case "onDownloadProgress":
                mOwner.onDownloadProgress(a.getLong("downloadId"), a.getLong("received"), a.getLong("total"));
                return;
            case "onDownloadFinished":
                mOwner.onDownloadFinished(a.getLong("downloadId"), a.getBoolean("ok"), a.getString("error"));
                return;
            case "onLinkLongPressed":
                mOwner.onLinkLongPressed(a.getString("linkUrl"), a.getString("imageUrl"), a.getFloat("x"), a.getFloat("y"));
                return;
            default:
                Log.w(TAG, "RemoteWebView: unknown callback " + method);
        }
    }

    void onDefaultUserAgent(String userAgent) {
        mOwner.onDefaultUserAgent(userAgent);
    }

    // --- WebViewEngine ---

    private void call(String method, Bundle args) {
        mClient.call(this, method, args);
    }

    private static Bundle args() {
        return new Bundle();
    }

    @Override
    public void installMessageBridge(String namespace, String[] allowedOrigins, String invokeKey,
                                     String[] userScripts, String bootstrapPageScript,
                                     String bootstrapBridgeScript, String inlineDownloadScript) {
        final Bundle b = args();
        b.putString("namespace", namespace);
        b.putStringArray("allowedOrigins", allowedOrigins);
        b.putString("invokeKey", invokeKey);
        b.putStringArray("userScripts", userScripts);
        b.putString("bootstrapPageScript", bootstrapPageScript);
        b.putString("bootstrapBridgeScript", bootstrapBridgeScript);
        b.putString("inlineDownloadScript", inlineDownloadScript);
        synchronized (this) {
            mBridge = new Bundle(b);
            mAllowedOrigins = null;
        }
        mClient.callIfLive(this, "installMessageBridge", b);
    }

    @Override
    public void updateAllowedOrigins(String[] origins) {
        final Bundle b = args();
        b.putStringArray("origins", origins);
        synchronized (this) {
            mAllowedOrigins = origins;
        }
        mClient.callIfLive(this, "updateAllowedOrigins", b);
    }

    @Override
    public void loadUrl(String url) {
        synchronized (this) {
            mLastUrl = url;
        }
        final Bundle b = args();
        b.putString("url", url);
        call("loadUrl", b);
    }

    @Override
    public void loadHtml(String html, String baseUrl) {
        final Bundle b = args();
        b.putString("html", html);
        b.putString("baseUrl", baseUrl);
        call("loadHtml", b);
    }

    @Override public void goBack() { call("goBack", args()); }
    @Override public void goForward() { call("goForward", args()); }

    @Override
    public void goBackOrForward(int offset) {
        final Bundle b = args();
        b.putInt("offset", offset);
        call("goBackOrForward", b);
    }

    @Override public void reload() { call("reload", args()); }
    @Override public void stop() { mClient.callIfLive(this, "stop", args()); }
    @Override public void clearHistory() { call("clearHistory", args()); }

    private void requestCall(String method, long requestId) {
        final Bundle b = args();
        b.putLong("requestId", requestId);
        call(method, b);
    }

    @Override public void clearHttpCache(long requestId) { requestCall("clearHttpCache", requestId); }
    @Override public void deleteAllCookies(long requestId) { requestCall("deleteAllCookies", requestId); }
    @Override public void clearDomStorage(long requestId) { requestCall("clearDomStorage", requestId); }

    @Override
    public boolean isClearSiteDataSupported() {
        return DataClearManager.isClearSiteDataSupported();
    }

    @Override
    public void clearSiteData(String site, long requestId) {
        final Bundle b = args();
        b.putString("site", site);
        b.putLong("requestId", requestId);
        call("clearSiteData", b);
    }

    @Override public void reloadAndBypassCache() { call("reloadAndBypassCache", args()); }

    @Override
    public void setZoomFactor(float factor) {
        synchronized (this) {
            mZoom = factor;
        }
        final Bundle b = args();
        b.putFloat("factor", factor);
        mClient.callIfLive(this, "setZoomFactor", b);
    }

    @Override
    public void setHttpUserAgent(String userAgent) {
        synchronized (this) {
            mUserAgent = userAgent != null ? userAgent : "";
        }
        final Bundle b = args();
        b.putString("userAgent", userAgent);
        mClient.callIfLive(this, "setHttpUserAgent", b);
    }

    @Override
    public void findText(String text, int flags) {
        final Bundle b = args();
        b.putString("text", text);
        b.putInt("flags", flags);
        call("findText", b);
    }

    @Override public void stopFind() { mClient.callIfLive(this, "stopFind", args()); }

    @Override
    public void evaluateJavaScript(String script) {
        final Bundle b = args();
        b.putString("script", script);
        call("evaluateJavaScript", b);
    }

    @Override
    public void postMessageToJavaScript(String json) {
        final Bundle b = args();
        b.putString("json", json);
        call("postMessageToJavaScript", b);
    }

    @Override
    public void setGeometry(int x, int y, int width, int height) {
        mMain.post(() -> {
            mX = x;
            mY = y;
            if (mWindow == null) return;
            applyGeometry(x, y, Math.max(1, width), Math.max(1, height));
        });
    }

    // Main thread. Host chrome sliding over the page (the navbar) shrinks the item, but the
    // remote WebView keeps its full size: the panel window takes the item rect and crops the
    // SurfaceView, which stays put at the full rect. Only a new full rect (the host content was
    // resized, e.g. rotation, or the item grew) relayouts the browser process.
    private final Rect mFull = new Rect();
    private int mHostW = -1, mHostH = -1;

    private void applyGeometry(int x, int y, int w, int h) {
        final Rect item = new Rect(x, y, x + w, y + h);
        final int hw = mHostContent.getWidth(), hh = mHostContent.getHeight();
        final boolean newFull;
        if (hw != mHostW || hh != mHostH || mFull.isEmpty()) {
            mHostW = hw;
            mHostH = hh;
            newFull = !item.equals(mFull);
            mFull.set(item);
        } else {
            final Rect u = new Rect(mFull);
            u.union(item);
            newFull = !u.equals(mFull);
            mFull.set(u);
        }
        mWindow.setGeometry(x, y, w, h);
        final android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) mSurfaceView.getLayoutParams();
        if (lp.width != mFull.width() || lp.height != mFull.height()) {
            lp.width = mFull.width();
            lp.height = mFull.height();
            mSurfaceView.setLayoutParams(lp);
        }
        mSurfaceView.setTranslationX(mFull.left - x);
        mSurfaceView.setTranslationY(mFull.top - y);
        if (newFull) {
            synchronized (this) {
                mWidth = mFull.width();
                mHeight = mFull.height();
            }
            final Bundle b = args();
            b.putInt("width", mFull.width());
            b.putInt("height", mFull.height());
            mClient.callIfLive(this, "resize", b);
            Log.i(TAG, "RemoteWebView " + mId + " full rect " + mFull);
        }
        updatePassThrough();
    }

    // Host views over the page (the drawer's edge strip) keep their touches.
    private void updatePassThrough() {
        if (mWindow == null || android.os.Build.VERSION.SDK_INT < 34) return;
        final ArrayList<Rect> rects = new ArrayList<>(mWindow.hostRectsAbove());
        synchronized (this) {
            if (rects.equals(mPassThrough)) return;
            mPassThrough = rects;
        }
        Log.i(TAG, "RemoteWebView " + mId + " pass-through " + rects);
        final Bundle b = args();
        b.putParcelableArrayList("rects", rects);
        mClient.callIfLive(this, "passThrough", b);
    }

    @Override
    public void setVisible(boolean visible, boolean grabFocus) {
        synchronized (this) {
            mFrozen = !visible && mCaptureMs != 0
                    && SystemClock.uptimeMillis() - mCaptureMs < FREEZE_HIDE_WINDOW_MS;
            mCaptureMs = 0;
            mVisible = visible;
        }
        final Bundle b = args();
        b.putBoolean("visible", visible);
        mClient.callIfLive(this, "setVisible", b);
        mMain.post(() -> {
            if (mWindow != null) {
                mWindow.setVisible(visible, false);
                if (visible) updatePassThrough();
            }
        });
        mClient.updateIdle();
    }

    @Override
    public void captureSnapshotForFreeze(long requestId) {
        synchronized (this) {
            mCaptureMs = SystemClock.uptimeMillis();
        }
        if (!mClient.isViewLive(this)) {
            mOwner.onFreezeSnapshotReady(requestId, null);
            return;
        }
        requestCall("captureSnapshotForFreeze", requestId);
    }

    @Override
    public void setInteractionEnabled(boolean enabled) {
        synchronized (this) {
            mInteraction = enabled;
        }
        final Bundle b = args();
        b.putBoolean("enabled", enabled);
        mClient.callIfLive(this, "setInteractionEnabled", b);
    }

    @Override
    public void probeDownload(String url, String token) {
        final Bundle b = args();
        b.putString("url", url);
        b.putString("token", token);
        call("probeDownload", b);
    }

    @Override
    public void startDownload(long downloadId, String url, String destination) {
        final Bundle b = args();
        b.putLong("downloadId", downloadId);
        b.putString("url", url);
        b.putString("destination", destination);
        call("startDownload", b);
    }

    private void downloadCall(String method, long downloadId) {
        final Bundle b = args();
        b.putLong("downloadId", downloadId);
        call(method, b);
    }

    @Override public void cancelDownload(long downloadId) { downloadCall("cancelDownload", downloadId); }
    @Override public void pauseDownload(long downloadId) { downloadCall("pauseDownload", downloadId); }
    @Override public void resumeDownload(long downloadId) { downloadCall("resumeDownload", downloadId); }

    @Override
    public void destroy() {
        mClient.unregister(this);
        mMain.post(() -> {
            mDestroyed = true;
            if (mWindow != null) {
                mWindow.remove();
                mWindow = null;
            }
            mSurfaceView = null;
        });
    }
}
