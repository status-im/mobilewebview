#include "freezecontroller.h"

FreezeController::FreezeController(Callbacks callbacks)
    : m_cb(std::move(callbacks))
{
}

bool FreezeController::beginFreeze()
{
    if (m_state == State::Capturing || m_state == State::Frozen)
        return false;

    m_state = State::Capturing;
    m_freezeRequestId = ++m_nextSnapshotId;
    if (m_cb.emitFreezeChanged)
        m_cb.emitFreezeChanged();
    if (m_cb.captureSnapshot)
        m_cb.captureSnapshot(m_freezeRequestId);
    return true;
}

bool FreezeController::endFreeze()
{
    if (m_state == State::Idle)
        return false;

    if (m_state == State::Frozen) {
        m_state = State::Idle;
        if (m_cb.unfreezeFromFrozen)
            m_cb.unfreezeFromFrozen();
        if (m_cb.restoreClip)
            m_cb.restoreClip();
        if (m_cb.updateNativeVisibility)
            m_cb.updateNativeVisibility();
        if (m_cb.emitFreezeChanged)
            m_cb.emitFreezeChanged();
        return true;
    }

    // Capturing → Idle
    clear();
    if (m_cb.emitFreezeChanged)
        m_cb.emitFreezeChanged();
    return true;
}

void FreezeController::clear()
{
    m_state = State::Idle;
    if (m_cb.hideOverlay)
        m_cb.hideOverlay();
    if (m_cb.restoreClip)
        m_cb.restoreClip();
    if (m_cb.updateNativeVisibility)
        m_cb.updateNativeVisibility();
}

quint64 FreezeController::beginPublicSnapshot(const QSize &targetSize, qreal dpr)
{
    m_publicSnapshotRequestId = ++m_nextSnapshotId;
    m_publicSnapshotPending = true;
    m_publicSnapshotTargetSize = targetSize;
    m_publicSnapshotDpr = dpr > 0 ? dpr : 1.0;
    if (m_cb.captureSnapshot)
        m_cb.captureSnapshot(m_publicSnapshotRequestId);
    return m_publicSnapshotRequestId;
}

void FreezeController::notifySnapshotReady(quint64 requestId, const QImage &image)
{
    if (m_publicSnapshotPending && requestId == m_publicSnapshotRequestId) {
        m_publicSnapshotPending = false;
        if (m_cb.deliverPublicSnapshot) {
            m_cb.deliverPublicSnapshot(
                requestId, image, m_publicSnapshotTargetSize, m_publicSnapshotDpr);
        }
        return;
    }

    if (requestId != m_freezeRequestId)
        return;
    if (m_state != State::Capturing)
        return;

    if (image.isNull()) {
        if (m_cb.warnEmptyFreezeSnapshot)
            m_cb.warnEmptyFreezeSnapshot();
        clear();
        if (m_cb.emitFreezeChanged)
            m_cb.emitFreezeChanged();
        return;
    }

    if (m_cb.applyOverlay)
        m_cb.applyOverlay(image);
    if (m_cb.scheduleMarkFrozen)
        m_cb.scheduleMarkFrozen(requestId);
}

void FreezeController::markFrozen(quint64 captureToken)
{
    if (m_state != State::Capturing || m_freezeRequestId != captureToken)
        return;
    m_state = State::Frozen;
    if (m_cb.updateNativeVisibility)
        m_cb.updateNativeVisibility();
}
