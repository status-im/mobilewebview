// Intercept <a download> clicks for blob:/data: URLs and post an Inline Download
// envelope to native (not via WebChannel RPC). Runs at document-start in page world.
(function() {
  'use strict';

  var TAG = '[MwvInlineDownload]';
  // Substituted at injection from InlineDownloadCodec::kMaxDecodedBytes.
  var MAX_DECODED_BYTES = %MAX_INLINE_BYTES%;
  var MAX_BASE64_CHARS = Math.floor(MAX_DECODED_BYTES * 4 / 3) + 4;

  function postNative(packet) {
    var json = JSON.stringify(packet);
    if (typeof NativeBridge !== 'undefined' && typeof NativeBridge.postMessage === 'function') {
      try {
        NativeBridge.postMessage(json);
        return;
      } catch (e) {
        console.error(TAG, 'NativeBridge.postMessage failed:', e);
      }
    }
    if (typeof webkit !== 'undefined' && webkit.messageHandlers && webkit.messageHandlers.qtbridge) {
      try {
        webkit.messageHandlers.qtbridge.postMessage(json);
        return;
      } catch (e) {
        console.error(TAG, 'qtbridge.postMessage failed:', e);
      }
    }
    // Darwin isolated world: bridge world listens and forwards without invokeKey wrap.
    document.dispatchEvent(new CustomEvent('__mwv_download__', { detail: json }));
  }

  function mimeFromDataUrl(url) {
    var m = /^data:([^;,]+)/i.exec(url || '');
    return m ? m[1] : 'application/octet-stream';
  }

  function emitInline(url, fileName, mimeType, base64) {
    if (!base64 || base64.length > MAX_BASE64_CHARS) {
      console.warn(TAG, 'Inline download skipped: empty or exceeds size limit');
      return;
    }
    postNative({
      mwvDownload: true,
      url: url,
      fileName: fileName || 'download',
      mimeType: mimeType || 'application/octet-stream',
      base64: base64
    });
  }

  function handleDataUrl(href, fileName) {
    var comma = href.indexOf(',');
    if (comma < 0) {
      console.warn(TAG, 'Malformed data: URL');
      return;
    }
    var header = href.substring(0, comma).toLowerCase();
    var body = href.substring(comma + 1);
    var mime = mimeFromDataUrl(href);
    if (header.indexOf(';base64') >= 0) {
      emitInline(href, fileName, mime, body);
      return;
    }
    // Percent-encoded data: encode as base64 for the native codec.
    try {
      var decoded = decodeURIComponent(body);
      var b64 = btoa(unescape(encodeURIComponent(decoded)));
      emitInline(href, fileName, mime, b64);
    } catch (e) {
      console.warn(TAG, 'Failed to encode data: URL', e);
    }
  }

  function handleBlobUrl(href, fileName, mimeHint) {
    if (typeof fetch !== 'function') {
      console.warn(TAG, 'fetch unavailable for blob: URL');
      return;
    }
    fetch(href).then(function(resp) {
      return resp.blob();
    }).then(function(blob) {
      if (blob.size > MAX_DECODED_BYTES) {
        console.warn(TAG, 'Inline download skipped: exceeds size limit');
        return;
      }
      var reader = new FileReader();
      reader.onloadend = function() {
        var result = reader.result || '';
        var comma = String(result).indexOf(',');
        var b64 = comma >= 0 ? String(result).substring(comma + 1) : String(result);
        var mime = blob.type || mimeHint || 'application/octet-stream';
        emitInline(href, fileName, mime, b64);
      };
      reader.onerror = function() {
        console.warn(TAG, 'FileReader failed for blob: URL');
      };
      reader.readAsDataURL(blob);
    }).catch(function(e) {
      console.warn(TAG, 'Failed to read blob: URL', e);
    });
  }

  function findDownloadAnchor(target) {
    var el = target;
    while (el && el !== document) {
      if (el.tagName === 'A' && el.hasAttribute('download'))
        return el;
      el = el.parentNode;
    }
    return null;
  }

  // Same-origin http(s) <a download>: native ignores the attribute — post a
  // URL-only envelope for the Download path. Cross-origin: navigate (Chrome-like).
  function handleHttpUrl(a, href, fileName) {
    try {
      if (a.origin !== window.location.origin)
        return false;
    } catch (e) {
      return false;
    }
    postNative({
      mwvDownload: true,
      url: href,
      fileName: fileName
    });
    return true;
  }

  document.addEventListener('click', function(ev) {
    if (ev.defaultPrevented)
      return;
    var a = findDownloadAnchor(ev.target);
    if (!a)
      return;
    var href = a.href || a.getAttribute('href') || '';
    if (!href)
      return;
    var isBlob = href.indexOf('blob:') === 0;
    var isData = href.indexOf('data:') === 0;
    var isHttp = /^https?:/i.test(href);
    if (!isBlob && !isData && !isHttp)
      return;

    if (isHttp) {
      // Empty download attr → DownloadPolicy names from URL.
      if (handleHttpUrl(a, href, a.getAttribute('download') || '')) {
        ev.preventDefault();
        ev.stopPropagation();
      }
      return;
    }

    ev.preventDefault();
    ev.stopPropagation();

    var fileName = a.getAttribute('download') || 'download';
    if (isData)
      handleDataUrl(href, fileName);
    else
      handleBlobUrl(href, fileName, a.type || '');
  }, true);
})();
