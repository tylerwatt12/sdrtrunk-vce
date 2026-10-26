'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function main() {
  const settings = await import(pathToFileURL(path.resolve(__dirname,
    '../../../../stats-web/assets/features/network-visualizer/settings.js')).href);
  assert.deepEqual(settings.defaultP25EventSettings().call, { highlight: true, autoZoom: false });
  const optedOut = settings.normalizeP25EventSettings({ call: { highlight: false, autoZoom: false } });
  assert.equal(settings.enabledP25EventCategories(optedOut, 'highlight').has('call'), false);
  assert.equal(settings.enabledP25EventCategories(optedOut, 'autoZoom').has('call'), false);
  assert.deepEqual(optedOut.call, { highlight: false, autoZoom: false }, 'explicit saved opt-outs survive');
  assert.deepEqual(settings.normalizeP25EventSettings({ call: { highlight: false, autoZoom: true } }).call,
    { highlight: false, autoZoom: true }, 'independent saved highlight and camera choices survive');
  console.log('P25 visualizer settings contracts passed');
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
