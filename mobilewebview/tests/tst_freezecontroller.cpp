#include <QtTest/QtTest>

#include "freezecontroller.h"

#include <QImage>

class FreezeControllerTest : public QObject
{
    Q_OBJECT

private slots:
    void idleToCapturingOnFreezeTrue();
    void capturingToFrozenOnMatchingSnapshot();
    void staleRequestIdIgnored();
    void freezeFalseFromFrozenToIdle();
    void clearResets();
};

namespace {

struct Probe {
    int captureCalls = 0;
    quint64 lastCaptureId = 0;
    int applyOverlayCalls = 0;
    int hideOverlayCalls = 0;
    int unfreezeFromFrozenCalls = 0;
    int restoreClipCalls = 0;
    int updateVisibilityCalls = 0;
    int emitFreezeChangedCalls = 0;
    int scheduleMarkFrozenCalls = 0;
    quint64 lastScheduleToken = 0;
    FreezeController *controller = nullptr;

    FreezeController::Callbacks callbacks()
    {
        FreezeController::Callbacks cb;
        cb.captureSnapshot = [this](quint64 id) {
            ++captureCalls;
            lastCaptureId = id;
        };
        cb.applyOverlay = [this](const QImage &) { ++applyOverlayCalls; };
        cb.hideOverlay = [this]() { ++hideOverlayCalls; };
        cb.unfreezeFromFrozen = [this]() { ++unfreezeFromFrozenCalls; };
        cb.restoreClip = [this]() { ++restoreClipCalls; };
        cb.updateNativeVisibility = [this]() { ++updateVisibilityCalls; };
        cb.emitFreezeChanged = [this]() { ++emitFreezeChangedCalls; };
        cb.scheduleMarkFrozen = [this](quint64 token) {
            ++scheduleMarkFrozenCalls;
            lastScheduleToken = token;
            // Immediate settle for unit tests (no frame delay).
            if (controller)
                controller->markFrozen(token);
        };
        cb.deliverPublicSnapshot = {};
        cb.warnEmptyFreezeSnapshot = {};
        return cb;
    }
};

} // namespace

void FreezeControllerTest::idleToCapturingOnFreezeTrue()
{
    Probe probe;
    FreezeController freeze(probe.callbacks());
    probe.controller = &freeze;

    using FS = FreezeController::State;
    QCOMPARE(freeze.state(), FS::Idle);

    QVERIFY(freeze.beginFreeze());
    QCOMPARE(freeze.state(), FS::Capturing);
    QCOMPARE(probe.captureCalls, 1);
    QCOMPARE(probe.lastCaptureId, freeze.freezeRequestId());
    QCOMPARE(probe.emitFreezeChangedCalls, 1);

    QVERIFY(!freeze.beginFreeze());
    QCOMPARE(probe.captureCalls, 1);
}

void FreezeControllerTest::capturingToFrozenOnMatchingSnapshot()
{
    Probe probe;
    FreezeController freeze(probe.callbacks());
    probe.controller = &freeze;

    using FS = FreezeController::State;
    freeze.beginFreeze();
    const quint64 rid = freeze.freezeRequestId();

    QImage img(2, 2, QImage::Format_ARGB32);
    img.fill(Qt::red);
    freeze.notifySnapshotReady(rid, img);

    QCOMPARE(probe.applyOverlayCalls, 1);
    QCOMPARE(probe.scheduleMarkFrozenCalls, 1);
    QCOMPARE(freeze.state(), FS::Frozen);
    QCOMPARE(probe.updateVisibilityCalls, 1);
}

void FreezeControllerTest::staleRequestIdIgnored()
{
    Probe probe;
    FreezeController freeze(probe.callbacks());
    probe.controller = &freeze;

    using FS = FreezeController::State;
    freeze.beginFreeze();
    const quint64 rid = freeze.freezeRequestId();

    QVERIFY(freeze.endFreeze());
    QCOMPARE(freeze.state(), FS::Idle);

    QImage img(1, 1, QImage::Format_ARGB32);
    img.fill(Qt::blue);
    freeze.notifySnapshotReady(rid, img);

    QCOMPARE(probe.applyOverlayCalls, 0);
    QCOMPARE(freeze.state(), FS::Idle);
}

void FreezeControllerTest::freezeFalseFromFrozenToIdle()
{
    Probe probe;
    FreezeController freeze(probe.callbacks());
    probe.controller = &freeze;

    using FS = FreezeController::State;
    freeze.beginFreeze();
    QImage img(2, 2, QImage::Format_ARGB32);
    img.fill(Qt::red);
    freeze.notifySnapshotReady(freeze.freezeRequestId(), img);
    QCOMPARE(freeze.state(), FS::Frozen);

    const int hideBefore = probe.hideOverlayCalls;
    QVERIFY(freeze.endFreeze());
    QCOMPARE(freeze.state(), FS::Idle);
    QCOMPARE(probe.unfreezeFromFrozenCalls, 1);
    QCOMPARE(probe.hideOverlayCalls, hideBefore);
    QVERIFY(probe.emitFreezeChangedCalls >= 2);
}

void FreezeControllerTest::clearResets()
{
    Probe probe;
    FreezeController freeze(probe.callbacks());
    probe.controller = &freeze;

    using FS = FreezeController::State;
    freeze.beginFreeze();
    QCOMPARE(freeze.state(), FS::Capturing);

    freeze.clear();
    QCOMPARE(freeze.state(), FS::Idle);
    QCOMPARE(probe.hideOverlayCalls, 1);
    QCOMPARE(probe.restoreClipCalls, 1);
}

QTEST_GUILESS_MAIN(FreezeControllerTest)
#include "tst_freezecontroller.moc"
