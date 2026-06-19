#include <QtTest/QtTest>

#include "android_js_result.h"

class AndroidJsResultTest : public QObject
{
    Q_OBJECT

private slots:
    void doubleEncodedObjectIsUnwrappedToJsonString();
    void jsonStringScalarIsUnwrapped();
    void numberDecodesToDouble();
    void boolDecodesToBool();
    void nullAndEmptyDecodeToInvalid();
    void bareObjectPassesThroughAsCompactJson();
    void malformedInputFallsBackToRaw();
};

// Android WebView.evaluateJavascript() JSON-encodes every result. A probe that
// returns JSON.stringify({...}) therefore arrives double-encoded:
//   "{\"ls\":\"v\"}"
// The decoder must mirror the Apple contract and hand back the inner JSON text
// so QML's JSON.parse sees an object, not a string.
void AndroidJsResultTest::doubleEncodedObjectIsUnwrappedToJsonString()
{
    const QString raw = QStringLiteral("\"{\\\"ls\\\":\\\"v\\\"}\"");
    const QVariant decoded = decodeAndroidEvaluateJsResult(raw);
    QCOMPARE(decoded.metaType().id(), QMetaType::QString);
    QCOMPARE(decoded.toString(), QStringLiteral("{\"ls\":\"v\"}"));
}

void AndroidJsResultTest::jsonStringScalarIsUnwrapped()
{
    const QVariant decoded = decodeAndroidEvaluateJsResult(QStringLiteral("\"hello\""));
    QCOMPARE(decoded.metaType().id(), QMetaType::QString);
    QCOMPARE(decoded.toString(), QStringLiteral("hello"));
}

void AndroidJsResultTest::numberDecodesToDouble()
{
    const QVariant decoded = decodeAndroidEvaluateJsResult(QStringLiteral("42"));
    QCOMPARE(decoded.metaType().id(), QMetaType::Double);
    QCOMPARE(decoded.toDouble(), 42.0);
}

void AndroidJsResultTest::boolDecodesToBool()
{
    const QVariant decoded = decodeAndroidEvaluateJsResult(QStringLiteral("true"));
    QCOMPARE(decoded.metaType().id(), QMetaType::Bool);
    QCOMPARE(decoded.toBool(), true);
}

void AndroidJsResultTest::nullAndEmptyDecodeToInvalid()
{
    QVERIFY(!decodeAndroidEvaluateJsResult(QStringLiteral("null")).isValid());
    QVERIFY(!decodeAndroidEvaluateJsResult(QString()).isValid());
}

void AndroidJsResultTest::bareObjectPassesThroughAsCompactJson()
{
    const QVariant decoded = decodeAndroidEvaluateJsResult(QStringLiteral("{\"a\":1}"));
    QCOMPARE(decoded.metaType().id(), QMetaType::QString);
    QCOMPARE(decoded.toString(), QStringLiteral("{\"a\":1}"));
}

void AndroidJsResultTest::malformedInputFallsBackToRaw()
{
    const QString raw = QStringLiteral("not json at all");
    const QVariant decoded = decodeAndroidEvaluateJsResult(raw);
    QCOMPARE(decoded.toString(), raw);
}

QTEST_MAIN(AndroidJsResultTest)
#include "tst_android_js_result.moc"
