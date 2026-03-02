#include <QtTest/QtTest>
#include <QUrl>

#include "userscript_utils.h"

class UserScriptUtilsTest : public QObject
{
    Q_OBJECT

private slots:
    void testNormalizeScriptPath_data();
    void testNormalizeScriptPath();
    void testExtractUserScriptPath_data();
    void testExtractUserScriptPath();
};

void UserScriptUtilsTest::testNormalizeScriptPath_data()
{
    QTest::addColumn<QString>("rawPath");
    QTest::addColumn<QString>("expected");

    QTest::newRow("empty") << QString() << QString();
    QTest::newRow("resource path stays") << QStringLiteral(":/CustomWebView/js/a.js")
                                         << QStringLiteral(":/CustomWebView/js/a.js");
    QTest::newRow("qrc url normalized") << QStringLiteral("qrc:/CustomWebView/js/a.js")
                                        << QStringLiteral(":/CustomWebView/js/a.js");
    QTest::newRow("file path stays") << QStringLiteral("/tmp/a.js")
                                     << QStringLiteral("/tmp/a.js");
}

void UserScriptUtilsTest::testNormalizeScriptPath()
{
    QFETCH(QString, rawPath);
    QFETCH(QString, expected);

    QCOMPARE(normalizeScriptPath(rawPath), expected);
}

void UserScriptUtilsTest::testExtractUserScriptPath_data()
{
    QTest::addColumn<QVariant>("scriptVariant");
    QTest::addColumn<QString>("expectedPath");

    QVariantMap pathMap;
    pathMap.insert(QStringLiteral("path"), QStringLiteral("qrc:/CustomWebView/js/a.js"));

    QVariantMap sourceUrlMap;
    sourceUrlMap.insert(QStringLiteral("sourceUrl"), QStringLiteral("qrc:/CustomWebView/js/b.js"));

    QVariantMap qurlMap;
    qurlMap.insert(QStringLiteral("path"), QUrl(QStringLiteral("qrc:/CustomWebView/js/c.js")));

    QVariantMap unknownMap;
    unknownMap.insert(QStringLiteral("foo"), QStringLiteral("bar"));

    QTest::newRow("plain string") << QVariant(QStringLiteral("qrc:/CustomWebView/js/plain.js"))
                                  << QStringLiteral(":/CustomWebView/js/plain.js");
    QTest::newRow("map path") << QVariant(pathMap)
                              << QStringLiteral(":/CustomWebView/js/a.js");
    QTest::newRow("map sourceUrl") << QVariant(sourceUrlMap)
                                   << QStringLiteral(":/CustomWebView/js/b.js");
    QTest::newRow("map qurl path") << QVariant(qurlMap)
                                   << QStringLiteral(":/CustomWebView/js/c.js");
    QTest::newRow("unknown map gives empty") << QVariant(unknownMap)
                                             << QString();
}

void UserScriptUtilsTest::testExtractUserScriptPath()
{
    QFETCH(QVariant, scriptVariant);
    QFETCH(QString, expectedPath);

    QCOMPARE(extractUserScriptPath(scriptVariant), expectedPath);
}

QTEST_MAIN(UserScriptUtilsTest)
#include "tst_userscript_utils.moc"
