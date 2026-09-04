#include <QtTest/QtTest>

#include "focus_policy.h"

using namespace MobileWebViewFocusPolicy;

class ImEnabledObject : public QObject
{
    Q_OBJECT
public:
    explicit ImEnabledObject(bool enabled) : m_enabled(enabled) {}

protected:
    bool event(QEvent *e) override
    {
        if (e->type() == QEvent::InputMethodQuery) {
            auto *q = static_cast<QInputMethodQueryEvent *>(e);
            if (q->queries() & Qt::ImEnabled)
                q->setValue(Qt::ImEnabled, m_enabled);
            return true;
        }
        return QObject::event(e);
    }

private:
    bool m_enabled;
};

class FocusPolicyTest : public QObject
{
    Q_OBJECT

private slots:
    void grabsWhenNothingFocused();
    void grabsWhenFocusObjectIsNotTextInput();
    void doesNotGrabWhenTextInputFocused();
};

void FocusPolicyTest::grabsWhenNothingFocused()
{
    QVERIFY(nativeViewMayGrabFocus(nullptr));
}

void FocusPolicyTest::grabsWhenFocusObjectIsNotTextInput()
{
    QObject plain;
    QVERIFY(nativeViewMayGrabFocus(&plain));

    ImEnabledObject disabled(false);
    QVERIFY(nativeViewMayGrabFocus(&disabled));
}

void FocusPolicyTest::doesNotGrabWhenTextInputFocused()
{
    ImEnabledObject textInput(true);
    QVERIFY(!nativeViewMayGrabFocus(&textInput));
}

QTEST_GUILESS_MAIN(FocusPolicyTest)
#include "tst_focus_policy.moc"
