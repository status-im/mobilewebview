import QtQuick
import QtWebChannel
import MobileWebView 1.0

Item {
    id: root

    readonly property alias bridgeObject: testBridge
    property url initialUrl: "https://opensea.io"
    property url testPageUrl: "qrc:/web/test_webchannel.html"
    readonly property alias webView: webView
    readonly property int clickCount: testBridge.clickCount
    readonly property string lastMessage: testBridge.lastMessage
    readonly property bool loading: webView.loading
    readonly property string currentUrlText: webView.url.toString()
    readonly property string pageTitle: webView.title
    readonly property bool canGoBack: webView.canGoBack
    readonly property bool canGoForward: webView.canGoForward
    readonly property int loadProgress: webView.loadProgress
    readonly property string favicon: webView.favicon
    readonly property real zoomFactor: webView.zoomFactor

    signal logMessage(string message)

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

        function resetCounter() {
            clickCount = 0
            lastMessage = "reset"
            qmlEvent("counter reset")
        }
    }

    function toJsLiteral(value) {
        return JSON.stringify(String(value))
    }

    function normalizeUrl(rawText) {
        var text = (rawText || "").trim()
        if (text.length === 0)
            return ""
        if (text.indexOf("://") === -1)
            text = "https://" + text
        return text
    }

    function loadAddress(rawText) {
        var normalized = normalizeUrl(rawText)
        if (normalized.length === 0)
            return
        webView.loadUrl(normalized)
        logMessage("loadAddress: " + normalized)
    }

    function loadTestPage() {
        var request = new XMLHttpRequest()
        request.open("GET", testPageUrl)
        request.onreadystatechange = function() {
            if (request.readyState !== XMLHttpRequest.DONE)
                return

            if (request.status === 200 || request.responseText.length > 0) {
                webView.loadHtml(request.responseText, "https://test.local")
                logMessage("loadTestPage (loadHtml from resource)")
            } else {
                webView.loadUrl(testPageUrl)
                logMessage("loadTestPage fallback url: " + testPageUrl)
            }
        }
        request.send()
    }

    function qmlShowPopup(text) {
        webView.runJavaScript(
            "if (window.__testWebChannel) { window.__testWebChannel.showPopupFromQml(" + toJsLiteral(text) + "); }"
        )
    }

    function qmlIncrementViaWebChannel(reason) {
        webView.runJavaScript(
            "if (window.__testWebChannel) { window.__testWebChannel.incrementViaWebChannel(" + toJsLiteral(reason) + "); }"
        )
    }

    function incrementCounter() {
        testBridge.incrementFromJs("qml-plus")
    }

    function decrementCounter() {
        testBridge.clickCount -= 1
        testBridge.lastMessage = "qml-minus"
        testBridge.qmlEvent("decrementFromQml: qml-minus")
    }

    function qmlShowStaticPopup() {
        var script = "(function(count) {" +
            "  var id = '__qml_center_overlay';" +
            "  var el = document.getElementById(id);" +
            "  if (!el) {" +
            "    el = document.createElement('div');" +
            "    el.id = id;" +
            "    el.style.position = 'fixed';" +
            "    el.style.left = '50%';" +
            "    el.style.top = '50%';" +
            "    el.style.transform = 'translate(-50%, -50%)';" +
            "    el.style.padding = '14px 18px';" +
            "    el.style.background = 'rgba(0, 0, 0, 0.84)';" +
            "    el.style.color = '#fff';" +
            "    el.style.fontSize = '18px';" +
            "    el.style.fontFamily = 'sans-serif';" +
            "    el.style.borderRadius = '10px';" +
            "    el.style.boxShadow = '0 6px 20px rgba(0,0,0,0.35)';" +
            "    el.style.zIndex = '2147483647';" +
            "    document.documentElement.appendChild(el);" +
            "  }" +
            "  el.textContent = 'counter=' + count;" +
            "  el.style.display = 'block';" +
            "  clearTimeout(window.__qml_overlay_timer);" +
            "  window.__qml_overlay_timer = setTimeout(function() {" +
            "    if (el) el.style.display = 'none';" +
            "  }, 1800);" +
            "  return 'overlay_shown';" +
            "})(" + String(testBridge.clickCount) + ");"
        webView.runJavaScript(
            script
        )
    }

    function emitQmlEvent(message) {
        testBridge.qmlEvent(message)
    }

    function resetCounter() {
        testBridge.resetCounter()
    }

    MobileWebViewBackend {
        id: webView
        anchors.fill: parent
        webChannelNamespace: "qt"
        webChannel: WebChannel {
            id: channel
            registeredObjects: [testBridge]
        }
        userScripts: [
            { "path": "qrc:/js/qwebchannel.js", "runOnSubFrames": false },
            { "path": "qrc:/js/test_script.js", "runOnSubFrames": false }
        ]
        url: root.initialUrl
    }

    Connections {
        target: webView
        function onWebMessageReceived(message, origin, isMainFrame) {
            root.logMessage("webMessageReceived origin=" + origin + " main=" + isMainFrame + " msg=" + message)
        }
        function onJavaScriptResult(result, error) {
            var hasError = (error || "").length > 0
            var textResult = result === null || result === undefined ? "" : String(result)
            var hasResult = textResult.length > 0 && textResult !== "null" && textResult !== "undefined"
            if (hasError || hasResult)
                root.logMessage("javaScriptResult result=" + result + " error=" + error)
        }
        function onNewWindowRequested(url, userInitiated) {
            root.logMessage("newWindowRequested url=" + url + " userInitiated=" + userInitiated)
            if (url && String(url).length > 0)
                webView.loadUrl(url)
        }
    }
}
