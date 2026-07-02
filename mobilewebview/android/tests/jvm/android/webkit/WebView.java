package android.webkit;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Fake WebView for JVM unit tests; not used on device. */
public class WebView {
    private final List<String> mEvaluatedScripts = new ArrayList<>();
    private final List<String> mDocumentStartScripts = new ArrayList<>();
    private final Map<String, String> mScriptResponses = new HashMap<>();
    private final WebSettings mSettings = new WebSettings();

    private static int sClearCacheCount = 0;
    private static boolean sLastClearCacheIncludeDiskFiles = false;
    private static int sReloadCount = 0;

    public WebView(Context context) {}

    public WebSettings getSettings() {
        return mSettings;
    }

    public void clearCache(boolean includeDiskFiles) {
        ++sClearCacheCount;
        sLastClearCacheIncludeDiskFiles = includeDiskFiles;
    }

    public void reload() {
        ++sReloadCount;
    }

    public static int clearCacheCount() {
        return sClearCacheCount;
    }

    public static boolean lastClearCacheIncludeDiskFiles() {
        return sLastClearCacheIncludeDiskFiles;
    }

    public static int reloadCount() {
        return sReloadCount;
    }

    public static void resetDataClearCounters() {
        sClearCacheCount = 0;
        sLastClearCacheIncludeDiskFiles = false;
        sReloadCount = 0;
    }

    public void evaluateJavascript(String script, ValueCallback<String> callback) {
        mEvaluatedScripts.add(script);
        if (callback != null) {
            String response = mScriptResponses.get(script);
            if (response == null) {
                for (Map.Entry<String, String> e : mScriptResponses.entrySet()) {
                    if (script.contains(e.getKey())) {
                        response = e.getValue();
                        break;
                    }
                }
            }
            if (response == null) {
                response = "false";
            }
            callback.onReceiveValue(response);
        }
    }

    public List<String> evaluatedScripts() {
        return mEvaluatedScripts;
    }

    public void clearEvaluatedScripts() {
        mEvaluatedScripts.clear();
    }

    public void setScriptResponse(String scriptSubstring, String response) {
        mScriptResponses.put(scriptSubstring, response);
    }

    public Object addDocumentStartJavaScript(String script) {
        mDocumentStartScripts.add(script);
        return new DocumentStartScriptHandler();
    }

    public List<String> documentStartScripts() {
        return mDocumentStartScripts;
    }

    private static final class DocumentStartScriptHandler {
        boolean removed;

        public void remove() {
            removed = true;
        }
    }
}
