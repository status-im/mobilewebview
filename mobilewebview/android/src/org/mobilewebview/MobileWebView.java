package org.mobilewebview;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Rect;
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
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
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
        for (String origin : allowedOrigins) {
            mAllowedOrigins.add(origin);
        }

        mUserScripts.clear();
        for (String script : userScripts) {
            mUserScripts.add(script);
        }

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
        for (String origin : origins) {
            mAllowedOrigins.add(origin);
        }
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

    /**
     * Evaluate JavaScript and notify result via callback
     */
    public void evaluateJavaScript(String script) {
        runOnMainThread(() ->
            mWebView.evaluateJavascript(script, result ->
                safeNativeOnJavaScriptResult(result != null ? result : "", "")
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
            String origin = mCurrentMainFrameOrigin;
            if (origin == null || origin.isEmpty()) {
                String currentUrl = mWebView.getUrl();
                origin = OriginUtils.extractOrigin(currentUrl);
            }

            // Validate origin
            if (!OriginUtils.isOriginAllowed(origin, mAllowedOrigins)) {
                Log.w(TAG, "Rejected message from disallowed origin: " + origin);
                return;
            }

            // Forward to C++ layer
            safeNativeOnWebMessageReceived(message, origin, false);
        }
    }

    /**
     * WebViewClient for navigation callbacks
     */
    private class MobileWebViewClient extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            Log.d(TAG, "onPageStarted: " + url);
            mCurrentMainFrameOrigin = OriginUtils.extractOrigin(url);
            mBridgeInjectedForCurrentNavigation = false;
            safeNativeOnNavigationStarted();
            safeNativeOnNavigationStateChanged(view.canGoBack(), view.canGoForward());

            // Attempt bridge injection as early as possible for this navigation.
            if (mBridgeInstalled) {
                injectBridgeScriptsOnce();
            } else {
                Log.w(TAG, "onPageStarted: bridge not installed yet");
            }
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            mCurrentMainFrameOrigin = OriginUtils.extractOrigin(url);
            if (mBridgeInstalled) {
                injectBridgeScriptsOnce();
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            Log.d(TAG, "onPageFinished: " + url);
            mCurrentMainFrameOrigin = OriginUtils.extractOrigin(url);
            if (mBridgeInstalled) {
                // Final fallback to keep transport available even if earlier hooks were missed.
                injectBridgeScriptsOnce();
            }
            safeNativeOnNavigationFinished(url);
            safeNativeOnNavigationStateChanged(view.canGoBack(), view.canGoForward());
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                   WebResourceError error) {
            if (request.isForMainFrame()) {
                Log.e(TAG, "onReceivedError: " + error.getDescription());
                safeNativeOnNavigationFailed();
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
                safeNativeOnNewWindowRequested(requestedUrl, isUserGesture);
                return false;
            }

            return false;
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            safeNativeOnTitleChanged(title != null ? title : "");
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

        // Inject bootstrap_page.js (with namespace substitution)
        if (!mBootstrapPageScript.isEmpty()) {
            mWebView.evaluateJavascript(mBootstrapPageScript, null);
        } else {
            Log.w(TAG, "bootstrap_page script is empty");
        }

        // Inject bootstrap_bridge_android.js (with invokeKey substitution)
        if (!mBootstrapBridgeScript.isEmpty()) {
            mWebView.evaluateJavascript(mBootstrapBridgeScript, null);
        } else {
            Log.w(TAG, "bootstrap_bridge_android script is empty");
        }

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

    private void safeNativeOnWebMessageReceived(String message, String origin, boolean isMainFrame) {
        long ptr = mNativePtr;
        if (ptr != 0) {
            nativeOnWebMessageReceived(ptr, message, origin, isMainFrame);
        }
    }

    private void safeNativeOnNavigationStarted() {
        long ptr = mNativePtr;
        if (ptr != 0) {
            nativeOnNavigationStarted(ptr);
        }
    }

    private void safeNativeOnNavigationFinished(String url) {
        long ptr = mNativePtr;
        if (ptr != 0) {
            nativeOnNavigationFinished(ptr, url);
        }
    }

    private void safeNativeOnNavigationFailed() {
        long ptr = mNativePtr;
        if (ptr != 0) {
            nativeOnNavigationFailed(ptr);
        }
    }

    private void safeNativeOnJavaScriptResult(String result, String error) {
        long ptr = mNativePtr;
        if (ptr != 0) {
            nativeOnJavaScriptResult(ptr, result, error);
        }
    }

    private void safeNativeOnTitleChanged(String title) {
        long ptr = mNativePtr;
        if (ptr != 0) {
            nativeOnTitleChanged(ptr, title);
        }
    }

    private void safeNativeOnNavigationStateChanged(boolean canGoBack, boolean canGoForward) {
        long ptr = mNativePtr;
        if (ptr != 0) {
            nativeOnNavigationStateChanged(ptr, canGoBack, canGoForward);
        }
    }

    private void safeNativeOnNewWindowRequested(String url, boolean userInitiated) {
        long ptr = mNativePtr;
        if (ptr != 0) {
            nativeOnNewWindowRequested(ptr, url, userInitiated);
        }
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
    private native void nativeOnNewWindowRequested(long nativePtr, String url, boolean userInitiated);
}
