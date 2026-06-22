import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../helpers/ProbeUtils.js" as ProbeUtils
import MobileWebViewTest

ScreenScaffold {
    id: root

    required property var stackView

    title: "localStorage"
    autoLoad: false
    webViewHeight: 420

    property string probeResult: ""

    onBackRequested: stackView.pop()

    Component.onCompleted: loadStoragePage()

    function loadStoragePage() {
        if (!root.webView)
            return
        if (typeof _storageTestPageHtml === "string" && _storageTestPageHtml.length > 0) {
            root.webView.loadHtml(_storageTestPageHtml, "https://storage-test.local/")
            root.statusMessage("loadStorageTestPage")
            return
        }
        root.webView.loadUrl("qrc:/MobileWebViewTest/web/storage_profile_test.html")
    }

    ColumnLayout {
        width: parent.width
        spacing: Theme.spacingSm

        RowLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingXs

            Label {
                text: "key"
                color: Theme.textSecondary
                font.pixelSize: Theme.fontSm
                Layout.preferredWidth: 32
            }
            AppTextField {
                id: lsKey
                Layout.preferredWidth: 120
                compact: true
                text: "mwv_key"
                onEditingFocusChanged: function(f) { root.contentInputFocused = f }
            }
            Label {
                text: "value"
                color: Theme.textSecondary
                font.pixelSize: Theme.fontSm
            }
            AppTextField {
                id: lsValue
                Layout.fillWidth: true
                compact: true
                text: "persisted"
                onEditingFocusChanged: function(f) { root.contentInputFocused = f }
            }
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingXs

            AppButton {
                label: "Load test page"
                onClicked: root.loadStoragePage()
            }
            AppButton {
                label: "Write"
                accent: true
                onClicked: root.webView.runJavaScript(
                    ProbeUtils.writeLocalStorageScript(lsKey.text.trim(), lsValue.text))
            }
            AppButton {
                label: "Read"
                onClicked: root.webView.runJavaScript(ProbeUtils.readLocalStorageScript(lsKey.text.trim()))
            }
            AppButton {
                label: "Reload"
                onClicked: root.webView.reload()
            }
        }

        Label {
            Layout.fillWidth: true
            visible: root.probeResult.length > 0
            text: "Probe: " + root.probeResult
            color: Theme.accent
            font.pixelSize: Theme.fontSm
            elide: Text.ElideMiddle
        }
    }

    Connections {
        target: root.webView
        function onJavaScriptResult(result, error) {
            if ((error || "").length > 0) {
                root.statusMessage("javaScriptResult error=" + error)
                return
            }
            var text = result === null || result === undefined ? "" : String(result)
            if (text.length > 0) {
                root.probeResult = text
                root.statusMessage("javaScriptResult " + text)
            }
        }
    }
}
