#include "snapshotcapture.h"

#include "MobileWebView/mobilewebviewbackend.h"
#include "../common/mobilewebviewbackend_p.h"

#import <CoreGraphics/CoreGraphics.h>

#ifdef Q_OS_IOS
#import <UIKit/UIKit.h>
#else
#import <AppKit/AppKit.h>
#endif

#include <QImage>
#include <QMetaObject>
#include <QPointer>

#if defined(Q_OS_MACOS) || defined(Q_OS_IOS)

namespace {

QImage qImageFromCGImage(CGImageRef cg)
{
    if (!cg) {
        return QImage();
    }
    const size_t w = CGImageGetWidth(cg);
    const size_t h = CGImageGetHeight(cg);
    if (w == 0 || h == 0) {
        return QImage();
    }

    QImage img(static_cast<int>(w), static_cast<int>(h), QImage::Format_ARGB32_Premultiplied);
    img.fill(Qt::transparent);

    CGColorSpaceRef cs = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
    if (!cs) {
        return QImage();
    }

    CGContextRef ctx = CGBitmapContextCreate(
        img.bits(), w, h, 8, img.bytesPerLine(), cs,
        static_cast<CGBitmapInfo>(kCGImageAlphaPremultipliedFirst | kCGBitmapByteOrder32Little));
    CGColorSpaceRelease(cs);
    if (!ctx) {
        return QImage();
    }

    CGContextSetBlendMode(ctx, kCGBlendModeCopy);
    CGContextDrawImage(ctx, CGRectMake(0, 0, static_cast<CGFloat>(w), static_cast<CGFloat>(h)), cg);
    CGContextRelease(ctx);
    return img;
}

} // namespace

namespace SnapshotCapture {

void capture(WKWebView *webView, MobileWebViewBackendPrivate *priv, quint64 requestId)
{
    MobileWebViewBackend *backend = priv->q_ptr;

    if (!webView) {
        QPointer<MobileWebViewBackend> guard(backend);
        QMetaObject::invokeMethod(backend, [guard, priv, requestId]() {
            if (!guard) {
                return;
            }
            priv->notifySnapshotReady(requestId, QImage());
        }, Qt::QueuedConnection);
        return;
    }

    QPointer<MobileWebViewBackend> guard(backend);

    WKSnapshotConfiguration *cfg = [[WKSnapshotConfiguration alloc] init];
#if defined(Q_OS_IOS)
    if (@available(iOS 11.0, *)) {
        cfg.afterScreenUpdates = YES;
    }
#else
    if (@available(macOS 10.13, *)) {
        cfg.afterScreenUpdates = YES;
    }
#endif

    [webView takeSnapshotWithConfiguration:cfg
                         completionHandler:^(id snapshotImage, NSError *error) {
        QImage qimg;
        if (!error && snapshotImage) {
#if defined(Q_OS_IOS)
            if ([snapshotImage isKindOfClass:[UIImage class]]) {
                UIImage *ui = static_cast<UIImage *>(snapshotImage);
                CGImageRef cg = ui.CGImage;
                if (cg) {
                    qimg = qImageFromCGImage(cg);
                }
            }
#else
            if ([snapshotImage isKindOfClass:[NSImage class]]) {
                NSImage *ni = static_cast<NSImage *>(snapshotImage);
                CGImageRef cg = [ni CGImageForProposedRect:NULL context:nil hints:nil];
                if (cg) {
                    qimg = qImageFromCGImage(cg);
                }
            }
#endif
        }

        MobileWebViewBackend *backendObj = guard.data();
        if (!backendObj) {
            return;
        }

        QMetaObject::invokeMethod(backendObj, [priv, guard, requestId, qimg]() {
            if (!guard) {
                return;
            }
            priv->notifySnapshotReady(requestId, qimg);
        }, Qt::QueuedConnection);
    }];
    [cfg release];
}

} // namespace SnapshotCapture

#endif // Q_OS_MACOS || Q_OS_IOS
