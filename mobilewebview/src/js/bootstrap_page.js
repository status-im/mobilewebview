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
  // Sticky flag: once the native bridge has been usable, do not gate on dataset.sqBridgeReady.
  // SPAs (e.g. OpenSea) may clear/replace <html> after hydration and drop the attribute while
  // the __sq_req__ listener remains valid.
  var _transportReady = false;

  function getDocumentElement() {
    return document.documentElement || document.querySelector('html');
  }

  // Synchronous check for bridge readiness via DOM attribute
  // This is shared between content worlds and avoids race conditions
  function isBridgeReady() {
    var el = getDocumentElement();
    return el ? el.dataset.sqBridgeReady === '1' : false;
  }

  function markTransportReady() {
    if (_transportReady) {
      flushPendingMessages();
      return;
    }
    _transportReady = true;
    flushPendingMessages();
  }

  function postToBridge(msg) {
    document.dispatchEvent(new CustomEvent('__sq_req__', { detail: msg }));
  }

  function flushPendingMessages() {
    while (_pendingMessages.length > 0) {
      postToBridge(_pendingMessages.shift());
    }
  }

  // Wait for bridge to be ready using MutationObserver; if DOM is not ready yet, retry.
  function waitForBridge(callback) {
    if (_transportReady || isBridgeReady()) {
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
      if (_transportReady || isBridgeReady()) {
        markTransportReady();
        postToBridge(msg);
        return;
      }
      _pendingMessages.push(msg);
      waitForBridge(markTransportReady);
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

  // Listen for push messages from bridgeWorld; receiving one proves the transport is alive.
  document.addEventListener('__sq_push__', function(e) {
    markTransportReady();
    if (typeof _onmessage === 'function') {
      _onmessage({ data: e.detail });
    }
  });

  // Re-arm when SPA replaces <html> and drops data-sq-bridge-ready after first connect.
  (function watchBridgeDataset() {
    var lastEl = null;
    function onElement(el) {
      if (el === lastEl) {
        return;
      }
      lastEl = el;
      new MutationObserver(function() {
        if (!_transportReady && isBridgeReady()) {
          markTransportReady();
        }
      }).observe(el, {
        attributes: true,
        attributeFilter: ['data-sq-bridge-ready']
      });
      if (!_transportReady && isBridgeReady()) {
        markTransportReady();
      }
    }
    function attach() {
      var el = getDocumentElement();
      if (!el) {
        setTimeout(attach, 50);
        return;
      }
      onElement(el);
      new MutationObserver(function() {
        var current = getDocumentElement();
        if (current && current !== lastEl) {
          onElement(current);
        }
      }).observe(document, { childList: true, subtree: true });
    }
    attach();
  })();

  // Signal that the WebChannel transport is ready
  // This allows other scripts (like ethereum_injector.js) to know when they can initialize
  window[ns].__ready = true;
  window.dispatchEvent(new Event('qtWebChannelReady'));
})('%NS%');
