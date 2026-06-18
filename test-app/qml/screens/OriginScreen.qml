import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../helpers/ProbeUtils.js" as ProbeUtils
import MobileWebViewTest

ScreenScaffold {
    id: root

    required property var stackView

    title: "Origin"
    property string writeValue: "origin-a-" + Date.now()
    property string paneARead: ""
    property string paneBRead: ""
    property string verdict: "unknown"
    property bool inputFocused: false

    onBackRequested: stackView.pop()

    readonly property string htmlTemplate: "<!doctype html><html><head><meta charset='utf-8'><title>Origin probe</title></head><body><h1>Origin probe</h1><p id='origin'></p><script>document.getElementById('origin').textContent=location.origin;</script></body></html>"

    function loadBothPages() {
        paneA.loadHtml(htmlTemplate, "https://a.local/")
        paneB.loadHtml(htmlTemplate, "https://b.local/")
    }

    function updateVerdict() {
        var parsedA = ProbeUtils.parseProbeValue(paneARead)
        var parsedB = ProbeUtils.parseProbeValue(paneBRead)
        var valueA = parsedA ? (parsedA.ls || "") : ""
        var valueB = parsedB ? (parsedB.ls || "") : ""
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
            text: "Same partition (Profile_A), different origins via loadHtml base URLs."
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
                    root.writeValue = "origin-a-" + Date.now()
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
                    text: "Pane A — https://a.local/"
                    font.bold: true
                    color: Theme.textPrimary
                    font.pixelSize: Theme.fontSm
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
                    text: "Pane B — https://b.local/"
                    font.bold: true
                    color: Theme.textPrimary
                    font.pixelSize: Theme.fontSm
                }
                WebViewHost {
                    id: paneB
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    storageName: "Profile_A"
                    autoLoad: false
                    inputFocused: root.inputFocused
                }
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
