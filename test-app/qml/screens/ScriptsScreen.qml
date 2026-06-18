import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtWebChannel
import "../helpers/ProbeUtils.js" as ProbeUtils
import MobileWebViewTest

ScreenScaffold {
    id: root

    required property var stackView

    title: "Scripts & isolation"
    property string bridgeProbe: ""
    property string isolationProbe: ""
    property string isolationVerdict: "unknown"
    property bool inputFocused: false

    onBackRequested: stackView.pop()

    QtObject {
        id: testBridge
        objectName: "testBridge"
        WebChannel.id: "testBridge"
        property int clickCount: 0
        property string lastMessage: "none"
        signal qmlEvent(string message)

        function incrementFromJs(reason) {
            clickCount += 1
            lastMessage = reason
            qmlEvent("incrementFromJs: " + reason)
            return clickCount
        }
    }

    function loadBridgePage() {
        var request = new XMLHttpRequest()
        request.open("GET", "qrc:/web/test_webchannel.html")
        request.onreadystatechange = function() {
            if (request.readyState !== XMLHttpRequest.DONE)
                return
            if (request.responseText.length > 0) {
                webHost.loadHtml(request.responseText, "https://test.local")
                root.statusMessage("loadTestPage (loadHtml)")
            } else {
                webHost.webView.loadUrl("qrc:/web/test_webchannel.html")
            }
        }
        request.send()
    }

    function runIsolationProbe() {
        webHost.webView.runJavaScript(ProbeUtils.isolationProbeScript())
    }

    function evaluateIsolationResult(text) {
        var parsed = ProbeUtils.parseProbeValue(text)
        if (!parsed)
            return
        var isolated = parsed.pageSeesUserscriptVar === "undefined"
                && parsed.userscriptDomMarker === "set"
                && parsed.userscriptSeesPage === "undefined"
        root.isolationVerdict = isolated ? "isolated" : "shared"
    }

    ColumnLayout {
        width: parent.width
        spacing: Theme.spacingSm

        Label {
            Layout.fillWidth: true
            wrapMode: Text.WordWrap
            color: Theme.textSecondary
            font.pixelSize: Theme.fontSm
            text: "WebChannel bridge round-trip plus userscript/page JS world isolation probe."
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingSm

            AppButton {
                label: "Load bridge page"
                onClicked: root.loadBridgePage()
            }
            AppButton {
                label: "QML increment"
                accent: true
                onClicked: testBridge.incrementFromJs("qml-plus")
            }
            AppButton {
                label: "JS popup"
                onClicked: webHost.webView.runJavaScript(
                    "if (window.__testWebChannel) { window.__testWebChannel.showStaticPopup(); 'ok'; }")
            }
            AppButton {
                label: "Run isolation probe"
                highlighted: true
                onClicked: root.runIsolationProbe()
            }
        }

        Label {
            Layout.fillWidth: true
            visible: root.bridgeProbe.length > 0
            text: "Bridge: count=" + testBridge.clickCount + " last=" + testBridge.lastMessage
            color: Theme.accent
            font.pixelSize: Theme.fontSm
        }

        VerdictBadge {
            Layout.fillWidth: true
            visible: root.isolationProbe.length > 0
            verdict: root.isolationVerdict
            detail: root.isolationProbe
        }

        WebViewHost {
            id: webHost
            Layout.fillWidth: true
            Layout.preferredHeight: 420
            autoLoad: false
            inputFocused: root.inputFocused
            webChannel: WebChannel {
                registeredObjects: [testBridge]
            }
            userScripts: [
                { "path": "qrc:/js/qwebchannel.js", "runOnSubFrames": false },
                { "path": "qrc:/js/test_script.js", "runOnSubFrames": false },
                { "path": "qrc:/js/isolation_probe_script.js", "runOnSubFrames": false }
            ]
            Component.onCompleted: root.loadBridgePage()
        }
    }

    Connections {
        target: testBridge
        function onQmlEvent(message) {
            root.bridgeProbe = message
            root.statusMessage(message)
        }
    }

    Connections {
        target: webHost.webView
        function onJavaScriptResult(result, error) {
            if ((error || "").length > 0) {
                root.statusMessage("javaScriptResult error=" + error)
                return
            }
            var text = result === null || result === undefined ? "" : String(result)
            if (text.indexOf("pageSeesUserscriptVar") >= 0) {
                root.isolationProbe = text
                root.evaluateIsolationResult(text)
                root.statusMessage("isolation probe " + text)
            }
        }
    }
}
