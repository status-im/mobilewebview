import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import MobileWebViewTest

ScreenScaffold {
    id: root

    required property var stackView

    title: "Find in page"

    property int findActiveMatch: -1
    property int findMatchCount: 0
    property bool inputFocused: false

    onBackRequested: stackView.pop()

    ColumnLayout {
        width: parent.width
        spacing: Theme.spacingSm

        Label {
            Layout.fillWidth: true
            wrapMode: Text.WordWrap
            color: Theme.textSecondary
            font.pixelSize: Theme.fontSm
            text: webHost.webView.hasNativeFindPanel
                  ? "This platform exposes a native find panel."
                  : "Custom find bar is used on this platform."
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingXs
            visible: !webHost.webView.hasNativeFindPanel

            AppTextField {
                id: findField
                Layout.fillWidth: true
                placeholderText: "Find in page\u2026"
                compact: true
                onEditingFocusChanged: function(focused) {
                    root.inputFocused = focused
                    webHost.inputFocused = focused
                }
                onTextChanged: {
                    if (text.length > 0)
                        webHost.webView.findText(text, caseBtn.checked ? 2 : 0)
                    else
                        webHost.webView.stopFind()
                }
                Keys.onReturnPressed: webHost.webView.findText(text, caseBtn.checked ? 2 : 0)
                Keys.onEscapePressed: {
                    text = ""
                    webHost.webView.stopFind()
                }
            }

            Label {
                text: {
                    if (findField.text.length === 0)
                        return ""
                    if (root.findMatchCount === 0)
                        return "0/0"
                    return (root.findActiveMatch + 1) + "/" + root.findMatchCount
                }
                color: root.findMatchCount === 0 && findField.text.length > 0 ? Theme.danger : Theme.textSecondary
                font.pixelSize: Theme.fontSm
                Layout.preferredWidth: 44
                horizontalAlignment: Text.AlignHCenter
            }

            AppButton {
                label: "\u25B2"
                enabled: findField.text.length > 0
                onClicked: webHost.webView.findText(findField.text, 1 | (caseBtn.checked ? 2 : 0))
            }
            AppButton {
                label: "\u25BC"
                enabled: findField.text.length > 0
                onClicked: webHost.webView.findText(findField.text, caseBtn.checked ? 2 : 0)
            }
            AppButton {
                id: caseBtn
                label: "Aa"
                checkable: true
                highlighted: checked
            }
        }

        RowLayout {
            Layout.fillWidth: true
            visible: webHost.webView.hasNativeFindPanel
            spacing: Theme.spacingSm

            AppButton {
                label: "Show native panel"
                accent: true
                onClicked: webHost.webView.showFindPanel()
            }
            AppButton {
                label: "Hide native panel"
                onClicked: webHost.webView.hideFindPanel()
            }
        }

        WebViewHost {
            id: webHost
            Layout.fillWidth: true
            Layout.preferredHeight: 460
            initialUrl: "https://example.com/"
            inputFocused: root.inputFocused
        }
    }

    Connections {
        target: webHost.webView
        function onFindTextResult(activeMatchIndex, matchCount) {
            root.findActiveMatch = activeMatchIndex
            root.findMatchCount = matchCount
            root.statusMessage("findTextResult active=" + activeMatchIndex + " total=" + matchCount)
        }
    }
}
