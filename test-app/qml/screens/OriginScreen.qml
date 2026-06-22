import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../helpers/ProbeUtils.js" as ProbeUtils
import MobileWebViewTest

ScreenScaffold {
    id: root

    required property var stackView

    title: "Origin"
    hasWebView: false

    readonly property string fixedKey: "mwv_iso"
    readonly property string htmlTemplate: "<!doctype html><html><head><meta charset='utf-8'><title>Origin probe</title></head><body><h1>Origin probe</h1><p id='origin'></p><script>document.getElementById('origin').textContent=location.origin;</script></body></html>"

    property string writeValue: ""
    property string paneARead: ""
    property string paneBRead: ""
    property string verdict: "unknown"
    property string step: "idle"

    externalWebView: paneALoader.item ? paneALoader.item.webView : null

    onBackRequested: {
        paneALoader.active = false
        paneBLoader.active = false
        stackView.pop()
    }

    function loadBothPages() {
        if (paneALoader.item)
            paneALoader.item.loadHtml(htmlTemplate, "https://a.local/")
        if (paneBLoader.item)
            paneBLoader.item.loadHtml(htmlTemplate, "https://b.local/")
    }

    function runIsolationTest() {
        if (!paneALoader.item || !paneBLoader.item)
            return
        writeValue = "v-" + Date.now()
        paneARead = ""
        paneBRead = ""
        verdict = "unknown"
        step = "afterWriteA"
        paneALoader.item.webView.runJavaScript(
            ProbeUtils.writeLocalStorageScript(fixedKey, writeValue))
    }

    Component.onCompleted: Qt.callLater(loadBothPages)

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

            AppButton {
                label: "Reload pages"
                onClicked: root.loadBothPages()
            }
            AppButton {
                label: "Run isolation test"
                accent: true
                onClicked: root.runIsolationTest()
            }
        }

        VerdictBadge {
            Layout.fillWidth: true
            verdict: root.verdict
            detail: "wrote=" + root.writeValue + " | A=" + root.paneARead + " | B=" + root.paneBRead
        }

        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: 320
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

                Loader {
                    id: paneALoader
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    active: true
                    sourceComponent: WebViewHost {
                        storageName: "Profile_A"
                        autoLoad: false
                        inputFocused: root.addressFocused || root.contentInputFocused
                    }
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

                Loader {
                    id: paneBLoader
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    active: true
                    sourceComponent: WebViewHost {
                        storageName: "Profile_A"
                        autoLoad: false
                        inputFocused: root.addressFocused || root.contentInputFocused
                    }
                }
            }
        }
    }

    Connections {
        target: paneALoader.item ? paneALoader.item.webView : null
        function onJavaScriptResult(result, error) {
            if (root.step !== "afterWriteA" || (error || "").length > 0)
                return
            root.paneARead = String(result)
            root.step = "afterReadB"
            if (paneBLoader.item)
                paneBLoader.item.webView.runJavaScript(ProbeUtils.readLocalStorageScript(root.fixedKey))
        }
    }

    Connections {
        target: paneBLoader.item ? paneBLoader.item.webView : null
        function onJavaScriptResult(result, error) {
            if (root.step !== "afterReadB" || (error || "").length > 0)
                return
            root.paneBRead = String(result)
            root.step = "idle"
            var parsedB = ProbeUtils.parseProbeValue(root.paneBRead)
            root.verdict = ProbeUtils.compareIsolation(root.writeValue, parsedB ? parsedB.ls : "")
            root.statusMessage("isolation A=" + root.paneARead + " B=" + root.paneBRead)
        }
    }
}
