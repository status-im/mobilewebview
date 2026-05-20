package org.mobilewebview;

import android.webkit.WebView;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class BridgeScriptInjectorTest {
    public static void main(String[] args) throws Exception {
        injectOnceNoneIsNoOp();
        injectOnceSkipsWhenBridgeNotInstalled();
        injectOnceOnPageStartedEvaluatesScripts();
        secondInjectSkippedUnlessForceReinject();
        sameUrlReloadForcesReinject();
        markForceReinjectClearsGuard();
        configureInjectionModeNullWebViewSafe();
        configureInjectionModeBridgeNotInstalled();
        onPageCommitVisibleFallbackInjects();
        documentStartPresentCheckSkipsWhenScriptsPresent();
        clearDocumentStartScriptsRemovesHandlers();
        onPageStartedSkipsWhenDocumentStartCoversOrigin();
        onPageCommitVisibleInjectsWhenScriptsNotPresent();
        onPageCommitVisibleForceReinjectWithDocumentStart();
        injectSkipsEmptyBootstrapScripts();
        onPageCommitVisibleNullWebViewSafe();
        resetForNavigationFalseDoesNotForceClearState();
        injectFiltersNullAndEmptyUserScripts();
        documentStartRegistrationIncludesMarker();
        injectNullWebViewDoesNotFlipInjectedFlag();
        System.out.println("BridgeScriptInjectorTest passed");
    }

    private static void injectOnceNoneIsNoOp() {
        FakeBridgeInjectorHost host = hostWithWebView();
        BridgeScriptInjector injector = new BridgeScriptInjector(host);

        injector.injectOnce(ScriptInjectionPhase.NONE);

        TestAssert.assertEquals(0, host.webView.evaluatedScripts().size());
    }

    private static void injectOnceSkipsWhenBridgeNotInstalled() {
        FakeBridgeInjectorHost host = hostWithWebView();
        host.state = new BridgeState(false, Collections.emptyList(), "", "", Collections.emptyList());
        BridgeScriptInjector injector = new BridgeScriptInjector(host);

        injector.injectOnce(ScriptInjectionPhase.ON_PAGE_STARTED);

        TestAssert.assertEquals(0, host.webView.evaluatedScripts().size());
    }

    private static void injectOnceOnPageStartedEvaluatesScripts() {
        FakeBridgeInjectorHost host = hostWithWebView();
        BridgeScriptInjector injector = new BridgeScriptInjector(host);

        injector.injectOnce(ScriptInjectionPhase.ON_PAGE_STARTED);

        List<String> scripts = host.webView.evaluatedScripts();
        TestAssert.assertTrue(scripts.size() >= 3);
        TestAssert.assertContains(scripts.get(0), "bootstrapPage");
        TestAssert.assertContains(scripts.get(1), "bootstrapBridge");
        TestAssert.assertContains(scripts.get(scripts.size() - 1), "__SQ_USER_SCRIPTS_LOADED__");
    }

    private static void secondInjectSkippedUnlessForceReinject() {
        FakeBridgeInjectorHost host = hostWithWebView();
        BridgeScriptInjector injector = new BridgeScriptInjector(host);

        injector.injectOnce(ScriptInjectionPhase.ON_PAGE_STARTED);
        int afterFirst = host.webView.evaluatedScripts().size();

        injector.injectOnce(ScriptInjectionPhase.ON_PAGE_STARTED);
        TestAssert.assertEquals(afterFirst, host.webView.evaluatedScripts().size());
    }

    private static void sameUrlReloadForcesReinject() {
        FakeBridgeInjectorHost host = hostWithWebView();
        BridgeScriptInjector injector = new BridgeScriptInjector(host);

        injector.injectOnce(ScriptInjectionPhase.ON_PAGE_STARTED);
        host.webView.clearEvaluatedScripts();

        injector.resetForNavigation(true);
        injector.injectOnce(ScriptInjectionPhase.ON_PAGE_STARTED);

        TestAssert.assertTrue(host.webView.evaluatedScripts().size() > 0);
        String clearScript = host.webView.evaluatedScripts().get(0);
        TestAssert.assertContains(clearScript, "delete window.__SQ_USER_SCRIPTS_LOADED__");
        assertClearScriptDoesNotReferenceEmbedderGlobals(clearScript);
    }

    private static void markForceReinjectClearsGuard() {
        FakeBridgeInjectorHost host = hostWithWebView();
        BridgeScriptInjector injector = new BridgeScriptInjector(host);

        injector.injectOnce(ScriptInjectionPhase.ON_PAGE_STARTED);
        host.webView.clearEvaluatedScripts();
        injector.markForceReinject();
        injector.injectOnce(ScriptInjectionPhase.ON_PAGE_STARTED);

        List<String> scripts = host.webView.evaluatedScripts();
        TestAssert.assertTrue(scripts.size() > 0);
        assertClearScriptDoesNotReferenceEmbedderGlobals(scripts.get(0));
    }

    private static void configureInjectionModeNullWebViewSafe() {
        FakeBridgeInjectorHost host = new FakeBridgeInjectorHost();
        host.webView = null;
        BridgeScriptInjector injector = new BridgeScriptInjector(host);
        injector.configureInjectionMode();
    }

    private static void configureInjectionModeBridgeNotInstalled() {
        FakeBridgeInjectorHost host = hostWithWebView();
        host.state = new BridgeState(false, Collections.emptyList(), "", "", Collections.emptyList());
        BridgeScriptInjector injector = new BridgeScriptInjector(host);
        injector.configureInjectionMode();
        TestAssert.assertEquals(0, host.webView.evaluatedScripts().size());
    }

    private static void onPageCommitVisibleFallbackInjects() {
        FakeBridgeInjectorHost host = hostWithWebView();
        BridgeScriptInjector injector = new BridgeScriptInjector(host);
        injector.configureInjectionMode();

        host.webView.clearEvaluatedScripts();
        injector.injectOnce(ScriptInjectionPhase.ON_PAGE_COMMIT_VISIBLE);

        TestAssert.assertTrue(host.webView.evaluatedScripts().size() > 0);
    }

    private static void documentStartPresentCheckSkipsWhenScriptsPresent() throws Exception {
        FakeBridgeInjectorHost host = hostWithWebView();
        BridgeScriptInjector injector = new BridgeScriptInjector(host);
        setUseDocumentStartInjection(injector, true);

        host.webView.setScriptResponse("__SQ_USER_SCRIPTS_LOADED__", "true");
        host.webView.clearEvaluatedScripts();

        injector.injectOnce(ScriptInjectionPhase.ON_PAGE_COMMIT_VISIBLE);

        TestAssert.assertEquals(1, host.webView.evaluatedScripts().size());
        TestAssert.assertContains(host.webView.evaluatedScripts().get(0), "__SQ_USER_SCRIPTS_LOADED__");
    }

    private static void onPageStartedSkipsWhenDocumentStartCoversOrigin() throws Exception {
        FakeBridgeInjectorHost host = hostWithWebView();
        host.currentOrigin = "https://app.example";
        BridgeScriptInjector injector = new BridgeScriptInjector(host);
        setUseDocumentStartInjection(injector, true);

        injector.injectOnce(ScriptInjectionPhase.ON_PAGE_STARTED);

        TestAssert.assertEquals(0, host.webView.evaluatedScripts().size());
    }

    private static void onPageCommitVisibleInjectsWhenScriptsNotPresent() throws Exception {
        FakeBridgeInjectorHost host = hostWithWebView();
        BridgeScriptInjector injector = new BridgeScriptInjector(host);
        setUseDocumentStartInjection(injector, true);

        host.webView.clearEvaluatedScripts();
        injector.injectOnce(ScriptInjectionPhase.ON_PAGE_COMMIT_VISIBLE);

        List<String> scripts = host.webView.evaluatedScripts();
        TestAssert.assertTrue(scripts.size() >= 4);
        TestAssert.assertContains(scripts.get(0), "__SQ_USER_SCRIPTS_LOADED__");
        TestAssert.assertContains(scripts.get(scripts.size() - 1), "__SQ_USER_SCRIPTS_LOADED__=true");
        boolean hasBootstrapPage = false;
        for (String script : scripts) {
            if (script.contains("bootstrapPage")) {
                hasBootstrapPage = true;
                break;
            }
        }
        TestAssert.assertTrue(hasBootstrapPage);
    }

    private static void onPageCommitVisibleForceReinjectWithDocumentStart() throws Exception {
        FakeBridgeInjectorHost host = hostWithWebView();
        BridgeScriptInjector injector = new BridgeScriptInjector(host);
        setUseDocumentStartInjection(injector, true);

        injector.injectOnce(ScriptInjectionPhase.ON_PAGE_STARTED);
        host.webView.setScriptResponse("__SQ_USER_SCRIPTS_LOADED__", "true");
        host.webView.clearEvaluatedScripts();
        injector.markForceReinject();

        injector.injectOnce(ScriptInjectionPhase.ON_PAGE_COMMIT_VISIBLE);

        List<String> scripts = host.webView.evaluatedScripts();
        TestAssert.assertTrue(scripts.size() > 0);
        String clearScript = scripts.get(0);
        TestAssert.assertContains(clearScript, "delete window.__SQ_USER_SCRIPTS_LOADED__");
        assertClearScriptDoesNotReferenceEmbedderGlobals(clearScript);
    }

    private static void injectSkipsEmptyBootstrapScripts() {
        FakeBridgeInjectorHost host = hostWithWebView();
        host.state = new BridgeState(
            true,
            Collections.singletonList("window.userOnly=true;"),
            null,
            "",
            Arrays.asList("https://app.example", "https://other.example"));
        BridgeScriptInjector injector = new BridgeScriptInjector(host);

        injector.injectOnce(ScriptInjectionPhase.ON_PAGE_STARTED);

        List<String> scripts = host.webView.evaluatedScripts();
        TestAssert.assertEquals(2, scripts.size());
        TestAssert.assertContains(scripts.get(0), "window.userOnly=true;");
        TestAssert.assertContains(scripts.get(1), "__SQ_USER_SCRIPTS_LOADED__");
        for (String script : scripts) {
            TestAssert.assertFalse(script.contains("bootstrapPage"));
            TestAssert.assertFalse(script.contains("bootstrapBridge"));
        }
    }

    private static void onPageCommitVisibleNullWebViewSafe() {
        FakeBridgeInjectorHost host = new FakeBridgeInjectorHost();
        host.webView = null;
        BridgeScriptInjector injector = new BridgeScriptInjector(host);

        injector.injectOnce(ScriptInjectionPhase.ON_PAGE_COMMIT_VISIBLE);
    }

    private static void resetForNavigationFalseDoesNotForceClearState() {
        FakeBridgeInjectorHost host = hostWithWebView();
        BridgeScriptInjector injector = new BridgeScriptInjector(host);

        injector.injectOnce(ScriptInjectionPhase.ON_PAGE_STARTED);
        host.webView.clearEvaluatedScripts();
        injector.resetForNavigation(false);
        injector.injectOnce(ScriptInjectionPhase.ON_PAGE_STARTED);

        List<String> scripts = host.webView.evaluatedScripts();
        TestAssert.assertTrue(scripts.size() > 0);
        TestAssert.assertFalse(scripts.get(0).contains("delete window.__SQ_USER_SCRIPTS_LOADED__"));
        TestAssert.assertContains(scripts.get(0), "bootstrapPage");
    }

    private static void injectFiltersNullAndEmptyUserScripts() {
        FakeBridgeInjectorHost host = hostWithWebView();
        host.state = new BridgeState(
            true,
            Arrays.asList("window.valid1=true;", null, "", "window.valid2=true;"),
            "window.bootstrapPage=true;",
            "window.bootstrapBridge=true;",
            Arrays.asList("https://app.example"));
        BridgeScriptInjector injector = new BridgeScriptInjector(host);

        injector.injectOnce(ScriptInjectionPhase.ON_PAGE_STARTED);

        List<String> scripts = host.webView.evaluatedScripts();
        int userScriptEvals = 0;
        for (String script : scripts) {
            if (script.contains("valid1")) {
                userScriptEvals++;
            }
            if (script.contains("valid2")) {
                userScriptEvals++;
            }
        }
        TestAssert.assertEquals(2, userScriptEvals);
        TestAssert.assertEquals(5, scripts.size());
    }

    private static void documentStartRegistrationIncludesMarker() throws Exception {
        FakeBridgeInjectorHost host = hostWithWebView();
        BridgeScriptInjector injector = new BridgeScriptInjector(host);

        injector.configureInjectionMode();

        List<String> scripts = host.webView.documentStartScripts();
        boolean hasMarker = false;
        for (String script : scripts) {
            if (script.contains("__SQ_USER_SCRIPTS_LOADED__")) {
                hasMarker = true;
                break;
            }
        }
        TestAssert.assertTrue(hasMarker);

        Field handlersField = BridgeScriptInjector.class.getDeclaredField("mDocumentStartScriptHandlers");
        handlersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Object> handlers = (List<Object>) handlersField.get(injector);
        TestAssert.assertEquals(4, handlers.size());
    }

    private static void injectNullWebViewDoesNotFlipInjectedFlag() throws Exception {
        FakeBridgeInjectorHost host = new FakeBridgeInjectorHost();
        host.webView = null;
        BridgeScriptInjector injector = new BridgeScriptInjector(host);

        Field injectedField =
            BridgeScriptInjector.class.getDeclaredField("mBridgeInjectedForCurrentNavigation");
        injectedField.setAccessible(true);
        TestAssert.assertFalse(injectedField.getBoolean(injector));

        injector.injectOnce(ScriptInjectionPhase.ON_PAGE_STARTED);
        TestAssert.assertFalse(injectedField.getBoolean(injector));

        host.webView = new WebView(new android.content.Context());
        injector.injectOnce(ScriptInjectionPhase.ON_PAGE_STARTED);
        TestAssert.assertTrue(injectedField.getBoolean(injector));
        TestAssert.assertTrue(host.webView.evaluatedScripts().size() > 0);
    }

    private static void clearDocumentStartScriptsRemovesHandlers() throws Exception {
        FakeBridgeInjectorHost host = hostWithWebView();
        BridgeScriptInjector injector = new BridgeScriptInjector(host);
        RemovableHandler handler = new RemovableHandler();

        Field handlersField = BridgeScriptInjector.class.getDeclaredField("mDocumentStartScriptHandlers");
        handlersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Object> handlers = (List<Object>) handlersField.get(injector);
        handlers.add(handler);

        injector.clearDocumentStartScripts();

        TestAssert.assertTrue(handler.removed);
        TestAssert.assertEquals(0, handlers.size());
    }

    private static void assertClearScriptDoesNotReferenceEmbedderGlobals(String script) {
        TestAssert.assertFalse(script.contains("__ETHEREUM_"));
        TestAssert.assertFalse(script.contains("__STATUS_"));
    }

    private static FakeBridgeInjectorHost hostWithWebView() {
        FakeBridgeInjectorHost host = new FakeBridgeInjectorHost();
        host.webView = new WebView(new android.content.Context());
        return host;
    }

    private static void setUseDocumentStartInjection(BridgeScriptInjector injector, boolean value)
            throws Exception {
        Field field = BridgeScriptInjector.class.getDeclaredField("mUseDocumentStartInjection");
        field.setAccessible(true);
        field.setBoolean(injector, value);
    }

    private static final class RemovableHandler {
        boolean removed;

        public void remove() {
            removed = true;
        }
    }
}
