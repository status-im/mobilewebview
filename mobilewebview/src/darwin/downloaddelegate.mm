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

/// Per-download state: active WKDownload, destination handoff, pause resumeData.
@interface MWVDownloadEntry : NSObject
@property (nonatomic, assign) WKDownload *download;
@property (nonatomic, copy) void (^destinationHandler)(NSURL *_Nullable);
@property (nonatomic, copy) NSString *pendingPath;
@property (nonatomic, copy) NSData *resumeData;
@property (nonatomic, assign) BOOL pausing;
/// Host called resume() before WK cancel finished providing resumeData.
@property (nonatomic, assign) BOOL resumeWhenReady;
@property (nonatomic, assign) WKWebView *pendingResumeWebView;
@property (nonatomic, assign) BOOL observingProgress;
@property (nonatomic, assign) DownloadDelegate *observer;

- (void)detachActive; // drop WKDownload + handler + pending; keep resumeData
- (void)destroy;      // detachActive + wipe resumeData
@end

@implementation MWVDownloadEntry

- (void)detachActive
{
    if (self.download && self.observingProgress && self.observer) {
        @try {
            [self.download.progress removeObserver:self.observer
                                        forKeyPath:@"completedUnitCount"];
        } @catch (NSException *) {
        }
        self.observingProgress = NO;
    }
    self.download = nil;
    if (self.destinationHandler) {
        self.destinationHandler = nil;
    }
    self.pendingPath = nil;
    // Keep pausing / resumeWhenReady / resumeData — pause finalizer clears them
    // after WK cancel + didFailWithError have had a chance to deliver resumeData.
}

- (void)destroy
{
    [self detachActive];
    self.resumeData = nil;
    self.resumeWhenReady = NO;
    self.pendingResumeWebView = nil;
}

- (void)dealloc
{
    [self destroy];
    [super dealloc];
}

@end

@interface DownloadDelegate ()
@property (nonatomic, strong) NSMutableDictionary<NSNumber *, MWVDownloadEntry *> *entriesById;
@property (nonatomic, strong) NSMapTable<WKDownload *, NSNumber *> *idsByDownload;
@end

@implementation DownloadDelegate

- (instancetype)init
{
    self = [super init];
    if (self) {
        _entriesById = [[NSMutableDictionary alloc] init];
        _idsByDownload = [[NSMapTable alloc] initWithKeyOptions:NSPointerFunctionsWeakMemory
                                                   valueOptions:NSPointerFunctionsStrongMemory
                                                       capacity:0];
    }
    return self;
}

- (void)dealloc
{
    [self cancelAll];
    [_entriesById release];
    _entriesById = nil;
    [_idsByDownload release];
    _idsByDownload = nil;
    [super dealloc];
}

- (MWVDownloadEntry *)entryForId:(uint64_t)downloadId create:(BOOL)create
{
    NSNumber *key = @(downloadId);
    MWVDownloadEntry *entry = self.entriesById[key];
    if (!entry && create) {
        entry = [[MWVDownloadEntry alloc] init];
        entry.observer = self;
        self.entriesById[key] = entry;
        [entry release];
        entry = self.entriesById[key];
    }
    return entry;
}

- (void)destroyEntryId:(uint64_t)downloadId
{
    NSNumber *key = @(downloadId);
    MWVDownloadEntry *entry = self.entriesById[key];
    if (!entry)
        return;
    if (entry.download)
        [self.idsByDownload removeObjectForKey:entry.download];
    [entry destroy];
    [self.entriesById removeObjectForKey:key];
}

- (void)detachActiveForId:(uint64_t)downloadId
{
    NSNumber *key = @(downloadId);
    MWVDownloadEntry *entry = self.entriesById[key];
    if (!entry)
        return;
    entry.download = nil;
    // NSMapTable weak keys: never call removeObjectForKey: with a possibly
    // dangling WKDownload*. Remove by matching downloadId among live keys.
    NSArray *mapKeys = self.idsByDownload.keyEnumerator.allObjects;
    for (WKDownload *dl in mapKeys) {
        NSNumber *mapped = [self.idsByDownload objectForKey:dl];
        if (mapped && mapped.unsignedLongLongValue == downloadId)
            [self.idsByDownload removeObjectForKey:dl];
    }
    [entry detachActive];
}

- (void)forgetDownloadId:(uint64_t)downloadId
{
    [self destroyEntryId:downloadId];
}

- (void)observeProgressForDownload:(WKDownload *)download downloadId:(uint64_t)downloadId
{
    if (!download.progress)
        return;
    MWVDownloadEntry *entry = [self entryForId:downloadId create:NO];
    if (!entry)
        return;
    [download.progress addObserver:self
                        forKeyPath:@"completedUnitCount"
                           options:NSKeyValueObservingOptionNew
                           context:(void *)(uintptr_t)downloadId];
    entry.observingProgress = YES;
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
    MWVDownloadEntry *entry = [self entryForId:downloadId create:YES];
    entry.download = download;
    [self.idsByDownload setObject:@(downloadId) forKey:download];
    [self observeProgressForDownload:download downloadId:downloadId];

    if (entry.pendingPath.length > 0 && entry.destinationHandler) {
        void (^completion)(NSURL *) = [entry.destinationHandler retain];
        entry.destinationHandler = nil;
        NSString *pending = [[entry.pendingPath retain] autorelease];
        entry.pendingPath = nil;
        completion([NSURL fileURLWithPath:pending]);
        [completion release];
    }
}

- (void)attachDownload:(WKDownload *)download
{
    if (!download || !self.owner)
        return;

    NSURL *url = download.originalRequest.URL;
    // Reject blob:/data: WKDownload attachments — Inline Download script owns those.
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

    MWVDownloadEntry *entry = [self entryForId:downloadId create:NO];
    if (!entry || (!entry.download && !entry.destinationHandler)) {
        // Unknown id — caller should start an explicit WKDownload (downloadUrl).
        return NO;
    }

    if (entry.destinationHandler) {
        void (^completion)(NSURL *) = [entry.destinationHandler retain];
        entry.destinationHandler = nil;
        entry.pendingPath = nil;
        completion([NSURL fileURLWithPath:path]);
        [completion release];
        return YES;
    }

    entry.pendingPath = path;
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

    MWVDownloadEntry *entry = [self entryForId:downloadId create:YES];
    entry.pendingPath = path;

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
    MWVDownloadEntry *entry = [self entryForId:downloadId create:NO];
    if (!entry) {
        [self destroyEntryId:downloadId];
        return;
    }

    if (entry.destinationHandler) {
        void (^completion)(NSURL *) = [entry.destinationHandler retain];
        entry.destinationHandler = nil;
        completion(nil);
        [completion release];
    }
    WKDownload *download = entry.download;
    if (download)
        [download cancel:^(NSData *) {}];
    [self destroyEntryId:downloadId];
}

- (void)pauseDownloadId:(uint64_t)downloadId
{
    NSNumber *key = @(downloadId);
    MWVDownloadEntry *entry = self.entriesById[key];
    if (!entry || !entry.download) {
        // Still Requested (destination handler pending) — release like cancel.
        if (entry && entry.destinationHandler) {
            void (^completion)(NSURL *) = [entry.destinationHandler retain];
            entry.destinationHandler = nil;
            completion(nil);
            [completion release];
        }
        return;
    }

    entry.pausing = YES;
    WKDownload *download = entry.download;
    __block DownloadDelegate *blockSelf = self;
    [download cancel:^(NSData *resumeData) {
        MWVDownloadEntry *paused = blockSelf.entriesById[key];
        if (!paused)
            return;
        if (resumeData)
            paused.resumeData = resumeData;

        // Do NOT detach yet — didFailWithError:resumeData: looks up the WKDownload
        // in idsByDownload and is where resumeData usually arrives.
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.5 * NSEC_PER_SEC)),
                       dispatch_get_main_queue(), ^{
            MWVDownloadEntry *entry = blockSelf.entriesById[key];
            if (!entry || !entry.pausing)
                return;
            const BOOL wantResume = entry.resumeWhenReady;
            WKWebView *resumeWebView = entry.pendingResumeWebView;
            entry.resumeWhenReady = NO;
            entry.pendingResumeWebView = nil;
            entry.pausing = NO;
            if (entry.download)
                [blockSelf detachActiveForId:downloadId];
            if (wantResume) {
                [blockSelf resumeDownloadId:downloadId webView:resumeWebView];
                return;
            }
            if (!entry.resumeData) {
                MobileWebViewBackend *owner = blockSelf.owner;
                if (!owner)
                    return;
                QPointer<MobileWebViewBackend> guard(owner);
                QMetaObject::invokeMethod(owner, [guard, downloadId]() {
                    if (guard)
                        guard->reportDownloadFinished(
                            downloadId, false,
                            QStringLiteral("Pause not supported (no resume data)"));
                }, Qt::QueuedConnection);
            }
        });
    }];
}

- (void)resumeDownloadId:(uint64_t)downloadId webView:(WKWebView *)webView
{
    NSNumber *key = @(downloadId);
    MWVDownloadEntry *entry = self.entriesById[key];
    auto fail = ^(MobileWebViewBackend *owner, const QString &message) {
        if (!owner)
            return;
        QPointer<MobileWebViewBackend> guard(owner);
        QMetaObject::invokeMethod(owner, [guard, downloadId, message]() {
            if (guard)
                guard->reportDownloadFinished(downloadId, false, message);
        }, Qt::QueuedConnection);
    };

    if (!webView || !entry) {
        fail(self.owner, QStringLiteral("Resume data unavailable"));
        return;
    }

    // pause() sets Paused immediately, but WK only delivers resumeData in the
    // cancel completion — host resume() often arrives first. Queue it.
    if (!entry.resumeData) {
        if (entry.pausing) {
            entry.resumeWhenReady = YES;
            entry.pendingResumeWebView = webView;
            return;
        }
        fail(self.owner, QStringLiteral("Resume data unavailable"));
        return;
    }

    NSData *resumeData = [[entry.resumeData retain] autorelease];
    entry.resumeData = nil;
    entry.resumeWhenReady = NO;
    entry.pendingResumeWebView = nil;
    __block DownloadDelegate *blockSelf = self;
    [webView resumeDownloadFromResumeData:resumeData completionHandler:^(WKDownload *download) {
        if (!download) {
            fail(blockSelf.owner, QStringLiteral("Resume data unavailable"));
            return;
        }
        download.delegate = blockSelf;
        [blockSelf registerDownload:download downloadId:downloadId];
    }];
}

- (void)cancelAll
{
    NSArray<NSNumber *> *keys = [self.entriesById.allKeys copy];
    for (NSNumber *key in keys)
        [self cancelDownloadId:key.unsignedLongLongValue];
    [keys release];

    for (NSNumber *key in self.entriesById.allKeys) {
        MWVDownloadEntry *entry = self.entriesById[key];
        if (entry.destinationHandler) {
            void (^completion)(NSURL *) = [entry.destinationHandler retain];
            entry.destinationHandler = nil;
            completion(nil);
            [completion release];
        }
        [entry destroy];
    }
    [self.entriesById removeAllObjects];
    [self.idsByDownload removeAllObjects];
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
        MWVDownloadEntry *entry = [self entryForId:downloadId create:NO];
        if (entry.pendingPath.length > 0) {
            // Retain before clearing the copy property — otherwise pending dangles.
            NSString *pending = [[entry.pendingPath retain] autorelease];
            entry.pendingPath = nil;
            completionHandler([NSURL fileURLWithPath:pending]);
            return;
        }
        entry.destinationHandler = completionHandler;
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
        MWVDownloadEntry *entry = [delegate entryForId:downloadId create:NO];
        entry.destinationHandler = handlerCopy;
        [handlerCopy release];

        // Host accept() during this emit can provideDestinationPath (known id).
        guard->emitDownloadRequested(item);

        if (entry.pendingPath.length > 0 && entry.destinationHandler) {
            void (^completion)(NSURL *) = [entry.destinationHandler retain];
            entry.destinationHandler = nil;
            NSString *pending = [[entry.pendingPath retain] autorelease];
            entry.pendingPath = nil;
            completion([NSURL fileURLWithPath:pending]);
            [completion release];
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
    [self destroyEntryId:downloadId];
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
    NSNumber *idNumber = [self.idsByDownload objectForKey:download];
    if (!idNumber)
        return;

    const uint64_t downloadId = idNumber.unsignedLongLongValue;
    NSNumber *key = @(downloadId);
    MWVDownloadEntry *entry = self.entriesById[key];
    if (!entry)
        return;

    // Pause path: WK often delivers resumeData here (not in cancel's completion).
    // Detach while \a download is still a live map key; pause finalizer keeps the entry.
    if (entry.pausing) {
        if (resumeData && !entry.resumeData)
            entry.resumeData = resumeData;
        [self detachActiveForId:downloadId];
        entry.pausing = YES;
        return;
    }
    if (entry.resumeData) {
        // Already paused with data; ignore late failure.
        return;
    }

    // Keep resumeData for a possible host retry path that reuses WK resume.
    if (resumeData)
        entry.resumeData = resumeData;

    const QString message = qStringFromNS(error.localizedDescription);
    MobileWebViewBackend *owner = self.owner;
    [self detachActiveForId:downloadId];

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
