'use strict';

const fs = require('fs');
const path = require('path');
const Module = require('module');

const SOURCE_PATH = path.resolve(
  __dirname,
  '../../src/js/inline_download_interceptor.js'
);

function readRawSource() {
  return fs.readFileSync(SOURCE_PATH, 'utf8');
}

/**
 * Load the interceptor module after substituting %MAX_INLINE_BYTES%.
 * Uses Module._compile so we never write a temp file.
 */
function loadInterceptor(maxDecodedBytes) {
  const source = readRawSource().replace(
    /%MAX_INLINE_BYTES%/g,
    String(maxDecodedBytes)
  );
  const mod = new Module(SOURCE_PATH);
  mod.filename = SOURCE_PATH;
  mod.paths = Module._nodeModulePaths(path.dirname(SOURCE_PATH));
  mod._compile(source, SOURCE_PATH);
  return mod.exports;
}

module.exports = {
  SOURCE_PATH,
  readRawSource,
  loadInterceptor,
};
