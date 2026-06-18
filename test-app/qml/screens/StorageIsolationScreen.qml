import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../helpers/ProbeUtils.js" as ProbeUtils
import MobileWebViewTest

ScreenScaffold {
    id: root

    required property var stackView

    title: "Storage isolation"
    property string writeValue: "pane-a-" + Date.now()
    property string paneARead: ""
    property string paneBRead: ""
    property string verdict: "unknown"
    property bool inputFocused: false

    readonly property string storageBaseUrl: "https://storage-test.local/"

    onBackRequested: stackView.pop()

    function loadBothPages() {
        var html = typeof _storageTestPageHtml === "string" ? _storageTestPageHtml : ""
        if (html.length > 0) {
            paneA.loadHtml(html, storageBaseUrl)
            paneB.loadHtml(html, storageBaseUrl)
        } else {
            paneA.webView.loadUrl("qrc:/web/storage_profile_test.html")
            paneB.webView.loadUrl("qrc:/web/storage_profile_test.html")
        }
    }

    function updateVerdict() {
        var parsedA = ProbeUtils.parseProbeValue(paneARead)
        var parsedB = ProbeUtils.parseProbeValue(paneBRead)
        var valueA = parsedA ? (parsedA.ls || parsedA.cookie || "") : ""
        var valueB = parsedB ? (parsedB.ls || parsedB.cookie || "") : ""
        root.verdict = ProbeUtils.compareIsolation(root.writeValue, valueB)
        root.statusMessage("A=" + valueA + " B=" + valueB + " wrote=" + root.writeValue)
    }

    ColumnLayout {
        width: parent.width
        spacing: Theme.spacingSm

        Label {
            Layout.fillWidth: true
            wrapMode: Text.WordWrap
            color: Theme.textSecondary
            font.pixelSize: Theme.fontSm
            text: "Same origin, different partitions. Write in pane A, read the same key in pane B."
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingSm

            Label { text: "key"; color: Theme.textSecondary; font.pixelSize: Theme.fontSm }
            AppTextField {
                id: lsKey
                Layout.preferredWidth: 120
                compact: true
                text: "mwv_key"
                onEditingFocusChanged: function(f) { root.inputFocused = f }
            }
            AppButton {
                label: "Load pages"
                onClicked: root.loadBothPages()
            }
            AppButton {
                label: "Write A"
                accent: true
                onClicked: {
                    root.writeValue = "pane-a-" + Date.now()
                    paneA.webView.runJavaScript(
                        ProbeUtils.writeLocalStorageScript(lsKey.text.trim(), root.writeValue))
                }
            }
            AppButton {
                label: "Read A"
                onClicked: paneA.webView.runJavaScript(ProbeUtils.readLocalStorageScript(lsKey.text.trim()))
            }
            AppButton {
                label: "Read B"
                onClicked: paneB.webView.runJavaScript(ProbeUtils.readLocalStorageScript(lsKey.text.trim()))
            }
        }

        VerdictBadge {
            Layout.fillWidth: true
            verdict: root.verdict
            detail: "wrote=" + root.writeValue + " | A=" + root.paneARead + " | B=" + root.paneBRead
        }

        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: 360
            spacing: Theme.spacingSm

            ColumnLayout {
                Layout.fillWidth: true
                Layout.fillHeight: true
                spacing: Theme.spacingXs

                Label {
                    text: "Pane A — Profile_A"
                    font.bold: true
                    color: Theme.textPrimary
                }
                WebViewHost {
                    id: paneA
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    storageName: "Profile_A"
                    autoLoad: false
                    inputFocused: root.inputFocused
                }
            }

            ColumnLayout {
                Layout.fillWidth: true
                Layout.fillHeight: true
                spacing: Theme.spacingXs

                Label {
                    text: "Pane B — Profile_B"
                    font.bold: true
                    color: Theme.textPrimary
                }
                WebViewHost {
                    id: paneB
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    storageName: "Profile_B"
                    autoLoad: false
                    inputFocused: root.inputFocused
                }
            }
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingSm

            Switch {
                id: incognitoSwitch
                text: "Pane B incognito"
                onToggled: paneB.offTheRecord = checked
            }
            Label {
                Layout.fillWidth: true
                wrapMode: Text.WordWrap
                color: Theme.textSecondary
                font.pixelSize: Theme.fontXs
                text: "Toggle incognito on pane B to compare ephemeral storage."
            }
        }
    }

    Component.onCompleted: loadBothPages()

    Connections {
        target: paneA.webView
        function onJavaScriptResult(result, error) {
            if ((error || "").length === 0 && result !== undefined && result !== null)
                root.paneARead = String(result)
            root.updateVerdict()
        }
    }

    Connections {
        target: paneB.webView
        function onJavaScriptResult(result, error) {
            if ((error || "").length === 0 && result !== undefined && result !== null)
                root.paneBRead = String(result)
            root.updateVerdict()
        }
    }
}
