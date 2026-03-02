// Android bridge bootstrap (runs in page world)
// Uses NativeBridge.postMessage() JavascriptInterface instead of webkit.messageHandlers
(function(invokeKey) {
  'use strict';
  
  // Test if NativeBridge is available
  var bridgeAvailable = typeof NativeBridge !== 'undefined' &&
                        typeof NativeBridge.postMessage === 'function';
  
  if (!bridgeAvailable) {
    console.error('[QtBridge/android] NativeBridge not available');
    return;
  }
  
  // Listen for requests from page world
  // Note: On Android we don't have content world isolation, so we use custom events
  document.addEventListener('__sq_req__', function(e) {
    var packet = JSON.stringify({
      invokeKey: invokeKey,
      data: String(e.detail)
    });
    
    try {
      NativeBridge.postMessage(packet);
    } catch (error) {
      console.error('[QtBridge/android] Failed to post message:', error);
    }
  });
  
  // Signal to page world that bridge is ready
  document.documentElement.dataset.sqBridgeReady = '1';
  
  console.log('[QtBridge/android] Bridge ready, NativeBridge available:', bridgeAvailable);
})('%INVOKE_KEY%');
