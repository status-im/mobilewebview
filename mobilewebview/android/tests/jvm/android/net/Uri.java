package android.net;

/** Minimal JVM-test stub; not used on device. */
public final class Uri {
    private final String mValue;

    private Uri(String value) {
        mValue = value;
    }

    public static Uri parse(String value) {
        return new Uri(value);
    }

    @Override
    public String toString() {
        return mValue;
    }
}
