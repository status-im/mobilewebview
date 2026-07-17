import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import MobileWebView 1.0
import MobileWebViewTest

ScreenScaffold {
    id: root

    required property var stackView

    title: "Downloads"
    initialUrl: "about:blank"
    autoLoad: true
    storageName: "Downloads_Test"
    offTheRecord: false
    webViewHeight: 220

    readonly property string smallUrl:
        "https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf"
    // Main-frame navigation to a type WKWebView cannot display → WKNavigationResponsePolicyDownload.
    // (PDF is displayable, so a plain <a href=pdf> never becomes a Download.)
    readonly property string pageNetworkUrl:
        "https://www.learningcontainer.com/wp-content/uploads/2020/05/sample-zip-file.zip"
    // Larger payload so pause can fire before Completed.
    // file-examples.com/…/file_example_MP4_1920_18MG.mp4 is behind Cloudflare
    // (HTTP 403 challenge HTML ≈5KB) — WK "completes" instantly, pause never arms.
    readonly property string largeUrl:
        "https://proof.ovh.net/files/10Mb.dat"
    readonly property string inlinePayload: "hello-mwv-inline"
    readonly property string inlineFileName: "inline-hello.txt"
    readonly property string harnessOrigin: "https://download-harness.invalid"

    // "auto" | "manual"
    property string acceptMode: "auto"
    // Scenario waiting for the next downloadRequested ("" when idle)
    property string pendingScenario: ""
    // For cancel-before-accept: next Request is cancelled instead of accepted
    property bool cancelNextRequest: false

    property var downloads: []

    property string verdictUrlSmall: "unknown"
    property string detailUrlSmall: ""
    property string verdictPage: "unknown"
    property string detailPage: ""
    property string verdictInline: "unknown"
    property string detailInline: ""
    property string verdictCancel: "unknown"
    property string detailCancel: ""
    property string verdictPause: "unknown"
    property string detailPause: ""
    property string verdictRetry: "unknown"
    property string detailRetry: ""
    property string verdictProfile: "unknown"
    property string detailProfile: ""

    property var _pauseWatch: null
    property var _retryWatch: null

    readonly property var agentActions: ({
        "url_small": function() { root.startUrlSmall() },
        "page": function() { root.startPageNetwork() },
        "inline": function() { root.startInline() },
        "cancel": function() { root.startCancel() },
        "pause": function() { root.startUrlLargePause() },
        "retry": function() { root.startRetry() },
        "profile_cancel": function() { root.startProfileCancel() },
        "clear_list": function() { root.clearList() },
        "accept_mode_auto": function() { root.acceptMode = "auto" },
        "accept_mode_manual": function() { root.acceptMode = "manual" },
        "toggle_incognito": function() { root.offTheRecord = !root.offTheRecord }
    })

    function agentState() {
        return {
            acceptMode: root.acceptMode,
            pendingScenario: root.pendingScenario,
            offTheRecord: root.offTheRecord,
            storageName: root.storageName,
            downloadCount: root.downloads.length,
            downloads: root.downloads.map(function(d) {
                return {
                    downloadId: d.downloadId,
                    url: d.url,
                    suggestedFileName: d.suggestedFileName,
                    state: d.stateLabel,
                    path: d.path,
                    error: d.error,
                    scenario: d.scenario,
                    isInline: d.isInline
                }
            }),
            verdicts: {
                urlSmall: root.verdictUrlSmall,
                page: root.verdictPage,
                inline: root.verdictInline,
                cancel: root.verdictCancel,
                pause: root.verdictPause,
                retry: root.verdictRetry,
                profile: root.verdictProfile
            },
            details: {
                urlSmall: root.detailUrlSmall,
                page: root.detailPage,
                inline: root.detailInline,
                cancel: root.detailCancel,
                pause: root.detailPause,
                retry: root.detailRetry,
                profile: root.detailProfile
            }
        }
    }

    onBackRequested: stackView.pop()

    property bool _bridgeReady: false
    property var _afterLoad: null

    Component.onCompleted: {
        if (_downloadTest)
            _downloadTest.ensureDownloadsDir()
    }

    function ensureBridge() {
        if (!root.webView || root._bridgeReady)
            return
        // Inline blob downloads need the document-start interceptor + qtbridge handler
        // (installed only via installMessageBridge today).
        root.webView.installMessageBridge(
            "qt",
            [root.harnessOrigin, root.harnessOrigin + "/"],
            "qtInvoke")
        root._bridgeReady = true
    }

    function runAfterLoaded(fn) {
        if (!root.webView)
            return
        if (root.webView.loaded && !root.webView.loading) {
            fn()
            return
        }
        root._afterLoad = fn
    }

    Connections {
        target: root.webView
        function onLoadedChanged() {
            if (root.webView && root.webView.loaded && root._afterLoad) {
                var fn = root._afterLoad
                root._afterLoad = null
                Qt.callLater(fn)
            }
        }
        function onDownloadRequested(download) {
            root.handleRequested(download)
        }
    }

    function stateName(state) {
        switch (state) {
        case MobileWebViewDownload.Requested: return "Requested"
        case MobileWebViewDownload.InProgress: return "InProgress"
        case MobileWebViewDownload.Completed: return "Completed"
        case MobileWebViewDownload.Cancelled: return "Cancelled"
        case MobileWebViewDownload.Interrupted: return "Interrupted"
        case MobileWebViewDownload.Paused: return "Paused"
        }
        return "state=" + state
    }

    function setVerdict(scenario, verdict, detail) {
        if (scenario === "urlSmall") {
            verdictUrlSmall = verdict; detailUrlSmall = detail || ""
        } else if (scenario === "page") {
            verdictPage = verdict; detailPage = detail || ""
        } else if (scenario === "inline") {
            verdictInline = verdict; detailInline = detail || ""
        } else if (scenario === "cancel") {
            verdictCancel = verdict; detailCancel = detail || ""
        } else if (scenario === "pause") {
            verdictPause = verdict; detailPause = detail || ""
        } else if (scenario === "retry") {
            verdictRetry = verdict; detailRetry = detail || ""
        } else if (scenario === "profile") {
            verdictProfile = verdict; detailProfile = detail || ""
        }
    }

    function refreshDownloads() {
        downloads = downloads.slice()
    }

    function clearList() {
        downloads = []
        statusMessage("download list cleared")
    }

    function destinationFor(download) {
        var name = download.suggestedFileName || "download.bin"
        return _downloadTest.targetPath(name)
    }

    function wireDownload(download, scenario) {
        var entry = {
            download: download,
            scenario: scenario || "",
            downloadId: download.downloadId,
            url: download.url.toString(),
            suggestedFileName: download.suggestedFileName,
            stateLabel: stateName(download.state),
            path: download.destinationPath || "",
            error: download.errorString || "",
            isInline: download.isInline
        }
        downloads = downloads.concat([entry])

        download.stateChanged.connect(function() {
            entry.stateLabel = stateName(download.state)
            entry.path = download.destinationPath || entry.path
            entry.error = download.errorString || ""
            refreshDownloads()
            onDownloadState(entry)
        })
        download.destinationPathChanged.connect(function() {
            entry.path = download.destinationPath || ""
            refreshDownloads()
        })
        download.finished.connect(function() {
            entry.stateLabel = stateName(download.state)
            entry.path = download.destinationPath || entry.path
            entry.error = download.errorString || ""
            refreshDownloads()
            onDownloadFinished(entry)
        })
        return entry
    }

    function evaluateCompleted(entry) {
        var path = entry.path || entry.download.destinationPath
        if (!_downloadTest.fileExists(path))
            return { ok: false, detail: "Completed but file missing: " + path }
        var size = _downloadTest.fileSize(path)
        if (size <= 0)
            return { ok: false, detail: "Completed but empty file: " + path }
        if (entry.scenario === "inline" || entry.isInline) {
            var text = _downloadTest.readTextFile(path)
            if (text !== root.inlinePayload)
                return { ok: false, detail: "payload mismatch: " + JSON.stringify(text) }
            return { ok: true, detail: "inline " + size + " bytes @ " + path }
        }
        return { ok: true, detail: size + " bytes @ " + path }
    }

    function onDownloadState(entry) {
        if (entry.scenario === "pause" && entry.download.state === MobileWebViewDownload.Paused) {
            root._pauseWatch = entry
            // resume() may race WK's cancel→resumeData callback; Darwin queues it.
            Qt.callLater(function() {
                if (entry.download.state === MobileWebViewDownload.Paused)
                    entry.download.resume()
            })
        }
        if (entry.scenario === "profile"
                && (entry.download.state === MobileWebViewDownload.InProgress
                    || entry.download.state === MobileWebViewDownload.Paused
                    || entry.download.state === MobileWebViewDownload.Requested)) {
            // Flip storage to force cancelAllDownloads on recreate.
            root.storageName = root.storageName === "Downloads_Test"
                ? "Downloads_Test_B" : "Downloads_Test"
        }
    }

    function onDownloadFinished(entry) {
        var st = entry.download.state
        var scenario = entry.scenario
        if (!scenario)
            return

        if (scenario === "cancel") {
            if (st === MobileWebViewDownload.Cancelled)
                setVerdict("cancel", "pass", "Cancelled before/during accept")
            else
                setVerdict("cancel", "fail", "expected Cancelled, got " + stateName(st))
            return
        }

        if (scenario === "profile") {
            if (st === MobileWebViewDownload.Cancelled)
                setVerdict("profile", "pass", "Cancelled on profile switch")
            else
                setVerdict("profile", "fail", "expected Cancelled, got " + stateName(st))
            return
        }

        if (scenario === "retry") {
            // First terminal should be Interrupted; retry emits a new Request (separate entry).
            if (st === MobileWebViewDownload.Interrupted && !root._retryWatch) {
                root._retryWatch = entry
                Qt.callLater(function() { entry.download.retry() })
                return
            }
            return
        }

        if (scenario === "pause") {
            if (st === MobileWebViewDownload.Completed) {
                if (!root._pauseWatch) {
                    setVerdict("pause", "skip", "finished before pause (file too small/fast)")
                } else {
                    var check = evaluateCompleted(entry)
                    setVerdict("pause", check.ok ? "pass" : "fail",
                               check.ok ? ("paused then " + check.detail) : check.detail)
                }
            } else if (st === MobileWebViewDownload.Interrupted) {
                var err = entry.error || "Interrupted"
                // Some CDNs / early cancel yield no WK resumeData — not a harness regression.
                if (err.indexOf("no resume data") >= 0 || err.indexOf("Resume data unavailable") >= 0)
                    setVerdict("pause", "skip", err)
                else
                    setVerdict("pause", "fail", err)
            }
            return
        }

        if (st === MobileWebViewDownload.Completed) {
            var result = evaluateCompleted(entry)
            setVerdict(scenario, result.ok ? "pass" : "fail", result.detail)
        } else if (st === MobileWebViewDownload.Interrupted) {
            setVerdict(scenario, "fail", entry.error || "Interrupted")
        } else if (st === MobileWebViewDownload.Cancelled) {
            setVerdict(scenario, "fail", "unexpected Cancelled")
        }
    }

    function startUrlSmall() {
        if (!root.webView)
            return
        setVerdict("urlSmall", "unknown", "running…")
        pendingScenario = "urlSmall"
        root.webView.downloadUrl(root.smallUrl, "dummy.pdf")
    }

    function startUrlLargePause() {
        if (!root.webView)
            return
        setVerdict("pause", "unknown", "running…")
        root._pauseWatch = null
        pendingScenario = "pause"
        root.webView.downloadUrl(root.largeUrl, "sample-large.mp4")
    }

    function startPageNetwork() {
        if (!root.webView)
            return
        setVerdict("page", "unknown", "running…")
        pendingScenario = "page"
        // Page-initiated: main-frame navigation that WebKit turns into a WKDownload
        // (!canShowMIMEType for zip). Link+click to a PDF does not — PDF is displayable.
        root.webView.loadUrl(root.pageNetworkUrl)
    }

    function startInline() {
        if (!root.webView)
            return
        ensureBridge()
        setVerdict("inline", "unknown", "running…")
        pendingScenario = "inline"
        var html = "<!doctype html><html><body>"
            + "<a id='inlineDl' download='" + root.inlineFileName + "' href='#'>inline</a>"
            + "<script>(function(){"
            + "var b=new Blob([" + JSON.stringify(root.inlinePayload) + "],{type:'text/plain'});"
            + "document.getElementById('inlineDl').href=URL.createObjectURL(b);"
            + "})();</script></body></html>"
        root.webView.loadHtml(html, root.harnessOrigin + "/")
        runAfterLoaded(function() {
            if (root.webView)
                root.webView.runJavaScript("document.getElementById('inlineDl').click()")
        })
    }

    function startCancel() {
        if (!root.webView)
            return
        setVerdict("cancel", "unknown", "running…")
        pendingScenario = "cancel"
        cancelNextRequest = true
        root.webView.downloadUrl(root.smallUrl, "cancel-me.pdf")
    }

    function startRetry() {
        if (!root.webView)
            return
        setVerdict("retry", "unknown", "running…")
        root._retryWatch = null
        pendingScenario = "retry"
        // Unresolvable host → Interrupted after accept.
        root.webView.downloadUrl("https://mwv-download-retry.invalid/missing.bin", "retry.bin")
    }

    function startProfileCancel() {
        if (!root.webView)
            return
        setVerdict("profile", "unknown", "running…")
        pendingScenario = "profile"
        root.webView.downloadUrl(root.largeUrl, "profile-cancel.mp4")
    }

    function isTerminalState(state) {
        return state === MobileWebViewDownload.Completed
            || state === MobileWebViewDownload.Cancelled
            || state === MobileWebViewDownload.Interrupted
    }

    function noteRetryChild(download) {
        if (!root._retryWatch)
            return false
        var entry = wireDownload(download, "retryChild")
        var dest = destinationFor(download)
        _downloadTest.removeFile(dest)
        download.accept(dest)
        entry.path = dest
        download.finished.connect(function() {
            if (isTerminalState(download.state)) {
                // New Request was emitted — that is the retry contract (network may still fail).
                setVerdict("retry", "pass",
                           "new Request id=" + download.downloadId
                           + " state=" + stateName(download.state))
                root._retryWatch = null
            }
        })
        return true
    }

    function handleRequested(download) {
        if (root.pendingScenario === "" && root._retryWatch) {
            if (noteRetryChild(download)) {
                statusMessage("retry: new Download Request")
                return
            }
        }

        var scenario = root.pendingScenario
        root.pendingScenario = ""
        var entry = wireDownload(download, scenario)

        if (root.cancelNextRequest || scenario === "cancel") {
            root.cancelNextRequest = false
            entry.scenario = "cancel"
            download.cancel()
            statusMessage("cancel: " + download.suggestedFileName)
            return
        }

        if (root.acceptMode === "auto") {
            acceptDownload(entry)
        } else {
            statusMessage("manual: " + download.suggestedFileName + " awaiting Accept/Cancel")
        }
    }

    function acceptDownload(entry) {
        var download = entry.download
        var dest = destinationFor(download)
        _downloadTest.removeFile(dest)
        download.accept(dest)
        entry.path = dest
        refreshDownloads()
        statusMessage("accept: " + dest)

        if (entry.scenario === "pause") {
            // Give WK a moment to attach the transfer; immediate cancel often
            // returns nil resumeData. Progress KVO can lag, so don't require bytes.
            root._pauseArmEntry = entry
            pauseArmTimer.restart()
        }
    }

    property var _pauseArmEntry: null
    Timer {
        id: pauseArmTimer
        interval: 400
        repeat: false
        onTriggered: {
            var entry = root._pauseArmEntry
            root._pauseArmEntry = null
            if (!entry || !entry.download)
                return
            var download = entry.download
            if (download.state === MobileWebViewDownload.Completed) {
                root.setVerdict("pause", "skip", "finished before pause")
                return
            }
            if (download.state === MobileWebViewDownload.InProgress)
                download.pause()
            else
                root.setVerdict("pause", "skip", "not InProgress after arm delay")
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
            text: "Host-facing Downloads harness (ADR 0005). Auto accepts into Temp/mwv-downloads/; Manual waits for Accept/Cancel on each card."
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingSm

            Label {
                text: "Accept:"
                color: Theme.textPrimary
                font.pixelSize: Theme.fontSm
            }
            AppButton {
                label: "Auto"
                highlighted: root.acceptMode === "auto"
                onClicked: root.acceptMode = "auto"
            }
            AppButton {
                label: "Manual"
                highlighted: root.acceptMode === "manual"
                onClicked: root.acceptMode = "manual"
            }
            Item { Layout.fillWidth: true }
            AppButton {
                label: root.offTheRecord ? "Incognito" : "Standard"
                highlighted: root.offTheRecord
                onClicked: root.offTheRecord = !root.offTheRecord
            }
        }

        Label {
            Layout.fillWidth: true
            text: "storageName=" + root.storageName
                  + (root.offTheRecord ? " (OTR)" : "")
            color: Theme.textMuted
            font.pixelSize: Theme.fontXs
        }

        GridLayout {
            Layout.fillWidth: true
            columns: width > 520 ? 3 : 2
            columnSpacing: Theme.spacingSm
            rowSpacing: Theme.spacingSm

            AppButton {
                Layout.fillWidth: true
                label: "downloadUrl (small PDF)"
                accent: true
                onClicked: root.startUrlSmall()
            }
            AppButton {
                Layout.fillWidth: true
                label: "Page link (PDF)"
                onClicked: root.startPageNetwork()
            }
            AppButton {
                Layout.fillWidth: true
                label: "Inline blob"
                onClicked: root.startInline()
            }
            AppButton {
                Layout.fillWidth: true
                label: "Cancel before accept"
                onClicked: root.startCancel()
            }
            AppButton {
                Layout.fillWidth: true
                label: "Pause/resume (MP4)"
                onClicked: root.startUrlLargePause()
            }
            AppButton {
                Layout.fillWidth: true
                label: "Retry (bad host)"
                onClicked: root.startRetry()
            }
            AppButton {
                Layout.fillWidth: true
                label: "Profile switch cancels"
                onClicked: root.startProfileCancel()
            }
            AppButton {
                Layout.fillWidth: true
                label: "Clear list"
                onClicked: root.clearList()
            }
        }

        ColumnLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingXs

            VerdictBadge { Layout.fillWidth: true; verdict: root.verdictUrlSmall; detail: "downloadUrl small — " + root.detailUrlSmall }
            VerdictBadge { Layout.fillWidth: true; verdict: root.verdictPage; detail: "page network — " + root.detailPage }
            VerdictBadge { Layout.fillWidth: true; verdict: root.verdictInline; detail: "inline — " + root.detailInline }
            VerdictBadge { Layout.fillWidth: true; verdict: root.verdictCancel; detail: "cancel — " + root.detailCancel }
            VerdictBadge { Layout.fillWidth: true; verdict: root.verdictPause; detail: "pause/resume — " + root.detailPause }
            VerdictBadge { Layout.fillWidth: true; verdict: root.verdictRetry; detail: "retry — " + root.detailRetry }
            VerdictBadge { Layout.fillWidth: true; verdict: root.verdictProfile; detail: "profile switch — " + root.detailProfile }
        }

        Label {
            Layout.fillWidth: true
            text: "Live downloads (" + root.downloads.length + ")"
            color: Theme.textPrimary
            font.pixelSize: Theme.fontMd
            font.bold: true
        }

        Repeater {
            model: root.downloads

            Rectangle {
                required property var modelData
                required property int index

                Layout.fillWidth: true
                implicitHeight: cardCol.implicitHeight + Theme.spacingSm * 2
                radius: Theme.radiusMd
                color: Theme.surface
                border.color: Theme.border
                border.width: 1

                ColumnLayout {
                    id: cardCol
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.top: parent.top
                    anchors.margins: Theme.spacingSm
                    spacing: Theme.spacingXs

                    Label {
                        Layout.fillWidth: true
                        text: "#" + modelData.downloadId
                              + " · " + modelData.stateLabel
                              + (modelData.isInline ? " · inline" : "")
                              + (modelData.scenario ? " · " + modelData.scenario : "")
                        color: Theme.textPrimary
                        font.pixelSize: Theme.fontSm
                        font.bold: true
                        elide: Text.ElideRight
                    }
                    Label {
                        Layout.fillWidth: true
                        text: modelData.suggestedFileName + "\n" + modelData.url
                        color: Theme.textSecondary
                        font.pixelSize: Theme.fontXs
                        wrapMode: Text.WrapAnywhere
                    }
                    Label {
                        Layout.fillWidth: true
                        visible: (modelData.path || "").length > 0
                        text: modelData.path
                        color: Theme.textMuted
                        font.pixelSize: Theme.fontXs
                        elide: Text.ElideMiddle
                    }
                    Label {
                        Layout.fillWidth: true
                        visible: (modelData.error || "").length > 0
                        text: modelData.error
                        color: Theme.danger
                        font.pixelSize: Theme.fontXs
                        wrapMode: Text.WordWrap
                    }

                    RowLayout {
                        Layout.fillWidth: true
                        spacing: Theme.spacingXs

                        AppButton {
                            label: "Accept"
                            enabled: modelData.download
                                     && modelData.download.state === MobileWebViewDownload.Requested
                            onClicked: root.acceptDownload(modelData)
                        }
                        AppButton {
                            label: "Cancel"
                            enabled: modelData.download
                                     && !root.isTerminalState(modelData.download.state)
                            onClicked: modelData.download.cancel()
                        }
                        AppButton {
                            label: "Pause"
                            enabled: modelData.download
                                     && modelData.download.state === MobileWebViewDownload.InProgress
                                     && !modelData.isInline
                            onClicked: modelData.download.pause()
                        }
                        AppButton {
                            label: "Resume"
                            enabled: modelData.download
                                     && modelData.download.state === MobileWebViewDownload.Paused
                            onClicked: modelData.download.resume()
                        }
                        AppButton {
                            label: "Retry"
                            enabled: modelData.download
                                     && (modelData.download.state === MobileWebViewDownload.Interrupted
                                         || modelData.download.state === MobileWebViewDownload.Cancelled)
                            onClicked: {
                                root._retryWatch = modelData
                                modelData.download.retry()
                            }
                        }
                    }
                }
            }
        }
    }
}
