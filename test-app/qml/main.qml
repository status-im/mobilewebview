import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Window
import MobileWebViewTest

ApplicationWindow {
    id: root

    visible: true
    width: Math.min(Screen.width, 960)
    height: Math.min(Screen.height, 900)
    title: "Mobile WebView Test"

    property string statusText: "Ready"
    property bool incognitoActive: false

    color: Theme.background

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        StackView {
            id: stack
            Layout.fillWidth: true
            Layout.fillHeight: true
            initialItem: HomeScreen {
                stackView: stack
            }

            onCurrentItemChanged: {
                if (currentItem && currentItem.statusMessage) {
                    currentItem.statusMessage.connect(function(message) {
                        root.statusText = message
                    })
                }
            }
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: Theme.statusBarHeight
            color: root.incognitoActive ? Theme.statusBarIncognito : Theme.statusBar

            Label {
                anchors.fill: parent
                anchors.leftMargin: Theme.spacingSm
                anchors.rightMargin: Theme.spacingSm
                text: root.statusText
                color: Theme.textSecondary
                font.pixelSize: Theme.fontXs
                elide: Text.ElideRight
                verticalAlignment: Text.AlignVCenter
            }
        }
    }
}
