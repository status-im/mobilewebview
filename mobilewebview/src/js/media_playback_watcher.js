// Report to native when this document starts or stops playing audible media,
// so the host can tell a page that plays sound from an idle one. Runs at
// document-start in every frame the injection reaches, page world. Paused,
// muted or silent media does not count.
//
// Seen: media elements in the document (their events, captured at the
// document) and any element play() is called on (inside shadow roots, or out
// of any document). Not seen: Web Audio API output, media that autoplays
// inside a shadow root without play(), and frames the injection does not
// reach (the onPageStarted fallback only covers the main frame).
//
// Wrapped in an IIFE so factory symbols do not leak onto window. Node tests
// load it via CommonJS (no document).
(function() {
  'use strict';

  var EVENTS = ['playing', 'play', 'pause', 'ended', 'emptied', 'volumechange', 'abort', 'error'];

  function createMediaPlaybackWatcher(options) {
    var win = options.window;
    var report = options.report;
    var tracked = [];
    var audible = false;
    // Elements with our listeners; kept out of the page's sight.
    var listening = typeof WeakSet === 'function' ? new WeakSet() : null;

    function isAudible(el) {
      return !el.paused && !el.ended && !el.muted && el.volume > 0;
    }

    function update() {
      // Stopped and out of the document: nothing left to follow.
      tracked = tracked.filter(function(el) { return el.isConnected || isAudible(el); });
      var now = false;
      for (var i = 0; i < tracked.length && !now; i++)
        now = isAudible(tracked[i]);
      if (now === audible)
        return;
      audible = now;
      report(now);
    }

    function track(el) {
      if (tracked.indexOf(el) === -1)
        tracked.push(el);
      // Its events stop reaching the document once it leaves it, and never do
      // from inside a shadow root.
      if (listening && !listening.has(el)) {
        listening.add(el);
        for (var i = 0; i < EVENTS.length; i++)
          el.addEventListener(EVENTS[i], onMediaEvent);
      }
    }

    function onMediaEvent(event) {
      var el = event.target;
      if (!(el instanceof win.HTMLMediaElement))
        return;
      track(el);
      update();
    }

    function onPageHide() {
      tracked = [];
      update();
    }

    return {
      // Test seam only.
      trackedCount: function() { return tracked.length; },
      attach: function() {
        var doc = win.document;
        for (var i = 0; i < EVENTS.length; i++)
          doc.addEventListener(EVENTS[i], onMediaEvent, true);
        win.addEventListener('pagehide', onPageHide);

        var proto = win.HTMLMediaElement && win.HTMLMediaElement.prototype;
        var originalPlay = proto && proto.play;
        if (typeof originalPlay === 'function') {
          proto.play = function() {
            track(this);
            return originalPlay.apply(this, arguments);
          };
        }
      }
    };
  }

  if (typeof document !== 'undefined') {
    if (window.__mwvMediaPlaybackWatcher)
      return;
    window.__mwvMediaPlaybackWatcher = true;
    var documentId = Math.random().toString(36).slice(2) + Date.now().toString(36);
    createMediaPlaybackWatcher({
      window: window,
      report: function(playing) {
        var bridge = window.NativeBridge;
        if (bridge && typeof bridge.mediaPlaybackChanged === 'function') {
          try {
            bridge.mediaPlaybackChanged(documentId, playing);
          } catch (e) {}
        }
      }
    }).attach();
  } else if (typeof module !== 'undefined' && module.exports) {
    module.exports = {
      createMediaPlaybackWatcher: createMediaPlaybackWatcher
    };
  }
})();
