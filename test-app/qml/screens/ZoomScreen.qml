import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import MobileWebViewTest

ScreenScaffold {
    id: root

    required property var stackView

    title: "Zoom"

    onBackRequested: stackView.pop()

    ColumnLayout {
        width: parent.width
        spacing: Theme.spacingSm

        RowLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingSm

            AppButton {
                label: "\u2212"
                onClicked: webHost.webView.zoomFactor = Math.max(webHost.webView.zoomFactor - 0.25, 0.25)
            }
            Label {
                Layout.preferredWidth: 64
                horizontalAlignment: Text.AlignHCenter
                text: Math.round(webHost.webView.zoomFactor * 100) + "%"
                font.pixelSize: Theme.fontLg
                font.bold: true
            }
            AppButton {
                label: "+"
                onClicked: webHost.webView.zoomFactor = Math.min(webHost.webView.zoomFactor + 0.25, 5.0)
            }
            AppButton {
                label: "Reset"
                onClicked: webHost.webView.zoomFactor = 1.0
            }
            AppButton {
                label: "Reload"
                onClicked: webHost.webView.reload()
            }
        }

        Label {
            Layout.fillWidth: true
            wrapMode: Text.WordWrap
            color: Theme.textSecondary
            font.pixelSize: Theme.fontSm
            text: "Adjust zoom, reload the page, and confirm the zoom factor persists."
        }

        WebViewHost {
            id: webHost
            Layout.fillWidth: true
            Layout.preferredHeight: 460
            initialUrl: "https://example.com/"
        }
    }
}
