import QtQuick
import QtQuick.Controls
import QtWebChannel
import MobileWebView 1.0

Item {
    id: root

    property alias webView: webView
    property bool inputFocused: false
    property url initialUrl: "https://example.com/"
    property string storageName: "Profile_A"
    property bool offTheRecord: false
    property bool freeze: false
    property var userScripts: []
    property var webChannel: null
    property string webChannelNamespace: "qt"
    property bool autoLoad: true

    implicitWidth: webView.implicitWidth
    implicitHeight: webView.implicitHeight

    MobileWebViewBackend {
        id: webView
        anchors.fill: parent
        freeze: root.freeze
        offTheRecord: root.offTheRecord
        storageName: root.storageName
        webChannelNamespace: root.webChannelNamespace
        webChannel: root.webChannel
        userScripts: root.userScripts
        interactionEnabled: !root.inputFocused
    }

    Component.onCompleted: {
        if (root.autoLoad)
            webView.url = root.initialUrl
    }

    function loadAddress(rawText) {
        var text = (rawText || "").trim()
        if (text.length === 0)
            return
        if (text.indexOf("://") === -1)
            text = "https://" + text
        webView.loadUrl(text)
    }

    function loadHtml(html, baseUrl) {
        webView.loadHtml(html, baseUrl)
    }

    function reload() {
        webView.reload()
    }
}
