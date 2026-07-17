import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import MobileWebViewTest

Item {
    id: root

    required property var stackView

    readonly property var features: [
        {
            title: "Navigation & History",
            subtitle: "Address bar, back/forward, reload, progress, history",
            screen: Qt.resolvedUrl("screens/NavigationScreen.qml")
        },
        {
            title: "Find in page",
            subtitle: "Search, next/prev, case sensitivity, match count",
            screen: Qt.resolvedUrl("screens/FindScreen.qml")
        },
        {
            title: "Zoom",
            subtitle: "Zoom factor controls and reload persistence",
            screen: Qt.resolvedUrl("screens/ZoomScreen.qml")
        },
        {
            title: "Cookies",
            subtitle: "Write/read cookies with max-age",
            screen: Qt.resolvedUrl("screens/CookiesScreen.qml")
        },
        {
            title: "localStorage",
            subtitle: "Write/read localStorage values",
            screen: Qt.resolvedUrl("screens/LocalStorageScreen.qml")
        },
        {
            title: "Profile isolation",
            subtitle: "3 profiles, persistence after recreate",
            screen: Qt.resolvedUrl("screens/ProfileIsolationScreen.qml")
        },
        {
            title: "Data clearing",
            subtitle: "Clear browsing data + clear current site data",
            screen: Qt.resolvedUrl("screens/DataClearingScreen.qml")
        },
        {
            title: "Origin",
            subtitle: "Same partition, different origins",
            screen: Qt.resolvedUrl("screens/OriginScreen.qml")
        },
        {
            title: "Scripts & isolation",
            subtitle: "WebChannel bridge and JS world isolation",
            screen: Qt.resolvedUrl("screens/ScriptsScreen.qml")
        },
        {
            title: "Snapshot & Freeze",
            subtitle: "requestSnapshot preview and freeze mode",
            screen: Qt.resolvedUrl("screens/RenderingScreen.qml")
        },
        {
            title: "Downloads",
            subtitle: "downloadUrl, page/inline, pause/resume, retry, profiles",
            screen: Qt.resolvedUrl("screens/DownloadsScreen.qml")
        }
    ]

    ScrollView {
        id: homeScroll
        anchors.fill: parent
        anchors.margins: Theme.spacingLg
        clip: true

        ColumnLayout {
            width: homeScroll.availableWidth
            spacing: Theme.spacingLg

            Label {
                Layout.fillWidth: true
                text: "MobileWebView Test Harness"
                font.pixelSize: 24
                font.bold: true
                color: Theme.textPrimary
            }

            Label {
                Layout.fillWidth: true
                text: "Pick a feature to open an isolated test screen."
                font.pixelSize: Theme.fontMd
                color: Theme.textSecondary
                wrapMode: Text.WordWrap
            }

            GridLayout {
                Layout.fillWidth: true
                columns: width > Theme.contentMaxWidth ? 3 : (width > 480 ? 2 : 1)
                columnSpacing: Theme.spacingMd
                rowSpacing: Theme.spacingMd

                Repeater {
                    model: root.features

                    FeatureCard {
                        Layout.fillWidth: true
                        title: modelData.title
                        subtitle: modelData.subtitle
                        onClicked: {
                            root.stackView.push(modelData.screen, {
                                stackView: root.stackView
                            })
                        }
                    }
                }
            }
        }
    }
}
