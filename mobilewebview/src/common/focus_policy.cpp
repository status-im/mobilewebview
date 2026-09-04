#include "focus_policy.h"

#include <QCoreApplication>
#include <QInputMethodEvent>

namespace MobileWebViewFocusPolicy {

bool nativeViewMayGrabFocus(QObject *qtFocusObject)
{
    if (!qtFocusObject)
        return true;

    QInputMethodQueryEvent query(Qt::ImEnabled);
    QCoreApplication::sendEvent(qtFocusObject, &query);
    return !query.value(Qt::ImEnabled).toBool();
}

} // namespace MobileWebViewFocusPolicy
