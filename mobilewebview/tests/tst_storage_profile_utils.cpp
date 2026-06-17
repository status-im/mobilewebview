#include <QtTest/QtTest>

#include "../src/common/storage_profile_utils.h"

class StorageProfileUtilsTest : public QObject
{
    Q_OBJECT

private slots:
    void identifierIsDeterministicForSameStorageName();
    void identifierDiffersForDifferentStorageNames();
};

void StorageProfileUtilsTest::identifierIsDeterministicForSameStorageName()
{
    const QUuid first = storageProfileIdentifier(QStringLiteral("Profile_A"));
    const QUuid second = storageProfileIdentifier(QStringLiteral("Profile_A"));
    QCOMPARE(first, second);
    QVERIFY(!first.isNull());
}

void StorageProfileUtilsTest::identifierDiffersForDifferentStorageNames()
{
    const QUuid profileA = storageProfileIdentifier(QStringLiteral("Profile_A"));
    const QUuid profileB = storageProfileIdentifier(QStringLiteral("Profile_B"));
    QVERIFY(!profileA.isNull());
    QVERIFY(!profileB.isNull());
    QVERIFY(profileA != profileB);
}

QTEST_MAIN(StorageProfileUtilsTest)
#include "tst_storage_profile_utils.moc"
