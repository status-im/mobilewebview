package org.mobilewebview;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.Base64;
import android.util.Log;
import android.app.Activity;
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
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;

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

    // Bridge configuration
    private String mBridgeNamespace = "qt";
    private String mInvokeKey = "";
    private List<String> mAllowedOrigins = new ArrayList<>();
    private List<String> mUserScripts = new ArrayList<>();
    private String mBootstrapPageScript = "";
    private String mBootstrapBridgeScript = "";
    private volatile String mCurrentMainFrameOrigin = "";
    private volatile boolean mBridgeInjectedForCurrentNavigation = false;

    // Navigation state
    private boolean mBridgeInstalled = false;
    private String mPendingUrl = null;

    @FunctionalInterface
    private interface NativeCallback {
        void invoke(long ptr);
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
            mWebView = new WebView(context);
            setupWebView();
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<RuntimeException> creationError = new AtomicReference<>();
        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(() -> {
            try {
                mWebView = new WebView(context);
                setupWebView();
            } catch (RuntimeException e) {
                creationError.set(e);
            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while creating WebView on main thread", e);
        }

        RuntimeException error = creationError.get();
        if (error != null) {
            throw error;
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

        new Handler(Looper.getMainLooper()).post(action);
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
        mBridgeNamespace = namespace;
        mInvokeKey = invokeKey;
        mAllowedOrigins.clear();
        mAllowedOrigins.addAll(Arrays.asList(allowedOrigins));

        mUserScripts.clear();
        mUserScripts.addAll(Arrays.asList(userScripts));

        // Store bootstrap scripts
        mBootstrapPageScript = bootstrapPageScript;
        mBootstrapBridgeScript = bootstrapBridgeScript;

        mBridgeInstalled = true;
        Log.d(TAG, "Message bridge installed: namespace=" + namespace +
                   ", invokeKey=" + invokeKey + ", origins=" + mAllowedOrigins.size());
    }

    /**
     * Update allowed origins after bridge installation (for dynamic origin changes during navigation)
     */
    public void updateAllowedOrigins(String[] origins) {
        mAllowedOrigins.clear();
        mAllowedOrigins.addAll(Arrays.asList(origins));
    }

    /**
     * Load URL in WebView
     */
    public void loadUrl(String url) {
        Log.d(TAG, "loadUrl: " + url);
        mPendingUrl = url;

        if (!mBridgeInstalled) {
            Log.w(TAG, "Bridge not installed, loading anyway");
        }

        runOnMainThread(() -> mWebView.loadUrl(url));
    }

    /**
     * Load HTML content with base URL
     */
    public void loadHtml(String html, String baseUrl) {
        Log.d(TAG, "loadHtml: baseUrl=" + baseUrl);
        mPendingUrl = baseUrl;

        if (!mBridgeInstalled) {
            Log.w(TAG, "Bridge not installed, loading anyway");
        }

        runOnMainThread(() -> mWebView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null));
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
        runOnMainThread(() ->
            mWebView.evaluateJavascript(script, result ->
                withNativePtr(ptr -> nativeOnJavaScriptResult(ptr, result != null ? result : "", ""))
            )
        );
    }

    /**
     * Post message to JavaScript WebChannel transport
     */
    public void postMessageToJavaScript(String json) {
        String deliverScript = BridgeScriptBuilder.buildDeliverScript(mBridgeNamespace, json);

        runOnMainThread(() ->
            mWebView.evaluateJavascript(deliverScript, value ->
                Log.d(TAG, "postMessageToJavaScript result: " + value)
            )
        );
    }

    /**
     * Set WebView geometry (x, y, width, height)
     */
    public void setGeometry(int x, int y, int width, int height) {
        runOnMainThread(() -> {
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
        runOnMainThread(() -> mWebView.setVisibility(visible ? View.VISIBLE : View.GONE));
    }

    public void setInteractionEnabled(boolean enabled) {
        runOnMainThread(() -> {
            if (mWebView != null) {
                mWebView.setFocusable(enabled);
                mWebView.setFocusableInTouchMode(enabled);
            }
        });
    }

    /**
     * Destroy WebView and cleanup
     */
    public void destroy() {
        mNativePtr = 0;  // zero out immediately so JNI callbacks are ignored
        runOnMainThread(() -> {
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

            // Validate origin
            if (!OriginUtils.isOriginAllowed(origin, mAllowedOrigins)) {
                Log.w(TAG, "Rejected message from disallowed origin: " + origin);
                return;
            }

            // Forward to C++ layer
            withNativePtr(ptr -> nativeOnWebMessageReceived(ptr, message, origin, false));
        }
    }

    /**
     * WebViewClient for navigation callbacks
     */
    private class MobileWebViewClient extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            Log.d(TAG, "onPageStarted: " + url);
            mBridgeInjectedForCurrentNavigation = false;
            withNativePtr(MobileWebView.this::nativeOnNavigationStarted);
            handleNavigationLifecycle(view, url, true);
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            handleNavigationLifecycle(view, url, false);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            Log.d(TAG, "onPageFinished: " + url);
            handleNavigationLifecycle(view, url, false);
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

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            // Allow navigation, but validate origin later
            return false;
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
     * Inject bootstrap scripts and user scripts at document start
     */
    private void injectBridgeScriptsOnce() {
        if (mBridgeInjectedForCurrentNavigation) {
            return;
        }

        injectBridgeScripts();
        mBridgeInjectedForCurrentNavigation = true;
    }

    private void injectBridgeScripts() {
        if (!mBridgeInstalled) {
            Log.w(TAG, "injectBridgeScripts skipped: bridge not installed");
            return;
        }
        if (mWebView == null) {
            Log.w(TAG, "injectBridgeScripts skipped: WebView is null");
            return;
        }
        Log.d(TAG, "Injecting bridge scripts: userScripts=" + mUserScripts.size()
                + ", bootstrapPageLen=" + mBootstrapPageScript.length()
                + ", bootstrapBridgeLen=" + mBootstrapBridgeScript.length());

        injectScriptIfPresent(mBootstrapPageScript, "bootstrap_page");
        injectScriptIfPresent(mBootstrapBridgeScript, "bootstrap_bridge_android");

        // Inject user scripts
        for (String scriptContent : mUserScripts) {
            if (!scriptContent.isEmpty()) {
                mWebView.evaluateJavascript(scriptContent, null);
            }
        }
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

    private void handleNavigationLifecycle(WebView view, String url, boolean warnWhenBridgeMissing) {
        mCurrentMainFrameOrigin = OriginUtils.extractOrigin(url);
        if (mBridgeInstalled) {
            injectBridgeScriptsOnce();
        } else if (warnWhenBridgeMissing) {
            Log.w(TAG, "onPageStarted: bridge not installed yet");
        }
        withNativePtr(ptr -> nativeOnNavigationStateChanged(ptr, view.canGoBack(), view.canGoForward()));
        notifyHistoryState(view);
    }

    private void injectScriptIfPresent(String script, String scriptName) {
        if (!script.isEmpty()) {
            mWebView.evaluateJavascript(script, null);
            return;
        }
        Log.w(TAG, scriptName + " script is empty");
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
    private native void nativeOnNavigationStarted(long nativePtr);
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
}
