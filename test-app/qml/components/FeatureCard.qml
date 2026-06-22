import QtQuick
import QtQuick.Controls
import MobileWebViewTest

AbstractButton {
    id: root

    property string title: ""
    property string subtitle: ""

    implicitWidth: 220
    implicitHeight: 96

    contentItem: Column {
        anchors.fill: parent
        anchors.margins: Theme.spacingMd
        spacing: Theme.spacingXs

        Text {
            width: parent.width
            text: root.title
            font.pixelSize: Theme.fontLg
            font.bold: true
            color: Theme.textPrimary
            elide: Text.ElideRight
        }

        Text {
            width: parent.width
            text: root.subtitle
            font.pixelSize: Theme.fontSm
            color: Theme.textSecondary
            wrapMode: Text.WordWrap
            maximumLineCount: 2
            elide: Text.ElideRight
        }
    }

    background: Rectangle {
        radius: Theme.radiusXl
        color: root.down ? Theme.accentSurface : (root.hovered ? "#f3f6fc" : Theme.surface)
        border.color: root.hovered || root.down ? Theme.accent : Theme.border
        border.width: 1
    }
}
