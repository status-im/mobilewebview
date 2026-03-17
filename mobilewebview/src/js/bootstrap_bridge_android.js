// Android bridge bootstrap (runs in page world)
// Uses NativeBridge.postMessage() JavascriptInterface instead of webkit.messageHandlers
(function(invokeKey) {
  'use strict';

  var TAG = '[QtBridge/android]';
  
  // Test if NativeBridge is available
  var bridgeAvailable = typeof NativeBridge !== 'undefined' &&
                        typeof NativeBridge.postMessage === 'function';
  
  if (!bridgeAvailable) {
    console.error(TAG, 'NativeBridge not available');
    return;
  }

  // Listen for requests from page world
  // Note: On Android we don't have content world isolation, so we use custom events
  document.addEventListener('__sq_req__', function(e) {
    if (typeof e.detail !== 'string') {
      console.error(TAG, 'Expected string detail, got:', typeof e.detail);
      return;
    }

    var packet = JSON.stringify({
      invokeKey: invokeKey,
      data: e.detail
    });
    
    try {
      NativeBridge.postMessage(packet);
    } catch (error) {
      console.error(TAG, 'Failed to post message:', error);
    }
  });
  
  // document.documentElement may be null during very early injection (onPageStarted)
  (function poll(n) {
    var el = document.documentElement || document.querySelector('html');
    if (el) el.dataset.sqBridgeReady = '1';
    else if (n) setTimeout(poll, 5, n - 1);
    else console.error(TAG, 'Bridge ready timeout: DOM did not appear');
  })(200);
})('%INVOKE_KEY%');
