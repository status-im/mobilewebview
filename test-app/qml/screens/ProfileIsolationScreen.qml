import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../helpers/ProbeUtils.js" as ProbeUtils
import MobileWebViewTest

ScreenScaffold {
    id: root

    required property var stackView

    title: "Profile isolation"
    hasWebView: false
    contentFillsViewport: true

    Component.onCompleted: Qt.callLater(loadAllPages)

    readonly property string storageBaseUrl: "https://storage-test.local/"
    readonly property string fixedKey: "mwv_iso"
    readonly property var panes: [
        {
            id: "A",
            label: "Pane A \u2014 Profile_A",
            storageName: "Profile_A",
            offTheRecord: false,
            persistent: true
        },
        {
            id: "B",
            label: "Pane B \u2014 Profile_B",
            storageName: "Profile_B",
            offTheRecord: false,
            persistent: true
        },
        {
            id: "INCO",
            label: "Incognito",
            storageName: "Incognito",
            offTheRecord: true,
            persistent: false
        }
    ]

    property var markers: ({})
    property var before: ({})
    property var after: ({})
    property var recreateLoaded: ({})
    property string phase: "idle"
    property int pending: 0
    property string verdict: "unknown"

    externalWebView: {
        var host = paneHostForId("A")
        return host ? host.webView : null
    }

    onBackRequested: {
        for (var i = 0; i < paneRepeater.count; i++) {
            var item = paneRepeater.itemAt(i)
            if (item && item.loader)
                item.loader.active = false
        }
        stackView.pop()
    }

    function paneItemForId(id) {
        for (var i = 0; i < paneRepeater.count; i++) {
            var item = paneRepeater.itemAt(i)
            if (item && item.paneDef.id === id)
                return item
        }
        return null
    }

    function paneHostForId(id) {
        var item = paneItemForId(id)
        return item && item.loader && item.loader.item ? item.loader.item : null
    }

    function paneWebView(id) {
        var host = paneHostForId(id)
        return host ? host.webView : null
    }

    function loadPaneHost(id) {
        var host = paneHostForId(id)
        if (!host)
            return

        var html = typeof _storageTestPageHtml === "string" ? _storageTestPageHtml : ""
        if (html.length > 0) {
            host.loadHtml(html, storageBaseUrl)
        } else {
            host.webView.loadUrl("qrc:/MobileWebViewTest/web/storage_profile_test.html")
        }
    }

    function loadAllPages() {
        for (var i = 0; i < panes.length; i++)
            loadPaneHost(panes[i].id)
    }

    function allPanesReady() {
        for (var i = 0; i < panes.length; i++) {
            if (!paneWebView(panes[i].id))
                return false
        }
        return true
    }

    function failTest(message) {
        phase = "idle"
        verdict = "fail"
        statusMessage(message || "profile isolation test failed")
    }

    function formatProbe(value) {
        if (!value)
            return "(empty)"
        var ls = value.ls || ""
        var cookie = value.cookie || ""
        return "ls=" + ls + ",cookie=" + cookie
    }

    function verdictDetail() {
        var parts = []
        for (var i = 0; i < panes.length; i++) {
            var id = panes[i].id
            parts.push(id + " before=" + formatProbe(before[id]) + " after=" + formatProbe(after[id]))
        }
        return parts.join(" | ")
    }

    function runProfileIsolationTest() {
        if (!allPanesReady())
            return

        before = ({})
        after = ({})
        markers = ({})
        recreateLoaded = ({})
        verdict = "unknown"
        phase = "writing"
        pending = panes.length

        for (var i = 0; i < panes.length; i++) {
            var pane = panes[i]
            markers[pane.id] = "v-" + pane.id + "-" + Date.now()
            paneWebView(pane.id).runJavaScript(
                ProbeUtils.writeProfileMarkerScript(fixedKey, markers[pane.id], 3600))
        }
    }

    function onPaneResult(id, result, error) {
        if (phase === "idle" || phase === "done" || phase === "recreating")
            return

        if ((error || "").length > 0) {
            failTest("JS error in pane " + id + ": " + error)
            return
        }

        var value = ProbeUtils.parseProbeValue(result) || {}

        if (phase === "writing") {
            if (--pending === 0)
                startReadBack()
            return
        }

        if (phase === "readBack") {
            before[id] = value
            if (--pending === 0)
                startRecreate()
            return
        }

        if (phase === "readAfter") {
            after[id] = value
            if (--pending === 0) {
                phase = "done"
                verdict = ProbeUtils.evaluateProfileMatrix(panes, markers, before, after)
                statusMessage("profile isolation " + verdict + " — " + verdictDetail())
            }
        }
    }

    function startReadBack() {
        phase = "readBack"
        pending = panes.length
        for (var i = 0; i < panes.length; i++)
            paneWebView(panes[i].id).runJavaScript(ProbeUtils.readProfileMarkerScript(fixedKey))
    }

    function startRecreate() {
        phase = "recreating"
        pending = panes.length
        recreateLoaded = ({})
        for (var i = 0; i < panes.length; i++)
            recreatePane(panes[i].id)
    }

    function recreatePane(id) {
        var item = paneItemForId(id)
        if (!item)
            return

        item.loader.active = false
        Qt.callLater(function() {
            item.loader.active = true
        })
    }

    function onPaneLoaded(id) {
        if (phase !== "recreating" || recreateLoaded[id])
            return

        recreateLoaded[id] = true
        if (--pending === 0)
            startReadAfter()
    }

    function startReadAfter() {
        phase = "readAfter"
        pending = panes.length
        for (var i = 0; i < panes.length; i++)
            paneWebView(panes[i].id).runJavaScript(ProbeUtils.readProfileMarkerScript(fixedKey))
    }

    ColumnLayout {
        width: parent.width
        height: parent.height
        spacing: Theme.spacingSm

        Label {
            Layout.fillWidth: true
            wrapMode: Text.WordWrap
            color: Theme.textSecondary
            font.pixelSize: Theme.fontSm
            text: "Same origin, three profiles. Writes localStorage + cookie per pane, recreates each webview, then re-reads to verify isolation and persistence."
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingSm

            AppButton {
                label: "Reload pages"
                onClicked: root.loadAllPages()
            }
            AppButton {
                label: "Run profile isolation test"
                accent: true
                enabled: root.phase === "idle" || root.phase === "done"
                onClicked: root.runProfileIsolationTest()
            }
        }

        VerdictBadge {
            Layout.fillWidth: true
            verdict: root.verdict
            detail: root.phase !== "idle" ? ("phase=" + root.phase + " | " + root.verdictDetail()) : root.verdictDetail()
        }

        RowLayout {
            id: paneRow
            Layout.fillWidth: true
            Layout.fillHeight: true
            spacing: Theme.spacingSm

            readonly property int paneLabelHeight: 22

            Repeater {
                id: paneRepeater
                model: root.panes

                delegate: Item {
                    id: paneRoot
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    Layout.preferredWidth: 0

                    property var paneDef: modelData
                    property alias loader: paneLoader

                    ColumnLayout {
                        anchors.fill: parent
                        spacing: Theme.spacingXs

                        Label {
                            Layout.fillWidth: true
                            Layout.preferredHeight: paneRow.paneLabelHeight
                            text: paneDef.label
                            font.bold: true
                            color: Theme.textPrimary
                            elide: Text.ElideRight
                            verticalAlignment: Text.AlignVCenter
                        }

                        Loader {
                            id: paneLoader
                            Layout.fillWidth: true
                            Layout.fillHeight: true
                            active: true

                            sourceComponent: WebViewHost {
                                storageName: paneDef.storageName
                                offTheRecord: paneDef.offTheRecord
                                autoLoad: false
                                inputFocused: root.addressFocused || root.contentInputFocused
                            }

                            onLoaded: root.loadPaneHost(paneDef.id)
                        }
                    }

                    Connections {
                        target: paneLoader.item ? paneLoader.item.webView : null

                        function onJavaScriptResult(result, error) {
                            root.onPaneResult(paneDef.id, result, error)
                        }

                        function onLoadingChanged() {
                            if (!paneLoader.item || !paneLoader.item.webView)
                                return
                            if (root.phase === "recreating" && !paneLoader.item.webView.loading)
                                root.onPaneLoaded(paneDef.id)
                        }
                    }
                }
            }
        }
    }
}
