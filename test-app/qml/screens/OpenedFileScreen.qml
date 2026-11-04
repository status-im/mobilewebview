import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import MobileWebViewTest

// Lands here when the OS hands the app a file through "Open with": records
// the receipt, reads the file in place and through a copy, and loads it in
// the Web View with a one-file read grant. Re-reads at +60/120/180 s measure
// how long the grant lasts while the app waits (e.g. for a login).
ScreenScaffold {
    id: root

    required property var stackView

    readonly property bool isOpenedFileScreen: true
    property var receipt: null
    property var steps: []
    property string copyUrl: ""
    property string pendingLoad: ""
    property double loadStartedAt: 0
    property double receivedAtMs: 0
    property bool jsPending: false
    property int rereadCount: 0

    readonly property string fingerprintScript: "(function(){try{"
        + "var b=document.body;var m=document.querySelector('video,audio,embed,object,img');"
        + "return JSON.stringify({contentType:document.contentType||'',"
        + "textLength:b?(b.innerText||'').length:-1,"
        + "text:b?(b.innerText||'').slice(0,60):'',media:m?m.tagName:''});"
        + "}catch(e){return 'error: '+e}})()"

    title: "Opened file"
    initialUrl: "about:blank"
    webViewHeight: 360

    onBackRequested: stackView.pop()

    readonly property var agentActions: ({
        probe: function() { if (root.receipt) root.runProbes(root.receipt) },
        loadInPlace: function() { root.loadInPlace() },
        loadCopy: function() { root.loadCopy() },
        reread: function() { root.reread("manual") },
        clearLog: function() { _openedFile.clearLog() }
    })

    function agentState() {
        return {
            receipt: root.receipt,
            steps: root.steps,
            copyUrl: root.copyUrl,
            logPath: _openedFile.logPath,
            receipts: _openedFile.receipts(),
            nativeContexts: _openedFile.nativeContexts(),
            inbox: _openedFile.inboxFiles()
        }
    }

    function record(step, result) {
        root.steps = root.steps.concat([{ step: step, result: result }])
        _openedFile.log({ event: "probe", seq: root.receipt ? root.receipt.seq : 0,
                          step: step, result: result })
    }

    function isPdf(described) {
        var intentType = root.receipt.intent ? String(root.receipt.intent.type) : ""
        var hints = [String(described.resolverType || ""), intentType,
                     String(described.displayName || ""), String(root.receipt.url)]
        return hints.join(" ").toLowerCase().indexOf("pdf") !== -1
    }

    function runProbes(r) {
        root.receipt = r
        root.steps = []
        root.copyUrl = ""
        root.receivedAtMs = Date.now()
        var url = r.url
        var described = _openedFile.describe(url)
        record("describe", described)
        record("read.before", _openedFile.read(url))
        if (_openedFile.platform === "ios") {
            record("access.rebuilt", _openedFile.startAccess(url, false))
            record("read.afterRebuilt", _openedFile.read(url))
            record("access.native", _openedFile.startAccess(url, true))
            record("read.afterNative", _openedFile.read(url))
        }
        var copy = _openedFile.copyToCache(url)
        record("copy", copy)
        if (copy.ok)
            root.copyUrl = copy.url
        if (_openedFile.platform === "android" && isPdf(described))
            record("pdfRenderer.inPlace", _openedFile.pdfPages(url))
        loadInPlace()
        root.rereadCount = 0
        rereadTimer.restart()
    }

    function reread(tag) {
        if (root.receipt)
            record("read." + tag, _openedFile.read(root.receipt.url))
    }

    function loadInPlace() {
        if (root.receipt && root.webView)
            startLoad("web.inPlace", root.receipt.url)
    }

    function loadCopy() {
        if (root.copyUrl.length > 0 && root.webView)
            startLoad("web.copy", root.copyUrl)
    }

    function startLoad(step, url) {
        root.pendingLoad = step
        root.loadStartedAt = Date.now()
        if (url.indexOf("file:") === 0)
            root.webView.loadFileUrl(url, url) // read grant: this one file
        else
            root.webView.loadUrl(url)
        loadWatch.restart()
    }

    function finishLoad(js, error) {
        record(root.pendingLoad, {
            loaded: root.webView.loaded,
            loading: root.webView.loading,
            url: root.webView.url.toString(),
            title: root.webView.title,
            ms: Date.now() - root.loadStartedAt,
            js: js === null || js === undefined ? "" : String(js),
            jsError: error || ""
        })
        root.pendingLoad = ""
    }

    function summary(result) {
        var text = JSON.stringify(result)
        return text.length > 220 ? text.slice(0, 220) + "…" : text
    }

    Timer {
        id: loadWatch
        interval: 250
        repeat: true
        onTriggered: {
            var elapsed = Date.now() - root.loadStartedAt
            if ((elapsed > 1000 && !root.webView.loading) || elapsed > 10000) {
                stop()
                root.jsPending = true
                root.webView.runJavaScript(root.fingerprintScript)
                jsTimeout.restart()
            }
        }
    }

    Timer {
        id: jsTimeout
        interval: 3000
        onTriggered: {
            if (root.jsPending) {
                root.jsPending = false
                root.finishLoad(null, "timeout")
            }
        }
    }

    Timer {
        id: rereadTimer
        interval: 60000
        repeat: true
        onTriggered: {
            root.reread("t+" + Math.round((Date.now() - root.receivedAtMs) / 1000) + "s")
            root.rereadCount += 1
            if (root.rereadCount >= 3)
                stop()
        }
    }

    Connections {
        target: root.webView
        function onJavaScriptResult(result, error) {
            if (!root.jsPending)
                return
            root.jsPending = false
            jsTimeout.stop()
            root.finishLoad(result, error)
        }
    }

    Connections {
        target: Qt.application
        function onStateChanged() {
            if (Qt.application.state === Qt.ApplicationActive)
                root.reread("onActive")
        }
    }

    ColumnLayout {
        width: parent.width
        spacing: Theme.spacingSm

        Label {
            Layout.fillWidth: true
            wrapMode: Text.WordWrap
            font.pixelSize: Theme.fontMd
            font.bold: true
            color: Theme.textPrimary
            text: root.receipt
                ? ("#" + root.receipt.seq + " via " + root.receipt.via)
                : "Waiting for “Open with”…"
        }

        Label {
            Layout.fillWidth: true
            wrapMode: Text.WrapAnywhere
            font.pixelSize: Theme.fontSm
            color: Theme.textSecondary
            text: root.receipt ? root.receipt.url : ("log: " + _openedFile.logPath)
        }

        Flow {
            Layout.fillWidth: true
            spacing: Theme.spacingXs

            AppButton {
                label: "Re-run"
                enabled: root.receipt !== null
                onClicked: root.runProbes(root.receipt)
            }
            AppButton {
                label: "In place"
                enabled: root.receipt !== null
                onClicked: root.loadInPlace()
            }
            AppButton {
                label: "Copy"
                enabled: root.copyUrl.length > 0
                onClicked: root.loadCopy()
            }
            AppButton {
                label: "Re-read"
                enabled: root.receipt !== null
                onClicked: root.reread("manual")
            }
        }

        Repeater {
            model: root.steps

            Label {
                required property var modelData
                Layout.fillWidth: true
                wrapMode: Text.WrapAnywhere
                font.pixelSize: Theme.fontXs
                color: modelData.result && modelData.result.ok === false
                    ? Theme.danger : Theme.textPrimary
                text: modelData.step + "  " + root.summary(modelData.result)
            }
        }
    }
}
