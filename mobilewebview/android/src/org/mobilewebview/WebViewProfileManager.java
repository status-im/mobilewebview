package org.mobilewebview;

import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebView;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;

/**
 * Configures Android WebView profiles for standard (persistent) and incognito storage.
 * Uses androidx.webkit MULTI_PROFILE when available; falls back to the default profile.
 */
final class WebViewProfileManager {
    private static final String TAG = "WebViewProfileManager";
    private static final String MULTI_PROFILE = "MULTI_PROFILE";
    private static final String INCOGNITO_PREFIX = "Incognito_";
    private static volatile boolean sSweepDone = false;

    static void ensureOrphanIncognitoProfilesSwept() {
        if (sSweepDone) {
            return;
        }
        sSweepDone = true;
        sweepOrphanedIncognitoProfiles();
    }

    static void sweepOrphanedIncognitoProfiles() {
        if (!isMultiProfileSupported()) {
            return;
        }
        try {
            Object store = getProfileStore();
            if (store == null) {
                return;
            }
            Method getAll = store.getClass().getMethod("getAllProfileNames");
            Object namesObj = getAll.invoke(store);
            if (!(namesObj instanceof Set)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Set<String> names = (Set<String>) namesObj;
            for (String name : names) {
                if (name != null && name.startsWith(INCOGNITO_PREFIX)) {
                    deleteProfile(name);
                }
            }
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "sweepOrphanedIncognitoProfiles failed", e);
        }
    }

    String configureProfile(WebView webView, String storageName, boolean offTheRecord) {
        ensureOrphanIncognitoProfilesSwept();
        if (webView == null) {
            return null;
        }
        if (!isMultiProfileSupported()) {
            if (offTheRecord) {
                Log.w(TAG, "Incognito requested but MULTI_PROFILE is not supported");
            }
            return null;
        }

        final String profileName = offTheRecord
                ? INCOGNITO_PREFIX + UUID.randomUUID()
                : (storageName != null && !storageName.isEmpty() ? storageName : null);
        if (profileName == null) {
            return null;
        }

        try {
            Object store = getProfileStore();
            if (store == null) {
                return null;
            }
            Method getOrCreate = store.getClass().getMethod("getOrCreateProfile", String.class);
            Object profile = getOrCreate.invoke(store, profileName);
            if (profile == null) {
                return null;
            }

            Class<?> compatClass = Class.forName("androidx.webkit.WebViewCompat");
            Method setProfile = compatClass.getMethod("setProfile", WebView.class, profile.getClass());
            setProfile.invoke(null, webView, profile);
            return profileName;
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "configureProfile failed", e);
            return null;
        }
    }

    void destroyProfile(String profileName, boolean offTheRecord) {
        if (profileName == null || !offTheRecord) {
            return;
        }
        deleteProfile(profileName);
    }

    void flushCookiesIfPersistent(boolean offTheRecord) {
        if (offTheRecord) {
            return;
        }
        try {
            CookieManager.getInstance().flush();
        } catch (RuntimeException e) {
            Log.w(TAG, "flushCookiesIfPersistent failed", e);
        }
    }

    private static boolean isMultiProfileSupported() {
        try {
            Class<?> featureClass = Class.forName("androidx.webkit.WebViewFeature");
            Method isSupported = featureClass.getMethod("isFeatureSupported", String.class);
            Object result = isSupported.invoke(null, MULTI_PROFILE);
            return result instanceof Boolean && (Boolean) result;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static Object getProfileStore() throws ReflectiveOperationException {
        Class<?> storeClass = Class.forName("androidx.webkit.ProfileStore");
        Method getInstance = storeClass.getMethod("getInstance");
        return getInstance.invoke(null);
    }

    private static void deleteProfile(String profileName) {
        try {
            Object store = getProfileStore();
            if (store == null) {
                return;
            }
            Method deleteProfile = store.getClass().getMethod("deleteProfile", String.class);
            deleteProfile.invoke(store, profileName);
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "deleteProfile failed for " + profileName, e);
        }
    }
}
