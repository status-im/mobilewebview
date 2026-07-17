#pragma once

#import <WebKit/WebKit.h>

class MobileWebViewBackend;
@class DownloadDelegate;

@interface NavigationDelegate : NSObject <WKNavigationDelegate>
@property (nonatomic, assign) MobileWebViewBackend *owner;
@property (nonatomic, assign) DownloadDelegate *downloadDelegate;
@end

