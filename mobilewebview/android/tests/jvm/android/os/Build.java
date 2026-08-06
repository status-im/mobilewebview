package android.os;

/** Minimal JVM-test stub; not used on device. */
public final class Build {
    public static final class VERSION {
        // Pre-Q so JVM tests never enter the MediaStore publish path.
        public static final int SDK_INT = 28;
    }

    public static final class VERSION_CODES {
        public static final int Q = 29;
    }

    private Build() {
    }
}
