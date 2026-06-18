import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import MobileWebViewTest

ScreenScaffold {
    id: root

    required property var stackView

    title: "Find in page"
    initialUrl: "https://example.com/"
    webViewHeight: 460

    property int findActiveMatch: -1
    property int findMatchCount: 0

    onBackRequested: stackView.pop()

    ColumnLayout {
        width: parent.width
        spacing: Theme.spacingSm

        Label {
            Layout.fillWidth: true
            wrapMode: Text.WordWrap
            color: Theme.textSecondary
            font.pixelSize: Theme.fontSm
            text: {
                if (!root.webView)
                    return ""
                if (root.webView.hasNativeFindPanel)
                    return "This platform exposes a native find panel."
                if (!root.webView.findSupported)
                    return "Find in page is not supported on this platform."
                return "Custom find bar is used on this platform."
            }
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingXs
            visible: root.webView && root.webView.findSupported && !root.webView.hasNativeFindPanel

            AppTextField {
                id: findField
                Layout.fillWidth: true
                placeholderText: "Find in page\u2026"
                compact: true
                onEditingFocusChanged: function(focused) {
                    root.contentInputFocused = focused
                }
                onTextChanged: {
                    if (!root.webView)
                        return
                    if (text.length > 0)
                        root.webView.findText(text, caseBtn.checked ? 2 : 0)
                    else
                        root.webView.stopFind()
                }
                Keys.onReturnPressed: {
                    if (root.webView)
                        root.webView.findText(text, caseBtn.checked ? 2 : 0)
                }
                Keys.onEscapePressed: {
                    text = ""
                    if (root.webView)
                        root.webView.stopFind()
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
                onClicked: root.webView.findText(findField.text, 1 | (caseBtn.checked ? 2 : 0))
            }
            AppButton {
                label: "\u25BC"
                enabled: findField.text.length > 0
                onClicked: root.webView.findText(findField.text, caseBtn.checked ? 2 : 0)
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
            visible: root.webView && root.webView.hasNativeFindPanel
            spacing: Theme.spacingSm

            AppButton {
                label: "Show native panel"
                accent: true
                onClicked: root.webView.showFindPanel()
            }
            AppButton {
                label: "Hide native panel"
                onClicked: root.webView.hideFindPanel()
            }
        }
    }

    Connections {
        target: root.webView
        function onFindTextResult(activeMatchIndex, matchCount) {
            root.findActiveMatch = activeMatchIndex
            root.findMatchCount = matchCount
            root.statusMessage("findTextResult active=" + activeMatchIndex + " total=" + matchCount)
        }
    }
}
