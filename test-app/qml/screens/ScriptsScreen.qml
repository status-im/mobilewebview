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
    autoLoad: false
    webViewHeight: 420

    property string bridgeProbe: ""
    property string isolationProbe: ""
    property string isolationVerdict: "unknown"

    property bool _bridgePageLoaded: false
    property string _probeNonce: ""
    property var _probePart: null

    readonly property var scriptResources: [
        { "path": "qrc:/MobileWebViewTest/js/qwebchannel.js", "runOnSubFrames": false },
        { "path": "qrc:/MobileWebViewTest/js/test_script.js", "runOnSubFrames": false },
        { "path": "qrc:/MobileWebViewTest/js/isolation_probe_script.js", "runOnSubFrames": false }
    ]

    userScripts: scriptResources
    webChannel: WebChannel {
        registeredObjects: [testBridge]
    }

    onBackRequested: stackView.pop()

    Component.onCompleted: tryAutoLoad()

    Timer {
        id: autoLoadTimer
        interval: 50
        repeat: true
        running: !root._bridgePageLoaded
        onTriggered: root.tryAutoLoad()
    }

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

    function tryAutoLoad() {
        if (root._bridgePageLoaded || !root.webView)
            return
        root._bridgePageLoaded = true
        loadBridgePage()
    }

    function loadBridgePage() {
        if (!root.webView)
            return

        root.isolationVerdict = "unknown"
        root.isolationProbe = ""

        if (typeof _webChannelTestPageHtml === "string" && _webChannelTestPageHtml.length > 0) {
            root.webView.loadHtml(_webChannelTestPageHtml, "https://test.local/")
            root.statusMessage("loadTestPage (loadHtml)")
            return
        }
        root.webView.loadUrl("qrc:/MobileWebViewTest/web/test_webchannel.html")
        root.statusMessage("loadTestPage (loadUrl fallback)")
    }

    function runIsolationProbe() {
        if (!root.webView)
            return

        readbackTimer.stop()
        root.isolationVerdict = "inconclusive"
        root.isolationProbe = "Running probe…"
        root._probePart = null
        root._probeNonce = String(Date.now())
        root.webView.runJavaScript(ProbeUtils.isolationProbeScript(root._probeNonce))
    }

    function finalizeVerdict(readback) {
        var probe = root._probePart
        var userscriptRan = probe && probe.userscriptRan === "set"
        var pageReacted = readback && readback.pageNonce === root._probeNonce

        if (!probe || !userscriptRan || !pageReacted) {
            root.isolationVerdict = "inconclusive"
            root.isolationProbe = "Page or user scripts not ready — load the bridge page and retry."
            root.statusMessage("isolation verdict=inconclusive")
            return
        }

        var isolatedGlobals = probe.isolatedSeesPageVar === "undefined"
                && readback.pageSeesBridgeVar === "undefined"
        var domShared = readback.pageDomMarker === "set"

        if (isolatedGlobals && domShared) {
            root.isolationVerdict = "isolated"
            root.isolationProbe = "Platform provides an isolated content world: bridge globals are "
                    + "hidden from the page; the shared channel is the DOM. User scripts run in "
                    + "page-world (sees __userscriptVar=" + readback.pageSeesUserscriptVar + ")."
        } else {
            root.isolationVerdict = "shared"
            root.isolationProbe = "Bridge runs in page-world on this platform (Android / pre-iOS14 / "
                    + "pre-macOS11): globals are shared. The DOM bridge channel still works."
        }
        root.statusMessage("isolation verdict=" + root.isolationVerdict)
    }

    Timer {
        id: readbackTimer
        interval: 50
        repeat: true
        property int attempts: 0

        onTriggered: {
            if (!root.webView) {
                stop()
                return
            }
            attempts += 1
            if (attempts > 20) {
                stop()
                root.finalizeVerdict(null)
                return
            }
            root.webView.runJavaScript(ProbeUtils.isolationReadbackScript())
        }
    }

    ColumnLayout {
        width: parent.width
        spacing: Theme.spacingSm

        Label {
            Layout.fillWidth: true
            wrapMode: Text.WordWrap
            color: Theme.textSecondary
            font.pixelSize: Theme.fontSm
            text: "WebChannel uses the shared DOM while bridge and page JS globals stay isolated. "
                    + "Buttons below exercise the round-trip; Run isolation probe measures the split."
        }

        Flow {
            Layout.fillWidth: true
            spacing: Theme.spacingSm

            AppButton {
                label: "Load / reload bridge page"
                onClicked: root.loadBridgePage()
            }
            AppButton {
                label: "QML increment"
                accent: true
                onClicked: testBridge.incrementFromJs("qml-plus")
            }
            AppButton {
                label: "JS popup"
                onClicked: root.webView.runJavaScript(
                    "document.dispatchEvent(new CustomEvent('__test_show_popup__')); 'ok'")
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
    }

    Connections {
        target: testBridge
        function onQmlEvent(message) {
            root.bridgeProbe = message
            root.statusMessage(message)
        }
    }

    Connections {
        target: root.webView
        function onJavaScriptResult(result, error) {
            if ((error || "").length > 0) {
                root.statusMessage("javaScriptResult error=" + error)
                return
            }

            var parsed = ProbeUtils.parseProbeValue(result === null || result === undefined ? "" : String(result))
            if (!parsed)
                return

            if (parsed.kind === "probe") {
                root._probePart = parsed
                readbackTimer.attempts = 0
                readbackTimer.start()
            } else if (parsed.kind === "readback") {
                if (parsed.pageNonce === root._probeNonce) {
                    readbackTimer.stop()
                    root.finalizeVerdict(parsed)
                }
            }
        }
    }
}
