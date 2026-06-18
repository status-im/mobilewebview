import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import MobileWebViewTest

ScreenScaffold {
    id: root

    required property var stackView

    title: "Snapshot & Freeze"
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
            AppButton {
                label: "Capture snapshot"
                highlighted: true
                onClicked: requestSnapshot()
            }
        }

        Label {
            Layout.fillWidth: true
            visible: root.snapshotPending
            text: "Capturing preview\u2026"
            color: Theme.textSecondary
            font.italic: true
            font.pixelSize: Theme.fontSm
        }

        Image {
            Layout.fillWidth: true
            Layout.preferredHeight: 220
            visible: root.snapshotUrl.toString().length > 0
            fillMode: Image.PreserveAspectFit
            asynchronous: true
            cache: false
            source: root.snapshotUrl
        }

        WebViewHost {
            id: webHost
            Layout.fillWidth: true
            Layout.preferredHeight: 360
            initialUrl: "https://example.com/"
            freeze: freezeDialog.opened
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

        onOpened: requestSnapshot()

        Column {
            width: freezeDialog.availableWidth
            spacing: Theme.spacingMd

            Label {
                width: parent.width
                wrapMode: Text.WordWrap
                text: qsTr("While open, the WebView uses freeze (native view hidden, last frame in scene).")
                color: Theme.textPrimary
                font.pixelSize: Theme.fontMd
            }

            Image {
                width: parent.width
                height: 180
                visible: root.snapshotUrl.toString().length > 0
                fillMode: Image.PreserveAspectFit
                asynchronous: true
                cache: false
                source: root.snapshotUrl
            }
        }

        standardButtons: Dialog.Close
    }

    function requestSnapshot() {
        root.snapshotPending = true
        root.snapshotUrl = ""
        Qt.callLater(function() {
            var w = Math.max(120, Math.floor(root.width - Theme.spacingXl * 2))
            webHost.webView.requestSnapshot(Qt.size(w, 220))
        })
    }

    Connections {
        target: webHost.webView
        function onSnapshotReady(url, ok) {
            root.snapshotPending = false
            if (ok)
                root.snapshotUrl = url
            root.statusMessage(ok ? "snapshotReady" : "snapshot failed")
        }
    }
}
