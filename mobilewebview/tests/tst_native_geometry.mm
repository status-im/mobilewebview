#include <QtTest/QtTest>

#include <QGuiApplication>
#include <QQuickWindow>

#include "MobileWebView/mobilewebviewbackend.h"

#import <AppKit/AppKit.h>
#import <WebKit/WebKit.h>

static WKWebView *findWebView(NSView *view)
{
    for (NSView *child in view.subviews) {
        if ([child isKindOfClass:[WKWebView class]])
            return static_cast<WKWebView *>(child);
        if (WKWebView *nested = findWebView(child))
            return nested;
    }
    return nil;
}

class NativeGeometryTest : public QObject
{
    Q_OBJECT

private slots:
    void nativeFrameFollowsQtScaleFactor();
};

void NativeGeometryTest::nativeFrameFollowsQtScaleFactor()
{
    QQuickWindow window;
    window.setGeometry(0, 0, 480, 320);

    MobileWebViewBackend backend;
    backend.setParentItem(window.contentItem());
    backend.setPosition(QPointF(10, 20));
    backend.setWidth(200);
    backend.setHeight(100);
    backend.setVisible(true);
    window.show();
    QVERIFY(QTest::qWaitForWindowExposed(&window));

    NSView *hostView = reinterpret_cast<NSView *>(window.winId());
    QVERIFY(hostView);

    WKWebView *webView = nil;
    QTRY_VERIFY((webView = findWebView(hostView)) != nil);

    // QT_SCALE_FACTOR=2: one Qt logical pixel spans two native points.
    QTRY_COMPARE(webView.frame.size.width, CGFloat(400));
    QCOMPARE(webView.frame.size.height, CGFloat(200));
    QCOMPARE(webView.frame.origin.x, CGFloat(20));
    const CGFloat expectedY = hostView.isFlipped
        ? CGFloat(40)
        : hostView.bounds.size.height - CGFloat(40) - CGFloat(200);
    QCOMPARE(webView.frame.origin.y, expectedY);
}

int main(int argc, char *argv[])
{
    qputenv("QT_SCALE_FACTOR", "2");
    QGuiApplication app(argc, argv);
    NativeGeometryTest test;
    return QTest::qExec(&test, argc, argv);
}

#include "tst_native_geometry.moc"
