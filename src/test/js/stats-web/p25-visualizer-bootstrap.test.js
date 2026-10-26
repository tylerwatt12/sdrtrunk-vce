'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const source = fs.readFileSync(path.resolve(process.argv[2] || path.resolve(__dirname, '../../../../stats-web/assets/app.js')), 'utf8');
const bootstrap = source.slice(source.indexOf('async function renderP25Visualizer()'),
  source.indexOf('async function requestSpectrumSnapPresetDocument'));

test('the live scene mounts while the optional message catalog is still loading', { timeout: 1000 }, async () => {
  const controller = { element: {}, close() { throw new Error('The current scene must remain open.'); } };
  const pageConnections = new Set();
  let mounted = null, catalogRequested = false;
  const context = vm.createContext({
    p25VisualizerModulePromise: Promise.resolve({ createP25Visualizer: () => controller }),
    captureRenderContext: () => ({ signal: new AbortController().signal }),
    renderIsCurrent: () => true,
    p25VisualizerDependencies: () => ({}),
    loadActivityMessageTypes: () => { catalogRequested = true; return new Promise(() => {}); },
    pageConnections,
    beginPage: (_context, element) => { mounted = element; }
  });
  vm.runInContext(bootstrap, context);
  await context.renderP25Visualizer();
  assert.equal(catalogRequested, true);
  assert.equal(mounted, controller.element);
  assert.equal(pageConnections.has(controller), true);
});
