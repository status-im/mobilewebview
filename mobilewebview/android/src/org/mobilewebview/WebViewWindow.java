package org.mobilewebview;

import android.app.Activity;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;

/**
 * Hosts one WebView in its own panel window attached to the activity window, sized and placed
 * like the QML item. For hosts whose activity window draws no views (it took its surface): the
 * WebView needs HWUI, and only this window gets it.
 *
 * Host views stacked over the WebView in the activity window (elevation above 0, e.g. an edge
 * swipe strip) still get the gestures that start on them: the window routes those to the
 * activity window.
 */
final class WebViewWindow {
    private static final String TAG = "MobileWebView";

    private final Activity mActivity;
    private final ViewGroup mHostContent;
    private final Root mRoot;
    private WindowManager.LayoutParams mParams;
    private boolean mAdded = false;
    private int mX, mY, mWidth = 1, mHeight = 1;
    private boolean mVisible = false;
    private boolean mFocusable = false;

    WebViewWindow(Activity activity, ViewGroup hostContent, WebView webView) {
        mActivity = activity;
        mHostContent = hostContent;
        mRoot = new Root(activity);
        mRoot.addView(webView, new FrameLayout.LayoutParams(
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
        mVisible = visible;
        mFocusable = visible && focusable;
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
        } catch (RuntimeException e) {
            Log.e(TAG, "WebViewWindow add failed", e);
        }
    }

    private int flags() {
        int f = WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
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

    private void takeFocus() {
        if (mFocusable || !mVisible) return;
        mFocusable = true;
        update();
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
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mRouteTo = hostViewAbove(ev.getRawX(), ev.getRawY());
                if (mRouteTo == null) takeFocus();
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
