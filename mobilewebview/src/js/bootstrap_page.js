// Page-side bootstrap (runs in pageWorld/default world, public)
// Creates a facade transport that communicates with bridgeWorld via DOM events
(function(ns) {
  'use strict';

  var TAG = '[BootstrapPage]';
  
  window[ns] = window[ns] || {};
  var _onmessage = null;
  
  // Queue for messages sent before bridge is ready
  var _pendingMessages = [];
  var _bridgeObserver = null;
  
  function getDocumentElement() {
    return document.documentElement || document.querySelector('html');
  }

  // Synchronous check for bridge readiness via DOM attribute
  // This is shared between content worlds and avoids race conditions
  function isBridgeReady() {
    var el = getDocumentElement();
    return el ? el.dataset.sqBridgeReady === '1' : false;
  }
  
  function flushPendingMessages() {
    console.log(TAG, 'Flushing', _pendingMessages.length, 'pending messages');
    while (_pendingMessages.length > 0) {
      var msg = _pendingMessages.shift();
      document.dispatchEvent(new CustomEvent('__sq_req__', { detail: msg }));
    }
  }
  
  // Wait for bridge to be ready using MutationObserver; if DOM is not ready yet, retry.
  function waitForBridge(callback) {
    if (isBridgeReady()) {
      callback();
      return;
    }
    
    // Only create one observer
    if (_bridgeObserver) return;

    var el = getDocumentElement();
    if (!el) {
      (function poll(n) {
        var domEl = getDocumentElement();
        if (domEl) {
          isBridgeReady() ? callback() : observeElement(domEl, callback);
        } else if (n) {
          setTimeout(poll, 10, n - 1);
        } else {
          console.error(TAG, 'Timeout: DOM did not appear');
        }
      })(100);
      return;
    }

    observeElement(el, callback);
  }

  function observeElement(el, callback) {
    _bridgeObserver = new MutationObserver(function() {
      if (isBridgeReady()) {
        _bridgeObserver.disconnect();
        _bridgeObserver = null;
        console.log(TAG, 'Bridge became ready (MutationObserver)');
        callback();
      }
    });
    
    _bridgeObserver.observe(el, {
      attributes: true,
      attributeFilter: ['data-sq-bridge-ready']
    });
  }
  
  // Create WebChannel transport facade
  window[ns].webChannelTransport = {
    send: function(msg) {
      if (isBridgeReady()) {
        // Send request to bridgeWorld via DOM event
        // see IsolatedWorldContext in webviewbackend.mm
        document.dispatchEvent(new CustomEvent('__sq_req__', { detail: msg }));
      } else {
        // Queue message until bridge is ready
        _pendingMessages.push(msg);
        waitForBridge(flushPendingMessages);
      }
    },
    set onmessage(fn) {
      _onmessage = fn;
    },
    get onmessage() {
      return _onmessage;
    }
  };
  
  // Called from native via evaluateJavaScript to deliver messages from Qt
  // (Kept for backward compatibility with non-isolated mode)
  // see PageWorldContext in webviewbackend.mm
  window[ns].__deliverMessage = function(data) {
    if (typeof _onmessage === 'function') {
      _onmessage({ data: data });
    }
  };
  
  // Listen for push messages from bridgeWorld
  document.addEventListener('__sq_push__', function(e) {
    if (typeof _onmessage === 'function') {
      _onmessage({ data: e.detail });
    }
  });

  // Signal that the WebChannel transport is ready
  // This allows other scripts (like ethereum_injector.js) to know when they can initialize
  window.dispatchEvent(new Event('qtWebChannelReady'));
})('%NS%');
