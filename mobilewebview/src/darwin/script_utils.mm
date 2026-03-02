#include "script_utils.h"
#include "userscripts.h"
#include "../common/userscript_utils.h"

#include <QVariantMap>

QList<UserScriptInfo> parseUserScripts(const QVariantList &scripts)
{
    QList<UserScriptInfo> result;
    
    for (const QVariant &scriptEntry : scripts) {
        const QString path = extractUserScriptPath(scriptEntry);
        if (path.isEmpty()) {
            continue;
        }

        if (scriptEntry.canConvert<QVariantMap>()) {
            const QVariantMap map = scriptEntry.toMap();
            const bool runOnSubFrames = map.value(QStringLiteral("runOnSubFrames"), false).toBool();
            result.append(UserScriptInfo(path, runOnSubFrames));
        } else {
            result.append(UserScriptInfo(path, false));
        }
    }
    
    return result;
}
