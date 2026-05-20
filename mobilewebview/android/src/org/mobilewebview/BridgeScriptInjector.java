package org.mobilewebview;

import android.util.Log;
import android.webkit.WebView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class BridgeScriptInjector {
    private static final String TAG = "MobileWebView";

    /** True when document-start or evaluateJavascript already loaded user scripts. */
    private static final String SCRIPTS_ALREADY_PRESENT_JS =
        "(function(){return !!window.__SQ_USER_SCRIPTS_LOADED__;})();";

    private static final String MARK_USER_SCRIPTS_LOADED_JS =
        "window.__SQ_USER_SCRIPTS_LOADED__=true;";

    /** Clears mobilewebview inject markers so reload of the same URL can reinject bridge scripts. */
    private static final String CLEAR_INJECT_STATE_JS =
        "(function(){try{"
            + "delete window.__SQ_USER_SCRIPTS_LOADED__;"
            + "var el=document.documentElement;"
            + "if(el&&el.dataset){delete el.dataset.sqBridgeReady;}"
            + "}catch(e){}})();";

    private final BridgeInjectorHost mHost;

    private volatile boolean mBridgeInjectedForCurrentNavigation = false;
    /** Same-URL reload/back-forward: force evaluateJavascript reinject (ignore stale script markers). */
    private volatile boolean mForceScriptReinject = false;
    private final List<Object> mDocumentStartScriptHandlers = new ArrayList<>();
    private volatile boolean mUseDocumentStartInjection = false;

    BridgeScriptInjector(BridgeInjectorHost host) {
        mHost = host;
    }

    void resetForNavigation(boolean sameUrlReload) {
        mBridgeInjectedForCurrentNavigation = false;
        if (sameUrlReload) {
            mForceScriptReinject = true;
        }
    }

    void markForceReinject() {
        mBridgeInjectedForCurrentNavigation = false;
        mForceScriptReinject = true;
    }

    void injectOnce(ScriptInjectionPhase phase) {
        switch (phase) {
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
    }

    void configureInjectionMode() {
        WebView webView = mHost.webView();
        if (webView == null) {
            return;
        }
        BridgeState state = mHost.snapshot();
        if (!state.bridgeInstalled) {
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

    void clearDocumentStartScripts() {
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
                && isOriginCoveredByAllowedOrigins(mHost.currentOrigin())) {
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
        WebView webView = mHost.webView();
        if (webView == null) {
            return;
        }

        if (!mUseDocumentStartInjection || mForceScriptReinject) {
            injectBridgeScriptsOnce();
            return;
        }

        webView.evaluateJavascript(SCRIPTS_ALREADY_PRESENT_JS, value -> {
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
        BridgeState state = mHost.snapshot();
        if (!state.bridgeInstalled) {
            Log.w(TAG, "injectBridgeScripts skipped: bridge not installed");
            return;
        }
        WebView webView = mHost.webView();
        if (webView == null) {
            Log.w(TAG, "injectBridgeScripts skipped: WebView is null");
            return;
        }

        final Runnable injectBody = () -> {
            injectScriptIfPresent(webView, state.bootstrapPageScript, "bootstrap_page");
            injectScriptIfPresent(webView, state.bootstrapBridgeScript, "bootstrap_bridge_android");

            for (String scriptContent : state.userScripts) {
                if (scriptContent != null && !scriptContent.isEmpty()) {
                    webView.evaluateJavascript(scriptContent, null);
                }
            }
            webView.evaluateJavascript(MARK_USER_SCRIPTS_LOADED_JS, value -> {
                if (onComplete != null) {
                    onComplete.run();
                }
            });
        };

        if (mForceScriptReinject) {
            webView.evaluateJavascript(CLEAR_INJECT_STATE_JS, value -> injectBody.run());
            return;
        }

        injectBody.run();
    }

    private boolean isOriginCoveredByAllowedOrigins(String origin) {
        return OriginUtils.isOriginAllowed(origin, mHost.snapshot().allowedOrigins);
    }

    private void injectScriptIfPresent(WebView webView, String script, String scriptName) {
        if (script != null && !script.isEmpty()) {
            webView.evaluateJavascript(script, null);
            return;
        }
        Log.w(TAG, scriptName + " script is empty");
    }

    private boolean registerDocumentStartScripts() {
        clearDocumentStartScripts();

        BridgeState state = mHost.snapshot();
        final Set<String> allowedOriginRules = buildAllowedOriginRules(state.allowedOrigins);
        boolean ok = addDocumentStartScriptIfPresent(state.bootstrapPageScript, allowedOriginRules)
            && addDocumentStartScriptIfPresent(state.bootstrapBridgeScript, allowedOriginRules);

        for (String scriptContent : state.userScripts) {
            if (scriptContent == null || scriptContent.isEmpty()) {
                continue;
            }
            ok = ok && addDocumentStartScriptIfPresent(scriptContent, allowedOriginRules);
        }

        final String markScript =
            "(function(){try{" + MARK_USER_SCRIPTS_LOADED_JS + "}catch(e){}})();";
        ok = ok && addDocumentStartScriptIfPresent(markScript, allowedOriginRules);

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
        WebView webView = mHost.webView();
        if (webView == null) {
            return null;
        }
        try {
            Class<?> compatClass = Class.forName("androidx.webkit.WebViewCompat");
            return compatClass
                .getMethod("addDocumentStartJavaScript", WebView.class, String.class, Set.class)
                .invoke(null, webView, script, allowedOriginRules);
        } catch (Throwable t) {
            return null;
        }
    }
}
