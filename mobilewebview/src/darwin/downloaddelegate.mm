#include "downloaddelegate.h"
#include "MobileWebView/mobilewebviewbackend.h"
#include "MobileWebView/mobilewebviewdownload.h"

#if defined(Q_OS_MACOS) || defined(Q_OS_IOS)

#import <Foundation/Foundation.h>
#include <QMetaObject>
#include <QPointer>
#include <QUrl>

namespace {

QString qStringFromNS(NSString *value)
{
    return value ? QString::fromNSString(value) : QString();
}

bool isUnsupportedScheme(NSURL *url)
{
    NSString *scheme = url.scheme.lowercaseString;
    return [scheme isEqualToString:@"blob"] || [scheme isEqualToString:@"data"];
}

} // namespace

@interface DownloadDelegate ()
@property (nonatomic, strong) NSMutableDictionary<NSNumber *, WKDownload *> *downloadsById;
@property (nonatomic, strong) NSMapTable<WKDownload *, NSNumber *> *idsByDownload;
@property (nonatomic, strong) NSMutableDictionary<NSNumber *, id> *destinationHandlersById;
@property (nonatomic, strong) NSMutableDictionary<NSNumber *, NSString *> *pendingDestinationsById;
@end

@implementation DownloadDelegate

- (instancetype)init
{
    self = [super init];
    if (self) {
        _downloadsById = [[NSMutableDictionary alloc] init];
        _idsByDownload = [[NSMapTable alloc] initWithKeyOptions:NSPointerFunctionsWeakMemory
                                                   valueOptions:NSPointerFunctionsStrongMemory
                                                       capacity:0];
        _destinationHandlersById = [[NSMutableDictionary alloc] init];
        _pendingDestinationsById = [[NSMutableDictionary alloc] init];
    }
    return self;
}

- (void)dealloc
{
    [self cancelAll];
    [_downloadsById release];
    _downloadsById = nil;
    [_idsByDownload release];
    _idsByDownload = nil;
    [_destinationHandlersById release];
    _destinationHandlersById = nil;
    [_pendingDestinationsById release];
    _pendingDestinationsById = nil;
    [super dealloc];
}

- (void)forgetDownloadId:(uint64_t)downloadId
{
    NSNumber *key = @(downloadId);
    WKDownload *download = self.downloadsById[key];
    if (download) {
        @try {
            [download.progress removeObserver:self forKeyPath:@"completedUnitCount"];
        } @catch (NSException *) {
        }
        [self.idsByDownload removeObjectForKey:download];
    }
    [self.downloadsById removeObjectForKey:key];

    id handler = self.destinationHandlersById[key];
    if (handler)
        [handler release];
    [self.destinationHandlersById removeObjectForKey:key];
    [self.pendingDestinationsById removeObjectForKey:key];
}

- (void)observeProgressForDownload:(WKDownload *)download downloadId:(uint64_t)downloadId
{
    Q_UNUSED(downloadId);
    if (!download.progress)
        return;
    [download.progress addObserver:self
                        forKeyPath:@"completedUnitCount"
                           options:NSKeyValueObservingOptionNew
                           context:(void *)(uintptr_t)downloadId];
}

- (void)observeValueForKeyPath:(NSString *)keyPath
                      ofObject:(id)object
                        change:(NSDictionary *)change
                       context:(void *)context
{
    Q_UNUSED(change);
    if (![keyPath isEqualToString:@"completedUnitCount"] || ![object isKindOfClass:[NSProgress class]])
        return;

    const uint64_t downloadId = (uint64_t)(uintptr_t)context;
    NSProgress *progress = (NSProgress *)object;
    const qint64 received = static_cast<qint64>(progress.completedUnitCount);
    const qint64 total = progress.totalUnitCount > 0
        ? static_cast<qint64>(progress.totalUnitCount)
        : -1;

    MobileWebViewBackend *owner = self.owner;
    if (!owner)
        return;
    QPointer<MobileWebViewBackend> guard(owner);
    QMetaObject::invokeMethod(owner, [guard, downloadId, received, total]() {
        if (guard)
            guard->reportDownloadProgress(downloadId, received, total);
    }, Qt::QueuedConnection);
}

- (void)registerDownload:(WKDownload *)download downloadId:(uint64_t)downloadId
{
    NSNumber *key = @(downloadId);
    self.downloadsById[key] = download;
    [self.idsByDownload setObject:key forKey:download];
    [self observeProgressForDownload:download downloadId:downloadId];

    NSString *pending = self.pendingDestinationsById[key];
    id handler = self.destinationHandlersById[key];
    if (pending.length > 0 && handler) {
        void (^completion)(NSURL *) = handler;
        completion([NSURL fileURLWithPath:pending]);
        [handler release];
        [self.destinationHandlersById removeObjectForKey:key];
        [self.pendingDestinationsById removeObjectForKey:key];
    }
}

- (void)attachDownload:(WKDownload *)download
{
    if (!download || !self.owner)
        return;

    NSURL *url = download.originalRequest.URL;
    if (!url || isUnsupportedScheme(url)) {
        [download cancel:^(NSData *) {}];
        return;
    }

    download.delegate = self;
}

- (BOOL)provideDestinationPath:(NSString *)path forDownloadId:(uint64_t)downloadId
{
    if (path.length == 0)
        return NO;

    NSNumber *key = @(downloadId);
    if (!self.downloadsById[key] && !self.destinationHandlersById[key]) {
        // Unknown id — caller should start an explicit WKDownload (downloadUrl).
        return NO;
    }

    id handler = self.destinationHandlersById[key];
    if (handler) {
        void (^completion)(NSURL *) = handler;
        completion([NSURL fileURLWithPath:path]);
        [handler release];
        [self.destinationHandlersById removeObjectForKey:key];
        [self.pendingDestinationsById removeObjectForKey:key];
        return YES;
    }

    self.pendingDestinationsById[key] = [[path copy] autorelease];
    return YES;
}

- (void)startExplicitDownloadWithURL:(NSURL *)url
                          downloadId:(uint64_t)downloadId
                     destinationPath:(NSString *)path
                             webView:(WKWebView *)webView
{
    auto fail = ^(MobileWebViewBackend *owner) {
        if (!owner)
            return;
        QPointer<MobileWebViewBackend> guard(owner);
        QMetaObject::invokeMethod(owner, [guard, downloadId]() {
            if (guard)
                guard->reportDownloadFinished(downloadId, false,
                                              QStringLiteral("Failed to start download"));
        }, Qt::QueuedConnection);
    };

    if (!webView || !url || path.length == 0) {
        fail(self.owner);
        return;
    }

    NSNumber *key = @(downloadId);
    self.pendingDestinationsById[key] = [[path copy] autorelease];

    NSURLRequest *request = [NSURLRequest requestWithURL:url];
    __block DownloadDelegate *blockSelf = self;
    [webView startDownloadUsingRequest:request completionHandler:^(WKDownload *download) {
        if (!download) {
            fail(blockSelf.owner);
            return;
        }
        download.delegate = blockSelf;
        [blockSelf registerDownload:download downloadId:downloadId];
    }];
}

- (void)cancelDownloadId:(uint64_t)downloadId
{
    NSNumber *key = @(downloadId);
    id handler = self.destinationHandlersById[key];
    if (handler) {
        void (^completion)(NSURL *) = handler;
        completion(nil);
        [handler release];
        [self.destinationHandlersById removeObjectForKey:key];
    }
    WKDownload *download = self.downloadsById[key];
    if (download)
        [download cancel:^(NSData *) {}];
    [self forgetDownloadId:downloadId];
}

- (void)cancelAll
{
    NSArray<NSNumber *> *keys = [self.downloadsById.allKeys copy];
    for (NSNumber *key in keys)
        [self cancelDownloadId:key.unsignedLongLongValue];
    [keys release];

    for (NSNumber *key in self.destinationHandlersById.allKeys) {
        void (^completion)(NSURL *) = self.destinationHandlersById[key];
        if (completion) {
            completion(nil);
            [completion release];
        }
    }
    [self.destinationHandlersById removeAllObjects];
    [self.pendingDestinationsById removeAllObjects];
}

- (void)download:(WKDownload *)download
    decideDestinationUsingResponse:(NSURLResponse *)response
                 suggestedFilename:(NSString *)suggestedFilename
                 completionHandler:(void (^)(NSURL * _Nullable))completionHandler
API_AVAILABLE(macos(11.3), ios(14.5))
{
    MobileWebViewBackend *owner = self.owner;
    if (!owner) {
        completionHandler(nil);
        return;
    }

    NSURL *url = response.URL ?: download.originalRequest.URL;
    if (!url || isUnsupportedScheme(url)) {
        completionHandler(nil);
        return;
    }

    NSNumber *existingId = [self.idsByDownload objectForKey:download];
    if (existingId) {
        const uint64_t downloadId = existingId.unsignedLongLongValue;
        NSNumber *key = @(downloadId);
        NSString *pending = self.pendingDestinationsById[key];
        if (pending.length > 0) {
            completionHandler([NSURL fileURLWithPath:pending]);
            [self.pendingDestinationsById removeObjectForKey:key];
            return;
        }
        self.destinationHandlersById[key] = [completionHandler copy];
        return;
    }

    const QUrl qUrl = QUrl::fromNSURL(url);
    QString suggested = qStringFromNS(suggestedFilename);
    if (suggested.isEmpty())
        suggested = qStringFromNS(url.lastPathComponent);
    QString mime = qStringFromNS(response.MIMEType);
    qint64 total = -1;
    if ([response isKindOfClass:[NSHTTPURLResponse class]]) {
        NSHTTPURLResponse *http = (NSHTTPURLResponse *)response;
        id lengthValue = http.allHeaderFields[@"Content-Length"];
        if ([lengthValue respondsToSelector:@selector(longLongValue)])
            total = [lengthValue longLongValue];
    } else if (response.expectedContentLength > 0) {
        total = response.expectedContentLength;
    }

    void (^handlerCopy)(NSURL *) = [completionHandler copy];
    QPointer<MobileWebViewBackend> guard(owner);
    DownloadDelegate *delegate = self;
    QMetaObject::invokeMethod(owner, [guard, delegate, download, qUrl, suggested, mime, total, handlerCopy]() {
        if (!guard) {
            handlerCopy(nil);
            [handlerCopy release];
            return;
        }

        MobileWebViewDownload *item = guard->createDownload(qUrl, suggested, mime, total);
        if (!item) {
            handlerCopy(nil);
            [handlerCopy release];
            return;
        }

        const uint64_t downloadId = item->downloadId();
        [delegate registerDownload:download downloadId:downloadId];
        delegate.destinationHandlersById[@(downloadId)] = handlerCopy;

        // Host accept() during this emit can provideDestinationPath (known id).
        guard->emitDownloadRequested(item);

        NSString *pending = delegate.pendingDestinationsById[@(downloadId)];
        if (pending.length > 0) {
            id handler = delegate.destinationHandlersById[@(downloadId)];
            if (handler) {
                void (^completion)(NSURL *) = handler;
                completion([NSURL fileURLWithPath:pending]);
                [handler release];
                [delegate.destinationHandlersById removeObjectForKey:@(downloadId)];
            }
            [delegate.pendingDestinationsById removeObjectForKey:@(downloadId)];
        }
    }, Qt::QueuedConnection);
}

- (void)downloadDidFinish:(WKDownload *)download
API_AVAILABLE(macos(11.3), ios(14.5))
{
    NSNumber *idNumber = [self.idsByDownload objectForKey:download];
    if (!idNumber)
        return;

    const uint64_t downloadId = idNumber.unsignedLongLongValue;
    MobileWebViewBackend *owner = self.owner;
    [self forgetDownloadId:downloadId];
    if (!owner)
        return;

    QPointer<MobileWebViewBackend> guard(owner);
    QMetaObject::invokeMethod(owner, [guard, downloadId]() {
        if (guard)
            guard->reportDownloadFinished(downloadId, true);
    }, Qt::QueuedConnection);
}

- (void)download:(WKDownload *)download
    didFailWithError:(NSError *)error
          resumeData:(NSData *)resumeData
API_AVAILABLE(macos(11.3), ios(14.5))
{
    Q_UNUSED(resumeData);
    NSNumber *idNumber = [self.idsByDownload objectForKey:download];
    if (!idNumber)
        return;

    const uint64_t downloadId = idNumber.unsignedLongLongValue;
    const QString message = qStringFromNS(error.localizedDescription);
    MobileWebViewBackend *owner = self.owner;
    [self forgetDownloadId:downloadId];
    if (!owner)
        return;

    QPointer<MobileWebViewBackend> guard(owner);
    QMetaObject::invokeMethod(owner, [guard, downloadId, message]() {
        if (guard)
            guard->reportDownloadFinished(downloadId, false, message);
    }, Qt::QueuedConnection);
}

@end

#endif // Q_OS_MACOS || Q_OS_IOS
