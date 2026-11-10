#include "openedfileintake_p.h"

#import <Foundation/Foundation.h>
#include <TargetConditionals.h>
#if TARGET_OS_IPHONE
#import <UIKit/UIKit.h>
#include <objc/runtime.h>
#endif

#include <QElapsedTimer>
#include <QUrl>

// Manual retain/release, like the rest of the Darwin sources.

namespace {

NSURL *urlFromString(const QString &url)
{
    return [NSURL URLWithString:url.toNSString()];
}

QString errorText(NSError *error)
{
    if (!error)
        return QString();
    return QStringLiteral("%1 (%2 %3)")
        .arg(QString::fromNSString(error.localizedDescription),
             QString::fromNSString(error.domain))
        .arg(error.code);
}

QString hex(NSData *data)
{
    return QString::fromLatin1(QByteArray::fromNSData(data).toHex());
}

// "/private/var/…" and "/var/…" name the same file.
NSString *comparablePath(NSString *path)
{
    return [path hasPrefix:@"/private/"] ? [path substringFromIndex:8] : path;
}

// URLs as the scene delegate received them. A security scope travels with the
// NSURL object, so a URL rebuilt from Qt's string may not carry it.
NSMutableArray<NSDictionary *> *nativeRecords()
{
    static NSMutableArray<NSDictionary *> *records = [[NSMutableArray alloc] init];
    return records;
}

// URLs whose security-scoped access was started; held for the app's lifetime
// so a later re-read measures how long the grant lasts.
NSMutableArray<NSURL *> *accessedUrls()
{
    static NSMutableArray<NSURL *> *urls = [[NSMutableArray alloc] init];
    return urls;
}

std::function<void(const QString &)> &nativeReceiver()
{
    static std::function<void(const QString &)> receiver;
    return receiver;
}

NSDictionary *nativeRecordFor(NSURL *url)
{
    NSString *path = comparablePath(url.path);
    for (NSDictionary *record in nativeRecords()) {
        NSURL *nativeUrl = record[@"url"];
        if ([comparablePath(nativeUrl.path) isEqualToString:path])
            return record;
    }
    return nil;
}

#if TARGET_OS_IPHONE

void recordContexts(NSSet<UIOpenURLContext *> *contexts, NSString *via)
{
    for (UIOpenURLContext *context in contexts) {
        NSDictionary *record = @{
            @"url": context.URL,
            @"openInPlace": @(context.options.openInPlace),
            @"sourceApplication": context.options.sourceApplication ?: @"",
            @"via": via,
            @"at": [NSDate date],
        };
        [nativeRecords() addObject:record];
        if (nativeReceiver())
            nativeReceiver()(QString::fromNSString(context.URL.absoluteString));
    }
}

IMP originalOpenUrlContexts = nullptr;
IMP originalWillConnect = nullptr;

void hookedOpenUrlContexts(id self, SEL cmd, UIScene *scene, NSSet<UIOpenURLContext *> *contexts)
{
    recordContexts(contexts, @"scene:openURLContexts:");
    reinterpret_cast<void (*)(id, SEL, UIScene *, NSSet *)>(originalOpenUrlContexts)(
        self, cmd, scene, contexts);
}

void hookedWillConnect(id self, SEL cmd, UIScene *scene, UISceneSession *session,
                       UISceneConnectionOptions *options)
{
    recordContexts(options.URLContexts, @"scene:willConnectToSession:options:");
    reinterpret_cast<void (*)(id, SEL, UIScene *, UISceneSession *, UISceneConnectionOptions *)>(
        originalWillConnect)(self, cmd, scene, session, options);
}

// Wraps Qt's scene delegate so the probe sees each URL, its open-in-place flag
// and its source app exactly as UIKit delivered them.
__attribute__((constructor)) void installSceneHooks()
{
    Class delegateClass = objc_getClass("QIOSWindowSceneDelegate");
    if (!delegateClass)
        return;
    Method openUrl = class_getInstanceMethod(delegateClass, @selector(scene:openURLContexts:));
    if (openUrl)
        originalOpenUrlContexts =
            method_setImplementation(openUrl, reinterpret_cast<IMP>(hookedOpenUrlContexts));
    Method willConnect = class_getInstanceMethod(
        delegateClass, @selector(scene:willConnectToSession:options:));
    if (willConnect)
        originalWillConnect =
            method_setImplementation(willConnect, reinterpret_cast<IMP>(hookedWillConnect));
}

#endif // TARGET_OS_IPHONE

} // namespace

namespace OpenedFilePlatform {

QVariantMap describe(const QString &url)
{
    QVariantMap out;
    NSURL *nsUrl = urlFromString(url);
    NSString *path = nsUrl.path ?: @"";
    NSString *home = comparablePath(NSHomeDirectory());
    out.insert(QStringLiteral("path"), QString::fromNSString(path));
    out.insert(QStringLiteral("inOwnContainer"), bool([comparablePath(path) hasPrefix:home]));
    out.insert(QStringLiteral("inInbox"),
               bool([path containsString:@"/Inbox/"] || [path containsString:@"-Inbox/"]));
    out.insert(QStringLiteral("exists"),
               bool([[NSFileManager defaultManager] fileExistsAtPath:path]));

    NSError *error = nil;
    NSNumber *size = nil;
    if ([nsUrl getResourceValue:&size forKey:NSURLFileSizeKey error:&error])
        out.insert(QStringLiteral("size"), size ? size.longLongValue : -1);
    else
        out.insert(QStringLiteral("sizeError"), errorText(error));
    NSNumber *ubiquitous = nil;
    if ([nsUrl getResourceValue:&ubiquitous forKey:NSURLIsUbiquitousItemKey error:nil])
        out.insert(QStringLiteral("ubiquitous"), bool(ubiquitous.boolValue));
    NSString *downloading = nil;
    if ([nsUrl getResourceValue:&downloading
                         forKey:NSURLUbiquitousItemDownloadingStatusKey
                          error:nil] && downloading)
        out.insert(QStringLiteral("ubiquitousStatus"), QString::fromNSString(downloading));

    if (NSDictionary *record = nativeRecordFor(nsUrl)) {
        NSURL *nativeUrl = record[@"url"];
        out.insert(QStringLiteral("nativeVia"), QString::fromNSString(record[@"via"]));
        out.insert(QStringLiteral("openInPlace"), bool([record[@"openInPlace"] boolValue]));
        out.insert(QStringLiteral("sourceApplication"),
                   QString::fromNSString(record[@"sourceApplication"]));
        out.insert(QStringLiteral("nativeUrlMatchesString"),
                   bool([nativeUrl.absoluteString isEqualToString:nsUrl.absoluteString]));
    }
    out.insert(QStringLiteral("ok"), true);
    return out;
}

QVariantMap read(const QString &url)
{
    QVariantMap out;
    QElapsedTimer timer;
    timer.start();
    NSError *error = nil;
    NSFileHandle *handle = [NSFileHandle fileHandleForReadingFromURL:urlFromString(url)
                                                               error:&error];
    if (!handle) {
        out.insert(QStringLiteral("ok"), false);
        out.insert(QStringLiteral("error"), errorText(error));
    } else {
        NSData *head = [handle readDataUpToLength:8 error:&error];
        out.insert(QStringLiteral("head"), hex(head));
        unsigned long long end = 0;
        bool seekable = [handle seekToEndReturningOffset:&end error:&error]
            && [handle seekToOffset:end / 2 error:&error]
            && [handle readDataUpToLength:8 error:&error] != nil;
        out.insert(QStringLiteral("size"), qint64(end));
        out.insert(QStringLiteral("seekable"), seekable);
        if (!seekable)
            out.insert(QStringLiteral("seekError"), errorText(error));
        [handle closeAndReturnError:nil];
        out.insert(QStringLiteral("ok"), head.length > 0);
    }
    out.insert(QStringLiteral("ms"), timer.elapsed());
    return out;
}

QVariantMap startAccess(const QString &url, bool nativeUrl)
{
    QVariantMap out;
    NSURL *target = urlFromString(url);
    if (nativeUrl) {
        NSDictionary *record = nativeRecordFor(target);
        if (!record) {
            out.insert(QStringLiteral("available"), false);
            out.insert(QStringLiteral("ok"), false);
            return out;
        }
        target = record[@"url"];
    }
    const bool started = [target startAccessingSecurityScopedResource];
    if (started)
        [accessedUrls() addObject:target];
    out.insert(QStringLiteral("available"), true);
    out.insert(QStringLiteral("ok"), started);
    return out;
}

QVariantMap copyToCache(const QString &url)
{
    QVariantMap out;
    QElapsedTimer timer;
    timer.start();
    NSFileManager *files = [NSFileManager defaultManager];
    NSURL *source = urlFromString(url);
    NSString *dirPath = [NSTemporaryDirectory()
        stringByAppendingPathComponent:[@"opened" stringByAppendingPathComponent:
                                                      [NSUUID UUID].UUIDString]];
    NSURL *dir = [NSURL fileURLWithPath:dirPath isDirectory:YES];
    NSURL *destination = [dir URLByAppendingPathComponent:source.lastPathComponent];
    NSError *error = nil;
    __block NSError *copyError = nil;
    __block BOOL copied = NO;
    if ([files createDirectoryAtURL:dir withIntermediateDirectories:YES attributes:nil
                              error:&error]) {
        // Coordinated, so an iCloud file is downloaded before the copy.
        NSFileCoordinator *coordinator = [[NSFileCoordinator alloc] initWithFilePresenter:nil];
        [coordinator coordinateReadingItemAtURL:source
                                        options:NSFileCoordinatorReadingWithoutChanges
                                          error:&error
                                     byAccessor:^(NSURL *readUrl) {
                                         copied = [files copyItemAtURL:readUrl
                                                                 toURL:destination
                                                                 error:&copyError];
                                         [copyError retain];
                                     }];
        [coordinator release];
    }
    out.insert(QStringLiteral("ok"), bool(copied));
    if (copied) {
        out.insert(QStringLiteral("url"), QString::fromNSString(destination.absoluteString));
        NSDictionary *attributes = [files attributesOfItemAtPath:destination.path error:nil];
        out.insert(QStringLiteral("bytes"), qint64(attributes.fileSize));
    } else {
        out.insert(QStringLiteral("error"), errorText(copyError ?: error));
    }
    [copyError release];
    out.insert(QStringLiteral("ms"), timer.elapsed());
    return out;
}

QVariantMap pdfPages(const QString &)
{
    // WKWebView renders PDF itself; PdfRenderer is the Android question.
    return {{QStringLiteral("supported"), false}};
}

QVariantList inboxFiles()
{
    QVariantList out;
    NSFileManager *files = [NSFileManager defaultManager];
    NSString *documents =
        NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES).firstObject;
    NSMutableArray<NSString *> *dirs = [NSMutableArray array];
    if (documents)
        [dirs addObject:[documents stringByAppendingPathComponent:@"Inbox"]];
    for (NSString *name in [files contentsOfDirectoryAtPath:NSTemporaryDirectory() error:nil]) {
        if ([name hasSuffix:@"-Inbox"])
            [dirs addObject:[NSTemporaryDirectory() stringByAppendingPathComponent:name]];
    }
    for (NSString *dir in dirs) {
        for (NSString *name in [files contentsOfDirectoryAtPath:dir error:nil]) {
            NSString *path = [dir stringByAppendingPathComponent:name];
            NSDictionary *attributes = [files attributesOfItemAtPath:path error:nil];
            out.append(QVariantMap{
                {QStringLiteral("path"), QString::fromNSString(path)},
                {QStringLiteral("bytes"), qint64(attributes.fileSize)},
                {QStringLiteral("modified"),
                 QString::fromNSString(attributes.fileModificationDate.description)},
            });
        }
    }
    return out;
}

QVariantList nativeContexts()
{
    QVariantList out;
    for (NSDictionary *record in nativeRecords()) {
        NSURL *url = record[@"url"];
        out.append(QVariantMap{
            {QStringLiteral("url"), QString::fromNSString(url.absoluteString)},
            {QStringLiteral("via"), QString::fromNSString(record[@"via"])},
            {QStringLiteral("openInPlace"), bool([record[@"openInPlace"] boolValue])},
            {QStringLiteral("sourceApplication"),
             QString::fromNSString(record[@"sourceApplication"])},
            {QStringLiteral("at"), QString::fromNSString([record[@"at"] description])},
        });
    }
    return out;
}

QString drainIntents()
{
    return QStringLiteral("[]");
}

void setNativeReceiver(std::function<void(const QString &)> receiver)
{
    nativeReceiver() = std::move(receiver);
    if (!nativeReceiver())
        return;
    for (NSDictionary *record in nativeRecords())
        nativeReceiver()(QString::fromNSString([record[@"url"] absoluteString]));
}

} // namespace OpenedFilePlatform
