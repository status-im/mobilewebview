#include <QtTest/QtTest>

#include "downloadpolicy.h"

using namespace MobileWebView::DownloadPolicy;

class DownloadPolicyTest : public QObject
{
    Q_OBJECT

private slots:
    void isSupportedUrl_httpsAndHttp();
    void isSupportedUrl_rejectsBlobDataEmptyInvalid();
    void isInlineUrl_blobAndData();
    void suggestedFileName_fromContentDisposition();
    void suggestedFileName_fromUrlPath();
    void suggestedFileName_mimeFallback();
    void suggestedFileName_filenameStarUtf8();
    void suggestedFileName_quotedNames();
    void suggestedFileName_pathSanitization();
    void suggestedFileName_platformSuggestionWins();
};

void DownloadPolicyTest::isSupportedUrl_httpsAndHttp()
{
    QVERIFY(isSupportedUrl(QUrl(QStringLiteral("https://example.com/a.pdf"))));
    QVERIFY(isSupportedUrl(QUrl(QStringLiteral("http://example.com/a.pdf"))));
}

void DownloadPolicyTest::isSupportedUrl_rejectsBlobDataEmptyInvalid()
{
    QVERIFY(!isSupportedUrl(QUrl(QStringLiteral("blob:https://example.com/uuid"))));
    QVERIFY(!isSupportedUrl(QUrl(QStringLiteral("data:text/plain,hi"))));
    QVERIFY(!isSupportedUrl(QUrl()));
    QVERIFY(!isSupportedUrl(QUrl(QStringLiteral(""))));
    QVERIFY(!isSupportedUrl(QUrl(QStringLiteral("not-a-url"))));
    QVERIFY(!isSupportedUrl(QUrl(QStringLiteral("file:///tmp/x.bin"))));
}

void DownloadPolicyTest::isInlineUrl_blobAndData()
{
    QVERIFY(isInlineUrl(QUrl(QStringLiteral("blob:https://example.com/uuid"))));
    QVERIFY(isInlineUrl(QUrl(QStringLiteral("data:text/plain,hi"))));
    QVERIFY(!isInlineUrl(QUrl(QStringLiteral("https://example.com/a.pdf"))));
    QVERIFY(!isInlineUrl(QUrl()));
}

void DownloadPolicyTest::suggestedFileName_fromContentDisposition()
{
    QCOMPARE(suggestedFileName(QUrl(QStringLiteral("https://example.com/x")),
                               QString(),
                               QStringLiteral("attachment; filename=\"report.pdf\""),
                               QStringLiteral("application/pdf")),
             QStringLiteral("report.pdf"));
}

void DownloadPolicyTest::suggestedFileName_fromUrlPath()
{
    QCOMPARE(suggestedFileName(QUrl(QStringLiteral("https://example.com/path/file.bin?token=1")),
                               QString(),
                               QString(),
                               QStringLiteral("application/octet-stream")),
             QStringLiteral("file.bin"));
}

void DownloadPolicyTest::suggestedFileName_mimeFallback()
{
    QCOMPARE(suggestedFileName(QUrl(QStringLiteral("https://example.com/")),
                               QString(),
                               QString(),
                               QStringLiteral("application/pdf")),
             QStringLiteral("download.pdf"));
}

void DownloadPolicyTest::suggestedFileName_filenameStarUtf8()
{
    QCOMPARE(suggestedFileName(QUrl(QStringLiteral("https://example.com/x")),
                               QString(),
                               QStringLiteral("attachment; filename*=UTF-8''caf%C3%A9.pdf"),
                               QString()),
             QStringLiteral("caf%C3%A9.pdf"));
}

void DownloadPolicyTest::suggestedFileName_quotedNames()
{
    QCOMPARE(suggestedFileName(QUrl(QStringLiteral("https://example.com/x")),
                               QString(),
                               QStringLiteral("attachment; filename='notes.txt'"),
                               QString()),
             QStringLiteral("notes.txt"));
}

void DownloadPolicyTest::suggestedFileName_pathSanitization()
{
    QCOMPARE(suggestedFileName(QUrl(QStringLiteral("https://example.com/x")),
                               QString(),
                               QStringLiteral("attachment; filename=\"../evil\""),
                               QString()),
             QStringLiteral(".._evil"));
}

void DownloadPolicyTest::suggestedFileName_platformSuggestionWins()
{
    QCOMPARE(suggestedFileName(QUrl(QStringLiteral("https://example.com/ignored.bin")),
                               QStringLiteral("from-wk.pdf"),
                               QStringLiteral("attachment; filename=\"disp.bin\""),
                               QStringLiteral("application/pdf")),
             QStringLiteral("from-wk.pdf"));
}

QTEST_MAIN(DownloadPolicyTest)
#include "tst_downloadpolicy.moc"
