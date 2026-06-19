import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import MobileWebViewTest

Item {
    id: root

    property string title: ""
    property bool showNavBar: true

    property bool hasWebView: true
    property url initialUrl: "https://example.com/"
    property bool autoLoad: true
    property var userScripts: []
    property var webChannel: null
    property bool freeze: false
    property int webViewHeight: 420
    property bool contentFillsViewport: false

    property var externalWebView: null
    readonly property var webView: externalWebView
        ? externalWebView
        : (webLoader.item ? webLoader.item.webView : null)

    property bool addressFocused: false
    property bool contentInputFocused: false

    default property alias contentData: contentSlot.data

    signal backRequested()
    signal statusMessage(string message)

    function teardown() {
        webLoader.active = false
    }

    function goBack() {
        teardown()
        backRequested()
    }

    function navigateTo(rawText) {
        var text = (rawText || "").trim()
        if (text.length === 0)
            return
        if (text.indexOf("://") === -1)
            text = "https://" + text

        if (root.externalWebView) {
            root.externalWebView.loadUrl(text)
        } else if (webLoader.item) {
            webLoader.item.loadAddress(rawText)
        } else {
            return
        }

        addressField.focus = false
        root.statusMessage("loadAddress: " + text)
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: headerRow.implicitHeight + Theme.spacingSm * 2
            color: Theme.surfaceMuted

            RowLayout {
                id: headerRow
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                anchors.margins: Theme.spacingSm
                spacing: Theme.spacingSm

                AppButton {
                    label: "\u2190 Menu"
                    onClicked: root.goBack()
                }

                Label {
                    Layout.fillWidth: true
                    text: root.title
                    font.pixelSize: Theme.fontXl
                    font.bold: true
                    color: Theme.textPrimary
                    elide: Text.ElideRight
                }
            }
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: navRow.implicitHeight + Theme.spacingSm * 2
            visible: root.showNavBar && (root.hasWebView || root.externalWebView)
            color: Theme.surface

            RowLayout {
                id: navRow
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                anchors.margins: Theme.spacingSm
                spacing: Theme.spacingXs

                AppButton {
                    label: "\u25C0"
                    enabled: root.webView ? root.webView.canGoBack : false
                    onClicked: if (root.webView) root.webView.goBack()
                }
                AppButton {
                    label: "\u25B6"
                    enabled: root.webView ? root.webView.canGoForward : false
                    onClicked: if (root.webView) root.webView.goForward()
                }
                AppButton {
                    label: (root.webView && root.webView.loading) ? "\u2715" : "\u21BB"
                    onClicked: {
                        if (!root.webView)
                            return
                        if (root.webView.loading)
                            root.webView.stop()
                        else
                            root.webView.reload()
                    }
                }

                AppTextField {
                    id: addressField
                    Layout.fillWidth: true
                    addressMode: true
                    placeholderText: "Enter URL"
                    text: root.webView ? root.webView.url.toString() : ""
                    onEditingFocusChanged: function(focused) {
                        root.addressFocused = focused
                    }
                    onAccepted: root.navigateTo(text)
                }

                AppButton {
                    label: "\u2192"
                    accent: true
                    onClicked: root.navigateTo(addressField.text)
                }
            }
        }

        Rectangle {
            Layout.fillWidth: true
            height: 3
            color: Theme.borderStrong
            visible: root.webView && (root.webView.loading || (root.webView.loadProgress > 0 && root.webView.loadProgress < 100))

            Rectangle {
                width: parent.width * (root.webView ? Math.max(0, Math.min(root.webView.loadProgress, 100)) / 100 : 0)
                height: parent.height
                color: Theme.accent
            }
        }

        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true

            ColumnLayout {
                anchors.fill: parent
                spacing: 0

                Flickable {
                    id: contentFlickable
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    contentWidth: width
                    contentHeight: contentColumn.implicitHeight
                    clip: true

                    Column {
                        id: contentColumn
                        width: Math.min(parent.width, Theme.contentMaxWidth)
                        anchors.horizontalCenter: parent.horizontalCenter
                        topPadding: Theme.spacingMd
                        bottomPadding: Theme.spacingMd
                        leftPadding: Theme.spacingMd
                        rightPadding: Theme.spacingMd
                        spacing: Theme.spacingMd

                        Item {
                            id: contentSlot
                            width: parent.width
                            implicitHeight: root.contentFillsViewport
                                ? Math.max(0, contentFlickable.height - topPadding - bottomPadding)
                                : childrenRect.height
                        }
                    }
                }

                Loader {
                    id: webLoader
                    Layout.fillWidth: true
                    Layout.preferredHeight: root.hasWebView ? root.webViewHeight : 0
                    active: root.hasWebView
                    sourceComponent: WebViewHost {
                        initialUrl: root.initialUrl
                        autoLoad: root.autoLoad
                        userScripts: root.userScripts
                        webChannel: root.webChannel
                        freeze: root.freeze
                        inputFocused: root.addressFocused || root.contentInputFocused
                    }
                }
            }
        }
    }

    Connections {
        target: root.webView
        function onUrlChanged() {
            if (!addressField.activeFocus && root.webView)
                addressField.text = root.webView.url.toString()
        }
    }
}
