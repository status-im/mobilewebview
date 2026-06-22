import QtQuick
import QtQuick.Controls
import MobileWebViewTest

TextField {
    id: root

    property bool addressMode: false
    property bool compact: false

    signal editingFocusChanged(bool focused)

    implicitHeight: compact ? Theme.controlHeight : Theme.controlHeightLg
    font.pixelSize: compact ? Theme.fontSm : Theme.fontMd
    color: Theme.textPrimary
    placeholderTextColor: Theme.textMuted
    leftPadding: 10
    rightPadding: 10
    verticalAlignment: Text.AlignVCenter
    selectByMouse: true

    background: Rectangle {
        radius: Theme.radiusLg
        color: Theme.surface
        border.color: root.activeFocus ? Theme.accent : Theme.border
        border.width: root.activeFocus ? 2 : 1
    }

    onActiveFocusChanged: {
        editingFocusChanged(activeFocus)

        if (addressMode && activeFocus) {
            // Ensure Qt Quick Controls receives keys instead of the native WebView.
            forceActiveFocus()
        }
    }

    Keys.onPressed: function(event) {
        if (!addressMode)
            return

        var meta = (event.modifiers & Qt.ControlModifier) || (event.modifiers & Qt.MetaModifier)

        if (meta && event.key === Qt.Key_A) {
            selectAll()
            event.accepted = true
            return
        }

        if (meta && event.key === Qt.Key_Backspace) {
            // macOS-native: delete to start of line.
            if (selectionStart !== selectionEnd) {
                remove(selectionStart, selectionEnd)
            } else if (cursorPosition > 0) {
                remove(0, cursorPosition)
            }
            event.accepted = true
        }
    }
}
