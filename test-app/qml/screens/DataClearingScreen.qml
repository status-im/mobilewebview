import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../helpers/ProbeUtils.js" as ProbeUtils
import MobileWebViewTest

ScreenScaffold {
    id: root

    required property var stackView

    title: "Data clearing"
    hasWebView: false
    contentFillsViewport: true

    readonly property string fixedKey: "mwv_clear"
    // Distinct full hostnames so WKWebsiteDataRecord.displayName can distinguish
    // the two sites. Reserved TLDs like .local get collapsed to a registrable
    // domain by WebKit, which breaks per-host clearing (see ADR 0004 /
    // tst_data_clearing).
    readonly property string urlA: "https://siteaaa.invalid/"
    readonly property string urlB: "https://sitebbb.invalid/"
    readonly property string htmlTemplate: "<!doctype html><html><head><meta charset='utf-8'><title>Data clearing</title></head><body><h1>Data clearing probe</h1><p id='origin'></p><script>document.getElementById('origin').textContent=location.origin;</script></body></html>"

    property string markerA: ""
    property string markerB: ""
    property var probeA: ({})
    property var probeB: ({})
    property string siteVerdict: "unknown"
    property string profileVerdict: "unknown"
    property string siteDetail: ""
    property string profileDetail: ""
    property string phase: "idle"
    property int pendingSeeds: 0
    property int pendingProbes: 0
    property int pendingReloads: 0
    property string activeTest: "" // "site" | "profile" | ""

    readonly property bool busy: {
        var a = paneWebView("A")
        var b = paneWebView("B")
        return phase !== "idle"
            || (a && a.clearing)
            || (b && b.clearing)
    }

    property bool clearCache: true
    property bool clearCookies: true
    property bool clearDomStorage: true

    externalWebView: paneWebView("A")

    onBackRequested: {
        paneALoader.active = false
        paneBLoader.active = false
        stackView.pop()
    }

    Component.onCompleted: Qt.callLater(loadBothPages)

    function paneWebView(id) {
        if (id === "A")
            return paneALoader.item ? paneALoader.item.webView : null
        if (id === "B")
            return paneBLoader.item ? paneBLoader.item.webView : null
        return null
    }

    function panesReady() {
        return paneWebView("A") && paneWebView("B")
    }

    function loadBothPages() {
        if (paneALoader.item)
            paneALoader.item.loadHtml(htmlTemplate, urlA)
        if (paneBLoader.item)
            paneBLoader.item.loadHtml(htmlTemplate, urlB)
    }

    function formatProbe(value) {
        if (!value)
            return "(empty)"
        var ls = value.ls
        var cookie = value.cookie
        if ((ls === null || ls === undefined || ls === "")
                && (cookie === null || cookie === undefined || cookie === ""))
            return "(empty)"
        return "ls=" + (ls || "") + ",cookie=" + (cookie || "")
    }

    function isEmptyProbe(value) {
        if (!value)
            return true
        var ls = value.ls
        var cookie = value.cookie
        return (ls === null || ls === undefined || ls === "")
            && (cookie === null || cookie === undefined || cookie === "")
    }

    function failActive(message) {
        if (activeTest === "site") {
            siteVerdict = "fail"
            siteDetail = message || "clear-site-data test failed"
        } else if (activeTest === "profile") {
            profileVerdict = "fail"
            profileDetail = message || "clear-profile-data test failed"
        }
        phase = "idle"
        activeTest = ""
        statusMessage(message || "data clearing test failed")
    }

    function seedBoth(thenPhase) {
        if (!panesReady())
            return false
        markerA = "v-A-" + Date.now()
        markerB = "v-B-" + Date.now()
        probeA = ({})
        probeB = ({})
        pendingSeeds = 2
        phase = "seeding"
        paneWebView("A").runJavaScript(
            ProbeUtils.writeProfileMarkerScript(fixedKey, markerA, 3600))
        paneWebView("B").runJavaScript(
            ProbeUtils.writeProfileMarkerScript(fixedKey, markerB, 3600))
        root._thenPhase = thenPhase
        return true
    }

    property string _thenPhase: ""
    property bool _sawLoadingA: false
    property int pendingFlushes: 0
    property var flushedPanes: ({})

    Timer {
        id: reloadWatchdog
        interval: 2500
        repeat: false
        onTriggered: {
            if (root.phase === "waitingReloadA")
                root.startProbeBoth()
        }
    }

    function onSeedDone() {
        if (--pendingSeeds > 0)
            return
        // WKWebsiteDataStore per-record clear only sees flushed data (see ADR 0004 /
        // tst_data_clearing). Recreate both panes to flush cookie + localStorage.
        flushBothThenContinue(root._thenPhase)
    }

    function flushBothThenContinue(thenPhase) {
        if (!panesReady()) {
            failActive("panes not ready for flush")
            return
        }
        root._thenPhase = thenPhase
        pendingFlushes = 2
        flushedPanes = ({})
        phase = "flushing"
        statusMessage("clearing… flushing storage to profile")
        flushPane("A")
        flushPane("B")
    }

    function flushPane(id) {
        var wv = paneWebView(id)
        if (!wv) {
            onFlushDone(id)
            return
        }
        // Recreate native view to flush in-memory DOM storage / cookies into the store.
        wv.offTheRecord = true
        wv.offTheRecord = false
    }

    function onFlushDone(id) {
        if (phase !== "flushing")
            return
        if (flushedPanes[id])
            return
        flushedPanes[id] = true
        if (--pendingFlushes > 0)
            return
        phase = root._thenPhase
        if (phase === "clearingSite") {
            var a = paneWebView("A")
            if (!a || !a.clearSiteDataSupported) {
                failActive("clearSiteData not supported")
                return
            }
            statusMessage("clearing… clearSiteData on A")
            a.clearSiteData()
            return
        }
        if (phase === "clearingProfile") {
            statusMessage("clearing… clearProfileData")
            paneWebView("A").clearProfileData()
            return
        }
    }

    function startProbeBoth() {
        if (phase === "probing" || !panesReady()) {
            if (!panesReady())
                failActive("panes not ready for probe")
            return
        }
        reloadWatchdog.stop()
        pendingProbes = 2
        phase = "probing"
        paneWebView("A").runJavaScript(ProbeUtils.readProfileMarkerScript(fixedKey))
        paneWebView("B").runJavaScript(ProbeUtils.readProfileMarkerScript(fixedKey))
    }

    function onProbeDone(id, value) {
        if (id === "A")
            probeA = value
        else
            probeB = value
        if (--pendingProbes > 0)
            return
        finishActiveTest()
    }

    function finishActiveTest() {
        if (activeTest === "site") {
            var aGone = isEmptyProbe(probeA)
            var bOk = probeB && probeB.ls === markerB && probeB.cookie === markerB
            siteVerdict = (aGone && bOk) ? "pass" : "fail"
            siteDetail = "A=" + formatProbe(probeA) + " | B=" + formatProbe(probeB)
                + " | expect A empty, B=" + markerB
            statusMessage("clear-site-data " + siteVerdict + " — " + siteDetail)
        } else if (activeTest === "profile") {
            var bothGone = isEmptyProbe(probeA) && isEmptyProbe(probeB)
            profileVerdict = bothGone ? "pass" : "fail"
            profileDetail = "A=" + formatProbe(probeA) + " | B=" + formatProbe(probeB)
                + " | expect both empty"
            statusMessage("clear-profile-data " + profileVerdict + " — " + profileDetail)
        }
        phase = "idle"
        activeTest = ""
    }

    function runClearSiteDataTest() {
        if (busy || !panesReady())
            return
        siteVerdict = "unknown"
        siteDetail = ""
        activeTest = "site"
        if (!seedBoth("clearingSite"))
            failActive("panes not ready")
    }

    function runClearProfileDataTest() {
        if (busy || !panesReady())
            return
        profileVerdict = "unknown"
        profileDetail = ""
        activeTest = "profile"
        if (!seedBoth("clearingProfile"))
            failActive("panes not ready")
    }

    function clearSelected() {
        if (busy || !panesReady())
            return
        var wv = paneWebView("A")
        if (!wv)
            return
        if (!clearCache && !clearCookies && !clearDomStorage) {
            statusMessage("select at least one category")
            return
        }
        statusMessage("clearing… selected categories")
        if (clearCache)
            wv.clearHttpCache()
        if (clearCookies)
            wv.deleteAllCookies()
        if (clearDomStorage)
            wv.clearDomStorage()
    }

    function reloadBothThenProbe() {
        pendingReloads = 2
        phase = "reloading"
        loadBothPages()
    }

    ColumnLayout {
        width: parent.width
        spacing: Theme.spacingSm

        Label {
            Layout.fillWidth: true
            wrapMode: Text.WordWrap
            color: Theme.textSecondary
            font.pixelSize: Theme.fontSm
            text: "Same Storage Profile (Profile_A), two sites side-by-side. Clear browsing data is profile-wide; Clear current site data wipes only the current pane's site."
        }

        // --- Clear browsing data ---
        Label {
            text: "Clear browsing data"
            font.bold: true
            color: Theme.textPrimary
            font.pixelSize: Theme.fontMd
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingMd

            CheckBox {
                text: "HTTP cache"
                checked: root.clearCache
                enabled: !root.busy
                onToggled: root.clearCache = checked
            }
            CheckBox {
                text: "Cookies"
                checked: root.clearCookies
                enabled: !root.busy
                onToggled: root.clearCookies = checked
            }
            CheckBox {
                text: "DOM storage"
                checked: root.clearDomStorage
                enabled: !root.busy
                onToggled: root.clearDomStorage = checked
            }
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingSm

            AppButton {
                label: "Reload pages"
                enabled: !root.busy
                onClicked: root.loadBothPages()
            }
            AppButton {
                label: "Clear selected"
                enabled: !root.busy
                onClicked: root.clearSelected()
            }
            AppButton {
                label: "Clear all"
                enabled: !root.busy
                onClicked: {
                    if (!root.panesReady())
                        return
                    root.statusMessage("clearing… clearProfileData")
                    root.paneWebView("A").clearProfileData()
                }
            }
            AppButton {
                label: "Run clear-profile-data test"
                accent: true
                enabled: !root.busy
                onClicked: root.runClearProfileDataTest()
            }
        }

        VerdictBadge {
            Layout.fillWidth: true
            verdict: root.profileVerdict
            detail: root.profileDetail
        }

        // --- Clear current site data ---
        Label {
            text: "Clear current site data"
            font.bold: true
            color: Theme.textPrimary
            font.pixelSize: Theme.fontMd
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingSm

            AppButton {
                label: "Clear site A"
                enabled: !root.busy && root.paneWebView("A") && root.paneWebView("A").clearSiteDataSupported
                onClicked: {
                    root.statusMessage("clearing… clearSiteData on A")
                    root.paneWebView("A").clearSiteData()
                }
            }
            AppButton {
                label: "Run clear-site-data test"
                accent: true
                enabled: !root.busy
                         && root.paneWebView("A")
                         && root.paneWebView("A").clearSiteDataSupported
                onClicked: root.runClearSiteDataTest()
            }
            Label {
                visible: root.paneWebView("A") && !root.paneWebView("A").clearSiteDataSupported
                text: "clearSiteData not supported on this WebView"
                color: Theme.textMuted
                font.pixelSize: Theme.fontSm
            }
        }

        VerdictBadge {
            Layout.fillWidth: true
            verdict: root.siteVerdict
            detail: root.siteDetail
        }

        RowLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            Layout.preferredHeight: 280
            spacing: Theme.spacingSm

            ColumnLayout {
                Layout.fillWidth: true
                Layout.fillHeight: true
                spacing: Theme.spacingXs

                Label {
                    text: "Pane A — " + root.urlA
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
                    text: "Pane B — " + root.urlB
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
            root.handleJsResult("A", result, error)
        }
        function onClearSiteDataCompleted() {
            if (root.phase === "clearingSite" && root.activeTest === "site") {
                root.phase = "waitingReloadA"
                root._sawLoadingA = false
                root.statusMessage("clearing… waiting for A reload")
                reloadWatchdog.restart()
            }
        }
        function onClearProfileDataCompleted() {
            if (root.phase === "clearingProfile" && root.activeTest === "profile") {
                root.reloadBothThenProbe()
                return
            }
            if (root.phase === "idle")
                root.statusMessage("clearProfileDataCompleted")
        }
        function onClearHttpCacheCompleted() {
            if (root.phase === "idle")
                root.statusMessage("clearHttpCacheCompleted")
        }
        function onDeleteAllCookiesCompleted() {
            if (root.phase === "idle")
                root.statusMessage("deleteAllCookiesCompleted")
        }
        function onClearDomStorageCompleted() {
            if (root.phase === "idle")
                root.statusMessage("clearDomStorageCompleted")
        }
        function onLoadedChanged() {
            var wv = root.paneWebView("A")
            if (!wv || !wv.loaded)
                return
            if (root.phase === "flushing")
                root.onFlushDone("A")
            if (root.phase === "waitingReloadA" && root._sawLoadingA) {
                reloadWatchdog.stop()
                root.startProbeBoth()
            }
            if (root.phase === "reloading")
                root.onReloadPaneDone("A")
        }
        function onLoadingChanged() {
            var wv = root.paneWebView("A")
            if (root.phase === "waitingReloadA" && wv && wv.loading)
                root._sawLoadingA = true
        }
        function onClearingChanged() {
            if (root.paneWebView("A") && root.paneWebView("A").clearing)
                root.statusMessage("clearing…")
        }
    }

    Connections {
        target: paneBLoader.item ? paneBLoader.item.webView : null
        function onJavaScriptResult(result, error) {
            root.handleJsResult("B", result, error)
        }
        function onLoadedChanged() {
            var wv = root.paneWebView("B")
            if (!wv || !wv.loaded)
                return
            if (root.phase === "flushing")
                root.onFlushDone("B")
            if (root.phase === "reloading")
                root.onReloadPaneDone("B")
        }
    }

    function onReloadPaneDone(id) {
        if (phase !== "reloading")
            return
        if (--pendingReloads > 0)
            return
        startProbeBoth()
    }

    function handleJsResult(id, result, error) {
        if (phase === "seeding") {
            if ((error || "").length > 0) {
                failActive("seed failed on " + id + ": " + error)
                return
            }
            onSeedDone()
            return
        }
        if (phase === "probing") {
            if ((error || "").length > 0) {
                failActive("probe failed on " + id + ": " + error)
                return
            }
            onProbeDone(id, ProbeUtils.parseProbeValue(result) || {})
        }
    }
}
