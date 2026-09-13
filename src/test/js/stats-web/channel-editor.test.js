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
vm.createContext(context);
vm.runInContext(`
  ${functionSource('function channelEditorSectionId(value)')}
  ${functionSource('function channelEditorSectionPlan(sections)')}
`, context);

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
