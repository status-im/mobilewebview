import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Window

Window {
    id: root
    visible: true
    width: Screen.width
    height: Screen.height
    visibility: Window.FullScreen
    title: "Mobile WebView Test"

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        TopBar {
            Layout.fillWidth: true
            Layout.preferredHeight: implicitHeight
            address: testWidget.currentUrlText.length > 0 ? testWidget.currentUrlText : "https://opensea.io"
            clickCount: testWidget.clickCount
            loading: testWidget.loading
            onBackRequested: testWidget.webView.goBack()
            onForwardRequested: testWidget.webView.goForward()
            onReloadRequested: testWidget.webView.reload()
            onStopRequested: testWidget.webView.stop()
            onGoRequested: function(address) {
                testWidget.loadAddress(address)
            }
            onIncrementRequested: testWidget.incrementCounter()
            onDecrementRequested: testWidget.decrementCounter()
            onJsPopupRequested: testWidget.qmlShowStaticPopup()
        }

        TestWidget {
            id: testWidget
            Layout.fillWidth: true
            Layout.fillHeight: true
            onLogMessage: function(message) {
                statusLabel.text = message
            }
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 28
            color: "#99111111"

            Label {
                id: statusLabel
                anchors.fill: parent
                anchors.margins: 6
                color: "#ffffff"
                font.pixelSize: 12
                text: "Ready"
                elide: Text.ElideRight
                verticalAlignment: Text.AlignVCenter
            }
        }
    }
}
