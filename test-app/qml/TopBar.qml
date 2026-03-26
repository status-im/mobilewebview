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

    // Find-in-page state (set by parent via findTextResult)
    property int findActiveMatch: -1   // -1 = no match / session closed
    property int findMatchCount: 0

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

    // Find-in-page signals
    signal findRequested(string text, int flags)
    signal findNextRequested()
    signal findPreviousRequested()
    signal stopFindRequested()
    signal showFindPanelRequested()
    signal hideFindPanelRequested()

    // Keep WebView interactive while using find-in-page so WKWebView selection highlight stays visible.
    readonly property bool hasInputFocus: addressInput.activeFocus
    readonly property string findText: findInput.text

    // Internal state
    property bool findBarVisible: false

    // Find capabilities are provided by backend to avoid platform-specific checks in QML.
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
            Button {
                text: "🔍"
                Layout.preferredWidth: 52
                visible: root.findSupported
                highlighted: root.findBarVisible
                onClicked: root.findBarVisible ? root.closeFind() : root.openFind()
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

        // Find-in-page bar is used when no native find panel is available.
        RowLayout {
            Layout.fillWidth: true
            spacing: 6
            visible: root.findBarVisible && !root.hasNativeFindPanel

            TextField {
                id: findInput
                Layout.fillWidth: true
                placeholderText: "Find in page…"
                color: "#202020"
                placeholderTextColor: "#909090"
                background: Rectangle {
                    radius: 6
                    color: root.findMatchCount === 0 && findInput.text.length > 0
                           ? "#fff0f0"
                           : "#ffffff"
                    border.color: root.findMatchCount === 0 && findInput.text.length > 0
                                  ? "#e08080"
                                  : "#bcbcbc"
                    border.width: 1
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
                    if (root.findMatchCount === 0) return "No matches"
                    return (root.findActiveMatch + 1) + " / " + root.findMatchCount
                }
                color: root.findMatchCount === 0 && findInput.text.length > 0 ? "#c0392b" : "#505050"
                font.pixelSize: 12
                Layout.preferredWidth: 80
                horizontalAlignment: Text.AlignHCenter
            }

            Button {
                text: "▲"
                Layout.preferredWidth: 40
                enabled: findInput.text.length > 0
                onClicked: root.findPreviousRequested()
            }
            Button {
                text: "▼"
                Layout.preferredWidth: 40
                enabled: findInput.text.length > 0
                onClicked: root.findNextRequested()
            }

            Button {
                id: caseSensitiveBtn
                text: "Aa"
                Layout.preferredWidth: 44
                checkable: true
                highlighted: checked
                onCheckedChanged: {
                    if (findInput.text.length > 0)
                        root.findRequested(findInput.text, checked ? 2 : 0)
                }
            }

            Button {
                text: "✕"
                Layout.preferredWidth: 36
                onClicked: root.closeFind()
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
