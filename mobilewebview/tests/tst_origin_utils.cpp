#include <QtTest/QtTest>

#include "origin_utils.h"

class OriginUtilsTest : public QObject
{
    Q_OBJECT

private slots:
    void testExtractOrigin_data();
    void testExtractOrigin();
    void testIsOriginAllowed();
};

void OriginUtilsTest::testExtractOrigin_data()
{
    QTest::addColumn<QString>("url");
    QTest::addColumn<QString>("expectedOrigin");

    QTest::newRow("https default port") << QStringLiteral("https://example.com/path")
                                        << QStringLiteral("https://example.com");
    QTest::newRow("http custom port") << QStringLiteral("http://example.com:8080/path")
                                      << QStringLiteral("http://example.com:8080");
    QTest::newRow("https explicit default port omitted") << QStringLiteral("https://example.com:443/path")
                                                         << QStringLiteral("https://example.com");
    QTest::newRow("http explicit default port omitted") << QStringLiteral("http://example.com:80/path")
                                                        << QStringLiteral("http://example.com");
    QTest::newRow("missing host") << QStringLiteral("https:///path") << QString();
    QTest::newRow("missing scheme") << QStringLiteral("//example.com/path") << QString();
    QTest::newRow("invalid url") << QStringLiteral("not-a-url") << QString();
}

void OriginUtilsTest::testExtractOrigin()
{
    QFETCH(QString, url);
    QFETCH(QString, expectedOrigin);

    QCOMPARE(extractOrigin(QUrl(url)), expectedOrigin);
}

void OriginUtilsTest::testIsOriginAllowed()
{
    QVERIFY(!isOriginAllowed(QStringLiteral("https://example.com"), {}));
    QVERIFY(!isOriginAllowed(QString(), {QStringLiteral("*")}));
    QVERIFY(isOriginAllowed(QStringLiteral("https://example.com"),
                            {QStringLiteral("https://example.com")}));
    QVERIFY(isOriginAllowed(QStringLiteral("https://api.example.com"),
                            {QStringLiteral("https://*.example.com")}));
    QVERIFY(isOriginAllowed(QStringLiteral("https://example.com"),
                            {QStringLiteral("*://example.com")}));
    QVERIFY(isOriginAllowed(QStringLiteral("https://example.com"),
                            {QStringLiteral("*")}));
    QVERIFY(!isOriginAllowed(QStringLiteral("https://example.org"),
                             {QStringLiteral("https://*.example.com")}));
}

QTEST_MAIN(OriginUtilsTest)
#include "tst_origin_utils.moc"
