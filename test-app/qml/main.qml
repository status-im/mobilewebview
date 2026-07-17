import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Window
import MobileWebViewTest

ApplicationWindow {
    id: root

    visible: true
    width: Math.min(Screen.width, 960)
    height: Math.min(Screen.height, 900)
    title: "Mobile WebView Test"

    property string statusText: "Ready"
    property bool incognitoActive: false
    property int agentPort: 0

    color: Theme.background

    function agentListScreens() {
        return homeScreen.features.map(function(f) {
            return { id: f.screenId, title: f.title, subtitle: f.subtitle }
        })
    }

    function agentOpenScreen(id) {
        var features = homeScreen.features
        for (var i = 0; i < features.length; ++i) {
            if (features[i].screenId === id) {
                // Reset to home, then push the target screen.
                while (stack.depth > 1)
                    stack.pop()
                stack.push(features[i].screen, { stackView: stack })
                return { ok: true, id: id, title: features[i].title }
            }
        }
        return { ok: false, error: "unknown screen id: " + id }
    }

    function agentGoHome() {
        while (stack.depth > 1)
            stack.pop()
        return { ok: true }
    }

    function agentPressBack() {
        var item = stack.currentItem
        if (!item || stack.depth <= 1)
            return { ok: false, error: "already at home" }
        if (typeof item.goBack === "function")
            item.goBack()
        else if (typeof item.backRequested === "undefined")
            stack.pop()
        else
            item.backRequested()
        return { ok: true }
    }

    function agentWebView() {
        var item = stack.currentItem
        if (!item)
            return null
        if (item.webView)
            return item.webView
        return null
    }

    function agentInvoke(action) {
        var item = stack.currentItem
        if (!item)
            return { ok: false, error: "no current screen" }
        if (!item.agentActions || item.agentActions[action] === undefined)
            return { ok: false, error: "unknown action: " + action
                     + (item.agentActions
                        ? (" (have: " + Object.keys(item.agentActions).join(", ") + ")")
                        : " (screen has no agentActions)") }
        item.agentActions[action]()
        return { ok: true, action: action }
    }

    function agentSetProp(name, value) {
        var item = stack.currentItem
        if (!item)
            return { ok: false, error: "no current screen" }
        if (item[name] === undefined)
            return { ok: false, error: "unknown property: " + name }
        item[name] = value
        return { ok: true, name: name, value: item[name] }
    }

    function agentState() {
        var item = stack.currentItem
        var wv = agentWebView()
        var screenId = ""
        var screenTitle = ""
        if (item === homeScreen) {
            screenId = "home"
            screenTitle = "Home"
        } else if (item && item.title) {
            screenTitle = item.title
            var features = homeScreen.features
            for (var i = 0; i < features.length; ++i) {
                if (features[i].title === item.title) {
                    screenId = features[i].screenId
                    break
                }
            }
        }

        var actions = []
        if (item && item.agentActions) {
            actions = Object.keys(item.agentActions)
        }

        var screenState = {}
        if (item && typeof item.agentState === "function")
            screenState = item.agentState()

        return {
            agentPort: root.agentPort,
            statusText: root.statusText,
            stackDepth: stack.depth,
            screenId: screenId,
            screenTitle: screenTitle,
            actions: actions,
            screen: screenState,
            webView: wv ? {
                url: wv.url.toString(),
                title: wv.title,
                loading: wv.loading,
                loaded: wv.loaded,
                loadProgress: wv.loadProgress,
                canGoBack: wv.canGoBack,
                canGoForward: wv.canGoForward,
                zoomFactor: wv.zoomFactor,
                freeze: wv.freeze,
                offTheRecord: wv.offTheRecord,
                storageName: wv.storageName,
                clearing: wv.clearing
            } : null
        }
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        StackView {
            id: stack
            Layout.fillWidth: true
            Layout.fillHeight: true
            initialItem: HomeScreen {
                id: homeScreen
                stackView: stack
            }

            onCurrentItemChanged: {
                if (currentItem && currentItem.statusMessage) {
                    currentItem.statusMessage.connect(function(message) {
                        root.statusText = message
                    })
                }
            }
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: Theme.statusBarHeight
            color: root.incognitoActive ? Theme.statusBarIncognito : Theme.statusBar

            Label {
                anchors.fill: parent
                anchors.leftMargin: Theme.spacingSm
                anchors.rightMargin: Theme.spacingSm
                text: (root.agentPort > 0 ? ("agent :" + root.agentPort + " · ") : "")
                      + root.statusText
                color: Theme.textSecondary
                font.pixelSize: Theme.fontXs
                elide: Text.ElideRight
                verticalAlignment: Text.AlignVCenter
            }
        }
    }
}
