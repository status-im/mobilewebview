package org.mobilewebview;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import android.graphics.Rect;
import android.util.Base64;
import android.util.Log;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebBackForwardList;
import android.webkit.WebHistoryItem;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * MobileWebView - Native Android WebView wrapper for Qt integration
 * Provides QWebChannel bridge, user script injection, and origin validation
 */
public class MobileWebView {
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
    private volatile String mCurrentMainFrameOrigin = "";
    private volatile boolean mBridgeInjectedForCurrentNavigation = false;
    /** Same-URL reload/back-forward: force evaluateJavascript reinject (ignore stale script markers). */
    private volatile boolean mForceScriptReinject = false;
    private volatile String mActiveNavigationUrl = "";
    private final List<Object> mDocumentStartScriptHandlers = new ArrayList<>();
    private volatile boolean mUseDocumentStartInjection = false;

    /** True when document-start or evaluateJavascript already loaded user scripts. */
    private static final String SCRIPTS_ALREADY_PRESENT_JS =
        "(function(){"
            + "if(window.__SQ_USER_SCRIPTS_LOADED__)return true;"
            + "if(window.__ETHEREUM_WRAPPER_INSTANCE__)return true;"
            + "return false;"
            + "})();";

    private static final String MARK_USER_SCRIPTS_LOADED_JS =
        "window.__SQ_USER_SCRIPTS_LOADED__=true;";

    /** Clears inject markers so reload of the same URL can reinstall provider scripts. */
    private static final String CLEAR_INJECT_STATE_JS =
        "(function(){try{"
            + "delete window.__SQ_USER_SCRIPTS_LOADED__;"
            + "delete window.__ETHEREUM_WRAPPER_INSTANCE__;"
            + "delete window.__ETHEREUM_INSTALLED__;"
            + "delete window.__STATUS_ETHEREUM_INJECTOR_INIT__;"
            + "delete window.__STATUS_QWEBCHANNEL_CONNECTED__;"
            + "var el=document.documentElement;"
            + "if(el&&el.dataset){delete el.dataset.sqBridgeReady;}"
            + "}catch(e){}})();";

    // Navigation state
    private boolean mBridgeInstalled = false;
    private String mPendingUrl = null;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final PendingActionQueue mPendingActionQueue = new PendingActionQueue();

    @FunctionalInterface
    private interface NativeCallback {
        void invoke(long ptr);
    }

    private enum ScriptInjectionPhase {
        NONE,
        ON_PAGE_STARTED,
        ON_PAGE_COMMIT_VISIBLE
    }

    /**
     * Constructor - creates and initializes WebView
     */
    public MobileWebView(Context context, long nativePtr, View rootView) {
        mContext = context;
        mNativePtr = nativePtr;
        mRootView = resolveRootView(rootView);

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
            mWebView = new WebView(context);
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
        settings.setAllowFileAccess(false);
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

        // Set WebViewClient for navigation callbacks
        mWebView.setWebViewClient(new MobileWebViewClient());

        // Set WebChromeClient for console messages
        mWebView.setWebChromeClient(new MobileWebChromeClient());

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

        // JavaScript interface for QWebChannel bridge
        mWebView.addJavascriptInterface(new NativeBridge(), "NativeBridge");
    }

    /**
     * Install WebChannel message bridge with user scripts
     */
    public void installMessageBridge(String namespace, String[] allowedOrigins,
                                     String invokeKey, String[] userScripts,
                                     String bootstrapPageScript, String bootstrapBridgeScript) {
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
            mBridgeInstalled = true;
        }
        runOnMainThread(this::configureBridgeInjectionMode);
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
        runOnMainThread(this::configureBridgeInjectionMode);
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
                mBridgeInjectedForCurrentNavigation = false;
                mForceScriptReinject = true;
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

    public void setZoomFactor(float factor) {
        runOnMainThread(() -> {
            if (mWebView == null) return;

            String js = "document.documentElement.style.zoom = '" + factor + "'";
            mWebView.evaluateJavascript(js, null);
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
                nativeOnFreezeSnapshotReady(mNativePtr, requestId, 0, 0, null);
                return;
            }
            try {
                int w = mWebView.getWidth();
                int h = mWebView.getHeight();
                if (w <= 0 || h <= 0) {
                    nativeOnFreezeSnapshotReady(mNativePtr, requestId, 0, 0, null);
                    return;
                }
                Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(android.graphics.Color.WHITE);
                Canvas canvas = new Canvas(bitmap);
                mWebView.draw(canvas);
                ByteBuffer buffer = ByteBuffer.allocateDirect(w * h * 4);
                buffer.order(ByteOrder.nativeOrder());
                bitmap.copyPixelsToBuffer(buffer);
                byte[] pixels = new byte[w * h * 4];
                buffer.rewind();
                buffer.get(pixels);
                bitmap.recycle();
                nativeOnFreezeSnapshotReady(mNativePtr, requestId, w, h, pixels);
            } catch (RuntimeException e) {
                Log.e(TAG, "captureSnapshotForFreeze failed", e);
                nativeOnFreezeSnapshotReady(mNativePtr, requestId, 0, 0, null);
            }
        });
    }

    public void setInteractionEnabled(boolean enabled) {
        runOnMainThread(() -> {
            if (mWebView == null) return;
            mWebView.setFocusable(enabled);
            mWebView.setFocusableInTouchMode(enabled);
        });
    }

    /**
     * Destroy WebView and cleanup
     */
    public void destroy() {
        mNativePtr = 0;  // zero out immediately so JNI callbacks are ignored
        runOnMainThread(() -> {
            clearDocumentStartScripts();
            if (mWebView != null) {
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
        });
    }

    /**
     * Get WebView instance for adding to view hierarchy
     */
    public WebView getWebView() {
        return mWebView;
    }

    /**
     * JavaScript interface for Qt bridge
     */
    private class NativeBridge {
        /**
         * Called from JavaScript via NativeBridge.postMessage()
         */
        @JavascriptInterface
        public void postMessage(String message) {
            if (!hasNativePtr()) {
                return;
            }

            // Prefer tracked main-frame origin to avoid transient URL mismatches during redirects.
            String resolvedOrigin = mCurrentMainFrameOrigin;
            if (resolvedOrigin == null || resolvedOrigin.isEmpty()) {
                String currentUrl = mWebView.getUrl();
                resolvedOrigin = OriginUtils.extractOrigin(currentUrl);
            }
            final String origin = resolvedOrigin;

            final List<String> allowedOrigins;
            synchronized (mBridgeLock) {
                allowedOrigins = new ArrayList<>(mAllowedOrigins);
            }
            if (!OriginUtils.isOriginAllowed(origin, allowedOrigins)) {
                Log.w(TAG, "Rejected message from disallowed origin: " + origin);
                return;
            }

            // Forward to C++ layer
            withNativePtr(ptr -> nativeOnWebMessageReceived(ptr, message, origin, false));
        }
    }

    /**
     * Handles custom URL schemes (intent, tel, mailto, geo, market, etc.).
     * @return true if navigation was handled or cancelled; false to let WebView load
     */
    private static boolean handleCustomSchemeUrl(WebView view, Uri uri) {
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

    /**
     * WebViewClient for navigation callbacks
     */
    private class MobileWebViewClient extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            Log.d(TAG, "onPageStarted: " + url);
            final String navUrl = url != null ? url : "";
            final boolean sameUrlReload = navUrl.equals(mActiveNavigationUrl);
            mActiveNavigationUrl = navUrl;
            mBridgeInjectedForCurrentNavigation = false;
            if (sameUrlReload) {
                mForceScriptReinject = true;
            }
            withNativePtr(ptr -> nativeOnNavigationStarted(ptr, navUrl));
            handleNavigationLifecycle(view, url, true, ScriptInjectionPhase.ON_PAGE_STARTED);
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            handleNavigationLifecycle(view, url, false, ScriptInjectionPhase.ON_PAGE_COMMIT_VISIBLE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            Log.d(TAG, "onPageFinished: " + url);
            handleNavigationLifecycle(view, url, false, ScriptInjectionPhase.NONE);
            withNativePtr(ptr -> nativeOnNavigationFinished(ptr, url));
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                   WebResourceError error) {
            if (request.isForMainFrame()) {
                Log.e(TAG, "onReceivedError: " + error.getDescription());
                withNativePtr(MobileWebView.this::nativeOnNavigationFailed);
            }
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (url == null) {
                return false;
            }
            // Pre-24 only; no isForMainFrame / hasGesture — stricter only on API 24+.
            return handleCustomSchemeUrl(view, Uri.parse(url));
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (request == null || !request.isForMainFrame() || request.getUrl() == null) {
                return false;
            }
            Uri u = request.getUrl();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                String sch = u.getScheme();
                String sl = sch != null ? sch.toLowerCase(Locale.ROOT) : "";
                if (!"intent".equals(sl) && !request.hasGesture()) {
                    return false;
                }
            }
            return handleCustomSchemeUrl(view, u);
        }
    }

    /**
     * WebChromeClient for console messages
     */
    private class MobileWebChromeClient extends WebChromeClient {
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
            WebView.HitTestResult hitTestResult = view.getHitTestResult();
            String requestedUrl = hitTestResult != null ? hitTestResult.getExtra() : null;

            if (requestedUrl != null && !requestedUrl.isEmpty()) {
                withNativePtr(ptr -> nativeOnNewWindowRequested(ptr, requestedUrl, isUserGesture));
                return false;
            }

            return false;
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            withNativePtr(ptr -> nativeOnTitleChanged(ptr, title != null ? title : ""));
        }

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            withNativePtr(ptr -> nativeOnLoadProgressChanged(ptr, newProgress));
        }

        @Override
        public void onReceivedIcon(WebView view, Bitmap icon) {
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
        public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
            Log.d(TAG, "Console [" + consoleMessage.messageLevel() + "]: " +
                       consoleMessage.message() + " -- From line " +
                       consoleMessage.lineNumber() + " of " +
                       consoleMessage.sourceId());
            return true;
        }
    }

    /**
     * Inject bootstrap and user scripts via evaluateJavascript when document-start
     * is unavailable or did not cover the current origin.
     */
    private void injectBridgeScriptsOnce() {
        if (mBridgeInjectedForCurrentNavigation && !mForceScriptReinject) {
            return;
        }

        if (!mForceScriptReinject
                && mUseDocumentStartInjection
                && isOriginCoveredByAllowedOrigins(mCurrentMainFrameOrigin)) {
            return;
        }

        injectBridgeScripts(() -> {
            mBridgeInjectedForCurrentNavigation = true;
            mForceScriptReinject = false;
        });
    }

    /**
     * Fallback on onPageCommitVisible: inject only if document-start did not set bridge ready.
     */
    private void injectBridgeScriptsOnceWithFallbackCheck() {
        if (mBridgeInjectedForCurrentNavigation && !mForceScriptReinject) {
            return;
        }
        if (mWebView == null) {
            return;
        }

        if (!mUseDocumentStartInjection || mForceScriptReinject) {
            injectBridgeScriptsOnce();
            return;
        }

        mWebView.evaluateJavascript(SCRIPTS_ALREADY_PRESENT_JS, value -> {
            if (mBridgeInjectedForCurrentNavigation && !mForceScriptReinject) {
                return;
            }
            if ("true".equals(value) && !mForceScriptReinject) {
                mBridgeInjectedForCurrentNavigation = true;
                return;
            }
            injectBridgeScripts(() -> {
                mBridgeInjectedForCurrentNavigation = true;
                mForceScriptReinject = false;
            });
        });
    }

    private void injectBridgeScripts(Runnable onComplete) {
        final boolean installed;
        final List<String> userScripts;
        final String pageScript;
        final String bridgeScript;
        synchronized (mBridgeLock) {
            installed = mBridgeInstalled;
            userScripts = new ArrayList<>(mUserScripts);
            pageScript = mBootstrapPageScript;
            bridgeScript = mBootstrapBridgeScript;
        }
        if (!installed) {
            Log.w(TAG, "injectBridgeScripts skipped: bridge not installed");
            return;
        }
        if (mWebView == null) {
            Log.w(TAG, "injectBridgeScripts skipped: WebView is null");
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }

        final Runnable injectBody = () -> {
            injectScriptIfPresent(pageScript, "bootstrap_page");
            injectScriptIfPresent(bridgeScript, "bootstrap_bridge_android");

            for (String scriptContent : userScripts) {
                if (scriptContent != null && !scriptContent.isEmpty()) {
                    mWebView.evaluateJavascript(scriptContent, null);
                }
            }
            mWebView.evaluateJavascript(MARK_USER_SCRIPTS_LOADED_JS, value -> {
                if (onComplete != null) {
                    onComplete.run();
                }
            });
        };

        if (mForceScriptReinject) {
            mWebView.evaluateJavascript(CLEAR_INJECT_STATE_JS, value -> injectBody.run());
            return;
        }

        injectBody.run();
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
                configureBridgeInjectionMode();
            }
        }

        if (installed) {
            switch (injectionPhase) {
                case ON_PAGE_STARTED:
                    injectBridgeScriptsOnce();
                    break;
                case ON_PAGE_COMMIT_VISIBLE:
                    injectBridgeScriptsOnceWithFallbackCheck();
                    break;
                case NONE:
                default:
                    break;
            }
        } else if (warnWhenBridgeMissing) {
            Log.w(TAG, "onPageStarted: bridge not installed yet");
        }
        withNativePtr(ptr -> nativeOnNavigationStateChanged(ptr, view.canGoBack(), view.canGoForward()));
        notifyHistoryState(view);
    }

    private boolean isOriginCoveredByAllowedOrigins(String origin) {
        synchronized (mBridgeLock) {
            return OriginUtils.isOriginAllowed(origin, mAllowedOrigins);
        }
    }

    private void injectScriptIfPresent(String script, String scriptName) {
        if (script != null && !script.isEmpty()) {
            mWebView.evaluateJavascript(script, null);
            return;
        }
        Log.w(TAG, scriptName + " script is empty");
    }

    private void configureBridgeInjectionMode() {
        if (mWebView == null) {
            return;
        }
        final boolean installed;
        synchronized (mBridgeLock) {
            installed = mBridgeInstalled;
        }
        if (!installed) {
            return;
        }

        final boolean supportsDocumentStart = supportsDocumentStartScript();
        if (!supportsDocumentStart) {
            mUseDocumentStartInjection = false;
            clearDocumentStartScripts();
            Log.i(TAG, "DOCUMENT_START_SCRIPT unavailable; using onPageStarted fallback injection");
            return;
        }

        boolean registeredOk;
        try {
            registeredOk = registerDocumentStartScripts();
        } catch (RuntimeException ignored) {
            clearDocumentStartScripts();
            registeredOk = false;
        }
        mUseDocumentStartInjection = registeredOk;
    }

    private boolean registerDocumentStartScripts() {
        clearDocumentStartScripts();

        final List<String> originsSnap;
        final List<String> userScriptsSnap;
        final String pageScript;
        final String bridgeScript;
        synchronized (mBridgeLock) {
            originsSnap = new ArrayList<>(mAllowedOrigins);
            userScriptsSnap = new ArrayList<>(mUserScripts);
            pageScript = mBootstrapPageScript;
            bridgeScript = mBootstrapBridgeScript;
        }

        final Set<String> allowedOriginRules = buildAllowedOriginRules(originsSnap);
        boolean ok = addDocumentStartScriptIfPresent(pageScript, allowedOriginRules)
            && addDocumentStartScriptIfPresent(bridgeScript, allowedOriginRules);

        for (String scriptContent : userScriptsSnap) {
            if (scriptContent == null || scriptContent.isEmpty()) {
                continue;
            }
            ok = ok && addDocumentStartScriptIfPresent(scriptContent, allowedOriginRules);
        }

        if (!ok) {
            Log.w(TAG, "document-start registration failed; using onPageStarted fallback injection");
            clearDocumentStartScripts();
        }
        return ok;
    }

    private boolean addDocumentStartScriptIfPresent(String script, Set<String> allowedOriginRules) {
        if (script == null || script.isEmpty()) {
            return true;
        }

        Object handler = addDocumentStartJavaScript(script, allowedOriginRules);
        if (handler != null) {
            mDocumentStartScriptHandlers.add(handler);
            return true;
        }
        Log.w(TAG, "Skipping document-start script: WebViewCompat.addDocumentStartJavaScript failed");
        return false;
    }

    private static Set<String> buildAllowedOriginRules(List<String> allowedOrigins) {
        Set<String> allowedOriginRules = new HashSet<>();
        // Wallet/bootstrap scripts must run on redirect targets; NativeBridge still validates origins.
        allowedOriginRules.add("https://*");
        allowedOriginRules.add("http://*");
        for (String origin : allowedOrigins) {
            if (origin != null && !origin.isEmpty()) {
                allowedOriginRules.add(origin);
            }
        }

        if (allowedOriginRules.isEmpty()) {
            allowedOriginRules.add("*");
        }
        return allowedOriginRules;
    }

    private void clearDocumentStartScripts() {
        for (Object handler : mDocumentStartScriptHandlers) {
            if (handler == null) {
                continue;
            }
            try {
                Method remove = handler.getClass().getMethod("remove");
                remove.invoke(handler);
            } catch (Exception e) {
                Log.w(TAG, "Failed to remove document-start script", e);
            }
        }
        mDocumentStartScriptHandlers.clear();
    }

    private static boolean supportsDocumentStartScript() {
        try {
            Class<?> featureClass = Class.forName("androidx.webkit.WebViewFeature");
            Object featureName = featureClass.getField("DOCUMENT_START_SCRIPT").get(null);
            Object supported = featureClass
                .getMethod("isFeatureSupported", String.class)
                .invoke(null, featureName);
            return supported instanceof Boolean && (Boolean) supported;
        } catch (Throwable t) {
            return false;
        }
    }

    private Object addDocumentStartJavaScript(String script, Set<String> allowedOriginRules) {
        try {
            Class<?> compatClass = Class.forName("androidx.webkit.WebViewCompat");
            return compatClass
                .getMethod("addDocumentStartJavaScript", WebView.class, String.class, Set.class)
                .invoke(null, mWebView, script, allowedOriginRules);
        } catch (Throwable t) {
            return null;
        }
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
    private native void nativeOnNavigationFinished(long nativePtr, String url);
    private native void nativeOnNavigationFailed(long nativePtr);
    private native void nativeOnJavaScriptResult(long nativePtr, String result, String error);
    private native void nativeOnTitleChanged(long nativePtr, String title);
    private native void nativeOnNavigationStateChanged(long nativePtr, boolean canGoBack, boolean canGoForward);
    private native void nativeOnHistoryChanged(long nativePtr, String[] urls, String[] titles, int currentHistoryIndex);
    private native void nativeOnNewWindowRequested(long nativePtr, String url, boolean userInitiated);
    private native void nativeOnLoadProgressChanged(long nativePtr, int progress);
    private native void nativeOnFaviconReceived(long nativePtr, String faviconUrl);
    private native void nativeOnFindResultChanged(long nativePtr, int activeMatchIndex, int matchCount);
    private native void nativeOnFreezeSnapshotReady(long nativePtr, long requestId, int width, int height,
                                                    byte[] pixels);
}
