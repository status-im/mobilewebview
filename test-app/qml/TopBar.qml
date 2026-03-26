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
    property string pageTitle: ""
    property int clickCount: 0
    property bool loading: false
    property bool canGoBack: false
    property bool canGoForward: false
    property int loadProgress: 0
    property string favicon: ""
    property real zoomFactor: 1.0

    signal backRequested()
    signal forwardRequested()
    signal reloadRequested()
    signal stopRequested()
    signal goRequested(string address)
    signal incrementRequested()
    signal decrementRequested()
    signal jsPopupRequested()
    signal clearHistoryRequested()
    signal zoomInRequested()
    signal zoomOutRequested()
    signal zoomResetRequested()

    readonly property bool hasInputFocus: addressInput.activeFocus

    ColumnLayout {
        id: controlsColumn
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.margins: 8
        spacing: 6

        // Navigation row
        RowLayout {
            Layout.fillWidth: true
            spacing: 6

            Button {
                text: "<"
                Layout.preferredWidth: 52
                enabled: root.canGoBack
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
                enabled: root.canGoForward
                onClicked: root.forwardRequested()
            }
            Button {
                text: "Hist✕"
                Layout.preferredWidth: 64
                onClicked: root.clearHistoryRequested()
            }
        }

        // Progress bar (only visible while loading)
        Rectangle {
            Layout.fillWidth: true
            height: 3
            color: "#e0e0e0"
            radius: 1
            visible: root.loading || root.loadProgress > 0 && root.loadProgress < 100

            Rectangle {
                width: parent.width * Math.max(0, Math.min(root.loadProgress, 100)) / 100
                height: parent.height
                color: "#4a90d9"
                radius: 1

                Behavior on width {
                    NumberAnimation { duration: 120 }
                }
            }
        }

        // Title row with favicon
        RowLayout {
            Layout.fillWidth: true
            spacing: 6

            Image {
                id: faviconImage
                source: root.favicon || ""
                width: 16
                height: 16
                visible: root.favicon.length > 0
                fillMode: Image.PreserveAspectFit
                smooth: true
            }

            Label {
                Layout.fillWidth: true
                color: "#202020"
                elide: Text.ElideRight
                text: root.pageTitle.length > 0 ? root.pageTitle : "(no title)"
            }
        }

        // Address bar
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

        // Zoom row
        RowLayout {
            Layout.fillWidth: true
            spacing: 6

            Label {
                text: "Zoom:"
                color: "#606060"
            }
            Button {
                text: "−"
                Layout.preferredWidth: 44
                onClicked: root.zoomOutRequested()
            }
            Label {
                Layout.preferredWidth: 52
                horizontalAlignment: Text.AlignHCenter
                color: "#202020"
                text: Math.round(root.zoomFactor * 100) + "%"
            }
            Button {
                text: "+"
                Layout.preferredWidth: 44
                onClicked: root.zoomInRequested()
            }
            Button {
                text: "1:1"
                Layout.preferredWidth: 44
                onClicked: root.zoomResetRequested()
            }

            Item { Layout.fillWidth: true }
        }

        // Counter / JS row
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
