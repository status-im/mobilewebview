#include "dataclearops.h"
#include "dispatch_utils.h"

#include "MobileWebView/mobilewebviewbackend.h"

#include <QDebug>
#include <QMetaObject>
#include <QPointer>
#include <QUrl>

#if defined(Q_OS_MACOS) || defined(Q_OS_IOS)

namespace {

void invokeClearCompletion(MobileWebViewBackend *backend, std::function<void()> completion)
{
    if (!completion) {
        return;
    }

    QPointer<MobileWebViewBackend> guard(backend);
    QMetaObject::invokeMethod(backend, [guard, completion = std::move(completion)]() mutable {
        if (guard) {
            completion();
        }
    }, Qt::QueuedConnection);
}

void removeDataOfTypes(WKWebView *webView, MobileWebViewBackend *backend,
                       NSSet *types, std::function<void()> completion)
{
    runOnMainThread(^{
        [webView.configuration.websiteDataStore removeDataOfTypes:types
                                                    modifiedSince:[NSDate distantPast]
                                                completionHandler:^{
            invokeClearCompletion(backend, std::move(completion));
        }];
    });
}

} // namespace

namespace DataClearOps {

void clearHttpCache(WKWebView *webView, MobileWebViewBackend *backend,
                    std::function<void()> completion)
{
    if (!webView) {
        invokeClearCompletion(backend, std::move(completion));
        return;
    }

    NSSet *types = [NSSet setWithObjects:
        WKWebsiteDataTypeDiskCache,
        WKWebsiteDataTypeMemoryCache,
        WKWebsiteDataTypeOfflineWebApplicationCache,
        nil];
    removeDataOfTypes(webView, backend, types, std::move(completion));
}

void deleteAllCookies(WKWebView *webView, MobileWebViewBackend *backend,
                      std::function<void()> completion)
{
    if (!webView) {
        invokeClearCompletion(backend, std::move(completion));
        return;
    }

    NSSet *types = [NSSet setWithObjects:WKWebsiteDataTypeCookies, nil];
    removeDataOfTypes(webView, backend, types, std::move(completion));
}

void clearDomStorage(WKWebView *webView, MobileWebViewBackend *backend,
                     std::function<void()> completion)
{
    if (!webView) {
        invokeClearCompletion(backend, std::move(completion));
        return;
    }

    NSSet *types = [NSSet setWithObjects:
        WKWebsiteDataTypeLocalStorage,
        WKWebsiteDataTypeSessionStorage,
        WKWebsiteDataTypeIndexedDBDatabases,
        WKWebsiteDataTypeWebSQLDatabases,
        WKWebsiteDataTypeServiceWorkerRegistrations,
        WKWebsiteDataTypeOfflineWebApplicationCache,
        nil];
    removeDataOfTypes(webView, backend, types, std::move(completion));
}

void clearSiteData(WKWebView *webView, MobileWebViewBackend *backend,
                   const QString &origin, std::function<void()> completion)
{
    if (!webView) {
        invokeClearCompletion(backend, std::move(completion));
        return;
    }

    const QUrl url(origin);
    const QString host = url.host();
    if (host.isEmpty()) {
        qWarning() << "DataClearOps::clearSiteData: invalid origin, ignoring:" << origin;
        invokeClearCompletion(backend, std::move(completion));
        return;
    }

    // Per-site clearing is host-granular: WKWebsiteDataRecord.displayName is the
    // eTLD+1 (registrable domain), not the full host, and does not include the
    // port (see ADR 0004). A page at "sub.example.com" is stored under a record
    // named "example.com", so match when the record name equals the host or is
    // a dot-boundary suffix of it. Plain suffix/substring matching would clear
    // unrelated sites (e.g. "aaa.invalid" matching "siteaaa.invalid").
    runOnMainThread(^{
        NSSet *types = [WKWebsiteDataStore allWebsiteDataTypes];
        WKWebsiteDataStore *store = webView.configuration.websiteDataStore;
        NSString *hostName = host.toNSString();
        [store fetchDataRecordsOfTypes:types completionHandler:^(NSArray<WKWebsiteDataRecord *> *records) {
            NSMutableArray<WKWebsiteDataRecord *> *toRemove = [NSMutableArray array];
            for (WKWebsiteDataRecord *record in records) {
                if (record.displayName == nil) {
                    continue;
                }
                const BOOL exactMatch = [record.displayName isEqualToString:hostName];
                const BOOL registrableDomainOfHost =
                    [hostName hasSuffix:[@"." stringByAppendingString:record.displayName]];
                if (exactMatch || registrableDomainOfHost) {
                    [toRemove addObject:record];
                }
            }
            if (toRemove.count > 0) {
                [store removeDataOfTypes:types forDataRecords:toRemove completionHandler:^{
                    invokeClearCompletion(backend, std::move(completion));
                }];
            } else {
                invokeClearCompletion(backend, std::move(completion));
            }
        }];
    });
}

} // namespace DataClearOps

#endif // Q_OS_MACOS || Q_OS_IOS
