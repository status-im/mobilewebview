package org.mobilewebview;

import android.graphics.Color;
import android.os.Build;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.WebChromeClient;
import android.widget.FrameLayout;

/**
 * A page's fullscreen element (a playing video) over the whole window with the
 * system bars hidden, until the page leaves fullscreen or the user presses Back.
 * Main thread only.
 */
final class FullscreenVideo {
    private Window mWindow;
    private FrameLayout mContainer;
    private WebChromeClient.CustomViewCallback mCallback;

    boolean isShowing() {
        return mContainer != null;
    }

    /** The page entered fullscreen (onShowCustomView); refused without a window to cover. */
    @SuppressWarnings("deprecation")
    void show(Window window, View view, WebChromeClient.CustomViewCallback callback) {
        if (mContainer != null || window == null || !(window.getDecorView() instanceof ViewGroup)) {
            callback.onCustomViewHidden();
            return;
        }
        FrameLayout container = new FrameLayout(window.getContext());
        container.setBackgroundColor(Color.BLACK);
        container.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Held while the container is attached, whatever flags the app sets on its own views.
        container.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        container.setFocusable(true);
        container.setFocusableInTouchMode(true);
        container.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                leave();
            }
            return true;
        });
        ((ViewGroup) window.getDecorView()).addView(container, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        container.requestFocus();

        mWindow = window;
        mContainer = container;
        mCallback = callback;
        container.post(() -> {
            if (mContainer == container) {
                hideSystemBars(window);
            }
        });
    }

    /** The page left fullscreen (onHideCustomView). */
    void hide() {
        if (mContainer == null) {
            return;
        }
        ViewParent parent = mContainer.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(mContainer);
        }
        mContainer.removeAllViews();
        showSystemBars(mWindow);
        mWindow = null;
        mContainer = null;
        mCallback = null;
    }

    /** Takes the page out of fullscreen: Back, or the web view hidden or destroyed. */
    void leave() {
        WebChromeClient.CustomViewCallback callback = mCallback;
        hide();
        if (callback != null) {
            callback.onCustomViewHidden();
        }
    }

    private static void hideSystemBars(Window window) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        WindowInsetsController controller = window.getInsetsController();
        if (controller != null) {
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsets.Type.systemBars());
        }
    }

    private static void showSystemBars(Window window) {
        if (window == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        WindowInsetsController controller = window.getInsetsController();
        if (controller != null) {
            controller.show(WindowInsets.Type.systemBars());
        }
    }
}
