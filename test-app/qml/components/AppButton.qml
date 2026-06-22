import QtQuick
import QtQuick.Controls
import MobileWebViewTest

Button {
    id: root

    property string label: ""
    property bool accent: false

    implicitWidth: Math.max(Theme.buttonMinWidth, contentItem.implicitWidth + Theme.spacingLg)
    implicitHeight: Theme.controlHeight
    flat: true

    contentItem: Text {
        text: root.label
        font.pixelSize: Theme.fontLg
        color: !root.enabled ? Theme.textMuted
               : root.accent ? "#ffffff"
               : root.highlighted ? Theme.accent
               : root.down ? Theme.accentDark
               : Theme.textPrimary
        horizontalAlignment: Text.AlignHCenter
        verticalAlignment: Text.AlignVCenter
    }

    background: Rectangle {
        radius: Theme.radiusMd
        color: {
            if (root.accent)
                return root.down ? Theme.accentDark : Theme.accent
            if (root.highlighted)
                return root.down ? "#d2e3fc" : Theme.accentSurface
            return root.down ? Theme.borderStrong : (root.hovered ? "#f0f0f0" : "transparent")
        }
    }
}
