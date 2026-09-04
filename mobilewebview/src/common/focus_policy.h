#pragma once

#include <QObject>

namespace MobileWebViewFocusPolicy {

/// Whether the native web view may grab platform focus when it is shown.
///
/// The Android WebView requests focus on show so it receives KEYCODE_BACK.
/// If a Qt text input (find bar, URL bar) already owns the keyboard, grabbing
/// focus would redirect the soft keyboard's input to the web page while the
/// keyboard stays visibly attached to the Qt field.
bool nativeViewMayGrabFocus(QObject *qtFocusObject);

} // namespace MobileWebViewFocusPolicy
