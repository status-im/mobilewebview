package androidx.webkit;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class ProfileStore {
    private static final ProfileStore sInstance = new ProfileStore();
    private final Map<String, Profile> mProfiles = new LinkedHashMap<>();

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

    public void deleteProfile(String name) {
        mProfiles.remove(name);
    }

    public Set<String> getAllProfileNames() {
        return new LinkedHashSet<>(mProfiles.keySet());
    }

    public static void reset() {
        sInstance.mProfiles.clear();
    }
}
