package androidx.webkit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ProfileStore {
    private static final ProfileStore sInstance = new ProfileStore();
    private final Map<String, Profile> mProfiles = new LinkedHashMap<>();
    // Profiles a WebView still holds: WebView refuses to delete them.
    private final Set<String> mInUse = new HashSet<>();

    private ProfileStore() {}

    public static ProfileStore getInstance() {
        return sInstance;
    }

    public Profile getOrCreateProfile(String name) {
        Profile existing = mProfiles.get(name);
        if (existing != null) {
            return existing;
        }
        Profile created = new Profile(name);
        mProfiles.put(name, created);
        return created;
    }

    public Profile getProfile(String name) {
        return mProfiles.get(name);
    }

    public boolean deleteProfile(String name) {
        if (mInUse.contains(name)) {
            throw new IllegalStateException("Cannot delete in-use profile");
        }
        return mProfiles.remove(name) != null;
    }

    // androidx.webkit returns a List here.
    public List<String> getAllProfileNames() {
        return new ArrayList<>(mProfiles.keySet());
    }

    public static void setInUse(String name, boolean inUse) {
        if (inUse) {
            sInstance.mInUse.add(name);
        } else {
            sInstance.mInUse.remove(name);
        }
    }

    public static void reset() {
        sInstance.mProfiles.clear();
        sInstance.mInUse.clear();
    }
}
