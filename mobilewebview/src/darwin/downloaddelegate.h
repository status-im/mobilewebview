#pragma once

#import <WebKit/WebKit.h>
#include <cstdint>

class MobileWebViewBackend;

API_AVAILABLE(macos(11.3), ios(14.5))
@interface DownloadDelegate : NSObject <WKDownloadDelegate>
@property (nonatomic, assign) MobileWebViewBackend *owner;

/// Page-initiated: WKDownload already exists; emit downloadRequested and wait for accept().
- (void)attachDownload:(WKDownload *)download;

/// Host accepted: supply destination for a pending page-initiated download.
/// Returns YES if the id is already known (handler applied or path stashed).
/// Returns NO if the id is unknown — caller should start an explicit download.
- (BOOL)provideDestinationPath:(NSString *)path forDownloadId:(uint64_t)downloadId;

/// Register a page-initiated WKDownload with a known id before emitDownloadRequested.
- (void)registerDownload:(WKDownload *)download downloadId:(uint64_t)downloadId;

/// Explicit downloadUrl() after accept(): start a new WKDownload with a known destination.
- (void)startExplicitDownloadWithURL:(NSURL *)url
                          downloadId:(uint64_t)downloadId
                     destinationPath:(NSString *)path
                             webView:(WKWebView *)webView;

- (void)cancelDownloadId:(uint64_t)downloadId;
/// Pause: cancel the WKDownload and keep resumeData for a later resume.
- (void)pauseDownloadId:(uint64_t)downloadId;
/// Resume from stored resumeData via WKWebView resumeDownloadFromResumeData:.
- (void)resumeDownloadId:(uint64_t)downloadId webView:(WKWebView *)webView;
- (void)cancelAll;
@end
