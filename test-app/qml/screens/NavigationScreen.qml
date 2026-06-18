import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import MobileWebViewTest

ScreenScaffold {
    id: root

    required property var stackView

    title: "Navigation & History"

    property bool addressEditing: false
    property string draftAddress: ""

    onBackRequested: stackView.pop()

    ColumnLayout {
        width: parent.width
        spacing: Theme.spacingSm

        RowLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingXs

            AppButton {
                label: "\u25C0"
                enabled: webHost.webView.canGoBack
                onClicked: webHost.webView.goBack()
            }
            AppButton {
                label: webHost.webView.loading ? "Stop" : "Reload"
                Layout.fillWidth: true
                onClicked: webHost.webView.loading ? webHost.webView.stop() : webHost.webView.reload()
            }
            AppButton {
                label: "\u25B6"
                enabled: webHost.webView.canGoForward
                onClicked: webHost.webView.goForward()
            }
            AppButton {
                label: historyPanel.visible ? "\u2715 History" : "History"
                highlighted: historyPanel.visible
                onClicked: historyPanel.visible = !historyPanel.visible
            }
        }

        Rectangle {
            Layout.fillWidth: true
            height: 3
            radius: 2
            color: Theme.borderStrong
            visible: webHost.webView.loading || (webHost.webView.loadProgress > 0 && webHost.webView.loadProgress < 100)

            Rectangle {
                width: parent.width * Math.max(0, Math.min(webHost.webView.loadProgress, 100)) / 100
                height: parent.height
                color: Theme.accent
                radius: 2
            }
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingSm

            Image {
                source: webHost.webView.favicon || ""
                Layout.preferredWidth: 16
                Layout.preferredHeight: 16
                visible: (webHost.webView.favicon || "").length > 0
                fillMode: Image.PreserveAspectFit
            }

            Label {
                Layout.fillWidth: true
                text: webHost.webView.title.length > 0 ? webHost.webView.title : "(no title)"
                elide: Text.ElideRight
                color: Theme.textPrimary
                font.pixelSize: Theme.fontMd
            }
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingXs

            AppTextField {
                id: addressField
                Layout.fillWidth: true
                addressMode: true
                text: root.addressEditing ? root.draftAddress : webHost.webView.url.toString()
                placeholderText: "Enter URL"
                onEditingFocusChanged: function(focused) {
                    webHost.inputFocused = focused
                    if (focused) {
                        root.addressEditing = true
                        root.draftAddress = webHost.webView.url.toString()
                    } else {
                        root.addressEditing = false
                    }
                }
                onAccepted: navigateTo(text)
                onTextEdited: root.draftAddress = text
            }

            AppButton {
                label: "\u2192"
                accent: true
                onClicked: navigateTo(addressField.text)
            }
        }

        Rectangle {
            id: historyPanel
            Layout.fillWidth: true
            Layout.preferredHeight: Math.min(180, historyList.contentHeight + Theme.spacingMd)
            visible: false
            radius: Theme.radiusMd
            color: Theme.surface
            border.color: Theme.border
            border.width: 1
            clip: true

            Column {
                id: historyList
                anchors.fill: parent
                anchors.margins: Theme.spacingSm
                spacing: Theme.spacingXs

                Repeater {
                    model: webHost.webView.historyItems

                    Rectangle {
                        required property var modelData
                        required property int index
                        width: historyList.width
                        height: 44
                        radius: Theme.radiusSm
                        color: index === webHost.webView.currentHistoryIndex ? Theme.accentSurface : "transparent"

                        MouseArea {
                            anchors.fill: parent
                            onClicked: {
                                var offset = index - webHost.webView.currentHistoryIndex
                                if (offset !== 0)
                                    webHost.webView.goBackOrForward(offset)
                            }
                        }

                        Column {
                            anchors.fill: parent
                            anchors.margins: Theme.spacingSm
                            spacing: 1

                            Label {
                                width: parent.width
                                text: (modelData && modelData.title) ? modelData.title : "(no title)"
                                font.pixelSize: Theme.fontMd
                                font.bold: index === webHost.webView.currentHistoryIndex
                                elide: Text.ElideRight
                            }
                            Label {
                                width: parent.width
                                text: (modelData && modelData.url) ? modelData.url : ""
                                font.pixelSize: Theme.fontXs
                                color: Theme.textSecondary
                                elide: Text.ElideMiddle
                            }
                        }
                    }
                }
            }
        }

        WebViewHost {
            id: webHost
            Layout.fillWidth: true
            Layout.preferredHeight: 420
            initialUrl: "https://example.com/"
        }
    }

    function navigateTo(rawText) {
        webHost.loadAddress(rawText)
        addressField.focus = false
        root.statusMessage("loadAddress: " + rawText)
    }

    Connections {
        target: webHost.webView
        function onUrlChanged() {
            if (!root.addressEditing)
                addressField.text = webHost.webView.url.toString()
        }
    }
}
