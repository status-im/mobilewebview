package org.mobilewebview;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.List;

/**
 * UI-process side of the browser process: binds {@link BrowserProcessService} while remote
 * WebViews are shown, and lets it go (the process exits) {@link #IDLE_KILL_MS} after the last one
 * was hidden. Before letting go it saves each WebView's history; the next bind re-creates the
 * WebViews from their {@link RemoteWebView} state.
 */
final class BrowserProcessClient {
    private static final String TAG = "MobileWebView";
    /** How long the browser process outlives the last shown (not frozen) WebView. */
    static final long IDLE_KILL_MS = 5_000;
    private static final String PREFS = "mobilewebview_browser_process";

    private static BrowserProcessClient sInstance;

    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Object mLock = new Object();
    private final SparseArray<RemoteWebView> mViews = new SparseArray<>();
    private final List<Object[]> mQueue = new ArrayList<>();  // {view, method, args}
    private IBinder mHost;
    private int mGeneration = 0;
    private boolean mBound = false;
    private boolean mSaving = false;
    private long mBindStartMs;

    private final Runnable mIdleKill = this::saveAndRelease;

    private final BrowserIpc.Receiver mCallbacks = new BrowserIpc.Receiver() {
        @Override
        protected void onCall(String method, Bundle args) {
            onCallback(method, args);
        }
    };

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "browser process connected in "
                    + (SystemClock.uptimeMillis() - mBindStartMs) + " ms");
            try {
                service.linkToDeath(() -> onHostGone(service), 0);
            } catch (android.os.RemoteException e) {
                onHostGone(service);
                return;
            }
            synchronized (mLock) {
                mHost = service;
                mGeneration++;
                final Bundle hello = new Bundle();
                hello.putBinder("client", mCallbacks);
                BrowserIpc.send(mHost, "hello", hello, false);
                for (int i = 0; i < mViews.size(); i++) {
                    mViews.valueAt(i).replayInto(mHost, mGeneration);
                }
                for (Object[] c : mQueue) {
                    final RemoteWebView v = (RemoteWebView) c[0];
                    if (mViews.get(v.mId) == v) sendLocked(v, (String) c[1], (Bundle) c[2]);
                }
                mQueue.clear();
            }
            updateIdle();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            onHostGone(null);
        }
    };

    static synchronized BrowserProcessClient get(Context context) {
        if (sInstance == null) sInstance = new BrowserProcessClient(context.getApplicationContext());
        return sInstance;
    }

    private BrowserProcessClient(Context context) {
        mContext = context;
        final SharedPreferences p = prefs(context);
        DataClearManager.setClientAnswer(p.getBoolean("clearSiteData", false));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String cachedDefaultUserAgent(Context context) {
        get(context);
        return prefs(context).getString("userAgent", "");
    }

    void register(RemoteWebView view) {
        synchronized (mLock) {
            mViews.put(view.mId, view);
            if (mHost != null) view.replayInto(mHost, mGeneration);
        }
        updateIdle();
    }

    void unregister(RemoteWebView view) {
        synchronized (mLock) {
            if (mViews.get(view.mId) == view) mViews.remove(view.mId);
            if (mHost != null && view.isLive(mGeneration)) {
                final Bundle args = new Bundle();
                args.putInt("id", view.mId);
                BrowserIpc.send(mHost, "destroy", args, false);
            }
        }
        updateIdle();
    }

    /** Sends a call now, or queues it for the next bind (which re-creates the WebView first). */
    void call(RemoteWebView view, String method, Bundle args) {
        synchronized (mLock) {
            if (mHost != null && view.isLive(mGeneration)) {
                sendLocked(view, method, args);
                return;
            }
            mQueue.add(new Object[] {view, method, args});
        }
        mMain.post(this::ensureBound);
    }

    boolean isViewLive(RemoteWebView view) {
        synchronized (mLock) {
            return mHost != null && view.isLive(mGeneration);
        }
    }

    /** Sends a call only to a live WebView; replay covers it otherwise. */
    void callIfLive(RemoteWebView view, String method, Bundle args) {
        synchronized (mLock) {
            if (mHost != null && view.isLive(mGeneration)) sendLocked(view, method, args);
        }
    }

    private void sendLocked(RemoteWebView view, String method, Bundle args) {
        args.putInt("id", view.mId);
        args.putString("m", method);
        if (!BrowserIpc.send(mHost, "call", args, false)) {
            Log.w(TAG, "browser process call " + method + " lost");
        }
    }

    /** Main thread: a WebView was shown, hidden or frozen. */
    void updateIdle() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mMain.post(this::updateIdle);
            return;
        }
        boolean needed = false;
        synchronized (mLock) {
            for (int i = 0; i < mViews.size(); i++) {
                if (mViews.valueAt(i).keepsProcess()) needed = true;
            }
            if (mViews.size() > 0 && mHost == null && !mBound) needed = needed || hasQueuedLocked();
        }
        mMain.removeCallbacks(mIdleKill);
        if (needed) {
            ensureBound();
        } else if (mBound) {
            mMain.postDelayed(mIdleKill, IDLE_KILL_MS);
        }
    }

    private boolean hasQueuedLocked() {
        return !mQueue.isEmpty();
    }

    private void ensureBound() {
        if (mBound) return;
        synchronized (mLock) {
            if (mViews.size() == 0) return;
        }
        mBound = true;
        mBindStartMs = SystemClock.uptimeMillis();
        Log.i(TAG, "binding browser process");
        final Intent intent = new Intent(mContext, BrowserProcessService.class);
        if (!mContext.bindService(intent, mConnection,
                Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT)) {
            Log.e(TAG, "bindService(BrowserProcessService) failed");
            mBound = false;
        }
    }

    private void saveAndRelease() {
        synchronized (mLock) {
            if (mHost == null || mSaving) return;
            mSaving = true;
            Log.i(TAG, "browser process idle: saving state and letting it go");
            BrowserIpc.send(mHost, "saveAll", new Bundle(), false);
        }
    }

    private void finishRelease() {
        boolean needed = false;
        synchronized (mLock) {
            mSaving = false;
            for (int i = 0; i < mViews.size(); i++) {
                if (mViews.valueAt(i).keepsProcess()) needed = true;
            }
            if (needed) return;
            mHost = null;
        }
        if (mBound) {
            mBound = false;
            mContext.unbindService(mConnection);
            Log.i(TAG, "browser process released");
        }
    }

    private void onHostGone(IBinder which) {
        synchronized (mLock) {
            if (which != null && which != mHost) return;
            if (mHost == null) return;
            Log.w(TAG, "browser process died");
            mHost = null;
            mSaving = false;
        }
        mMain.post(() -> {
            if (mBound) {
                mBound = false;
                try {
                    mContext.unbindService(mConnection);
                } catch (IllegalArgumentException ignored) {
                }
            }
            updateIdle();
        });
    }

    private void onCallback(String method, Bundle args) {
        switch (method) {
            case "info": {
                final String ua = args.getString("userAgent", "");
                final boolean clear = args.getBoolean("clearSiteData", false);
                prefs(mContext).edit().putString("userAgent", ua).putBoolean("clearSiteData", clear).apply();
                DataClearManager.setClientAnswer(clear);
                final List<RemoteWebView> views = snapshotViews();
                for (RemoteWebView v : views) v.onDefaultUserAgent(ua);
                return;
            }
            case "saved":
                mMain.post(this::finishRelease);
                return;
            default: {
                final RemoteWebView v;
                synchronized (mLock) {
                    v = mViews.get(args.getInt("id", -1));
                }
                if (v != null) v.onCallback(method, args);
            }
        }
    }

    private List<RemoteWebView> snapshotViews() {
        final List<RemoteWebView> out = new ArrayList<>();
        synchronized (mLock) {
            for (int i = 0; i < mViews.size(); i++) out.add(mViews.valueAt(i));
        }
        return out;
    }
}
