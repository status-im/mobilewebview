package org.mobilewebview;

import java.util.List;

interface NativeBridgeHost {
    long nativePtr();
    String currentMainFrameOrigin();
    List<String> allowedOriginsSnapshot();
    void onWebMessage(String message, String origin);
}
