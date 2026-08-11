// Intercept <a download> clicks for blob:/data: URLs and post an Inline Download
// envelope to native (not via WebChannel RPC). Runs at document-start in page world.
//
// Wrapped in an IIFE so factory symbols do not leak onto window. Browser bootstrap
// keys off `document` (not `module`) so a page UMD shim cannot skip attach.
// Node tests load via CommonJS after substituting %MAX_INLINE_BYTES%.
(function() {
  'use strict';

  var TAG = '[MwvInlineDownload]';

  function createDefaultPost(doc) {
    return function postNative(packet) {
      var json = JSON.stringify(packet);
      var win = doc.defaultView || (typeof window !== 'undefined' ? window : undefined);
      var bridge = win && win.NativeBridge;
      if (bridge && typeof bridge.postMessage === 'function') {
        try {
          bridge.postMessage(json);
          return;
        } catch (e) {
          console.error(TAG, 'NativeBridge.postMessage failed:', e);
        }
      }
      var wk = win && win.webkit;
      if (wk && wk.messageHandlers && wk.messageHandlers.qtbridge) {
        try {
          wk.messageHandlers.qtbridge.postMessage(json);
          return;
        } catch (e) {
          console.error(TAG, 'qtbridge.postMessage failed:', e);
        }
      }
      // Darwin isolated world: bridge world listens and forwards without invokeKey wrap.
      var CustomEventCtor =
        (win && win.CustomEvent) ||
        (typeof CustomEvent !== 'undefined' ? CustomEvent : undefined);
      if (typeof CustomEventCtor !== 'function') {
        console.error(TAG, 'CustomEvent unavailable for __mwv_download__');
        return;
      }
      doc.dispatchEvent(new CustomEventCtor('__mwv_download__', { detail: json }));
    };
  }

  function createInlineDownloadInterceptor(opts) {
    var MAX_DECODED_BYTES = opts.maxDecodedBytes;
    var MAX_BASE64_CHARS = Math.floor(MAX_DECODED_BYTES * 4 / 3) + 4;
    var postNative = opts.post;
    var fetchFn = opts.fetch;
    var FileReaderCtor = opts.FileReader;
    var btoaFn = opts.btoa;
    var attachedDocument = null;

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
        var b64 = btoaFn(unescape(encodeURIComponent(decoded)));
        emitInline(href, fileName, mime, b64);
      } catch (e) {
        console.warn(TAG, 'Failed to encode data: URL', e);
      }
    }

    function handleBlobUrl(href, fileName, mimeHint) {
      if (typeof fetchFn !== 'function') {
        console.warn(TAG, 'fetch unavailable for blob: URL');
        return Promise.resolve();
      }
      return fetchFn(href).then(function(resp) {
        return resp.blob();
      }).then(function(blob) {
        if (blob.size > MAX_DECODED_BYTES) {
          console.warn(TAG, 'Inline download skipped: exceeds size limit');
          return;
        }
        if (typeof FileReaderCtor !== 'function') {
          console.warn(TAG, 'FileReader unavailable for blob: URL');
          return;
        }
        return new Promise(function(resolve) {
          var reader = new FileReaderCtor();
          reader.onloadend = function() {
            var result = reader.result || '';
            var comma = String(result).indexOf(',');
            var b64 = comma >= 0 ? String(result).substring(comma + 1) : String(result);
            var mime = blob.type || mimeHint || 'application/octet-stream';
            emitInline(href, fileName, mime, b64);
            resolve();
          };
          reader.onerror = function() {
            console.warn(TAG, 'FileReader failed for blob: URL');
            resolve();
          };
          reader.readAsDataURL(blob);
        });
      }).catch(function(e) {
        console.warn(TAG, 'Failed to read blob: URL', e);
      });
    }

    function findDownloadAnchor(target, doc) {
      var el = target;
      while (el && el !== doc) {
        if (el.tagName === 'A' && el.hasAttribute('download'))
          return el;
        el = el.parentNode;
      }
      return null;
    }

    // Same-origin http(s) <a download>: native ignores the attribute — post a
    // URL-only envelope for the Download path. Cross-origin: navigate (Chrome-like).
    function handleHttpUrl(a, href, fileName) {
      var win = attachedDocument && (attachedDocument.defaultView || attachedDocument.parentWindow);
      try {
        if (!win || a.origin !== win.location.origin)
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

    function onClick(ev) {
      if (ev.defaultPrevented)
        return;
      var doc = attachedDocument;
      if (!doc)
        return;
      var a = findDownloadAnchor(ev.target, doc);
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
    }

    return {
      attach: function(doc) {
        attachedDocument = doc;
        doc.addEventListener('click', onClick, true);
        return function detach() {
          doc.removeEventListener('click', onClick, true);
          if (attachedDocument === doc)
            attachedDocument = null;
        };
      },
      // Test seams (also usable for direct invocation).
      handleDataUrl: handleDataUrl,
      handleBlobUrl: handleBlobUrl,
      findDownloadAnchor: findDownloadAnchor,
      maxBase64Chars: MAX_BASE64_CHARS,
      maxDecodedBytes: MAX_DECODED_BYTES
    };
  }

  // Prefer document (page / inject) over module so a page UMD `module` shim cannot
  // skip attach. Node unit loads have no document → CommonJS export.
  if (typeof document !== 'undefined') {
    // Substituted at injection from InlineDownloadCodec::kMaxDecodedBytes.
    createInlineDownloadInterceptor({
      maxDecodedBytes: %MAX_INLINE_BYTES%,
      post: createDefaultPost(document),
      fetch: typeof fetch === 'function' ? fetch : undefined,
      FileReader: typeof FileReader !== 'undefined' ? FileReader : undefined,
      btoa: typeof btoa === 'function' ? btoa : undefined
    }).attach(document);
  } else if (typeof module !== 'undefined' && module.exports) {
    module.exports = {
      createInlineDownloadInterceptor: createInlineDownloadInterceptor,
      createDefaultPost: createDefaultPost,
      TAG: TAG
    };
  }
})();
