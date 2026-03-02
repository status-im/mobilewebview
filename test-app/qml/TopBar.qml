import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Rectangle {
    id: root
    color: "#ffffff"
    implicitHeight: controlsColumn.implicitHeight + 16
    border.color: "#d8d8d8"
    border.width: 1

    property string address: "https://opensea.io"
    property int clickCount: 0
    property bool loading: false

    signal backRequested()
    signal forwardRequested()
    signal reloadRequested()
    signal stopRequested()
    signal goRequested(string address)
    signal incrementRequested()
    signal decrementRequested()
    signal jsPopupRequested()

    ColumnLayout {
        id: controlsColumn
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.margins: 8
        spacing: 6

        RowLayout {
            Layout.fillWidth: true
            spacing: 6

            Button {
                text: "<"
                Layout.preferredWidth: 52
                onClicked: root.backRequested()
            }
            Button {
                text: root.loading ? "Stop" : "Reload"
                Layout.fillWidth: true
                onClicked: {
                    if (root.loading)
                        root.stopRequested()
                    else
                        root.reloadRequested()
                }
            }
            Button {
                text: ">"
                Layout.preferredWidth: 52
                onClicked: root.forwardRequested()
            }
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: 6
            TextField {
                id: addressInput
                Layout.fillWidth: true
                text: root.address
                placeholderText: "Enter URL"
                color: "#202020"
                placeholderTextColor: "#808080"
                background: Rectangle {
                    radius: 6
                    color: "#ffffff"
                    border.color: "#bcbcbc"
                    border.width: 1
                }
                onAccepted: root.goRequested(text)
            }
            Button {
                text: "Go"
                Layout.preferredWidth: 58
                onClicked: root.goRequested(addressInput.text)
            }
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: 8

            Button {
                text: "+"
                Layout.preferredWidth: 52
                onClicked: root.incrementRequested()
            }

            Label {
                Layout.fillWidth: true
                color: "#202020"
                horizontalAlignment: Text.AlignHCenter
                verticalAlignment: Text.AlignVCenter
                text: "counter: " + root.clickCount
            }

            Button {
                text: "-"
                Layout.preferredWidth: 52
                onClicked: root.decrementRequested()
            }

            Button {
                text: "js"
                Layout.preferredWidth: 100
                onClicked: root.jsPopupRequested()
            }
        }
    }
}
