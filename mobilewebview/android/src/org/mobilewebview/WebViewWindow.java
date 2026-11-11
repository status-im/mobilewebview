package org.mobilewebview;

import android.app.Activity;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Hosts one WebView (or the SurfaceView of a remote one) in its own panel window attached to the activity window, sized and placed
 * like the QML item. For hosts whose activity window draws no views (it took its surface): the
 * WebView needs HWUI, and only this window gets it.
 *
 * Host views stacked over the WebView in the activity window (elevation above 0, e.g. an edge
 * swipe strip) still get the gestures that start on them: the window routes those to the
 * activity window. The window also carries the activity window's system gesture exclusion
 * rects: a window on top hides the exclusions of the windows below it.
 */
final class WebViewWindow {
    private static final String TAG = "MobileWebView";
    private static final boolean DEBUG_HOST_VIEWS = true;

    private final Activity mActivity;
    private final ViewGroup mHostContent;
    private final Root mRoot;
    private final View mContent;
    private final Content mHooks;
    private WindowManager.LayoutParams mParams;
    private boolean mAdded = false;
    private int mX, mY, mWidth = 1, mHeight = 1;
    private boolean mVisible = false;
    private boolean mFocusable = false;
    private List<Rect> mHostExclusion = Collections.emptyList();
    private final Consumer<List<Rect>> mExclusionListener =
            rects -> {
                mHostExclusion = new ArrayList<>(rects);
                applyExclusion();
            };

    /** What the window needs from the view it hosts. */
    interface Content {
        // Back while no view in this window has focus.
        boolean onBack(KeyEvent event);
        void onFocusableChanged(boolean focusable);
        void onWindowFocusChanged(boolean hasFocus);
    }

    WebViewWindow(Activity activity, ViewGroup hostContent, WebView webView) {
        this(activity, hostContent, webView, new Content() {
            @Override public boolean onBack(KeyEvent event) { return webView.dispatchKeyEvent(event); }
            @Override public void onFocusableChanged(boolean focusable) { if (focusable) webView.requestFocus(); }
            @Override public void onWindowFocusChanged(boolean hasFocus) {}
        });
    }

    WebViewWindow(Activity activity, ViewGroup hostContent, View content, Content hooks) {
        mActivity = activity;
        mHostContent = hostContent;
        mContent = content;
        mHooks = hooks;
        mRoot = new Root(activity);
        mRoot.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mRoot.setVisibility(View.GONE);
    }

    void setGeometry(int x, int y, int width, int height) {
        mX = x;
        mY = y;
        mWidth = Math.max(1, width);
        mHeight = Math.max(1, height);
        update();
    }

    void setVisible(boolean visible, boolean focusable) {
        // Only a touch on the WebView takes the focus: showing the window (also after a popup or
        // a freeze) must not take it from the activity window.
        if (!visible || !focusable) mFocusable = false;
        mVisible = visible;
        mRoot.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible && !mAdded) {
            add();
        } else {
            update();
        }
    }

    void remove() {
        if (!mAdded) return;
        mAdded = false;
        if (Build.VERSION.SDK_INT >= 29) {
            mActivity.getWindow().getDecorView().getViewTreeObserver()
                    .removeOnSystemGestureExclusionRectsChangedListener(mExclusionListener);
        }
        try {
            mActivity.getWindowManager().removeViewImmediate(mRoot);
        } catch (RuntimeException e) {
            Log.w(TAG, "WebViewWindow remove failed", e);
        }
    }

    private void add() {
        final IBinder token = mActivity.getWindow().getDecorView().getWindowToken();
        if (token == null) {
            mRoot.post(() -> { if (mVisible && !mAdded) add(); });
            return;
        }
        mParams = new WindowManager.LayoutParams(
                mWidth, mHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                flags(),
                PixelFormat.TRANSLUCENT);
        mParams.gravity = Gravity.TOP | Gravity.START;
        mParams.token = token;
        mParams.x = mX + hostOffsetX();
        mParams.y = mY + hostOffsetY();
        mParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        mParams.setTitle("MobileWebView");
        if (Build.VERSION.SDK_INT >= 30) mParams.setFitInsetsTypes(0);
        try {
            mActivity.getWindowManager().addView(mRoot, mParams);
            mAdded = true;
            if (Build.VERSION.SDK_INT >= 29) {
                final View decor = mActivity.getWindow().getDecorView();
                decor.getViewTreeObserver().addOnSystemGestureExclusionRectsChangedListener(mExclusionListener);
                mHostExclusion = new ArrayList<>();
                collectExclusion(decor, mHostExclusion);
                applyExclusion();
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "WebViewWindow add failed", e);
        }
    }

    private int flags() {
        int f = WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (!mFocusable) f |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        return f;
    }

    private void update() {
        if (!mAdded || mParams == null) return;
        final int x = mX + hostOffsetX();
        final int y = mY + hostOffsetY();
        final int f = flags();
        if (mParams.x == x && mParams.y == y && mParams.width == mWidth
                && mParams.height == mHeight && mParams.flags == f) {
            return;
        }
        mParams.x = x;
        mParams.y = y;
        mParams.width = mWidth;
        mParams.height = mHeight;
        mParams.flags = f;
        mActivity.getWindowManager().updateViewLayout(mRoot, mParams);
        applyExclusion();
    }

    private static void collectExclusion(View v, List<Rect> out) {
        if (v.getVisibility() != View.VISIBLE) return;
        final List<Rect> own = v.getSystemGestureExclusionRects();
        if (!own.isEmpty()) {
            final int[] loc = new int[2];
            v.getLocationInWindow(loc);
            for (Rect r : own) {
                final Rect w = new Rect(r);
                w.offset(loc[0], loc[1]);
                out.add(w);
            }
        }
        if (v instanceof ViewGroup) {
            final ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectExclusion(g.getChildAt(i), out);
        }
    }

    // Host rects are in activity window coordinates, the panel is placed relative to that window.
    private void applyExclusion() {
        if (Build.VERSION.SDK_INT < 29 || !mAdded || mParams == null) return;
        final List<Rect> local = new ArrayList<>();
        for (Rect r : mHostExclusion) {
            final Rect l = new Rect(r);
            l.offset(-mParams.x, -mParams.y);
            if (l.intersect(0, 0, mParams.width, mParams.height)) local.add(l);
        }
        mRoot.setSystemGestureExclusionRects(local);
    }

    private int hostOffsetX() {
        final int[] loc = new int[2];
        mHostContent.getLocationInWindow(loc);
        return loc[0];
    }

    private int hostOffsetY() {
        final int[] loc = new int[2];
        mHostContent.getLocationInWindow(loc);
        return loc[1];
    }

    // The topmost focusable window keeps the focus, so while this window is focusable the
    // activity window gets no key or IME focus: give it back on any touch outside the WebView.
    // A touch on the content that did not pass through this window (remote WebViews).
    void contentTouched() {
        setFocusable(true);
    }

    boolean isFocusable() {
        return mFocusable;
    }

    // Host views stacked over this window that take their own gestures, in window coordinates.
    List<Rect> hostRectsAbove() {
        final List<Rect> out = new ArrayList<>();
        if (mParams == null) return out;
        final int[] loc = new int[2];
        final int[] origin = new int[2];
        mHostContent.getLocationOnScreen(origin);
        final int ox = origin[0] - hostOffsetX() + mParams.x;
        final int oy = origin[1] - hostOffsetY() + mParams.y;
        for (int i = 0; i < mHostContent.getChildCount(); i++) {
            final View v = mHostContent.getChildAt(i);
            if (DEBUG_HOST_VIEWS) Log.i(TAG, "host view " + v.getClass().getSimpleName() + " vis=" + v.getVisibility() + " z=" + v.getZ() + " clickable=" + v.isClickable() + " " + v.getWidth() + "x" + v.getHeight() + " at " + v.getX() + "," + v.getY());
            if (v.getVisibility() != View.VISIBLE || v.getZ() <= 0f || !v.isClickable()) continue;
            v.getLocationOnScreen(loc);
            final Rect r = new Rect(loc[0] - ox, loc[1] - oy, loc[0] - ox + v.getWidth(), loc[1] - oy + v.getHeight());
            if (r.intersect(0, 0, mParams.width, mParams.height)) out.add(r);
        }
        return out;
    }

    private void setFocusable(boolean focusable) {
        focusable = focusable && mVisible;
        if (mFocusable == focusable) return;
        mFocusable = focusable;
        Log.i(TAG, "WebViewWindow focusable=" + focusable);
        update();
        mHooks.onFocusableChanged(focusable);
    }

    private View hostViewAbove(float rawX, float rawY) {
        final int[] loc = new int[2];
        for (int i = mHostContent.getChildCount() - 1; i >= 0; i--) {
            final View v = mHostContent.getChildAt(i);
            if (v.getVisibility() != View.VISIBLE || v.getZ() <= 0f || !v.isClickable()) continue;
            v.getLocationOnScreen(loc);
            if (rawX >= loc[0] && rawX < loc[0] + v.getWidth()
                    && rawY >= loc[1] && rawY < loc[1] + v.getHeight()) {
                return v;
            }
        }
        return null;
    }

    private final class Root extends FrameLayout {
        private View mRouteTo;

        Root(Activity activity) {
            super(activity);
        }

        @Override
        public void onWindowFocusChanged(boolean hasWindowFocus) {
            super.onWindowFocusChanged(hasWindowFocus);
            mHooks.onWindowFocusChanged(hasWindowFocus);
        }

        // Back must reach the WebView (it forwards Back to the host) even when no view in this
        // window has focus; unhandled, the system would send the activity to the background.
        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && !mContent.hasFocus()) {
                return mHooks.onBack(event);
            }
            return super.dispatchKeyEvent(event);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                setFocusable(false);
                return false;
            }
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mRouteTo = hostViewAbove(ev.getRawX(), ev.getRawY());
                Log.i(TAG, "WebViewWindow down at " + ev.getRawX() + "," + ev.getRawY() + " route=" + mRouteTo);
                setFocusable(mRouteTo == null);
            }
            if (mRouteTo != null) {
                final View target = mRouteTo;
                final int action = ev.getActionMasked();
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    mRouteTo = null;
                }
                final int[] loc = new int[2];
                target.getLocationOnScreen(loc);
                final MotionEvent routed = MotionEvent.obtain(ev);
                routed.setLocation(ev.getRawX() - loc[0], ev.getRawY() - loc[1]);
                target.dispatchTouchEvent(routed);
                routed.recycle();
                return true;
            }
            return super.dispatchTouchEvent(ev);
        }
    }
}
