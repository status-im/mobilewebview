#pragma once

#include <QQuickItem>
#include <QImage>

class QSGNode;

// Renders a QImage in the Qt Quick scene graph (used when native WebView is hidden).
class MobileWebViewSnapshotItem final : public QQuickItem
{
    Q_OBJECT

public:
    explicit MobileWebViewSnapshotItem(QQuickItem *parent = nullptr);

    void setImage(const QImage &image);
    QImage image() const { return m_image; }

protected:
    QSGNode *updatePaintNode(QSGNode *oldNode, UpdatePaintNodeData *data) override;
    void geometryChange(const QRectF &newGeometry, const QRectF &oldGeometry) override;

private:
    QImage m_image;
    bool m_textureDirty = true;
};
