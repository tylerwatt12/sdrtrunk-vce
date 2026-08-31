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

const context = {
  number: (value) => String(value),
  identifierNumber: (value) => String(value ?? ''),
  aliasMatcherOption: (value) => ({
    label: String(value || '').toLowerCase().replace(/_/g, ' ').replace(/\b\w/g,
      (character) => character.toUpperCase())
  })
};
vm.createContext(context);
vm.runInContext(`
  ${functionSource('function aliasListId(row)')}
  ${functionSource('function mergedAliasLists(publicRows, adminRows = [])')}
  ${functionSource('function aliasOptionLimit(options, name)')}
  ${functionSource('function aliasCloneOptionValue(value, configured, cloning, optionsTruncated)')}
  ${functionSource('function aliasStreamOptionSelected(selected, configured, editing, optionsTruncated)')}
  ${functionSource('function reorderedAliasToneRows(rows, index, direction)')}
  ${functionSource('function fullScanListMembershipRequest(revision, operation, aliasListId = null)')}
  ${functionSource('function aliasMatcherSummary(matcher)')}
  globalThis.mergeLists = mergedAliasLists;
  globalThis.optionLimit = aliasOptionLimit;
  globalThis.cloneOptionValue = aliasCloneOptionValue;
  globalThis.streamOptionSelected = aliasStreamOptionSelected;
  globalThis.reorderTones = reorderedAliasToneRows;
  globalThis.fullMembershipRequest = fullScanListMembershipRequest;
  globalThis.matcherSummary = aliasMatcherSummary;
`, context);

const adminLists = Array.from({ length: 150 }, (_, offset) => {
  const id = offset + 1;
  return {
    alias_list_id: id,
    name: id === 2 ? 'List 10' : (id === 3 ? 'List 2' : `Admin ${String(id).padStart(3, '0')}`),
    family: 'P25',
    alias_count: id * 10,
    assigned_channel_count: id,
    unmatched_talkgroup_policy: { recordable: id % 2 === 0 }
  };
});
const publicLists = adminLists.slice(0, 100).map((row) => ({
  alias_list_id: row.alias_list_id,
  name: `Public ${row.alias_list_id}`,
  family: 'WRONG',
  alias_count: row.alias_list_id * 100,
  assigned_channel_count: row.alias_list_id * 2,
  unmatched_talkgroup_policy: { recordable: 'must not replace admin state' }
}));
const merged = context.mergeLists(publicLists, adminLists);
assert.equal(merged.length, 150, 'The complete administrator catalog must drive list visibility.');
const first = merged.find((row) => row.alias_list_id === 1);
assert.equal(first.name, 'Admin 001', 'Public paging data must not replace administrator-owned list identity.');
assert.equal(first.family, 'P25');
assert.deepEqual(JSON.parse(JSON.stringify(first.unmatched_talkgroup_policy)), { recordable: false });
assert.equal(first.alias_count, 100, 'Public count data should overlay the matching administrator row.');
assert.equal(first.assigned_channel_count, 2);
const beyondPublicPage = merged.find((row) => row.alias_list_id === 150);
assert.equal(beyondPublicPage.alias_count, 1500,
  'Counts supplied by the complete administrator catalog must survive beyond the public page.');
assert.ok(merged.findIndex((row) => row.name === 'List 2') < merged.findIndex((row) => row.name === 'List 10'),
  'Alias lists should retain natural name ordering.');
assert.deepEqual(JSON.parse(JSON.stringify(context.optionLimit({
  group_names: ['Fire', 'Police'], group_names_total: 700, group_names_truncated: true
}, 'group_names'))), { shown: 2, total: 700, truncated: true });
assert.deepEqual(JSON.parse(JSON.stringify(context.optionLimit({ icon_names: ['Car'] }, 'icon_names'))),
  { shown: 1, total: 1, truncated: false });
assert.equal(context.cloneOptionValue('Rare icon', false, true, true), 'Rare icon',
  'A clone must preserve a valid source icon omitted by bounded suggestions.');
assert.equal(context.cloneOptionValue('Deleted icon', false, true, false), '',
  'A clone should not preserve an icon confirmed absent from the complete options response.');
assert.equal(context.streamOptionSelected(true, false, false, true), true,
  'A clone must preserve a valid stream omitted by bounded suggestions.');
assert.equal(context.streamOptionSelected(true, false, false, false), false,
  'A clone should not preserve a stream confirmed absent from the complete options response.');

const firstTone = { tone: 'A' };
const secondTone = { tone: 'B' };
const thirdTone = { tone: 'C' };
const tones = [firstTone, secondTone, thirdTone];
assert.deepEqual(Array.from(context.reorderTones(tones, 1, -1), (row) => row.tone), ['B', 'A', 'C']);
assert.deepEqual(Array.from(context.reorderTones(tones, 1, 1), (row) => row.tone), ['A', 'C', 'B']);
assert.deepEqual(Array.from(context.reorderTones(tones, 0, -1), (row) => row.tone), ['A', 'B', 'C']);
assert.deepEqual(tones.map((row) => row.tone), ['A', 'B', 'C'], 'Reordering must not mutate the input array.');

assert.deepEqual(JSON.parse(JSON.stringify(context.fullMembershipRequest(7, 'add', 42))), {
  revision: 7, operation: 'add', alias_scope: { alias_list_id: 42 }
});
assert.deepEqual(JSON.parse(JSON.stringify(context.fullMembershipRequest(8, 'remove'))), {
  revision: 8, operation: 'remove', alias_scope: {}
});
assert.equal(context.matcherSummary({ type: 'talkgroup_range', protocol: 'P25', minimum: 10, maximum: 20 }),
  'Talkgroup Range · P25 · 10–20');
assert.equal(context.matcherSummary({ type: 'tone_sequence', tones: [
  { tone: 'DTMF_1', duration: 2 }, { tone: 'DTMF_2', duration: 3 }
] }), 'Tone Sequence · DTMF_1 ×2 → DTMF_2 ×3');
