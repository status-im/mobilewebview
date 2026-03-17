// Bridge-side bootstrap (runs in bridgeWorld isolated world)
// Listens to DOM events from pageWorld and forwards to native handler
(function(invokeKey) {
  'use strict';

  var TAG = '[QtBridge/bridge]';

  var handlerAvailable = typeof webkit !== 'undefined' && 
                         webkit.messageHandlers && 
                         webkit.messageHandlers.qtbridge;

  document.addEventListener('__sq_req__', function(e) {
    if (typeof e.detail !== 'string') {
      console.error(TAG, 'Expected string detail, got:', typeof e.detail);
      return;
    }

    var packet = JSON.stringify({
      invokeKey: invokeKey,
      data: e.detail
    });

    if (handlerAvailable) {
      webkit.messageHandlers.qtbridge.postMessage(packet);
    } else {
      console.error(TAG, 'Message handler not available');
    }
  });

  // Signal to pageWorld that bridge is ready to receive messages
  // Using DOM attribute instead of event to avoid race conditions between content worlds
  document.documentElement.dataset.sqBridgeReady = '1';
  
  console.log(TAG, 'Listener ready, handler available:', handlerAvailable);
})('%INVOKE_KEY%');
