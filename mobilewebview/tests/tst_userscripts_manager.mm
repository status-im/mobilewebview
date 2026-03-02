#include <QtTest/QtTest>

#include <QSignalSpy>
#include <QVariant>

#include "../src/darwin/userscripts.h"

#if defined(Q_OS_MACOS) || defined(Q_OS_IOS)

class UserScriptsManagerTest : public QObject
{
    Q_OBJECT

private slots:
    void installBridgeAndEvaluateAndPost();
    void nullWebViewBranches();
};

void UserScriptsManagerTest::installBridgeAndEvaluateAndPost()
{
    WKWebViewConfiguration *cfg = [[WKWebViewConfiguration alloc] init];
    WKWebView *webView = [[WKWebView alloc] initWithFrame:CGRectZero configuration:cfg];

    UserScriptsManager manager(webView, nullptr);

    // Before install: should hit "bridge not installed" branch and return.
    manager.postMessageToJavaScript(QStringLiteral("{\"pre\":\"install\"}"));

    const QList<UserScriptInfo> userScripts = {
        UserScriptInfo(QStringLiteral(":/missing/script.js"), false),
        UserScriptInfo(QStringLiteral(":/CustomWebView/js/bootstrap_page.js"), true),
    };

    const bool ok = manager.installMessageBridge(
        QStringLiteral("qt"),
        QStringList{QStringLiteral("*")},
        QStringLiteral("invoke"),
        QStringLiteral(":/missing/qwebchannel.js"),
        userScripts);
    QVERIFY(ok);
    QVERIFY(manager.isBridgeInstalled());
    QCOMPARE(manager.bridgeNamespace(), QStringLiteral("qt"));

    // Cover updateAllowedOrigins/removeAllUserScripts helpers.
    manager.updateAllowedOrigins({QStringLiteral("https://example.com")});
    manager.removeAllUserScripts();

    QEventLoop loop;
    QVariant evalResult;
    QString evalError;
    manager.evaluateJavaScript(QStringLiteral("1 + 1"), [&](id result, NSError *error) {
        if (error) {
            evalError = QString::fromNSString(error.localizedDescription);
        } else if (result) {
            if ([result isKindOfClass:[NSNumber class]]) {
                evalResult = [(NSNumber *)result intValue];
            } else if ([result isKindOfClass:[NSString class]]) {
                evalResult = QString::fromNSString((NSString *)result);
            }
        }
        loop.quit();
    });

    QTimer timeout;
    timeout.setSingleShot(true);
    QObject::connect(&timeout, &QTimer::timeout, &loop, &QEventLoop::quit);
    timeout.start(5000);
    loop.exec();

    QVERIFY2(evalError.isEmpty(), qPrintable(evalError));
    QCOMPARE(evalResult.toInt(), 2);

    manager.postMessageToJavaScript(QStringLiteral("{\"kind\":\"fromQt\"}"));

    [webView release];
    [cfg release];
}

void UserScriptsManagerTest::nullWebViewBranches()
{
    UserScriptsManager manager(nullptr, nullptr);

    const bool installed = manager.installMessageBridge(
        QStringLiteral("qt"),
        QStringList{QStringLiteral("*")},
        QStringLiteral("invoke"));
    QVERIFY(!installed);

    manager.removeAllUserScripts();
    manager.updateAllowedOrigins({QStringLiteral("https://example.com")});

    bool completionCalled = false;
    manager.evaluateJavaScript(QStringLiteral("42"), [&](id result, NSError *error) {
        Q_UNUSED(result);
        completionCalled = true;
        QVERIFY(error != nil);
    });
    QVERIFY(completionCalled);
}

QTEST_MAIN(UserScriptsManagerTest)
#include "tst_userscripts_manager.moc"

#endif
