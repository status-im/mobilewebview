'use strict';

const { describe, it, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const Module = require('module');
const { JSDOM } = require('jsdom');

const SOURCE_PATH = path.resolve(__dirname, '../../../src/js/media_playback_watcher.js');

function loadWatcher() {
  const mod = new Module(SOURCE_PATH);
  mod.filename = SOURCE_PATH;
  mod.paths = Module._nodeModulePaths(path.dirname(SOURCE_PATH));
  mod._compile(fs.readFileSync(SOURCE_PATH, 'utf8'), SOURCE_PATH);
  return mod.exports;
}

// jsdom media elements never play; drive their state by hand.
function makeMedia(doc, tag, state) {
  const el = doc.createElement(tag || 'video');
  const s = Object.assign({ paused: true, ended: false, muted: false, volume: 1 }, state);
  for (const key of Object.keys(s)) {
    Object.defineProperty(el, key, {
      configurable: true,
      get() { return s[key]; },
      set(v) { s[key] = v; },
    });
  }
  el.state = s;
  return el;
}

function fire(win, el, type) {
  el.dispatchEvent(new win.Event(type));
}

describe('media_playback_watcher', () => {
  let exports;
  let dom;
  let win;
  let doc;
  let reports;
  let watcher;

  beforeEach(() => {
    exports = loadWatcher();
    dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'https://media.invalid/' });
    win = dom.window;
    doc = win.document;
    reports = [];
    win.HTMLMediaElement.prototype.play = function () {};
    watcher = exports.createMediaPlaybackWatcher({
      window: win,
      report: (playing) => reports.push(playing),
    });
    watcher.attach();
  });

  afterEach(() => {
    win.close();
  });

  it('reports audible playback once, and its end', () => {
    const video = makeMedia(doc, 'video');
    doc.body.appendChild(video);

    video.state.paused = false;
    fire(win, video, 'playing');
    fire(win, video, 'timeupdate');
    fire(win, video, 'playing');
    assert.deepEqual(reports, [true]);

    video.state.paused = true;
    fire(win, video, 'pause');
    assert.deepEqual(reports, [true, false]);
  });

  it('does not count muted or silent playback', () => {
    const muted = makeMedia(doc, 'video', { muted: true });
    const silent = makeMedia(doc, 'audio', { volume: 0 });
    doc.body.append(muted, silent);

    muted.state.paused = false;
    silent.state.paused = false;
    fire(win, muted, 'playing');
    fire(win, silent, 'playing');
    assert.deepEqual(reports, []);

    muted.state.muted = false;
    fire(win, muted, 'volumechange');
    assert.deepEqual(reports, [true]);
  });

  it('stays audible while any element still plays', () => {
    const a = makeMedia(doc, 'audio');
    const b = makeMedia(doc, 'audio');
    doc.body.append(a, b);

    a.state.paused = false;
    b.state.paused = false;
    fire(win, a, 'playing');
    fire(win, b, 'playing');
    a.state.ended = true;
    fire(win, a, 'ended');
    assert.deepEqual(reports, [true]);

    b.state.paused = true;
    fire(win, b, 'pause');
    assert.deepEqual(reports, [true, false]);
  });

  it('follows elements that are not in the document', () => {
    const audio = makeMedia(doc, 'audio');
    audio.play();

    audio.state.paused = false;
    fire(win, audio, 'playing');
    assert.deepEqual(reports, [true]);
  });

  // Removing a playing element pauses it, but its events no longer reach the document.
  it('reports the end when a playing element is removed', () => {
    const video = makeMedia(doc, 'video');
    doc.body.appendChild(video);
    video.state.paused = false;
    fire(win, video, 'playing');

    video.remove();
    video.state.paused = true;
    fire(win, video, 'pause');

    assert.deepEqual(reports, [true, false]);
  });

  // Media events are not composed: they never leave a shadow root.
  it('follows elements inside a shadow root', () => {
    const host = doc.createElement('div');
    doc.body.appendChild(host);
    const shadow = host.attachShadow({ mode: 'open' });
    const audio = makeMedia(doc, 'audio');
    shadow.appendChild(audio);
    audio.play();

    audio.state.paused = false;
    fire(win, audio, 'playing');
    audio.state.paused = true;
    fire(win, audio, 'pause');

    assert.deepEqual(reports, [true, false]);
  });

  it('forgets elements that stopped and left the document', () => {
    for (let i = 0; i < 5; ++i) {
      const audio = makeMedia(doc, 'audio');
      audio.play();
      audio.state.paused = false;
      fire(win, audio, 'playing');
      audio.state.ended = true;
      fire(win, audio, 'ended');
    }
    const live = makeMedia(doc, 'audio');
    doc.body.appendChild(live);
    fire(win, live, 'play');

    assert.equal(watcher.trackedCount(), 1);
  });

  it('reports the end when the page goes away', () => {
    const video = makeMedia(doc, 'video');
    doc.body.appendChild(video);
    video.state.paused = false;
    fire(win, video, 'playing');

    win.dispatchEvent(new win.Event('pagehide'));

    assert.deepEqual(reports, [true, false]);
  });
});

describe('page-world bootstrap', () => {
  it('reports through NativeBridge.mediaPlaybackChanged and leaks nothing', () => {
    const dom = new JSDOM('<!doctype html><html><body></body></html>', {
      url: 'https://media.invalid/',
      runScripts: 'dangerously',
    });
    const reports = [];
    const ids = [];
    dom.window.NativeBridge = {
      mediaPlaybackChanged: (id, playing) => { ids.push(id); reports.push(playing); },
    };
    dom.window.eval(fs.readFileSync(SOURCE_PATH, 'utf8'));
    assert.equal(typeof dom.window.createMediaPlaybackWatcher, 'undefined');

    const video = makeMedia(dom.window.document, 'video');
    dom.window.document.body.appendChild(video);
    video.state.paused = false;
    video.dispatchEvent(new dom.window.Event('playing'));

    assert.deepEqual(reports, [true]);
    assert.equal(typeof ids[0], 'string');
    assert.ok(ids[0].length > 0, 'each document reports under its own id');
    dom.window.close();
  });

  it('installs once per document', () => {
    const dom = new JSDOM('<!doctype html><html><body></body></html>', {
      url: 'https://media.invalid/',
      runScripts: 'dangerously',
    });
    const reports = [];
    dom.window.NativeBridge = { mediaPlaybackChanged: (id, playing) => reports.push(playing) };
    const source = fs.readFileSync(SOURCE_PATH, 'utf8');
    dom.window.eval(source);
    dom.window.eval(source);

    const video = makeMedia(dom.window.document, 'video');
    dom.window.document.body.appendChild(video);
    video.state.paused = false;
    video.dispatchEvent(new dom.window.Event('playing'));
    video.state.paused = true;
    video.dispatchEvent(new dom.window.Event('pause'));

    assert.deepEqual(reports, [true, false]);
    dom.window.close();
  });
});
