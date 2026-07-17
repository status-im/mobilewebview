#pragma once

#include <QImage>
#include <QSize>
#include <functional>

/// Freeze / Snapshot correlation state machine for one backend.
/// Overlay and native-view wiring are injected callbacks (narrow seam).
class FreezeController
{
public:
    enum class State {
        Idle,
        Capturing,
        Frozen,
    };

    struct Callbacks {
        std::function<void(quint64 requestId)> captureSnapshot;
        std::function<void(const QImage &image)> applyOverlay;
        /// Tear down overlay immediately (clear / failed capture / cancel while Capturing).
        std::function<void()> hideOverlay;
        /// Frozen → Idle: detach overlay for deferred deleteLater (do not hideOverlay).
        std::function<void()> unfreezeFromFrozen;
        std::function<void()> restoreClip;
        std::function<void()> updateNativeVisibility;
        std::function<void()> emitFreezeChanged;
        /// Invoke markFrozen(token) after the overlay has been presented for a frame.
        std::function<void(quint64 token)> scheduleMarkFrozen;
        std::function<void(quint64 requestId, const QImage &image, QSize targetSize, qreal dpr)>
            deliverPublicSnapshot;
        std::function<void()> warnEmptyFreezeSnapshot;
    };

    explicit FreezeController(Callbacks callbacks);

    State state() const { return m_state; }
    quint64 freezeRequestId() const { return m_freezeRequestId; }
    quint64 nextSnapshotId() const { return m_nextSnapshotId; }
    bool publicSnapshotPending() const { return m_publicSnapshotPending; }
    qreal publicSnapshotDpr() const { return m_publicSnapshotDpr; }

    /// Idle → Capturing. No-op (returns false) if already Capturing/Frozen.
    bool beginFreeze();

    /// → Idle. Returns false if already Idle.
    bool endFreeze();

    /// Force Idle and tear down overlay/clip/visibility. Does not emit freezeChanged.
    void clear();

    quint64 beginPublicSnapshot(const QSize &targetSize, qreal dpr);

    void notifySnapshotReady(quint64 requestId, const QImage &image);
    void markFrozen(quint64 captureToken);

    bool shouldShowNativeWebView(bool qmlItemVisible, bool nativeViewSetup) const
    {
        return qmlItemVisible && nativeViewSetup && m_state != State::Frozen;
    }

private:
    Callbacks m_cb;
    State m_state = State::Idle;
    quint64 m_freezeRequestId = 0;
    quint64 m_nextSnapshotId = 0;
    bool m_publicSnapshotPending = false;
    quint64 m_publicSnapshotRequestId = 0;
    QSize m_publicSnapshotTargetSize;
    qreal m_publicSnapshotDpr = 1.0;
};
