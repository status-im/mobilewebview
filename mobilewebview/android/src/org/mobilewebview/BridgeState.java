package org.mobilewebview;

import java.util.List;

final class BridgeState {
    final boolean bridgeInstalled;
    final List<String> userScripts;
    final String bootstrapPageScript;
    final String bootstrapBridgeScript;
    final String inlineDownloadScript;
    final String mediaPlaybackScript;
    final List<String> allowedOrigins;

    BridgeState(boolean bridgeInstalled, List<String> userScripts,
                String bootstrapPageScript, String bootstrapBridgeScript,
                String inlineDownloadScript, String mediaPlaybackScript,
                List<String> allowedOrigins) {
        this.bridgeInstalled = bridgeInstalled;
        this.userScripts = userScripts;
        this.bootstrapPageScript = bootstrapPageScript;
        this.bootstrapBridgeScript = bootstrapBridgeScript;
        this.inlineDownloadScript = inlineDownloadScript;
        this.mediaPlaybackScript = mediaPlaybackScript;
        this.allowedOrigins = allowedOrigins;
    }
}
