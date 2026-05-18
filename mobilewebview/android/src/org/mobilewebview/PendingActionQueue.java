package org.mobilewebview;

import java.util.ArrayDeque;

final class PendingActionQueue {
    private final Object mLock = new Object();
    private final ArrayDeque<Runnable> mPendingActions = new ArrayDeque<>();
    private boolean mReady = false;

    boolean enqueueIfNotReady(Runnable action) {
        synchronized (mLock) {
            if (mReady) {
                return false;
            }
            mPendingActions.addLast(action);
            return true;
        }
    }

    void markReady() {
        ArrayDeque<Runnable> actionsToRun;
        synchronized (mLock) {
            if (mReady) {
                return;
            }
            mReady = true;
            actionsToRun = new ArrayDeque<>(mPendingActions);
            mPendingActions.clear();
        }

        while (!actionsToRun.isEmpty()) {
            actionsToRun.removeFirst().run();
        }
    }
}
