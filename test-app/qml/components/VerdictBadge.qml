import QtQuick
import QtQuick.Controls
import MobileWebViewTest

Rectangle {
    id: root

    property string verdict: "unknown"
    property string detail: ""

    implicitHeight: Math.max(28, label.implicitHeight + Theme.spacingSm)
    radius: Theme.radiusMd
    color: {
        if (verdict === "isolated" || verdict === "pass")
            return Theme.successSurface
        if (verdict === "shared" || verdict === "fail")
            return Theme.dangerSurface
        if (verdict === "skip")
            return Theme.warningSurface
        if (verdict === "inconclusive")
            return Theme.accentSurface
        return Theme.accentSurface
    }
    border.color: {
        if (verdict === "isolated" || verdict === "pass")
            return Theme.success
        if (verdict === "shared" || verdict === "fail")
            return Theme.danger
        if (verdict === "skip")
            return Theme.warning
        if (verdict === "inconclusive")
            return Theme.accent
        return Theme.accent
    }
    border.width: 1

    Label {
        id: label
        anchors.fill: parent
        anchors.margins: Theme.spacingSm
        text: {
            var prefix = verdict.toUpperCase()
            if (detail.length > 0)
                return prefix + " — " + detail
            return prefix
        }
        wrapMode: Text.WordWrap
        color: Theme.textPrimary
        font.pixelSize: Theme.fontSm
        font.bold: true
    }
}
