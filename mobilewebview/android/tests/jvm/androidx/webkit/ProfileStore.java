package androidx.webkit;

import java.util.LinkedHashSet;
import java.util.Set;

public final class ProfileStore {
    private static final ProfileStore sInstance = new ProfileStore();
    private final Set<String> mProfiles = new LinkedHashSet<>();

    private ProfileStore() {}

    public static ProfileStore getInstance() {
        return sInstance;
    }

    public Profile getOrCreateProfile(String name) {
        mProfiles.add(name);
        return new Profile(name);
    }

    public void deleteProfile(String name) {
        mProfiles.remove(name);
    }

    public Set<String> getAllProfileNames() {
        return new LinkedHashSet<>(mProfiles);
    }

    public static void reset() {
        sInstance.mProfiles.clear();
    }
}
