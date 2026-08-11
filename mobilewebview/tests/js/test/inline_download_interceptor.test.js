'use strict';

const { describe, it, beforeEach, afterEach, mock } = require('node:test');
const assert = require('node:assert/strict');
const { JSDOM } = require('jsdom');
const { loadInterceptor, readRawSource, SOURCE_PATH } = require('../load_interceptor');

const MAX = 64; // small fixture limit (plan D)

function btoaUtf8(str) {
  return Buffer.from(str, 'binary').toString('base64');
}

function makeDom(url = 'https://download-harness.invalid/page', options = {}) {
  const dom = new JSDOM('<!doctype html><html><body></body></html>', {
    url,
    pretendToBeVisual: true,
    ...options,
  });
  return dom;
}

function makePageWorldDom(url) {
  // runScripts required for window.eval / injected userscript simulation.
  return makeDom(url, { runScripts: 'dangerously' });
}

function postedPackets(post) {
  return post.mock.calls.map((c) => c.arguments[0]);
}

function substitutedSource(maxDecodedBytes = MAX) {
  return readRawSource().replace(/%MAX_INLINE_BYTES%/g, String(maxDecodedBytes));
}

function clickDataDownload(doc, win, href = 'data:text/plain;base64,YQ==') {
  const a = doc.createElement('a');
  a.setAttribute('download', 'x.txt');
  a.setAttribute('href', href);
  Object.defineProperty(a, 'href', {
    configurable: true,
    get() {
      return href;
    },
  });
  doc.body.appendChild(a);
  const ev = new win.MouseEvent('click', { bubbles: true, cancelable: true });
  a.dispatchEvent(ev);
  return ev;
}

describe('inline_download_interceptor source', () => {
  it('keeps %MAX_INLINE_BYTES% placeholder for native substitution', () => {
    const raw = readRawSource();
    assert.match(raw, /%MAX_INLINE_BYTES%/);
    assert.doesNotMatch(raw, /maxDecodedBytes:\s*32\s*\*/);
    assert.ok(SOURCE_PATH.endsWith('inline_download_interceptor.js'));
  });
});

describe('page-world bootstrap', () => {
  it('does not leak factory symbols onto window', () => {
    const dom = makePageWorldDom();
    dom.window.eval(substitutedSource());
    assert.equal(typeof dom.window.createInlineDownloadInterceptor, 'undefined');
    assert.equal(typeof dom.window.createDefaultPost, 'undefined');
    assert.equal(typeof dom.window.TAG, 'undefined');
    dom.window.close();
  });

  it('attaches even when the page defines a global module shim', () => {
    const dom = makePageWorldDom();
    const posts = [];
    dom.window.module = { exports: {} };
    dom.window.NativeBridge = {
      postMessage(json) {
        posts.push(json);
      },
    };
    dom.window.eval(substitutedSource());

    assert.equal(
      typeof dom.window.module.exports.createInlineDownloadInterceptor,
      'undefined',
      'must not take the CommonJS export branch in page world'
    );

    const ev = clickDataDownload(dom.window.document, dom.window);
    assert.equal(ev.defaultPrevented, true);
    assert.equal(posts.length, 1);
    assert.equal(JSON.parse(posts[0]).mwvDownload, true);
    dom.window.close();
  });
});

describe('createDefaultPost', () => {
  let exports;
  let dom;
  let doc;

  beforeEach(() => {
    exports = loadInterceptor(MAX);
    dom = makeDom();
    doc = dom.window.document;
    delete dom.window.NativeBridge;
    delete dom.window.webkit;
  });

  afterEach(() => {
    dom.window.close();
  });

  it('prefers NativeBridge.postMessage', () => {
    const posts = [];
    dom.window.NativeBridge = {
      postMessage(json) {
        posts.push(json);
      },
    };
    const events = [];
    doc.addEventListener('__mwv_download__', (e) => events.push(e.detail));

    const post = exports.createDefaultPost(doc);
    post({ mwvDownload: true, url: 'data:text/plain,hi' });

    assert.equal(posts.length, 1);
    assert.equal(events.length, 0);
    assert.deepEqual(JSON.parse(posts[0]), {
      mwvDownload: true,
      url: 'data:text/plain,hi',
    });
  });

  it('falls back to webkit.messageHandlers.qtbridge', () => {
    const posts = [];
    dom.window.webkit = {
      messageHandlers: {
        qtbridge: {
          postMessage(json) {
            posts.push(json);
          },
        },
      },
    };
    const events = [];
    doc.addEventListener('__mwv_download__', (e) => events.push(e.detail));

    const post = exports.createDefaultPost(doc);
    post({ mwvDownload: true, url: 'blob:x' });

    assert.equal(posts.length, 1);
    assert.equal(events.length, 0);
  });

  it('falls back to __mwv_download__ CustomEvent', () => {
    const events = [];
    doc.addEventListener('__mwv_download__', (e) => events.push(e.detail));

    const post = exports.createDefaultPost(doc);
    post({ mwvDownload: true, fileName: 'a.txt' });

    assert.equal(events.length, 1);
    assert.deepEqual(JSON.parse(events[0]), {
      mwvDownload: true,
      fileName: 'a.txt',
    });
  });

  it('does not double-post when NativeBridge succeeds', () => {
    const bridgePosts = [];
    const qtPosts = [];
    dom.window.NativeBridge = {
      postMessage(json) {
        bridgePosts.push(json);
      },
    };
    dom.window.webkit = {
      messageHandlers: {
        qtbridge: {
          postMessage(json) {
            qtPosts.push(json);
          },
        },
      },
    };
    const events = [];
    doc.addEventListener('__mwv_download__', (e) => events.push(e.detail));

    exports.createDefaultPost(doc)({ mwvDownload: true });

    assert.equal(bridgePosts.length, 1);
    assert.equal(qtPosts.length, 0);
    assert.equal(events.length, 0);
  });
});

describe('createInlineDownloadInterceptor', () => {
  let exports;
  let post;
  let api;
  let dom;
  let doc;

  beforeEach(() => {
    exports = loadInterceptor(MAX);
    post = mock.fn();
    dom = makeDom();
    doc = dom.window.document;
    api = exports.createInlineDownloadInterceptor({
      maxDecodedBytes: MAX,
      post,
      fetch: undefined,
      FileReader: undefined,
      btoa: (s) => Buffer.from(s, 'binary').toString('base64'),
    });
  });

  afterEach(() => {
    dom.window.close();
  });

  describe('data: URLs', () => {
    it('posts base64 body as-is with MIME from header', () => {
      const href = 'data:text/plain;base64,aGVsbG8=';
      api.handleDataUrl(href, 'hello.txt');
      assert.equal(post.mock.callCount(), 1);
      assert.deepEqual(postedPackets(post)[0], {
        mwvDownload: true,
        url: href,
        fileName: 'hello.txt',
        mimeType: 'text/plain',
        base64: 'aGVsbG8=',
      });
    });

    it('encodes percent-encoded data: as base64', () => {
      const href = 'data:text/plain,hello%20world';
      api.handleDataUrl(href, 'p.txt');
      assert.equal(post.mock.callCount(), 1);
      const packet = postedPackets(post)[0];
      assert.equal(packet.mimeType, 'text/plain');
      assert.equal(packet.base64, btoaUtf8('hello world'));
    });

    it('defaults MIME when header has none', () => {
      api.handleDataUrl('data:;base64,YQ==', 'a.bin');
      assert.equal(postedPackets(post)[0].mimeType, 'application/octet-stream');
    });

    it('skips malformed data: without comma', () => {
      api.handleDataUrl('data:text/plain;base64', 'x.txt');
      assert.equal(post.mock.callCount(), 0);
    });

    it('skips empty base64', () => {
      api.handleDataUrl('data:text/plain;base64,', 'x.txt');
      assert.equal(post.mock.callCount(), 0);
    });

    it('skips oversized base64 payload', () => {
      const maxChars = api.maxBase64Chars;
      const big = 'A'.repeat(maxChars + 1);
      api.handleDataUrl('data:application/octet-stream;base64,' + big, 'big.bin');
      assert.equal(post.mock.callCount(), 0);
    });

    it('defaults fileName to download when empty', () => {
      api.handleDataUrl('data:text/plain;base64,YQ==', '');
      assert.equal(postedPackets(post)[0].fileName, 'download');
    });
  });

  describe('blob: URLs', () => {
    it('reads blob via fetch + FileReader and posts envelope', async () => {
      const href = 'blob:https://download-harness.invalid/uuid';
      const payload = 'hello-blob';
      const fetchFn = mock.fn(async () => ({
        blob: async () => ({
          size: payload.length,
          type: 'text/plain',
        }),
      }));
      function FakeFileReader() {
        this.result = null;
        this.onloadend = null;
        this.onerror = null;
      }
      FakeFileReader.prototype.readAsDataURL = function () {
        this.result = 'data:text/plain;base64,' + btoaUtf8(payload);
        this.onloadend();
      };

      const blobApi = exports.createInlineDownloadInterceptor({
        maxDecodedBytes: MAX,
        post,
        fetch: fetchFn,
        FileReader: FakeFileReader,
        btoa: (s) => Buffer.from(s, 'binary').toString('base64'),
      });

      await blobApi.handleBlobUrl(href, 'b.txt', '');
      assert.equal(post.mock.callCount(), 1);
      assert.deepEqual(postedPackets(post)[0], {
        mwvDownload: true,
        url: href,
        fileName: 'b.txt',
        mimeType: 'text/plain',
        base64: btoaUtf8(payload),
      });
    });

    it('uses mimeHint when blob.type is empty', async () => {
      const fetchFn = async () => ({
        blob: async () => ({ size: 1, type: '' }),
      });
      function FakeFileReader() {}
      FakeFileReader.prototype.readAsDataURL = function () {
        this.result = 'data:application/octet-stream;base64,YQ==';
        this.onloadend();
      };
      const blobApi = exports.createInlineDownloadInterceptor({
        maxDecodedBytes: MAX,
        post,
        fetch: fetchFn,
        FileReader: FakeFileReader,
        btoa: (s) => Buffer.from(s, 'binary').toString('base64'),
      });
      await blobApi.handleBlobUrl('blob:x', 'a.bin', 'application/pdf');
      assert.equal(postedPackets(post)[0].mimeType, 'application/pdf');
    });

    it('skips when blob.size exceeds maxDecodedBytes', async () => {
      const fetchFn = async () => ({
        blob: async () => ({ size: MAX + 1, type: 'text/plain' }),
      });
      let readerBuilt = false;
      function FakeFileReader() {
        readerBuilt = true;
      }
      FakeFileReader.prototype.readAsDataURL = function () {};
      const blobApi = exports.createInlineDownloadInterceptor({
        maxDecodedBytes: MAX,
        post,
        fetch: fetchFn,
        FileReader: FakeFileReader,
        btoa: (s) => Buffer.from(s, 'binary').toString('base64'),
      });
      await blobApi.handleBlobUrl('blob:x', 'big.txt', '');
      assert.equal(post.mock.callCount(), 0);
      assert.equal(readerBuilt, false);
    });

    it('skips when fetch is unavailable', async () => {
      await api.handleBlobUrl('blob:x', 'a.txt', '');
      assert.equal(post.mock.callCount(), 0);
    });

    it('skips when fetch rejects', async () => {
      const blobApi = exports.createInlineDownloadInterceptor({
        maxDecodedBytes: MAX,
        post,
        fetch: async () => {
          throw new Error('network');
        },
        FileReader: function () {},
        btoa: (s) => Buffer.from(s, 'binary').toString('base64'),
      });
      await blobApi.handleBlobUrl('blob:x', 'a.txt', '');
      assert.equal(post.mock.callCount(), 0);
    });

    it('skips when FileReader errors', async () => {
      const fetchFn = async () => ({
        blob: async () => ({ size: 1, type: 'text/plain' }),
      });
      function FakeFileReader() {}
      FakeFileReader.prototype.readAsDataURL = function () {
        this.onerror();
      };
      const blobApi = exports.createInlineDownloadInterceptor({
        maxDecodedBytes: MAX,
        post,
        fetch: fetchFn,
        FileReader: FakeFileReader,
        btoa: (s) => Buffer.from(s, 'binary').toString('base64'),
      });
      await blobApi.handleBlobUrl('blob:x', 'a.txt', '');
      assert.equal(post.mock.callCount(), 0);
    });
  });

  describe('click handling', () => {
    let detach;

    beforeEach(() => {
      detach = api.attach(doc);
    });

    afterEach(() => {
      if (detach) detach();
    });

    function addAnchor({ href, download, type, nested }) {
      const a = doc.createElement('a');
      if (download !== undefined) a.setAttribute('download', download);
      if (type) a.type = type;
      a.setAttribute('href', href);
      // Force href property for blob:/data: (jsdom may resolve relatively)
      Object.defineProperty(a, 'href', {
        configurable: true,
        get() {
          return href;
        },
      });
      if (nested) {
        const span = doc.createElement('span');
        span.textContent = 'x';
        a.appendChild(span);
        doc.body.appendChild(a);
        return { a, target: span };
      }
      a.textContent = 'dl';
      doc.body.appendChild(a);
      return { a, target: a };
    }

    function click(target, init = {}) {
      const ev = new dom.window.MouseEvent('click', {
        bubbles: true,
        cancelable: true,
        ...init,
      });
      if (init.defaultPrevented) ev.preventDefault();
      target.dispatchEvent(ev);
      return ev;
    }

    it('finds download anchor from nested target', () => {
      const { target } = addAnchor({
        href: 'data:text/plain;base64,YQ==',
        download: 'n.txt',
        nested: true,
      });
      click(target);
      assert.equal(post.mock.callCount(), 1);
      assert.equal(postedPackets(post)[0].fileName, 'n.txt');
    });

    it('ignores anchors without download attribute', () => {
      const a = doc.createElement('a');
      a.href = 'data:text/plain;base64,YQ==';
      doc.body.appendChild(a);
      click(a);
      assert.equal(post.mock.callCount(), 0);
    });

    it('ignores when event.defaultPrevented', () => {
      const { target } = addAnchor({
        href: 'data:text/plain;base64,YQ==',
        download: 'x.txt',
      });
      click(target, { defaultPrevented: true });
      assert.equal(post.mock.callCount(), 0);
    });

    it('prevents default on data: download click', () => {
      const { target } = addAnchor({
        href: 'data:text/plain;base64,YQ==',
        download: 'x.txt',
      });
      const ev = click(target);
      assert.equal(ev.defaultPrevented, true);
      assert.equal(post.mock.callCount(), 1);
    });

    it('posts URL-only envelope for same-origin http download', () => {
      const { target } = addAnchor({
        href: 'https://download-harness.invalid/file.bin',
        download: 'file.bin',
      });
      // jsdom sets origin from href; ensure origin matches page
      const a = target.closest ? target.closest('a') : target;
      Object.defineProperty(a, 'origin', {
        configurable: true,
        get() {
          return 'https://download-harness.invalid';
        },
      });
      const ev = click(target);
      assert.equal(ev.defaultPrevented, true);
      assert.deepEqual(postedPackets(post)[0], {
        mwvDownload: true,
        url: 'https://download-harness.invalid/file.bin',
        fileName: 'file.bin',
      });
    });

    it('uses empty fileName for empty download attr on http', () => {
      const { a, target } = addAnchor({
        href: 'https://download-harness.invalid/named-from-url.bin',
        download: '',
      });
      Object.defineProperty(a, 'origin', {
        configurable: true,
        get() {
          return 'https://download-harness.invalid';
        },
      });
      click(target);
      assert.equal(postedPackets(post)[0].fileName, '');
    });

    it('does not intercept cross-origin http download', () => {
      const { a, target } = addAnchor({
        href: 'https://other.example/file.bin',
        download: 'file.bin',
      });
      Object.defineProperty(a, 'origin', {
        configurable: true,
        get() {
          return 'https://other.example';
        },
      });
      const ev = click(target);
      assert.equal(ev.defaultPrevented, false);
      assert.equal(post.mock.callCount(), 0);
    });

    it('ignores non-http/blob/data schemes', () => {
      for (const href of ['mailto:a@b.c', 'javascript:void(0)']) {
        post.mock.resetCalls();
        const { target } = addAnchor({ href, download: 'x' });
        click(target);
        assert.equal(post.mock.callCount(), 0, href);
      }
    });

    it('defaults blob/data fileName to download when attr empty', () => {
      // empty download attribute → getAttribute returns ""
      const { target } = addAnchor({
        href: 'data:text/plain;base64,YQ==',
        download: '',
      });
      click(target);
      // a.getAttribute('download') || 'download' → 'download'
      assert.equal(postedPackets(post)[0].fileName, 'download');
    });
  });
});
