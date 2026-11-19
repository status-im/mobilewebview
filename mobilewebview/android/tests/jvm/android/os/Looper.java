package android.os;

/** JVM test stub. */
public final class Looper {
    private static final Looper sMain = new Looper();

    public static Looper getMainLooper() {
        return sMain;
    }
}
