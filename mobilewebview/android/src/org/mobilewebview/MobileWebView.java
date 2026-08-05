package org.mobilewebview;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import android.util.Base64;
import android.util.Log;
import android.app.Activity;
import android.net.Uri;
import android.content.ContextWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.PixelCopy;
import android.view.ViewParent;
import android.webkit.WebSettings;
import android.webkit.WebBackForwardList;
import android.webkit.WebHistoryItem;
import android.webkit.WebView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.graphics.Rect;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * MobileWebView - Native Android WebView wrapper for Qt integration
 * Provides QWebChannel bridge, user script injection, and origin validation
 */
public class MobileWebView implements ChromeHost, NavigationHost, NativeBridgeHost, BridgeInjectorHost {
    private static final String TAG = "MobileWebView";
    private static final int ANDROID_CONTENT_VIEW_ID = 0x01020002; // android.R.id.content

    private WebView mWebView;
    private Context mContext;
    private volatile long mNativePtr;  // Pointer to C++ AndroidWebViewBackend
    private ViewGroup mRootView;

    private final Object mBridgeLock = new Object();
    private String mBridgeNamespace = "qt";
    private String mInvokeKey = "";
    private final List<String> mAllowedOrigins = new ArrayList<>();
    private final List<String> mUserScripts = new ArrayList<>();
    private String mBootstrapPageScript = "";
    private String mBootstrapBridgeScript = "";
    private String mInlineDownloadScript = "";
    private volatile String mCurrentMainFrameOrigin = "";
    private volatile String mActiveNavigationUrl = "";

    // Navigation state
    private boolean mBridgeInstalled = false;
    private String mPendingUrl = null;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final PendingActionQueue mPendingActionQueue = new PendingActionQueue();
    private final BridgeScriptInjector mBridgeInjector = new BridgeScriptInjector(this);

    @FunctionalInterface
    private interface NativeCallback {
        void invoke(long ptr);
    }

    private final WebViewProfileManager mProfileManager = new WebViewProfileManager();
    private final DataClearManager mDataClearManager = new DataClearManager();
    private final DownloadFetcher mDownloadFetcher = new DownloadFetcher(new DownloadFetcher.Callbacks() {
        @Override
        public void onProgress(long downloadId, long receivedBytes, long totalBytes) {
            withNativePtr(ptr -> nativeOnDownloadProgress(ptr, downloadId, receivedBytes, totalBytes));
        }

        @Override
        public void onFinished(long downloadId, boolean ok, String error) {
            withNativePtr(ptr -> nativeOnDownloadFinished(ptr, downloadId, ok, error));
        }
    });
    private String mStorageName = "";
    private boolean mOffTheRecord = false;
    private String mActiveProfileName = null;
    private String mHttpUserAgent = "";

    // WebView-local px; HitTestResult has no coordinates.
    private float mLastTouchX = 0f;
    private float mLastTouchY = 0f;

    /**
     * Constructor - creates and initializes WebView
     */
    public MobileWebView(Context context, long nativePtr, View rootView,
                           String storageName, boolean offTheRecord) {
        mContext = context;
        mNativePtr = nativePtr;
        mRootView = resolveRootView(rootView);
        mStorageName = storageName != null ? storageName : "";
        mOffTheRecord = offTheRecord;

        Log.d(TAG, "MobileWebView created with nativePtr: " + nativePtr);

        // WebView must be created on Android main/UI thread.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            initializeWebViewOnMainThread(context);
            return;
        }

        mMainHandler.post(() -> initializeWebViewOnMainThread(context));
    }

    private void initializeWebViewOnMainThread(Context context) {
        try {
            mWebView = new WebView(context) {
                @Override
                public boolean dispatchKeyEvent(android.view.KeyEvent event) {
                    if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
                        final int action = event.getAction();
                        if (action == android.view.KeyEvent.ACTION_DOWN
                                || action == android.view.KeyEvent.ACTION_UP) {
                            final long ptr = mNativePtr;
                            if (ptr != 0) {
                                nativeOnBackRequested(ptr, action == android.view.KeyEvent.ACTION_DOWN);
                                return true; // consumed only because we forwarded it
                            }
                        }
                    }
                    return super.dispatchKeyEvent(event);
                }
            };
            setupWebView();
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to initialize WebView on main thread", e);
            mWebView = null;
        } finally {
            mPendingActionQueue.markReady();
        }
    }

    private ViewGroup resolveRootView(View rootView) {
        if (rootView instanceof ViewGroup) {
            return (ViewGroup) rootView;
        }

        if (mContext instanceof Activity) {
            View content = ((Activity) mContext).findViewById(ANDROID_CONTENT_VIEW_ID);
            if (content instanceof ViewGroup) {
                return (ViewGroup) content;
            }
        }

        Log.w(TAG, "Root view is not a ViewGroup; WebView will not be attached to hierarchy");
        return null;
    }

    private void runOnMainThread(Runnable action) {
        if (mPendingActionQueue.enqueueIfNotReady(action)) {
            return;
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
            return;
        }

        if (mWebView != null) {
            try {
                // Prefer posting via the view when it is still attached to its UI thread.
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.KITKAT
                        || mWebView.isAttachedToWindow()) {
                    if (mWebView.post(action)) {
                        return;
                    }
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "runOnMainThread: WebView.post failed, using main handler fallback", e);
            }
        }

        mMainHandler.post(action);
    }

    /**
     * Initialize WebView settings and clients
     */
    private void setupWebView() {
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        // Top-level file:// for opening Downloads / local media. Web→file stays
        // blocked; flags below keep file-origin JS from reading other local files.
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        // Enable WebView debugging only for debuggable app builds.
        boolean isDebuggableBuild =
            (mContext.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (isDebuggableBuild && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        mWebView.setWebViewClient(new MobileWebViewClient(this));
        mWebView.setWebChromeClient(new MobileWebChromeClient(this));
        mWebView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            // Scheme filter + filename guessing live in C++ DownloadPolicy; pass raw inputs.
            withNativePtr(ptr -> nativeOnDownloadDetected(
                    ptr,
                    url,
                    contentDisposition != null ? contentDisposition : "",
                    mimeType != null ? mimeType : "",
                    contentLength,
                    userAgent));
        });

        // Long-press → host context menu (ADR 0005). Touch only records position.
        mWebView.setOnTouchListener((v, event) -> {
            mLastTouchX = event.getX();
            mLastTouchY = event.getY();
            return false;
        });
        mWebView.setOnLongClickListener(v -> handleLongPress());

        mActiveProfileName = mProfileManager.configureProfile(mWebView, mStorageName, mOffTheRecord);

        // Add to view hierarchy (initially hidden). Must happen on UI thread.
        mWebView.setVisibility(View.GONE);
        if (mRootView != null) {
            ViewParent parent = mWebView.getParent();
            if (parent instanceof ViewGroup && parent != mRootView) {
                ((ViewGroup) parent).removeView(mWebView);
            }
            if (mWebView.getParent() == null) {
                mRootView.addView(mWebView);
            }
        }

        mWebView.addJavascriptInterface(new MobileWebViewNativeBridge(this), "NativeBridge");
    }

    /**
     * Install WebChannel message bridge with user scripts
     */
    public void installMessageBridge(String namespace, String[] allowedOrigins,
                                     String invokeKey, String[] userScripts,
                                     String bootstrapPageScript, String bootstrapBridgeScript,
                                     String inlineDownloadScript) {
        synchronized (mBridgeLock) {
            mBridgeNamespace = namespace != null ? namespace : "";
            mInvokeKey = invokeKey != null ? invokeKey : "";
            mAllowedOrigins.clear();
            if (allowedOrigins != null) {
                mAllowedOrigins.addAll(Arrays.asList(allowedOrigins));
            }
            mUserScripts.clear();
            if (userScripts != null) {
                mUserScripts.addAll(Arrays.asList(userScripts));
            }
            mBootstrapPageScript = bootstrapPageScript != null ? bootstrapPageScript : "";
            mBootstrapBridgeScript = bootstrapBridgeScript != null ? bootstrapBridgeScript : "";
            mInlineDownloadScript = inlineDownloadScript != null ? inlineDownloadScript : "";
            mBridgeInstalled = true;
        }
        runOnMainThread(mBridgeInjector::configureInjectionMode);
    }

    /**
     * Update allowed origins after bridge installation (for dynamic origin changes during navigation)
     */
    public void updateAllowedOrigins(String[] origins) {
        synchronized (mBridgeLock) {
            mAllowedOrigins.clear();
            if (origins != null) {
                mAllowedOrigins.addAll(Arrays.asList(origins));
            }
        }
        runOnMainThread(mBridgeInjector::configureInjectionMode);
    }

    /**
     * Load URL in WebView
     */
    public void loadUrl(String url) {
        Log.d(TAG, "loadUrl: " + url);
        mPendingUrl = url;

        synchronized (mBridgeLock) {
            if (!mBridgeInstalled) {
                Log.w(TAG, "Bridge not installed, loading anyway");
            }
        }

        runOnMainThread(() -> {
            if (mWebView == null) return;
            mWebView.loadUrl(url);
        });
    }

    /**
     * Load HTML content with base URL
     */
    public void loadHtml(String html, String baseUrl) {
        Log.d(TAG, "loadHtml: baseUrl=" + baseUrl);
        mPendingUrl = baseUrl;

        synchronized (mBridgeLock) {
            if (!mBridgeInstalled) {
                Log.w(TAG, "Bridge not installed, loading anyway");
            }
        }

        runOnMainThread(() -> {
            if (mWebView == null) return;
            mWebView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null);
        });
    }

    public void goBack() {
        runOnMainThread(() -> {
            if (mWebView != null && mWebView.canGoBack()) {
                mWebView.goBack();
            }
        });
    }

    public void goForward() {
        runOnMainThread(() -> {
            if (mWebView != null && mWebView.canGoForward()) {
                mWebView.goForward();
            }
        });
    }

    public void goBackOrForward(int offset) {
        runOnMainThread(() -> {
            if (mWebView != null && mWebView.canGoBackOrForward(offset)) {
                mWebView.goBackOrForward(offset);
            }
        });
    }

    public void reload() {
        runOnMainThread(() -> {
            if (mWebView != null) {
                mBridgeInjector.markForceReinject();
                mWebView.reload();
            }
        });
    }

    public void stop() {
        runOnMainThread(() -> {
            if (mWebView != null) {
                mWebView.stopLoading();
            }
        });
    }

    public void clearHistory() {
        runOnMainThread(() -> {
            if (mWebView != null) {
                mWebView.clearHistory();
                notifyHistoryState(mWebView);
            }
        });
    }

    public void clearHttpCache(long requestId) {
        runOnMainThread(() -> {
            if (mWebView != null) {
                mDataClearManager.clearHttpCache(mWebView, () ->
                        withNativePtr(ptr -> nativeOnClearHttpCacheCompleted(ptr, requestId)));
            } else {
                withNativePtr(ptr -> nativeOnClearHttpCacheCompleted(ptr, requestId));
            }
        });
    }

    public void deleteAllCookies(long requestId) {
        runOnMainThread(() -> {
            mDataClearManager.deleteAllCookies(resolveActiveProfile(), () ->
                    withNativePtr(ptr -> nativeOnDeleteAllCookiesCompleted(ptr, requestId)));
        });
    }

    public void clearDomStorage(long requestId) {
        runOnMainThread(() -> {
            mDataClearManager.clearDomStorage(resolveActiveProfile(), () ->
                    withNativePtr(ptr -> nativeOnClearDomStorageCompleted(ptr, requestId)));
        });
    }

    public boolean isClearSiteDataSupported() {
        return DataClearManager.isClearSiteDataSupported();
    }

    public void clearSiteData(String site, long requestId) {
        runOnMainThread(() -> {
            mDataClearManager.clearSiteData(resolveActiveProfile(), site, () ->
                    withNativePtr(ptr -> nativeOnClearSiteDataCompleted(ptr, requestId)));
        });
    }

    public void reloadAndBypassCache() {
        runOnMainThread(() -> {
            if (mWebView != null) {
                mBridgeInjector.markForceReinject();
                mDataClearManager.reloadAndBypassCache(mWebView);
            }
        });
    }

    private androidx.webkit.Profile resolveActiveProfile() {
        if (mWebView == null) {
            return null;
        }
        try {
            Class<?> featureClass = Class.forName("androidx.webkit.WebViewFeature");
            java.lang.reflect.Method isSupported =
                    featureClass.getMethod("isFeatureSupported", String.class);
            Object supported = isSupported.invoke(null, "MULTI_PROFILE");
            if (!(supported instanceof Boolean) || !((Boolean) supported)) {
                return null;
            }
            Class<?> compatClass = Class.forName("androidx.webkit.WebViewCompat");
            java.lang.reflect.Method getProfile =
                    compatClass.getMethod("getProfile", WebView.class);
            Object profile = getProfile.invoke(null, mWebView);
            if (profile instanceof androidx.webkit.Profile) {
                return (androidx.webkit.Profile) profile;
            }
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "resolveActiveProfile failed", e);
        }
        return null;
    }

    public void setZoomFactor(float factor) {
        runOnMainThread(() -> {
            if (mWebView == null) return;

            String js = "document.documentElement.style.zoom = '" + factor + "'";
            mWebView.evaluateJavascript(js, null);
        });
    }

    /**
     * Override the HTTP User-Agent for this WebView.
     * Null or empty restores the platform default.
     */
    public void setHttpUserAgent(String userAgent) {
        mHttpUserAgent = userAgent != null ? userAgent : "";
        runOnMainThread(() -> {
            if (mWebView == null) return;
            WebSettings settings = mWebView.getSettings();
            if (userAgent == null || userAgent.isEmpty()) {
                settings.setUserAgentString(null);
            } else {
                settings.setUserAgentString(userAgent);
            }
        });
    }

    private String mCurrentFindQuery = null;

    /**
     * Find text in the page.
     * flags: bit 0 = backwards, bit 1 = case-sensitive
     */
    public void findText(String text, int flags) {
        runOnMainThread(() -> {
            if (mWebView == null) return;
            if (text == null || text.isEmpty()) {
                mCurrentFindQuery = null;
                mWebView.clearMatches();
                withNativePtr(ptr -> nativeOnFindResultChanged(ptr, -1, 0));
                return;
            }
            boolean backwards = (flags & 1) != 0;
            if (text.equals(mCurrentFindQuery)) {
                mWebView.findNext(!backwards);
                return;
            }
            mCurrentFindQuery = text;
            mWebView.setFindListener((activeMatchOrdinal, numberOfMatches, isDoneCounting) -> {
                if (isDoneCounting) {
                    withNativePtr(ptr -> nativeOnFindResultChanged(
                        ptr,
                        numberOfMatches > 0 ? activeMatchOrdinal : -1,
                        numberOfMatches));
                }
            });
            mWebView.findAllAsync(text);
        });
    }

    /**
     * Stop find-in-page and clear highlights
     */
    public void stopFind() {
        runOnMainThread(() -> {
            if (mWebView == null) return;
            mCurrentFindQuery = null;
            mWebView.clearMatches();
            mWebView.setFindListener(null);
            withNativePtr(ptr -> nativeOnFindResultChanged(ptr, -1, 0));
        });
    }

    /**
     * Evaluate JavaScript and notify result via callback
     */
    public void evaluateJavaScript(String script) {
        runOnMainThread(() -> {
            if (mWebView == null) return;
            mWebView.evaluateJavascript(script, result ->
                withNativePtr(ptr -> nativeOnJavaScriptResult(ptr, result != null ? result : "", ""))
            );
        });
    }

    /**
     * Post message to JavaScript WebChannel transport
     */
    public void postMessageToJavaScript(String json) {
        final String namespace;
        synchronized (mBridgeLock) {
            namespace = mBridgeNamespace;
        }
        String deliverScript = BridgeScriptBuilder.buildDeliverScript(namespace, json);

        runOnMainThread(() -> {
            if (mWebView == null) return;
            mWebView.evaluateJavascript(deliverScript, value ->
                Log.d(TAG, "postMessageToJavaScript result: " + value)
            );
        });
    }

    /**
     * Set WebView geometry (x, y, width, height)
     */
    public void setGeometry(int x, int y, int width, int height) {
        runOnMainThread(() -> {
            if (mWebView == null) return;
            ViewGroup.LayoutParams params = mWebView.getLayoutParams();
            if (params == null) {
                params = new ViewGroup.LayoutParams(width, height);
            } else {
                params.width = width;
                params.height = height;
            }
            mWebView.setLayoutParams(params);
            mWebView.setX(x);
            mWebView.setY(y);
        });
    }

    /**
     * Set WebView visibility
     */
    public void setVisible(boolean visible) {
        runOnMainThread(() -> {
            if (mWebView == null) return;
            mWebView.setVisibility(visible ? View.VISIBLE : View.GONE);
            // Grab focus when shown so the WebView receives key events
            // (notably KEYCODE_BACK). Without focus, dispatchKeyEvent is not
            // called and Back is never forwarded to QML.
            if (visible) {
                mWebView.requestFocus();
            }
        });
    }

    /**
     * Capture an ARGB8888 snapshot of the WebView for freeze overlay (runs on UI thread, then notifies native).
     */
    public void captureSnapshotForFreeze(long requestId) {
        runOnMainThread(() -> {
            if (!hasNativePtr()) {
                return;
            }
            if (mWebView == null) {
                reportFreezeSnapshotFailure(requestId);
                return;
            }
            final int w = mWebView.getWidth();
            final int h = mWebView.getHeight();
            if (w <= 0 || h <= 0) {
                reportFreezeSnapshotFailure(requestId);
                return;
            }

            try {
                final Window window = resolveWindow(mWebView.getContext());
                if (window == null) {
                    captureFreezeSoftware(requestId, w, h);
                    return;
                }

                final Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                final int[] location = new int[2];
                mWebView.getLocationInWindow(location);
                final Rect srcRect = new Rect(location[0], location[1], location[0] + w, location[1] + h);

                PixelCopy.request(window, srcRect, bitmap, result -> {
                    if (result == PixelCopy.SUCCESS) {
                        deliverFreezeSnapshot(requestId, bitmap, w, h);
                        return;
                    }
                    bitmap.recycle();
                    Log.w(TAG, "PixelCopy freeze snapshot failed with code " + result
                            + ", falling back to software draw");
                    captureFreezeSoftware(requestId, w, h);
                }, new Handler(Looper.getMainLooper()));
            } catch (RuntimeException e) {
                Log.e(TAG, "captureSnapshotForFreeze failed", e);
                reportFreezeSnapshotFailure(requestId);
            }
        });
    }

    private void captureFreezeSoftware(long requestId, int w, int h) {
        try {
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(android.graphics.Color.WHITE);
            mWebView.draw(new Canvas(bitmap));
            deliverFreezeSnapshot(requestId, bitmap, w, h);
        } catch (RuntimeException e) {
            Log.e(TAG, "software freeze snapshot failed", e);
            reportFreezeSnapshotFailure(requestId);
        }
    }

    private void deliverFreezeSnapshot(long requestId, Bitmap bitmap, int w, int h) {
        final byte[] pixels;
        try {
            ByteBuffer buffer = ByteBuffer.allocate(w * h * 4);
            buffer.order(ByteOrder.nativeOrder());
            bitmap.copyPixelsToBuffer(buffer);
            pixels = buffer.array();
        } catch (RuntimeException e) {
            Log.e(TAG, "deliverFreezeSnapshot failed", e);
            reportFreezeSnapshotFailure(requestId);
            return;
        } finally {
            bitmap.recycle();
        }
        withNativePtr(ptr -> nativeOnFreezeSnapshotReady(ptr, requestId, w, h, pixels));
    }

    private void reportFreezeSnapshotFailure(long requestId) {
        withNativePtr(ptr -> nativeOnFreezeSnapshotReady(ptr, requestId, 0, 0, null));
    }

    private Window resolveWindow(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) {
                return ((Activity) current).getWindow();
            }
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == current) {
                break;
            }
            current = base;
        }
        return null;
    }

    public void setInteractionEnabled(boolean enabled) {
        runOnMainThread(() -> {
            if (mWebView == null) return;
            mWebView.setFocusable(enabled);
            mWebView.setFocusableInTouchMode(enabled);
        });
    }

    /**
     * HEAD-probe Content-Disposition/Type for unnamed downloadUrl, then the same
     * nativeOnDownloadDetected path as DownloadListener.
     */
    public void probeDownload(final String url) {
        final String ua;
        String agent = mHttpUserAgent;
        if ((agent == null || agent.isEmpty()) && mWebView != null) {
            agent = mWebView.getSettings().getUserAgentString();
        }
        ua = agent != null ? agent : "";
        final String cookies = mOffTheRecord
                ? null
                : android.webkit.CookieManager.getInstance().getCookie(url);

        new Thread(() -> {
            // Probe failure: empty metadata; DownloadPolicy names from the URL.
            final DownloadProbe.Result probed = DownloadProbe.probeHeaders(url, ua, cookies);
            withNativePtr(ptr -> nativeOnDownloadDetected(
                    ptr,
                    url,
                    probed.contentDisposition,
                    probed.mimeType,
                    probed.contentLength,
                    ua));
        }, "MwvDownloadProbe").start();
    }

    /**
     * Start a self-fetch download after the host accepted a Download Target.
     */
    public void startDownload(long downloadId, String url, String destination) {
        String ua = mHttpUserAgent;
        if ((ua == null || ua.isEmpty()) && mWebView != null) {
            ua = mWebView.getSettings().getUserAgentString();
        }
        mDownloadFetcher.start(downloadId, url, destination, ua, mOffTheRecord, mContext);
    }

    public void cancelDownload(long downloadId) {
        mDownloadFetcher.cancel(downloadId);
    }

    public void pauseDownload(long downloadId) {
        mDownloadFetcher.pause(downloadId);
    }

    public void resumeDownload(long downloadId) {
        String ua = mHttpUserAgent;
        if ((ua == null || ua.isEmpty()) && mWebView != null) {
            ua = mWebView.getSettings().getUserAgentString();
        }
        mDownloadFetcher.resume(downloadId, ua, mOffTheRecord, mContext);
    }

    /**
     * Destroy WebView and cleanup
     */
    public void destroy() {
        mNativePtr = 0;  // zero out immediately so JNI callbacks are ignored
        mDownloadFetcher.cancelAll();
        runOnMainThread(() -> {
            mBridgeInjector.clearDocumentStartScripts();
            if (mWebView != null) {
                mProfileManager.flushCookiesIfPersistent(mOffTheRecord);
                mWebView.stopLoading();
                mWebView.loadUrl("about:blank");
                mWebView.clearHistory();
                mWebView.removeJavascriptInterface("NativeBridge");
                ViewParent parent = mWebView.getParent();
                if (parent instanceof ViewGroup) {
                    ((ViewGroup) parent).removeView(mWebView);
                }
                mWebView.destroy();
                mWebView = null;
            }
            mProfileManager.destroyProfile(mActiveProfileName, mOffTheRecord);
            mActiveProfileName = null;
        });
    }

    /**
     * Get WebView instance for adding to view hierarchy
     */
    public WebView getWebView() {
        return mWebView;
    }

    // --- ChromeHost ---

    @Override
    public void onTitleChanged(String title) {
        withNativePtr(ptr -> nativeOnTitleChanged(ptr, title));
    }

    @Override
    public void onProgressChanged(int progress) {
        withNativePtr(ptr -> nativeOnLoadProgressChanged(ptr, progress));
    }

    @Override
    public void onFavicon(Bitmap icon) {
        if (icon == null) {
            return;
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            icon.compress(Bitmap.CompressFormat.PNG, 100, baos);
            String base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
            String dataUri = "data:image/png;base64," + base64;
            withNativePtr(ptr -> nativeOnFaviconReceived(ptr, dataUri));
        } catch (Exception e) {
            Log.w(TAG, "onReceivedIcon: failed to encode favicon", e);
        }
    }

    @Override
    public void onNewWindowRequested(String url, boolean userGesture) {
        withNativePtr(ptr -> nativeOnNewWindowRequested(ptr, url, userGesture));
    }

    // --- NavigationHost ---

    @Override
    public void onNavigationStarted(String url) {
        final String navUrl = url != null ? url : "";
        final boolean sameUrlReload = !navUrl.isEmpty() && navUrl.equals(mActiveNavigationUrl);
        mActiveNavigationUrl = navUrl;
        mBridgeInjector.resetForNavigation(sameUrlReload);
        withNativePtr(ptr -> nativeOnNavigationStarted(ptr, navUrl));
    }

    @Override
    public void onNavigationFinished(String url) {
        if (mWebView != null) {
            mDataClearManager.onPageFinished(mWebView);
        }
        mProfileManager.flushCookiesIfPersistent(mOffTheRecord);
        withNativePtr(ptr -> nativeOnNavigationFinished(ptr, url));
    }

    @Override
    public void onMainFrameError() {
        withNativePtr(MobileWebView.this::nativeOnNavigationFailed);
    }

    @Override
    public void onNavigationLifecycle(WebView view, String url, boolean warnIfBridgeMissing,
                                      ScriptInjectionPhase injectionPhase) {
        handleNavigationLifecycle(view, url, warnIfBridgeMissing, injectionPhase);
    }

    @Override
    public boolean handleCustomScheme(WebView view, Uri uri) {
        return CustomSchemeUrlHandler.handle(view, uri);
    }

    // --- NativeBridgeHost ---

    @Override
    public long nativePtr() {
        return mNativePtr;
    }

    @Override
    public String currentMainFrameOrigin() {
        return mCurrentMainFrameOrigin;
    }

    @Override
    public List<String> allowedOriginsSnapshot() {
        synchronized (mBridgeLock) {
            return new ArrayList<>(mAllowedOrigins);
        }
    }

    @Override
    public WebView webView() {
        return mWebView;
    }

    @Override
    public void onWebMessage(String message, String origin) {
        withNativePtr(ptr -> nativeOnWebMessageReceived(ptr, message, origin, false));
    }

    // --- BridgeInjectorHost ---

    @Override
    public BridgeState snapshot() {
        synchronized (mBridgeLock) {
            return new BridgeState(
                mBridgeInstalled,
                new ArrayList<>(mUserScripts),
                mBootstrapPageScript,
                mBootstrapBridgeScript,
                mInlineDownloadScript,
                new ArrayList<>(mAllowedOrigins));
        }
    }

    @Override
    public String currentOrigin() {
        return mCurrentMainFrameOrigin;
    }

    private boolean hasNativePtr() {
        return mNativePtr != 0;
    }

    private void withNativePtr(NativeCallback callback) {
        long ptr = mNativePtr;
        if (ptr != 0) {
            callback.invoke(ptr);
        }
    }

    private void handleNavigationLifecycle(WebView view, String url, boolean warnWhenBridgeMissing,
                                           ScriptInjectionPhase injectionPhase) {
        final String newOrigin = OriginUtils.extractOrigin(url);
        mCurrentMainFrameOrigin = newOrigin;
        final boolean installed;
        synchronized (mBridgeLock) {
            installed = mBridgeInstalled;
        }

        // Track redirect targets (e.g. opensea.io -> www.opensea.io) for NativeBridge and
        // document-start re-registration.
        if (newOrigin != null && !newOrigin.isEmpty()) {
            boolean originsExpanded = false;
            synchronized (mBridgeLock) {
                if (!OriginUtils.isOriginAllowed(newOrigin, mAllowedOrigins)) {
                    mAllowedOrigins.add(newOrigin);
                    originsExpanded = true;
                }
            }
            if (originsExpanded) {
                mBridgeInjector.configureInjectionMode();
            }
        }

        if (installed) {
            mBridgeInjector.injectOnce(injectionPhase);
        } else if (warnWhenBridgeMissing) {
            Log.w(TAG, "onPageStarted: bridge not installed yet");
        }
        withNativePtr(ptr -> nativeOnNavigationStateChanged(ptr, view.canGoBack(), view.canGoForward()));
        notifyHistoryState(view);
    }

    private void notifyHistoryState(WebView view) {
        if (view == null) {
            return;
        }

        WebBackForwardList list = view.copyBackForwardList();
        if (list == null) {
            withNativePtr(ptr -> nativeOnHistoryChanged(ptr, new String[0], new String[0], -1));
            return;
        }

        int size = list.getSize();
        String[] urls = new String[size];
        String[] titles = new String[size];
        for (int i = 0; i < size; i++) {
            WebHistoryItem item = list.getItemAtIndex(i);
            if (item == null) {
                urls[i] = "";
                titles[i] = "";
                continue;
            }
            String itemUrl = item.getUrl();
            String itemTitle = item.getTitle();
            urls[i] = itemUrl != null ? itemUrl : "";
            titles[i] = itemTitle != null ? itemTitle : "";
        }

        withNativePtr(ptr -> nativeOnHistoryChanged(ptr, urls, titles, list.getCurrentIndex()));
    }

    // Native callback methods (implemented in C++)
    private native void nativeOnWebMessageReceived(long nativePtr, String message,
                                                   String origin, boolean isMainFrame);
    private native void nativeOnNavigationStarted(long nativePtr, String url);
    /**
     * Link/image long-press: notify host and consume; else fall through (selection).
     */
    private boolean handleLongPress() {
        if (mWebView == null) {
            return false;
        }
        final WebView.HitTestResult hit = mWebView.getHitTestResult();
        if (hit == null) {
            return false;
        }
        final float x = mLastTouchX;
        final float y = mLastTouchY;
        final String extra = hit.getExtra();

        switch (hit.getType()) {
            case WebView.HitTestResult.SRC_ANCHOR_TYPE: {
                if (extra == null || extra.isEmpty()) {
                    return false;
                }
                notifyLinkLongPressed(extra, "", x, y);
                return true;
            }
            case WebView.HitTestResult.IMAGE_TYPE: {
                if (extra == null || extra.isEmpty()) {
                    return false;
                }
                notifyLinkLongPressed("", extra, x, y);
                return true;
            }
            case WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE: {
                // SRC_IMAGE_ANCHOR: extra is img src; href via requestFocusNodeHref.
                final Message hrefMsg = new Handler(Looper.getMainLooper()) {
                    @Override
                    public void handleMessage(Message m) {
                        final Bundle data = m.getData();
                        final String href = data != null ? data.getString("url") : null;
                        final String src = data != null ? data.getString("src") : null;
                        notifyLinkLongPressed(
                                href != null ? href : "",
                                src != null ? src : (extra != null ? extra : ""),
                                x, y);
                    }
                }.obtainMessage();
                mWebView.requestFocusNodeHref(hrefMsg);
                return true;
            }
            default:
                return false;
        }
    }

    private void notifyLinkLongPressed(String linkUrl, String imageUrl, float x, float y) {
        withNativePtr(ptr -> nativeOnLinkLongPressed(ptr, linkUrl, imageUrl, x, y));
    }

    private native void nativeOnNavigationFinished(long nativePtr, String url);
    private native void nativeOnNavigationFailed(long nativePtr);
    private native void nativeOnJavaScriptResult(long nativePtr, String result, String error);
    private native void nativeOnTitleChanged(long nativePtr, String title);
    private native void nativeOnNavigationStateChanged(long nativePtr, boolean canGoBack, boolean canGoForward);
    private native void nativeOnBackRequested(long nativePtr, boolean pressed);
    private native void nativeOnHistoryChanged(long nativePtr, String[] urls, String[] titles, int currentHistoryIndex);
    private native void nativeOnNewWindowRequested(long nativePtr, String url, boolean userInitiated);
    private native void nativeOnLoadProgressChanged(long nativePtr, int progress);
    private native void nativeOnFaviconReceived(long nativePtr, String faviconUrl);
    private native void nativeOnFindResultChanged(long nativePtr, int activeMatchIndex, int matchCount);
    private native void nativeOnFreezeSnapshotReady(long nativePtr, long requestId, int width, int height,
                                                    byte[] pixels);
    private native void nativeOnClearHttpCacheCompleted(long nativePtr, long requestId);
    private native void nativeOnDeleteAllCookiesCompleted(long nativePtr, long requestId);
    private native void nativeOnClearDomStorageCompleted(long nativePtr, long requestId);
    private native void nativeOnClearSiteDataCompleted(long nativePtr, long requestId);
    private native void nativeOnDownloadDetected(long nativePtr, String url, String contentDisposition,
                                                 String mimeType, long contentLength, String userAgent);
    private native void nativeOnDownloadProgress(long nativePtr, long downloadId,
                                                 long receivedBytes, long totalBytes);
    private native void nativeOnDownloadFinished(long nativePtr, long downloadId,
                                                 boolean ok, String error);
    private native void nativeOnLinkLongPressed(long nativePtr, String linkUrl, String imageUrl,
                                                float x, float y);
}
