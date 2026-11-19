package android.os;

/** JVM test stub: tasks are dropped. */
public class Handler {
    public Handler(Looper looper) {}

    public boolean postDelayed(Runnable r, long delayMillis) {
        return true;
    }
}
