'use strict';

const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');

const SOURCE = fs.readFileSync(path.join(__dirname, '../../../src/js/pdf_viewer.js'), 'utf8');
const BASE = 'https://pdf.mobilewebview.invalid/tok/';

// Two pages the way PdfViewer.html lays them out.
function viewer({ withText, runs = {} }) {
  const dom = new JSDOM(
    '<!doctype html><html><body>'
      + '<div class="page" data-page="0" data-w="600" data-h="800"><img><div class="text"></div></div>'
      + '<div class="page" data-page="1" data-w="600" data-h="800"><img><div class="text"></div></div>'
      + '</body></html>',
    { runScripts: 'outside-only', pretendToBeVisual: true });
  const win = dom.window;
  Object.defineProperty(win.document.documentElement, 'clientWidth', { value: 400 });
  win.devicePixelRatio = 2;
  win.HTMLCanvasElement.prototype.getContext = () => ({ font: '', measureText: (t) => ({ width: t.length * 10 }) });
  win.IntersectionObserver = class { observe() {} disconnect() {} };
  const fetched = [];
  win.fetch = (url) => {
    fetched.push(url);
    const page = +url.slice(BASE.length).split('/')[0];
    return Promise.resolve({ ok: true, json: () => Promise.resolve(runs[page] || []) });
  };
  win.eval(SOURCE
    .replace('%BASE%', JSON.stringify(BASE))
    .replace('%MAX_WIDTH%', '700')
    .replace('%WITH_TEXT%', String(withText)));
  return { doc: win.document, fetched };
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

describe('pdf_viewer.js', () => {
  it('asks each page for an image at the device width, within the limit', () => {
    const { doc, fetched } = viewer({ withText: false });
    const imgs = [...doc.querySelectorAll('.page img')].map((img) => img.src);
    assert.deepEqual(imgs, [BASE + '0?w=700', BASE + '1?w=700']);
    assert.deepEqual(fetched, []);
  });

  it('lays each run of text over its place on the page', async () => {
    const { doc, fetched } = viewer({
      withText: true,
      runs: { 0: [[60, 80, 360, 100, 'Hello', '\n']] },
    });
    await settle();
    await settle();
    assert.deepEqual(fetched.sort(), [BASE + '0/text', BASE + '1/text']);
    const span = doc.querySelector('.page[data-page="0"] .text span');
    assert.equal(span.textContent, 'Hello');
    assert.equal(span.style.left, '10%');
    assert.equal(span.style.top, '10%');
    assert.equal(span.style.transform, 'scaleX(6)');
    assert.equal(doc.querySelectorAll('.page[data-page="0"] .text br').length, 2);
  });
});
