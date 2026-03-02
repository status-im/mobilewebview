#pragma once

#import <WebKit/WebKit.h>

class MobileWebViewBackend;

@interface NavigationDelegate : NSObject <WKNavigationDelegate>
@property (nonatomic, assign) MobileWebViewBackend *owner;
@end

