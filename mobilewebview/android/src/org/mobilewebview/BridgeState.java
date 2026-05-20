package org.mobilewebview;

import java.util.List;

final class BridgeState {
    final boolean bridgeInstalled;
    final List<String> userScripts;
    final String bootstrapPageScript;
    final String bootstrapBridgeScript;
    final List<String> allowedOrigins;

    BridgeState(boolean bridgeInstalled, List<String> userScripts,
                String bootstrapPageScript, String bootstrapBridgeScript,
                List<String> allowedOrigins) {
        this.bridgeInstalled = bridgeInstalled;
        this.userScripts = userScripts;
        this.bootstrapPageScript = bootstrapPageScript;
        this.bootstrapBridgeScript = bootstrapBridgeScript;
        this.allowedOrigins = allowedOrigins;
    }
}
