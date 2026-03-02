#pragma once

#import <WebKit/WebKit.h>
#import <Foundation/Foundation.h>

#include "../common/origin_utils.h"

#pragma mark - Darwin-specific Origin Utility Functions

// Extracts the origin string from a WKFrameInfo object (format: "protocol://host" or "protocol://host:port")
// This is Darwin-specific as it uses WKFrameInfo from WebKit
NSString *extractOriginFromFrameInfo(WKFrameInfo *frameInfo);
