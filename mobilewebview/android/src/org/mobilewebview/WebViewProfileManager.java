package org.mobilewebview;

import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
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
    private static final int MAX_DELETE_ATTEMPTS = 5;
    private static final long DELETE_RETRY_MS = 1000;

    /** Runs a delayed task on the main thread; replaceable for tests. */
    interface RetryScheduler {
        void postDelayed(Runnable task, long delayMs);
    }

    private static RetryScheduler sRetryScheduler = (task, delayMs) ->
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(task, delayMs);

    static void setRetryScheduler(RetryScheduler scheduler) {
        sRetryScheduler = scheduler;
    }

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
            // androidx.webkit returns a List.
            Object namesObj = getAll.invoke(store);
            if (!(namesObj instanceof Collection)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Collection<String> names = new ArrayList<>((Collection<String>) namesObj);
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
            Method setProfile = compatClass.getMethod("setProfile", WebView.class, String.class);
            setProfile.invoke(null, webView, profileName);
            return profileName;
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "configureProfile failed", e);
            return null;
        }
    }

    /** What a data clear acts on; skip when its profile is gone with its view. */
    static final class ClearTarget {
        final boolean skip;
        final androidx.webkit.Profile profile;

        ClearTarget(boolean skip, androidx.webkit.Profile profile) {
            this.skip = skip;
            this.profile = profile;
        }
    }

    /**
     * The live view's profile; while the view is gone (renderer loss), its
     * named profile, never the process default; an incognito profile dies with
     * its view, so there is nothing left to clear. Null profile = the default.
     */
    ClearTarget clearTarget(WebView webView, String profileName, boolean offTheRecord) {
        if (!isMultiProfileSupported()) {
            return new ClearTarget(false, null);
        }
        try {
            if (webView != null) {
                Class<?> compatClass = Class.forName("androidx.webkit.WebViewCompat");
                Object profile = compatClass.getMethod("getProfile", WebView.class).invoke(null, webView);
                return new ClearTarget(false,
                        profile instanceof androidx.webkit.Profile ? (androidx.webkit.Profile) profile : null);
            }
            if (offTheRecord) {
                return new ClearTarget(true, null);
            }
            if (profileName == null) {
                return new ClearTarget(false, null);
            }
            Object store = getProfileStore();
            Object profile = store != null
                    ? store.getClass().getMethod("getProfile", String.class).invoke(store, profileName)
                    : null;
            if (profile instanceof androidx.webkit.Profile) {
                return new ClearTarget(false, (androidx.webkit.Profile) profile);
            }
            return new ClearTarget(true, null);
        } catch (ReflectiveOperationException e) {
            Log.e(TAG, "clearTarget failed", e);
            return new ClearTarget(webView == null, null);
        }
    }

    /**
     * Deletes an incognito profile. WebView releases a destroyed view's profile
     * only later, so a delete refused while it is still in use is retried; one
     * never released is left to the next process's sweep.
     */
    void destroyProfile(String profileName, boolean offTheRecord) {
        if (profileName == null || !offTheRecord) {
            return;
        }
        deleteWhenReleased(profileName, 1);
    }

    private static void deleteWhenReleased(String profileName, int attempt) {
        if (deleteProfile(profileName) || attempt >= MAX_DELETE_ATTEMPTS) {
            return;
        }
        sRetryScheduler.postDelayed(() -> deleteWhenReleased(profileName, attempt + 1),
                DELETE_RETRY_MS * attempt);
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

    /** False while WebView still holds the profile ("Cannot delete in-use profile"). */
    private static boolean deleteProfile(String profileName) {
        try {
            Object store = getProfileStore();
            if (store == null) {
                return true;
            }
            Method deleteProfile = store.getClass().getMethod("deleteProfile", String.class);
            deleteProfile.invoke(store, profileName);
            return true;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Log.w(TAG, "deleteProfile deferred for " + profileName + ": " + e.getCause());
            return false;
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "deleteProfile failed for " + profileName, e);
            return true;
        }
    }
}
