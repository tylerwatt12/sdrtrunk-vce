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
  const ALIAS_BULK_SELECTION_LIMIT = 10_000;
  const ALIAS_BULK_REQUEST_TIMEOUT_MS = 60_000;
  let aliasEditorSelection = new Set();
  let aliasEditorSelectionScope = null;
  let aliasEditorSelectionRequest = 0;
  let aliasEditorLastSelectionIndex = null;
  ${functionSource('function aliasListId(row)')}
  ${functionSource('function mergedAliasLists(publicRows, adminRows = [])')}
  ${functionSource('function aliasOptionLimit(options, name)')}
  ${functionSource('function aliasCloneOptionValue(value, configured, cloning, optionsTruncated)')}
  ${functionSource('function aliasStreamOptionSelected(selected, configured, editing, optionsTruncated)')}
  ${functionSource('function reorderedAliasToneRows(rows, index, direction)')}
  ${functionSource('function fullScanListMembershipRequest(revision, operation, aliasListId = null)')}
  ${functionSource('function aliasMatcherSummary(matcher)')}
  ${functionSource('function aliasSelectionScopeKey(kind, filters = {})')}
  ${functionSource('function completeAliasSelection(response, maximum = ALIAS_BULK_SELECTION_LIMIT)')}
  ${functionSource('function extendedAliasSelection(selection, additions, maximum = ALIAS_BULK_SELECTION_LIMIT)')}
  ${functionSource('function validatedAliasSelectionIds(selection, maximum = ALIAS_BULK_SELECTION_LIMIT)')}
  ${functionSource('function resetAliasEditorSelection(scope = null)')}
  ${functionSource('function synchronizeAliasEditorSelectionScope(scope)')}
  ${functionSource('function clearInactiveAliasSelection(activeTable)')}
  ${functionSource('function clearAliasSelectionOutsideEditor(view)')}
  ${functionSource('async function selectAllMatchingAliases(filters, scope, button, onSelectionChange)')}
  globalThis.mergeLists = mergedAliasLists;
  globalThis.optionLimit = aliasOptionLimit;
  globalThis.cloneOptionValue = aliasCloneOptionValue;
  globalThis.streamOptionSelected = aliasStreamOptionSelected;
  globalThis.reorderTones = reorderedAliasToneRows;
  globalThis.fullMembershipRequest = fullScanListMembershipRequest;
  globalThis.matcherSummary = aliasMatcherSummary;
  globalThis.selectionScopeKey = aliasSelectionScopeKey;
  globalThis.completeSelection = completeAliasSelection;
  globalThis.extendSelection = extendedAliasSelection;
  globalThis.validatedSelectionIds = validatedAliasSelectionIds;
  globalThis.resetSelection = resetAliasEditorSelection;
  globalThis.synchronizeSelectionScope = synchronizeAliasEditorSelectionScope;
  globalThis.clearInactiveSelection = clearInactiveAliasSelection;
  globalThis.clearSelectionOutsideEditor = clearAliasSelectionOutsideEditor;
  globalThis.selectAllMatching = selectAllMatchingAliases;
  globalThis.seedSelection = (ids, scope, request = 0) => {
    aliasEditorSelection = new Set(ids);
    aliasEditorSelectionScope = scope;
    aliasEditorSelectionRequest = request;
    aliasEditorLastSelectionIndex = null;
  };
  globalThis.selectionState = () => ({ ids: [...aliasEditorSelection], scope: aliasEditorSelectionScope,
    request: aliasEditorSelectionRequest });
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

const selectionFilters = {
  list: 42, type: 'talkgroup', matcher: 'talkgroup', group: 'Dispatch', scan_list_id: 7,
  record: 'true', stream: 'Primary', q: 'county', evidence: 'observed', use: 'used',
  last_activity_before: 2000, last_activity_after: 1000
};
const selectionScope = context.selectionScopeKey('alias-list', selectionFilters);
assert.equal(selectionScope, context.selectionScopeKey('alias-list', {
  ...selectionFilters, offset: 100, sort: 'calls', direction: 'desc', alias: 99, view: 'activity'
}), 'Pagination, sorting, modal routes, and view tabs must not change a matching selection scope.');
assert.notEqual(selectionScope, context.selectionScopeKey('alias-list', { ...selectionFilters, q: 'fire' }));
assert.notEqual(selectionScope, context.selectionScopeKey('alias-list', { ...selectionFilters, list: 43 }));
assert.notEqual(selectionScope, context.selectionScopeKey('scan-list-members', selectionFilters));

assert.deepEqual(Array.from(context.completeSelection({
  alias_ids: [11, 12], count: 2
})), [11, 12]);
assert.throws(() => context.completeSelection({
  alias_ids: [11, 11], count: 2
}), /invalid or duplicate Alias IDs/);
assert.throws(() => context.completeSelection({
  alias_ids: Array.from({ length: 10_001 }, (_, index) => index + 1), count: 10_001
}), /limited to 10000 aliases/);
assert.throws(() => context.completeSelection({ alias_ids: [11], count: 2 }), /invalid selection response/);
assert.deepEqual(Array.from(context.validatedSelectionIds(new Set([21, 22]))), [21, 22]);
assert.throws(() => context.validatedSelectionIds(
  new Set(Array.from({ length: 10_001 }, (_, index) => index + 1))), /no more than 10000 aliases/);

const priorSelection = new Set([1, 2]);
assert.throws(() => context.extendSelection(priorSelection,
  Array.from({ length: 9_999 }, (_, index) => index + 3)), /previous selection was kept/);
assert.deepEqual([...priorSelection], [1, 2], 'An overflowing page add must leave the previous selection unchanged.');

context.seedSelection([31, 32], selectionScope, 4);
context.synchronizeSelectionScope(selectionScope);
assert.deepEqual(JSON.parse(JSON.stringify(context.selectionState())), {
  ids: [31, 32], scope: selectionScope, request: 5
}, 'Rendering another page in the same scope must retain its selection.');
context.synchronizeSelectionScope(context.selectionScopeKey('alias-list', { ...selectionFilters, group: 'Fire' }));
assert.deepEqual(Array.from(context.selectionState().ids), [], 'Changing a matching filter must clear the selection.');
context.seedSelection([41, 42], selectionScope, 0);
context.clearSelectionOutsideEditor('aliases');
assert.deepEqual(Array.from(context.selectionState().ids), [41, 42],
  'Rendering the Alias editor itself must preserve a same-scope selection.');
context.clearInactiveSelection(true);
assert.deepEqual(Array.from(context.selectionState().ids), [41, 42],
  'An active Alias table must preserve its selection.');
context.clearInactiveSelection(false);
assert.deepEqual(Array.from(context.selectionState().ids), [],
  'An Alias route without a selectable table must clear destructive bulk targets.');
context.seedSelection([41, 42], selectionScope, 0);
context.clearSelectionOutsideEditor('dashboard');
assert.deepEqual(Array.from(context.selectionState().ids), [],
  'Leaving the Alias editor must clear destructive bulk targets.');

async function verifyAsyncSelectionLifecycle() {
  const button = () => ({ textContent: 'Select All Matching', disabled: false, isConnected: true });
  const messages = [];
  let selectionRequest;
  context.seedSelection([99], selectionScope, 0);
  context.api = async (path, parameters, options) => {
    selectionRequest = { path, parameters, options };
    return { alias_ids: [11, 12], count: 2 };
  };
  await context.selectAllMatching(selectionFilters, selectionScope, button(), (...message) => messages.push(message));
  assert.deepEqual(Array.from(context.selectionState().ids), [11, 12]);
  assert.match(messages.at(-1)[0], /Selected all 2 matching aliases/);
  assert.equal(selectionRequest.path, '/api/v1/aliases/ids');
  assert.deepEqual(JSON.parse(JSON.stringify(selectionRequest.parameters)), selectionFilters,
    'Select All must send only the filters defining the visible result set.');
  assert.equal(selectionRequest.options.timeoutMs, 60_000);

  messages.length = 0;
  context.seedSelection([99], selectionScope, 0);
  context.api = async () => { throw new Error('More than 10000 aliases match. Narrow the filters, then try again.'); };
  await context.selectAllMatching(selectionFilters, selectionScope, button(), (...message) => messages.push(message));
  assert.deepEqual(Array.from(context.selectionState().ids), [99],
    'An overflowing Select All response must preserve the prior selection.');
  assert.match(messages.at(-1)[0], /More than 10000 aliases match/);

  messages.length = 0;
  let resolveSelection;
  context.seedSelection([88], selectionScope, 0);
  context.api = () => new Promise((resolve) => { resolveSelection = resolve; });
  const pending = context.selectAllMatching(selectionFilters, selectionScope, button(),
    (...message) => messages.push(message));
  context.resetSelection(selectionScope);
  resolveSelection({ alias_ids: [77], count: 1 });
  await pending;
  assert.deepEqual(Array.from(context.selectionState().ids), [],
    'A late response must not replace a selection cleared while it was loading.');
  assert.deepEqual(messages, [], 'A stale Select All response must not announce success or failure.');
}

verifyAsyncSelectionLifecycle().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
