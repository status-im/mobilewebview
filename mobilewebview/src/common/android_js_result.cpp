#include "android_js_result.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonValue>

QVariant decodeAndroidEvaluateJsResult(const QString &raw)
{
    if (raw.isEmpty()) {
        return QVariant();
    }

    // QJsonDocument::fromJson only accepts a top-level object/array, so wrap the
    // value in an array and inspect element 0. This handles all JSON scalar
    // types uniformly.
    const QByteArray wrapped = QByteArray("[") + raw.toUtf8() + QByteArray("]");

    QJsonParseError parseError{};
    const QJsonDocument doc = QJsonDocument::fromJson(wrapped, &parseError);
    if (parseError.error != QJsonParseError::NoError || !doc.isArray()) {
        return raw;
    }

    const QJsonArray array = doc.array();
    if (array.isEmpty()) {
        return raw;
    }

    const QJsonValue value = array.at(0);
    switch (value.type()) {
    case QJsonValue::Null:
        return QVariant();
    case QJsonValue::Bool:
        return value.toBool();
    case QJsonValue::Double:
        return value.toDouble();
    case QJsonValue::String:
        return value.toString();
    case QJsonValue::Array:
        return QString::fromUtf8(
            QJsonDocument(value.toArray()).toJson(QJsonDocument::Compact).trimmed());
    case QJsonValue::Object:
        return QString::fromUtf8(
            QJsonDocument(value.toObject()).toJson(QJsonDocument::Compact).trimmed());
    case QJsonValue::Undefined:
    default:
        return raw;
    }
}
