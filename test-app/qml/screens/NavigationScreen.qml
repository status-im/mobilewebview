import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import MobileWebViewTest

ScreenScaffold {
    id: root

    required property var stackView

    title: "Navigation & History"
    initialUrl: "https://example.com/"

    property bool historyVisible: false

    onBackRequested: stackView.pop()

    ColumnLayout {
        width: parent.width
        spacing: Theme.spacingSm

        RowLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingSm

            AppButton {
                label: "Google"
                onClicked: root.navigateTo("google.com")
            }
            AppButton {
                label: "Netflix"
                onClicked: root.navigateTo("netflix.com")
            }
            AppButton {
                label: "Amazon"
                onClicked: root.navigateTo("amazon.com")
            }
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingSm

            Image {
                source: root.webView ? (root.webView.favicon || "") : ""
                Layout.preferredWidth: 16
                Layout.preferredHeight: 16
                visible: root.webView && (root.webView.favicon || "").length > 0
                fillMode: Image.PreserveAspectFit
            }

            Label {
                Layout.fillWidth: true
                text: root.webView && root.webView.title.length > 0 ? root.webView.title : "(no title)"
                elide: Text.ElideRight
                color: Theme.textPrimary
                font.pixelSize: Theme.fontMd
            }

            AppButton {
                label: root.historyVisible ? "\u2715 History" : "History"
                highlighted: root.historyVisible
                onClicked: root.historyVisible = !root.historyVisible
            }
        }

        Rectangle {
            id: historyPanel
            Layout.fillWidth: true
            Layout.preferredHeight: visible ? Math.min(180, historyList.contentHeight + Theme.spacingSm * 2) : 0
            visible: root.historyVisible && historyList.count > 0
            radius: Theme.radiusMd
            color: Theme.surface
            border.color: Theme.border
            border.width: 1
            clip: true

            ListView {
                id: historyList
                anchors.fill: parent
                anchors.margins: Theme.spacingSm
                spacing: 2
                interactive: false
                model: root.webView ? root.webView.historyItems : []

                delegate: Rectangle {
                    required property var modelData
                    required property int index

                    width: historyList.width
                    height: 44
                    radius: Theme.radiusSm
                    color: root.webView && index === root.webView.currentHistoryIndex
                           ? Theme.accentSurface : (historyMa.containsMouse ? "#f5f5f5" : "transparent")

                    MouseArea {
                        id: historyMa
                        anchors.fill: parent
                        hoverEnabled: true
                        onClicked: {
                            if (!root.webView)
                                return
                            var offset = index - root.webView.currentHistoryIndex
                            if (offset !== 0)
                                root.webView.goBackOrForward(offset)
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
                            font.bold: root.webView && index === root.webView.currentHistoryIndex
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

        Label {
            Layout.fillWidth: true
            visible: root.historyVisible && historyList.count === 0
            text: "No history items"
            color: Theme.textSecondary
            font.pixelSize: Theme.fontSm
            horizontalAlignment: Text.AlignHCenter
        }
    }
}
