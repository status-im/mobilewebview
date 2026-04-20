#include "snapshotitem.h"

#include <QQuickWindow>
#include <QSGSimpleTextureNode>
#include <QSGTexture>

MobileWebViewSnapshotItem::MobileWebViewSnapshotItem(QQuickItem *parent)
    : QQuickItem(parent)
{
    setFlag(ItemHasContents, true);
    setAntialiasing(false);
    setZ(1000000);
}

void MobileWebViewSnapshotItem::setImage(const QImage &image)
{
    if (m_image == image) {
        return;
    }
    m_image = image;
    m_textureDirty = true;
    update();
}

void MobileWebViewSnapshotItem::geometryChange(const QRectF &newGeometry, const QRectF &oldGeometry)
{
    QQuickItem::geometryChange(newGeometry, oldGeometry);
    if (newGeometry.size() != oldGeometry.size()) {
        update();
    }
}

QSGNode *MobileWebViewSnapshotItem::updatePaintNode(QSGNode *oldNode, UpdatePaintNodeData *data)
{
    Q_UNUSED(data)

    QQuickWindow *win = window();
    if (!win || m_image.isNull()) {
        delete oldNode;
        m_textureDirty = true;
        return nullptr;
    }

    auto *node = static_cast<QSGSimpleTextureNode *>(oldNode);
    if (!node) {
        node = new QSGSimpleTextureNode();
    }

    if (m_textureDirty) {
        if (node->texture()) {
            delete node->texture();
        }
        QSGTexture *texture = win->createTextureFromImage(m_image, QQuickWindow::TextureCanUseAtlas);
        node->setTexture(texture);
        node->setOwnsTexture(true);
        m_textureDirty = false;
    }

    node->setRect(boundingRect());
    node->setFiltering(QSGTexture::Linear);

    return node;
}
