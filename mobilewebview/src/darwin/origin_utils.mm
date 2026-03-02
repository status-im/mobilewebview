#import "origin_utils.h"

#import <WebKit/WebKit.h>
#import <Foundation/Foundation.h>

#include <QString>
#include <QUrl>

#pragma mark - Darwin-specific Origin Utility Functions

NSString *extractOriginFromFrameInfo(WKFrameInfo *frameInfo)
{
    if (!frameInfo || !frameInfo.securityOrigin) {
        return @"";
    }
    
    WKSecurityOrigin *securityOrigin = frameInfo.securityOrigin;
    
    QUrl url;
    url.setScheme(QString::fromNSString(securityOrigin.protocol));
    url.setHost(QString::fromNSString(securityOrigin.host));
    if (securityOrigin.port > 0) {
        url.setPort(securityOrigin.port);
    }
    
    return extractOrigin(url).toNSString();
}
