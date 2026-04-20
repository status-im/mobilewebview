import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Rectangle {
    id: root
    color: "#f8f9fa"
    implicitHeight: controlsColumn.implicitHeight + 12
    border.color: "#e0e0e0"
    border.width: 0

    property string address: "https://www.lipsum.com/"
    property string pageTitle: ""
    property int clickCount: 0
    property bool loading: false
    property bool canGoBack: false
    property bool canGoForward: false
    property var historyItems: []
    property int currentHistoryIndex: -1
    property int loadProgress: 0
    property string favicon: ""
    property real zoomFactor: 1.0

    property int findActiveMatch: -1
    property int findMatchCount: 0

    signal backRequested()
    signal forwardRequested()
    signal reloadRequested()
    signal stopRequested()
    signal goRequested(string address)
    signal incrementRequested()
    signal decrementRequested()
    signal jsPopupRequested()
    signal freezeDialogRequested()
    signal clearHistoryRequested()
    signal goBackOrForwardRequested(int offset)
    signal zoomInRequested()
    signal zoomOutRequested()
    signal zoomResetRequested()

    signal findRequested(string text, int flags)
    signal findNextRequested()
    signal findPreviousRequested()
    signal stopFindRequested()
    signal showFindPanelRequested()
    signal hideFindPanelRequested()

    readonly property bool hasInputFocus: addressInput.activeFocus
    readonly property string findText: findInput.text

    property bool findBarVisible: false
    property bool historyPanelVisible: false
    property bool toolsExpanded: false

    property bool hasNativeFindPanel: false
    property bool findSupported: true

    function openFind() {
        if (!hasNativeFindPanel) {
            findBarVisible = true
            findInput.forceActiveFocus()
            findInput.selectAll()
        } else {
            root.showFindPanelRequested()
        }
    }

    function closeFind() {
        if (!hasNativeFindPanel) {
            findBarVisible = false
            root.stopFindRequested()
        } else {
            root.hideFindPanelRequested()
        }
    }

    component ToolBtn : Button {
        id: _toolBtn
        property string label: ""
        property bool accent: false
        implicitWidth: Math.max(42, _toolBtnContent.implicitWidth + 16)
        implicitHeight: 36
        flat: true
        contentItem: Text {
            id: _toolBtnContent
            text: _toolBtn.label
            font.pixelSize: 15
            font.bold: false
            color: !_toolBtn.enabled ? "#b0b0b0"
                   : _toolBtn.accent ? "#ffffff"
                   : _toolBtn.highlighted ? "#1a73e8"
                   : _toolBtn.down ? "#1565c0"
                   : "#3c4043"
            horizontalAlignment: Text.AlignHCenter
            verticalAlignment: Text.AlignVCenter
        }
        background: Rectangle {
            radius: 8
            color: {
                if (_toolBtn.accent)
                    return _toolBtn.down ? "#1565c0" : "#1a73e8"
                if (_toolBtn.highlighted)
                    return _toolBtn.down ? "#d2e3fc" : "#e8f0fe"
                return _toolBtn.down ? "#e0e0e0" : (_toolBtn.hovered ? "#f0f0f0" : "transparent")
            }
        }
    }

    ColumnLayout {
        id: controlsColumn
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.margins: 6
        spacing: 4

        RowLayout {
            Layout.fillWidth: true
            spacing: 2

            ToolBtn {
                label: "\u25C0"
                enabled: root.canGoBack
                onClicked: root.backRequested()
            }
            ToolBtn {
                label: root.loading ? "Stop" : "Reload"
                Layout.fillWidth: true
                onClicked: root.loading ? root.stopRequested() : root.reloadRequested()
            }
            ToolBtn {
                label: "\u25B6"
                enabled: root.canGoForward
                onClicked: root.forwardRequested()
            }
            ToolBtn {
                label: "\u2261"
                highlighted: root.historyPanelVisible
                onClicked: root.historyPanelVisible = !root.historyPanelVisible
                ToolTip.text: "History"
                ToolTip.visible: hovered
            }
            ToolBtn {
                label: root.findBarVisible ? "\u2715 Find" : "Find"
                visible: root.findSupported
                highlighted: root.findBarVisible
                onClicked: root.findBarVisible ? root.closeFind() : root.openFind()
            }
            ToolBtn {
                label: "\u2699"
                highlighted: root.toolsExpanded
                onClicked: root.toolsExpanded = !root.toolsExpanded
                ToolTip.text: "Tools"
                ToolTip.visible: hovered
            }
        }

        Rectangle {
            Layout.fillWidth: true
            height: 3
            color: "#e8e8e8"
            radius: 2
            visible: root.loading || (root.loadProgress > 0 && root.loadProgress < 100)

            Rectangle {
                width: parent.width * Math.max(0, Math.min(root.loadProgress, 100)) / 100
                height: parent.height
                color: "#1a73e8"
                radius: 2
                Behavior on width { NumberAnimation { duration: 120 } }
            }
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: historyListView.contentHeight + 12
            Layout.maximumHeight: 200
            color: "#ffffff"
            radius: 8
            border.color: "#e0e0e0"
            border.width: 1
            visible: root.historyPanelVisible && root.historyItems.length > 0
            clip: true

            Flickable {
                id: historyFlickable
                anchors.fill: parent
                anchors.margins: 6
                contentHeight: historyListView.contentHeight
                clip: true

                Column {
                    id: historyListView
                    width: parent.width
                    spacing: 2

                    readonly property real contentHeight: {
                        var h = 0
                        for (var i = 0; i < historyRepeater.count; ++i) {
                            var item = historyRepeater.itemAt(i)
                            if (item) h += item.height + 2
                        }
                        return Math.max(0, h - 2)
                    }

                    Repeater {
                        id: historyRepeater
                        model: root.historyItems

                        Rectangle {
                            required property var modelData
                            required property int index

                            width: historyListView.width
                            height: historyItemCol.implicitHeight + 8
                            radius: 6
                            color: index === root.currentHistoryIndex ? "#e8f0fe" : (historyMa.containsMouse ? "#f5f5f5" : "transparent")
                            border.color: index === root.currentHistoryIndex ? "#1a73e8" : "transparent"
                            border.width: index === root.currentHistoryIndex ? 1 : 0

                            MouseArea {
                                id: historyMa
                                anchors.fill: parent
                                hoverEnabled: true
                                onClicked: {
                                    var offset = index - root.currentHistoryIndex
                                    if (offset !== 0)
                                        root.goBackOrForwardRequested(offset)
                                }
                            }

                            Column {
                                id: historyItemCol
                                anchors.left: parent.left
                                anchors.right: parent.right
                                anchors.verticalCenter: parent.verticalCenter
                                anchors.margins: 8
                                spacing: 1

                                Label {
                                    width: parent.width
                                    text: (modelData && modelData.title) ? modelData.title : "(no title)"
                                    font.pixelSize: 13
                                    font.bold: index === root.currentHistoryIndex
                                    color: "#202020"
                                    elide: Text.ElideRight
                                }

                                Label {
                                    width: parent.width
                                    text: (modelData && modelData.url) ? modelData.url : ""
                                    font.pixelSize: 11
                                    color: "#707070"
                                    elide: Text.ElideMiddle
                                }
                            }
                        }
                    }
                }
            }

            ToolBtn {
                label: "Clear"
                anchors.right: parent.right
                anchors.top: parent.top
                anchors.margins: 4
                onClicked: root.clearHistoryRequested()
            }
        }

        Label {
            Layout.fillWidth: true
            visible: root.historyPanelVisible && root.historyItems.length === 0
            text: "No history items"
            color: "#909090"
            font.pixelSize: 12
            horizontalAlignment: Text.AlignHCenter
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: 6

            Image {
                id: faviconImage
                source: root.favicon || ""
                Layout.preferredWidth: 16
                Layout.preferredHeight: 16
                visible: root.favicon.length > 0
                fillMode: Image.PreserveAspectFit
                smooth: true
            }

            Label {
                Layout.fillWidth: true
                color: "#3c4043"
                font.pixelSize: 13
                elide: Text.ElideRight
                text: root.pageTitle.length > 0 ? root.pageTitle : "(no title)"
            }
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: 4

            TextField {
                id: addressInput
                Layout.fillWidth: true
                Layout.preferredHeight: 38
                text: root.address
                placeholderText: "Enter URL"
                font.pixelSize: 13
                color: "#202020"
                placeholderTextColor: "#9e9e9e"
                leftPadding: 10
                rightPadding: 10
                verticalAlignment: Text.AlignVCenter
                background: Rectangle {
                    radius: 10
                    color: "#ffffff"
                    border.color: addressInput.activeFocus ? "#1a73e8" : "#dadce0"
                    border.width: addressInput.activeFocus ? 2 : 1
                }
                onAccepted: root.goRequested(text)
            }
            ToolBtn {
                label: "\u2192"
                accent: true
                implicitWidth: 42
                onClicked: root.goRequested(addressInput.text)
                ToolTip.text: "Go"
                ToolTip.visible: hovered
            }
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: 4
            visible: root.findBarVisible && !root.hasNativeFindPanel

            TextField {
                id: findInput
                Layout.fillWidth: true
                Layout.preferredHeight: 36
                placeholderText: "Find in page\u2026"
                font.pixelSize: 13
                color: "#202020"
                placeholderTextColor: "#9e9e9e"
                leftPadding: 10
                rightPadding: 10
                verticalAlignment: Text.AlignVCenter
                background: Rectangle {
                    radius: 10
                    color: root.findMatchCount === 0 && findInput.text.length > 0 ? "#fce8e6" : "#ffffff"
                    border.color: root.findMatchCount === 0 && findInput.text.length > 0 ? "#d93025" : (findInput.activeFocus ? "#1a73e8" : "#dadce0")
                    border.width: findInput.activeFocus ? 2 : 1
                }
                onTextChanged: {
                    if (text.length > 0)
                        root.findRequested(text, caseSensitiveBtn.checked ? 2 : 0)
                    else
                        root.stopFindRequested()
                }
                Keys.onReturnPressed: root.findNextRequested()
                Keys.onEscapePressed: root.closeFind()
            }

            Label {
                id: matchLabel
                text: {
                    if (findInput.text.length === 0) return ""
                    if (root.findMatchCount === 0) return "0/0"
                    return (root.findActiveMatch + 1) + "/" + root.findMatchCount
                }
                color: root.findMatchCount === 0 && findInput.text.length > 0 ? "#d93025" : "#5f6368"
                font.pixelSize: 12
                Layout.preferredWidth: 44
                horizontalAlignment: Text.AlignHCenter
            }

            ToolBtn {
                label: "\u25B2"
                enabled: findInput.text.length > 0
                onClicked: root.findPreviousRequested()
            }
            ToolBtn {
                label: "\u25BC"
                enabled: findInput.text.length > 0
                onClicked: root.findNextRequested()
            }

            ToolBtn {
                id: caseSensitiveBtn
                label: "Aa"
                checkable: true
                highlighted: checked
                onCheckedChanged: {
                    if (findInput.text.length > 0)
                        root.findRequested(findInput.text, checked ? 2 : 0)
                }
            }

            ToolBtn {
                label: "\u2715"
                onClicked: root.closeFind()
            }
        }

        ColumnLayout {
            Layout.fillWidth: true
            spacing: 4
            visible: root.toolsExpanded

            Rectangle {
                Layout.fillWidth: true
                height: 1
                color: "#e8e8e8"
            }

            RowLayout {
                Layout.fillWidth: true
                spacing: 4

                Label {
                    text: "Zoom"
                    color: "#5f6368"
                    font.pixelSize: 12
                }
                ToolBtn {
                    label: "\u2212"
                    onClicked: root.zoomOutRequested()
                }
                Label {
                    Layout.preferredWidth: 44
                    horizontalAlignment: Text.AlignHCenter
                    color: "#3c4043"
                    font.pixelSize: 13
                    font.bold: true
                    text: Math.round(root.zoomFactor * 100) + "%"
                }
                ToolBtn {
                    label: "+"
                    onClicked: root.zoomInRequested()
                }
                ToolBtn {
                    label: "Reset"
                    onClicked: root.zoomResetRequested()
                }
                Item { Layout.fillWidth: true }
            }

            RowLayout {
                Layout.fillWidth: true
                spacing: 4

                Label {
                    text: "Counter"
                    color: "#5f6368"
                    font.pixelSize: 12
                }

                ToolBtn {
                    label: "\u2212"
                    onClicked: root.decrementRequested()
                }

                Label {
                    Layout.preferredWidth: 40
                    horizontalAlignment: Text.AlignHCenter
                    color: "#3c4043"
                    font.pixelSize: 15
                    font.bold: true
                    text: String(root.clickCount)
                }

                ToolBtn {
                    label: "+"
                    onClicked: root.incrementRequested()
                }

                Item { Layout.fillWidth: true }

                ToolBtn {
                    label: "JS Popup"
                    accent: true
                    onClicked: root.jsPopupRequested()
                }

                ToolBtn {
                    label: "Freeze dialog"
                    highlighted: true
                    onClicked: root.freezeDialogRequested()
                    ToolTip.text: "Opens QML dialog; WebView uses freeze while open"
                    ToolTip.visible: hovered
                }
            }
        }
    }
}
