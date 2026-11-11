package org.mobilewebview;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

/**
 * The one Binder shape between the UI process and the browser process, both ways:
 * {@code call(method, args)}. Calls to the browser process are synchronous (they only post to its
 * main thread, so they return at once, and calls from one thread stay in order); callbacks to the
 * UI process are one-way (in order per binder).
 */
final class BrowserIpc {
    private static final String TAG = "MobileWebView";
    private static final int CALL = IBinder.FIRST_CALL_TRANSACTION;

    private BrowserIpc() {}

    /** False when the other process is gone. */
    static boolean send(IBinder target, String method, Bundle args, boolean oneway) {
        if (target == null) return false;
        final Parcel data = Parcel.obtain();
        final Parcel reply = oneway ? null : Parcel.obtain();
        try {
            data.writeString(method);
            data.writeBundle(args);
            target.transact(CALL, data, reply, oneway ? IBinder.FLAG_ONEWAY : 0);
            return true;
        } catch (RemoteException e) {
            Log.w(TAG, "browser ipc " + method + " failed", e);
            return false;
        } finally {
            data.recycle();
            if (reply != null) reply.recycle();
        }
    }

    abstract static class Receiver extends Binder {
        protected abstract void onCall(String method, Bundle args);

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code != CALL) return super.onTransact(code, data, reply, flags);
            if (Binder.getCallingUid() != Process.myUid()) {
                Log.w(TAG, "browser ipc from foreign uid " + Binder.getCallingUid());
                return false;
            }
            final String method = data.readString();
            final Bundle args = data.readBundle(BrowserIpc.class.getClassLoader());
            onCall(method, args != null ? args : new Bundle());
            return true;
        }
    }
}
