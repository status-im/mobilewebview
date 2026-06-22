import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import MobileWebViewTest

ScreenScaffold {
    id: root

    required property var stackView

    title: "Zoom"
    initialUrl: "https://example.com/"
    webViewHeight: 460

    onBackRequested: stackView.pop()

    ColumnLayout {
        width: parent.width
        spacing: Theme.spacingSm

        RowLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingSm

            AppButton {
                label: "\u2212"
                onClicked: {
                    if (root.webView)
                        root.webView.zoomFactor = Math.max(root.webView.zoomFactor - 0.25, 0.25)
                }
            }
            Label {
                Layout.preferredWidth: 64
                horizontalAlignment: Text.AlignHCenter
                text: root.webView ? Math.round(root.webView.zoomFactor * 100) + "%" : "100%"
                font.pixelSize: Theme.fontLg
                font.bold: true
            }
            AppButton {
                label: "+"
                onClicked: {
                    if (root.webView)
                        root.webView.zoomFactor = Math.min(root.webView.zoomFactor + 0.25, 5.0)
                }
            }
            AppButton {
                label: "Reset"
                onClicked: {
                    if (root.webView)
                        root.webView.zoomFactor = 1.0
                }
            }
            AppButton {
                label: "Reload"
                onClicked: {
                    if (root.webView)
                        root.webView.reload()
                }
            }
        }

        Label {
            Layout.fillWidth: true
            wrapMode: Text.WordWrap
            color: Theme.textSecondary
            font.pixelSize: Theme.fontSm
            text: "Adjust zoom, reload the page, and confirm the zoom factor persists."
        }
    }
}
