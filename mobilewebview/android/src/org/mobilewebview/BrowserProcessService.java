package org.mobilewebview;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.util.SparseArray;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

import java.util.ArrayList;

/**
 * The browser process ({@code android:process=":browser"}): owns the WebViews of
 * {@link RemoteWebView}s and renders each into a SurfaceControlViewHost whose SurfacePackage the
 * UI process embeds. Bound only; when the UI process unbinds, the process exits, so Chromium's
 * memory goes with it.
 *
 * The app manifest declares it:
 * {@code <service android:name="org.mobilewebview.BrowserProcessService"
 * android:process=":browser" android:exported="false" />}
 */
public final class BrowserProcessService extends Service {
    private static final String TAG = "MobileWebView";

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final SparseArray<Embedded> mViews = new SparseArray<>();
    private IBinder mClient;

    private final BrowserIpc.Receiver mHost = new BrowserIpc.Receiver() {
        @Override
        protected void onCall(String method, Bundle args) {
            mMain.post(() -> dispatch(method, args));
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "browser process service created, pid " + Process.myPid());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mHost;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "browser process unbound: destroying WebViews and exiting");
        mMain.post(() -> {
            for (int i = 0; i < mViews.size(); i++) mViews.valueAt(i).destroy();
            mViews.clear();
            mMain.postDelayed(() -> Process.killProcess(Process.myPid()), 200);
        });
        return false;
    }

    private void dispatch(String method, Bundle a) {
        switch (method) {
            case "hello": {
                mClient = a.getBinder("client");
                final Bundle info = new Bundle();
                String ua = "";
                try {
                    ua = WebSettings.getDefaultUserAgent(this);
                } catch (RuntimeException e) {
                    Log.w(TAG, "getDefaultUserAgent failed", e);
                }
                info.putString("userAgent", ua);
                info.putBoolean("clearSiteData", DataClearManager.isClearSiteDataSupported());
                info.putInt("pid", Process.myPid());
                BrowserIpc.send(mClient, "info", info, true);
                return;
            }
            case "saveAll": {
                for (int i = 0; i < mViews.size(); i++) {
                    final Embedded e = mViews.valueAt(i);
                    final Bundle state = e.core.saveState();
                    final Bundle b = new Bundle();
                    b.putInt("id", e.id);
                    b.putBundle("state", state);
                    BrowserIpc.send(mClient, "state", b, true);
                }
                BrowserIpc.send(mClient, "saved", new Bundle(), true);
                return;
            }
            case "destroy": {
                final int id = a.getInt("id");
                final Embedded e = mViews.get(id);
                if (e != null) {
                    e.destroy();
                    mViews.remove(id);
                }
                return;
            }
            case "call":
                dispatchCall(a);
                return;
            default:
                Log.w(TAG, "browser process: unknown call " + method);
        }
    }

    private void dispatchCall(Bundle a) {
        final int id = a.getInt("id");
        final String m = a.getString("m", "");
        if ("create".equals(m)) {
            final Embedded old = mViews.get(id);
            if (old != null) old.destroy();
            mViews.put(id, new Embedded(id, a.getString("storageName"), a.getBoolean("offTheRecord")));
            return;
        }
        final Embedded e = mViews.get(id);
        if (e == null) {
            Log.w(TAG, "browser process: call " + m + " for unknown WebView " + id);
            return;
        }
        final WebViewCore c = e.core;
        switch (m) {
            case "attach": e.attach(a.getBinder("hostToken"), a.getInt("displayId"), a.getInt("width"), a.getInt("height")); break;
            case "resize": e.resize(a.getInt("width"), a.getInt("height")); break;
            case "passThrough": e.setPassThrough(a.getParcelableArrayList("rects")); break;
            case "restoreState": c.restoreState(a.getBundle("state")); break;
            case "installMessageBridge":
                c.installMessageBridge(a.getString("namespace"), a.getStringArray("allowedOrigins"),
                        a.getString("invokeKey"), a.getStringArray("userScripts"),
                        a.getString("bootstrapPageScript"), a.getString("bootstrapBridgeScript"),
                        a.getString("inlineDownloadScript"));
                break;
            case "updateAllowedOrigins": c.updateAllowedOrigins(a.getStringArray("origins")); break;
            case "loadUrl": c.loadUrl(a.getString("url")); break;
            case "loadHtml": c.loadHtml(a.getString("html"), a.getString("baseUrl")); break;
            case "goBack": c.goBack(); break;
            case "goForward": c.goForward(); break;
            case "goBackOrForward": c.goBackOrForward(a.getInt("offset")); break;
            case "reload": c.reload(); break;
            case "stop": c.stop(); break;
            case "clearHistory": c.clearHistory(); break;
            case "clearHttpCache": c.clearHttpCache(a.getLong("requestId")); break;
            case "deleteAllCookies": c.deleteAllCookies(a.getLong("requestId")); break;
            case "clearDomStorage": c.clearDomStorage(a.getLong("requestId")); break;
            case "clearSiteData": c.clearSiteData(a.getString("site"), a.getLong("requestId")); break;
            case "reloadAndBypassCache": c.reloadAndBypassCache(); break;
            case "setZoomFactor": c.setZoomFactor(a.getFloat("factor")); break;
            case "setHttpUserAgent": c.setHttpUserAgent(a.getString("userAgent")); break;
            case "findText": c.findText(a.getString("text"), a.getInt("flags")); break;
            case "stopFind": c.stopFind(); break;
            case "evaluateJavaScript": c.evaluateJavaScript(a.getString("script")); break;
            case "postMessageToJavaScript": c.postMessageToJavaScript(a.getString("json")); break;
            case "setVisible": c.setVisible(a.getBoolean("visible"), false); break;
            case "captureSnapshotForFreeze": c.captureSnapshotForFreeze(a.getLong("requestId")); break;
            case "setInteractionEnabled": c.setInteractionEnabled(a.getBoolean("enabled")); break;
            case "probeDownload": c.probeDownload(a.getString("url"), a.getString("token")); break;
            case "startDownload": c.startDownload(a.getLong("downloadId"), a.getString("url"), a.getString("destination")); break;
            case "cancelDownload": c.cancelDownload(a.getLong("downloadId")); break;
            case "pauseDownload": c.pauseDownload(a.getLong("downloadId")); break;
            case "resumeDownload": c.resumeDownload(a.getLong("downloadId")); break;
            default: Log.w(TAG, "browser process: unknown method " + m);
        }
    }

    private final class Embedded {
        final int id;
        final WebViewCore core;
        final FrameLayout root;
        SurfaceControlViewHost host;
        int width = 1, height = 1;
        ArrayList<Rect> passThrough = new ArrayList<>();

        Embedded(int id, String storageName, boolean offTheRecord) {
            this.id = id;
            final Context ctx = new ContextThemeWrapper(BrowserProcessService.this,
                    android.R.style.Theme_DeviceDefault_DayNight);
            root = new FrameLayout(ctx);
            core = new WebViewCore(ctx, new RemoteSink(id), null, storageName, offTheRecord);
            core.setEmbedded();
            final WebView webView = core.getWebView();
            if (webView != null) {
                root.addView(webView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
        }

        void attach(IBinder hostToken, int displayId, int w, int h) {
            width = Math.max(1, w);
            height = Math.max(1, h);
            if (host == null) {
                final Display display = getSystemService(DisplayManager.class).getDisplay(displayId);
                host = new SurfaceControlViewHost(root.getContext(), display, hostToken);
                host.setView(root, width, height);
            } else {
                host.relayout(width, height);
            }
            applyTouchable();
            final Bundle b = new Bundle();
            b.putInt("id", id);
            b.putParcelable("package", host.getSurfacePackage());
            BrowserIpc.send(mClient, "surface", b, true);
            Log.i(TAG, "browser process: WebView " + id + " attached " + width + "x" + height);
        }

        void resize(int w, int h) {
            width = Math.max(1, w);
            height = Math.max(1, h);
            if (host != null) {
                host.relayout(width, height);
                applyTouchable();
            }
        }

        void setPassThrough(ArrayList<Rect> rects) {
            passThrough = rects != null ? rects : new ArrayList<>();
            applyTouchable();
        }

        // Touches on host views over the page (passThrough) fall through to the UI process.
        private void applyTouchable() {
            if (Build.VERSION.SDK_INT < 34 || host == null) return;
            // The root has no root surface control until its first traversal: try again then.
            if (root.getRootSurfaceControl() == null) {
                root.post(this::applyTouchable);
                return;
            }
            if (passThrough.isEmpty()) {
                root.getRootSurfaceControl().setTouchableRegion(null);
                return;
            }
            final Region region = new Region(0, 0, width, height);
            for (Rect r : passThrough) region.op(r, Region.Op.DIFFERENCE);
            root.getRootSurfaceControl().setTouchableRegion(region);
            Log.i(TAG, "browser process: WebView " + id + " touchable " + region);
        }

        void destroy() {
            core.destroy();
            if (host != null) {
                host.release();
                host = null;
            }
        }
    }

    // Callbacks back to the UI process, one-way and in order.
    private final class RemoteSink implements WebViewSink {
        private final int mId;

        RemoteSink(int id) {
            mId = id;
        }

        private Bundle b() {
            final Bundle b = new Bundle();
            b.putInt("id", mId);
            return b;
        }

        private void send(String method, Bundle b) {
            BrowserIpc.send(mClient, method, b, true);
        }

        @Override public void onWebMessageReceived(String message, String origin, boolean isMainFrame) {
            final Bundle b = b();
            b.putString("message", message);
            b.putString("origin", origin);
            b.putBoolean("isMainFrame", isMainFrame);
            send("onWebMessageReceived", b);
        }
        @Override public void onNavigationStarted(String url) {
            final Bundle b = b();
            b.putString("url", url);
            send("onNavigationStarted", b);
        }
        @Override public void onNavigationFinished(String url) {
            final Bundle b = b();
            b.putString("url", url);
            send("onNavigationFinished", b);
        }
        @Override public void onNavigationFailed() { send("onNavigationFailed", b()); }
        @Override public void onJavaScriptResult(String result, String error) {
            final Bundle b = b();
            b.putString("result", result);
            b.putString("error", error);
            send("onJavaScriptResult", b);
        }
        @Override public void onTitleChanged(String title) {
            final Bundle b = b();
            b.putString("title", title);
            send("onTitleChanged", b);
        }
        @Override public void onNavigationStateChanged(boolean canGoBack, boolean canGoForward) {
            final Bundle b = b();
            b.putBoolean("canGoBack", canGoBack);
            b.putBoolean("canGoForward", canGoForward);
            send("onNavigationStateChanged", b);
        }
        @Override public void onBackRequested(boolean pressed) {
            final Bundle b = b();
            b.putBoolean("pressed", pressed);
            send("onBackRequested", b);
        }
        @Override public void onHistoryChanged(String[] urls, String[] titles, int currentHistoryIndex) {
            final Bundle b = b();
            b.putStringArray("urls", urls);
            b.putStringArray("titles", titles);
            b.putInt("index", currentHistoryIndex);
            send("onHistoryChanged", b);
        }
        @Override public void onNewWindowRequested(String url, boolean userInitiated) {
            final Bundle b = b();
            b.putString("url", url);
            b.putBoolean("userInitiated", userInitiated);
            send("onNewWindowRequested", b);
        }
        @Override public void onLoadProgressChanged(int progress) {
            final Bundle b = b();
            b.putInt("progress", progress);
            send("onLoadProgressChanged", b);
        }
        @Override public void onFaviconReceived(String faviconUrl) {
            final Bundle b = b();
            b.putString("faviconUrl", faviconUrl);
            send("onFaviconReceived", b);
        }
        @Override public void onFindResultChanged(int activeMatchIndex, int matchCount) {
            final Bundle b = b();
            b.putInt("active", activeMatchIndex);
            b.putInt("count", matchCount);
            send("onFindResultChanged", b);
        }
        @Override public void onFreezeSnapshotReady(long requestId, Bitmap bitmap) {
            final Bundle b = b();
            b.putLong("requestId", requestId);
            b.putParcelable("bitmap", bitmap);
            send("onFreezeSnapshotReady", b);
            if (bitmap != null) bitmap.recycle();
        }
        @Override public void onClearHttpCacheCompleted(long requestId) { requestDone("onClearHttpCacheCompleted", requestId); }
        @Override public void onDeleteAllCookiesCompleted(long requestId) { requestDone("onDeleteAllCookiesCompleted", requestId); }
        @Override public void onClearDomStorageCompleted(long requestId) { requestDone("onClearDomStorageCompleted", requestId); }
        @Override public void onClearSiteDataCompleted(long requestId) { requestDone("onClearSiteDataCompleted", requestId); }

        private void requestDone(String method, long requestId) {
            final Bundle b = b();
            b.putLong("requestId", requestId);
            send(method, b);
        }

        @Override public void onDownloadDetected(String url, String contentDisposition, String mimeType,
                                                 long contentLength, String userAgent, String token) {
            final Bundle b = b();
            b.putString("url", url);
            b.putString("contentDisposition", contentDisposition);
            b.putString("mimeType", mimeType);
            b.putLong("contentLength", contentLength);
            b.putString("userAgent", userAgent);
            b.putString("token", token);
            send("onDownloadDetected", b);
        }
        @Override public void onDownloadProgress(long downloadId, long receivedBytes, long totalBytes) {
            final Bundle b = b();
            b.putLong("downloadId", downloadId);
            b.putLong("received", receivedBytes);
            b.putLong("total", totalBytes);
            send("onDownloadProgress", b);
        }
        @Override public void onDownloadFinished(long downloadId, boolean ok, String error) {
            final Bundle b = b();
            b.putLong("downloadId", downloadId);
            b.putBoolean("ok", ok);
            b.putString("error", error);
            send("onDownloadFinished", b);
        }
        @Override public void onLinkLongPressed(String linkUrl, String imageUrl, float x, float y) {
            final Bundle b = b();
            b.putString("linkUrl", linkUrl);
            b.putString("imageUrl", imageUrl);
            b.putFloat("x", x);
            b.putFloat("y", y);
            send("onLinkLongPressed", b);
        }
        @Override public void onTouchDown() { send("onTouchDown", b()); }
    }
}
