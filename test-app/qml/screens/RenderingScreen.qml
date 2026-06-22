import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import MobileWebViewTest

ScreenScaffold {
    id: root

    required property var stackView

    title: "Snapshot & Freeze"
    initialUrl: "https://example.com/"
    webViewHeight: 360
    freeze: freezeDialog.opened

    property url snapshotUrl: ""
    property bool snapshotPending: false

    onBackRequested: stackView.pop()

    ColumnLayout {
        width: parent.width
        spacing: Theme.spacingSm

        Label {
            Layout.fillWidth: true
            wrapMode: Text.WordWrap
            color: Theme.textSecondary
            font.pixelSize: Theme.fontSm
            text: "Open the freeze dialog to hide the native WebView and capture a platform snapshot."
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingSm

            AppButton {
                label: "Open freeze dialog"
                accent: true
                onClicked: freezeDialog.open()
            }
        }
    }

    Dialog {
        id: freezeDialog
        title: qsTr("Freeze test")
        modal: true
        parent: Overlay.overlay
        anchors.centerIn: parent
        width: Math.min(480, root.width - 48)
        padding: Theme.spacingLg

        onOpened: {
            root.snapshotPending = true
            root.snapshotUrl = ""
            Qt.callLater(function() {
                if (root.webView)
                    root.webView.requestSnapshot(Qt.size(Math.floor(freezeDialog.availableWidth), 220))
            })
        }

        Column {
            width: freezeDialog.availableWidth
            spacing: Theme.spacingMd

            Label {
                width: parent.width
                wrapMode: Text.WordWrap
                text: qsTr("While open, the WebView uses freeze (native view hidden, last frame in scene). Below: a platform snapshot for this dialog.")
                color: Theme.textPrimary
                font.pixelSize: Theme.fontMd
            }

            Label {
                width: parent.width
                visible: root.snapshotPending
                text: qsTr("Capturing preview\u2026")
                color: Theme.textSecondary
                font.italic: true
                font.pixelSize: Theme.fontSm
            }

            Image {
                width: parent.width
                height: 220
                visible: root.snapshotUrl.toString().length > 0
                fillMode: Image.PreserveAspectFit
                asynchronous: true
                cache: false
                source: root.snapshotUrl
            }
        }

        standardButtons: Dialog.Close
    }

    Connections {
        target: root.webView
        function onSnapshotReady(url, ok) {
            root.snapshotPending = false
            if (ok)
                root.snapshotUrl = url
            root.statusMessage(ok ? "snapshotReady" : "snapshot failed")
        }
    }
}
