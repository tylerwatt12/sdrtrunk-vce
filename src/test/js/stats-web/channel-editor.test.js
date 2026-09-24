'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const applicationPath = process.argv[2];
assert.ok(applicationPath, 'The app.js path is required.');
const application = fs.readFileSync(applicationPath, 'utf8');

function functionSource(signature) {
  const start = application.indexOf(signature);
  if (start < 0) throw new Error(`Missing ${signature}`);
  const openingBrace = application.indexOf('{', start + signature.length);
  let depth = 0;
  let quote = '';
  let escaped = false;
  for (let index = openingBrace; index < application.length; index += 1) {
    const character = application[index];
    if (escaped) {
      escaped = false;
      continue;
    }
    if (quote) {
      if (character === '\\') escaped = true;
      else if (character === quote) quote = '';
      continue;
    }
    if (character === '\'' || character === '"' || character === '`') {
      quote = character;
      continue;
    }
    if (character === '{') depth += 1;
    else if (character === '}' && --depth === 0) return application.slice(start, index + 1);
  }
  throw new Error(`Unterminated ${signature}`);
}

const context = {};
const squelchTunerSource = functionSource('function channelSquelchTuner(');
vm.createContext(context);
vm.runInContext(`
  ${functionSource('function channelEditorSectionId(value)')}
  ${functionSource('function channelEditorSectionPlan(sections)')}
  ${functionSource('function channelSquelchQuality(noise)')}
  ${functionSource('function channelSquelchNoise(quality)')}
`, context);

assert.equal(vm.runInContext('channelSquelchQuality(0.1)', context), 100);
assert.equal(vm.runInContext('channelSquelchQuality(0.5)', context), 0);
assert.equal(vm.runInContext('channelSquelchNoise(100)', context), 0.1);
assert.equal(vm.runInContext('channelSquelchNoise(0)', context), 0.5);
assert.match(application, /CHANNEL_SQUELCH_PATHS/);
assert.match(application, /Advanced diagnostics/);
assert.match(application, /Audio plays only while squelch is open/);
assert.match(application, /binaryFrameConnection\('channel_diagnostics'/);
assert.match(application, /\/squelch-preview/);
assert.doesNotMatch(squelchTunerSource, /node\('dd', '', value\)/,
  'Diagnostic output elements must be appended as nodes instead of stringified');
assert.match(squelchTunerSource, /detail\.append\(value\)/,
  'Diagnostic readouts must retain their live output elements');
assert.match(application, /action: 'RESTORE'/,
  'Closing the editor must restore runtime-only squelch preview settings');
const channelColumns = functionSource('function channelAdminColumns(');
assert.match(channelColumns, /event\.shiftKey/);
assert.match(channelColumns, /visibleIds\.slice\(Math\.min\(first, last\), Math\.max\(first, last\) \+ 1\)/);
assert.match(channelColumns, /state\.selectionAnchor = id/);
assert.doesNotMatch(channelColumns, /id: 'protocol'/,
  'The Channels table must show protocol as a group heading instead of a repeated column');
const channelCatalog = functionSource('async function renderModernChannelCatalog(');
assert.match(channelCatalog, /channel-catalog-admin-v2/);
assert.match(channelCatalog, /rowGroup: channelProtocolGroup, rowGroupNoun: 'channel'/);
assert.match(channelCatalog, /label: 'All statuses'/);
assert.match(channelCatalog, /Filter channels by status/);
assert.doesNotMatch(channelCatalog, /value: 'trunked'|value: 'conventional'/,
  'Protocol grouping replaces the mixed topology selector');
const protocolContext = { protocolFamily: (row) => row.protocol };
vm.createContext(protocolContext);
vm.runInContext(`
  ${functionSource('function channelProtocolGroup(row)')}
  ${functionSource('function channelProtocolOrder(left, right)')}
`, protocolContext);
assert.deepEqual(JSON.parse(vm.runInContext(
  "JSON.stringify(channelProtocolGroup({ protocol_label: 'P25 Phase 2' }))", protocolContext)),
{ key: 'p25 phase 2', label: 'P25 Phase 2' });
assert.deepEqual(JSON.parse(vm.runInContext(`JSON.stringify([
  { name: 'Zulu', protocol_label: 'P25 Phase 2' },
  { name: 'Bravo', protocol_label: 'NBFM' },
  { name: 'Alpha', protocol_label: 'NBFM' }
].sort(channelProtocolOrder).map((row) => row.name))`, protocolContext)), ['Alpha', 'Bravo', 'Zulu']);
const channelModal = functionSource('async function openChannelEditorModal(');
assert.match(channelModal, /action, configuration_ids: \[configurationId\]/,
  'The editor must start or stop only its own channel');
assert.match(channelModal, /modal\.isDirty\(\)/,
  'Start and Stop must not discard unsaved channel settings');
const stickyAssignments = [];
const stickyHeader = { getBoundingClientRect: () => ({ height: 52.1 }) };
let observedHeader = null;
class HeaderResizeObserver {
  constructor(callback) { this.callback = callback; }
  observe(value) {
    observedHeader = value;
    this.callback();
  }
}
const stickyContext = {
  document: {
    querySelector: (selector) => selector === '.app-header' ? stickyHeader : null,
    documentElement: { style: { setProperty: (name, value) => stickyAssignments.push([name, value]) } }
  },
  ResizeObserver: HeaderResizeObserver,
  window: { ResizeObserver: HeaderResizeObserver, addEventListener: () => assert.fail('Unexpected resize fallback') }
};
vm.runInNewContext(`(${functionSource('function installStickyHeaderOffset()')})()`, stickyContext);
assert.equal(observedHeader, stickyHeader);
assert.deepEqual(stickyAssignments.at(-1), ['--app-header-offset', '53px']);
assert.match(application, /\ninstallStickyHeaderOffset\(\);/,
  'The shared sticky offset must be installed once for every page');
assert.doesNotMatch(channelCatalog, /positionSelectionBar|new ResizeObserver/,
  'The Channels page must use the shared header offset instead of owning another observer');

const plan = JSON.parse(vm.runInContext(`JSON.stringify(channelEditorSectionPlan([
  { id: 'general', label: 'General', fields: [{ path: 'name', required: true }] },
  { id: 'source', label: 'Source', fields: [{ path: 'source.frequencies_hz', required: true }] },
  { id: 'protocol', label: 'Decoder', fields: [{ path: 'settings.squelch', required: false }] },
  { id: 'output', label: 'Logging & Recording', fields: [
    { path: 'event_logs' }, { path: 'recorders', required: false }
  ] }
]))`, context));

assert.deepEqual(plan.map(({ definition, id, advanced }) => ({
  section: definition.id, id, advanced
})), [
  { section: 'general', id: 'channel-editor-section-general', advanced: false },
  { section: 'source', id: 'channel-editor-section-source', advanced: false },
  { section: 'protocol', id: 'channel-editor-section-protocol', advanced: false },
  { section: 'output', id: 'channel-editor-section-output', advanced: true }
]);

assert.equal(vm.runInContext("channelEditorSectionId(' RF source / backup ')", context),
  'channel-editor-section-rf-source-backup');
assert.equal(vm.runInContext("channelEditorSectionId('')", context), 'channel-editor-section-section');

const requiredOutput = JSON.parse(vm.runInContext(`JSON.stringify(channelEditorSectionPlan([
  { id: 'output', label: 'Logging & Recording', fields: [{ path: 'recorders', required: true }] }
]))`, context));
assert.equal(requiredOutput[0].advanced, false,
  'An output section with a required field must remain open with the core form');

const emptyOutput = JSON.parse(vm.runInContext(`JSON.stringify(channelEditorSectionPlan([
  { id: 'output', label: 'Logging & Recording', fields: [] }
]))`, context));
assert.equal(emptyOutput[0].advanced, false,
  'An empty output section must not create a pointless disclosure');

const navigation = functionSource('function channelEditorSectionNavigation(panels, plan)');
assert.doesNotMatch(navigation, /aria-current/,
  'Section navigation must not claim a current location without scroll tracking');
assert.match(application, /node\('label', 'channel-map-field'\)/,
  'Frequency-map inputs need their own visible mobile label wrappers');
assert.match(application, /node\('span', 'channel-map-mobile-label', label\)/);
assert.match(application, /panel\.setAttribute\('aria-labelledby', labelId\)/,
  'Each editor fieldset must be named by its visible heading or disclosure summary');
assert.doesNotMatch(application,
  /panel\.append\(node\('legend', 'visually-hidden', sectionDefinition\.label\)\)/,
  'Editor sections must not announce duplicate hidden and visible labels');
