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
  URLSearchParams,
  Uint8Array,
  encodeURIComponent,
  window: {
    crypto: {
      getRandomValues: (bytes) => {
        bytes.forEach((_, index) => { bytes[index] = index; });
        return bytes;
      }
    },
    history: { replaceState: () => {} }
  },
  aliasMatcherOption: (value) => ({
    label: String(value || '').toLowerCase().replace(/_/g, ' ').replace(/\b\w/g,
      (character) => character.toUpperCase())
  })
};
vm.createContext(context);
vm.runInContext(`
  const ALIAS_BULK_SELECTION_LIMIT = 10_000;
  const ALIAS_BULK_REQUEST_TIMEOUT_MS = 60_000;
  const ALIAS_TRANSFER_EXPORT_READY_COOKIE_PREFIX = 'sdrtrunk_alias_export_ready_';
  const ALIAS_CREATE_ROUTE_KEYS = [];
  let route = new URLSearchParams('');
  let aliasEditorSelection = new Set();
  let aliasEditorSelectionScope = null;
  let aliasEditorSelectionRequest = 0;
  let aliasEditorLastSelectionIndex = null;
  ${functionSource('function aliasListId(row)')}
  ${functionSource('function mergedAliasLists(publicRows, adminRows = [])')}
  ${functionSource('function aliasOptionLimit(options, name)')}
  ${functionSource('function aliasCloneOptionValue(value, configured, cloning, optionsTruncated)')}
  ${functionSource('function aliasStreamOptionSelected(selected, configured, editing, optionsTruncated)')}
  ${functionSource('function aliasEditorDefaultOrder(view)')}
  ${functionSource('function aliasTransferListDefaults(selectedList, options = {})')}
  ${functionSource('function aliasTransferListDefaultsSummary(defaults)')}
  ${functionSource('function aliasTransferAssignmentNames(enabled, selectedNames = [], exactNames = [])')}
  ${functionSource('function aliasTransferDetectedFormat(csv)')}
  ${functionSource("function aliasTransferExportHref(listId, scope = 'all')")}
  ${functionSource('function aliasTransferExportToken()')}
  ${functionSource('function aliasTransferExportDownloadHref(listId, scope, token)')}
  ${functionSource('function aliasTransferExportIsReady(cookieHeader, token)')}
  ${functionSource("function exportCsvFileName(response, fallback = 'export.csv')")}
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
  globalThis.editorDefaultOrder = aliasEditorDefaultOrder;
  globalThis.transferListDefaults = aliasTransferListDefaults;
  globalThis.transferListDefaultsSummary = aliasTransferListDefaultsSummary;
  globalThis.transferAssignmentNames = aliasTransferAssignmentNames;
  globalThis.transferDetectedFormat = aliasTransferDetectedFormat;
  globalThis.transferExportHref = aliasTransferExportHref;
  globalThis.transferExportToken = aliasTransferExportToken;
  globalThis.transferExportDownloadHref = aliasTransferExportDownloadHref;
  globalThis.transferExportIsReady = aliasTransferExportIsReady;
  globalThis.exportFileName = exportCsvFileName;
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
assert.deepEqual(JSON.parse(JSON.stringify(context.editorDefaultOrder('activity'))),
  { sort: 'logical_call_count', direction: 'desc' },
  'The Activity view must initially rank aliases by highest call count.');
assert.deepEqual(JSON.parse(JSON.stringify(context.editorDefaultOrder('configure'))),
  { sort: 'name', direction: 'asc' },
  'Configuration views must retain their alphabetical default.');
const aliasRenderer = functionSource('async function renderAliases()');
const aliasFilterToolbar = functionSource('function aliasEditorFilterToolbar(listResponse, options = null)');
const aliasDiscoverToolbar = functionSource('function observedGroupIdentityToolbar(selectedList)');
const aliasExportLink = functionSource("function exportCsvLink(dataset, context = {}, label = 'Export CSV', options = {})");
const aliasDetailLink = functionSource('function aliasDetailLink(row)');
const aliasMutationFinisher = functionSource('async function finishAliasMutation(modal, result, routeChanges = {})');
assert.match(aliasRenderer, /sort: route\.get\('sort'\) \|\| defaultOrder\.sort/,
  'Explicit routed sorting must take precedence over the view default.');
assert.match(aliasRenderer, /defaultSort: defaultOrder\.sort/,
  'The table indicator must match the order requested from the server.');
assert.match(aliasRenderer, /admin\/alias-lists\?include_counts=false/,
  'Activity renders must not recount the complete in-memory Alias model.');
assert.match(aliasRenderer, /apiPage\('\/api\/v1\/alias-lists\?limit=500'\)/,
  'The database-backed catalog must supply counts for every Alias List the administrator catalog can return.');
assert.match(aliasRenderer, /optionParameters\.include_group_names = false/,
  'Activity renders must not rebuild global group-name suggestions.');
assert.match(aliasFilterToolbar, /selectFilter\('Evidence', 'evidence'/,
  'The Activity filters must expose the server-side evidence state filter.');
assert.match(aliasFilterToolbar, /covered_no_evidence/);
assert.match(aliasFilterToolbar, /not_collected/);
assert.match(aliasFilterToolbar, /unsupported/);
assert.match(aliasFilterToolbar, /aliasEditorFilterInput\('q'/,
  'Alias Editor filter searches must use the shared input control styling.');
assert.match(aliasFilterToolbar, /aliasEditorFilterInput\('group'/,
  'Alias Editor group filters must use the shared input control styling.');
assert.match(aliasFilterToolbar, /aliasEditorFilterInput\('', aliasLocalDateTimeValue/g,
  'Alias Editor date filters must use the shared input control styling.');
assert.match(aliasFilterToolbar, /ui-button ui-button-primary/,
  'Alias Editor filter actions must use the shared primary button styling.');
assert.match(aliasDiscoverToolbar, /aliasEditorFilterInput\('q'/,
  'Alias Editor discovery searches must use the shared input control styling.');
assert.match(aliasDiscoverToolbar, /ui-button ui-button-primary/,
  'Alias Editor discovery actions must use the shared primary button styling.');
assert.match(aliasExportLink, /options\.loading/,
  'Alias table exports must support an explicit loading state.');
assert.match(aliasExportLink, /new AbortController\(\)/,
  'Report downloads must have a cancellable request lifecycle.');
assert.match(aliasExportLink, /await fetch\(target/,
  'Report downloads must wait for the export response instead of handing off immediately to the browser.');
assert.match(aliasExportLink, /await response\.blob\(\)/,
  'Report downloads must wait until the complete response body is received.');
assert.match(aliasExportLink, /URL\.createObjectURL/,
  'Completed report data must be handed to a separate browser download link.');
assert.match(aliasExportLink, /activeController\.abort\(\)/,
  'A second report-button click must cancel an active report request.');
assert.doesNotMatch(aliasExportLink, /loadingTimeoutMs/,
  'Report loading must not depend on an arbitrary timeout.');
assert.equal(context.exportFileName({ headers: { get: () =>
  "attachment; filename*=UTF-8''alias%20report.csv" } }, 'fallback.csv'), 'alias report.csv');
assert.equal(context.exportFileName({ headers: { get: () =>
  'attachment; filename="alias/report.csv"' } }, 'fallback.csv'), 'alias_report.csv');
assert.match(aliasDetailLink, /openAliasEditorFromRow\(row\)/,
  'Alias table links must open the editor in place.');
assert.match(aliasRenderer, /aliasEditorPageController = renderObservedGroupIdentities/,
  'Observed-group creation must retain an in-place Alias Editor controller.');
assert.match(aliasRenderer, /const pageController = \{/,
  'Alias list and scan-list views must expose refresh controllers.');
assert.match(aliasMutationFinisher, /if \(!refreshed\) await render\(\)/,
  'Alias mutations should rerender only when an in-place refresh is not safe.');
assert.match(aliasMutationFinisher, /if \(aliasEditorContext\?\.scanListScope\) delete mutationRouteChanges\.list/,
  'Scan-list mutations must not leave a stale alias-list route behind.');
assert.match(aliasMutationFinisher, /resetAliasEditorSelection\(\);\s+if \(localRefresh\)/s,
  'Alias mutations must clear selection before an in-place refresh redraws the toolbar.');

vm.runInContext(`
  function closeReadOnlyModal() { return true; }
  function currentHref() { return '/?view=aliases'; }
  async function render() {}
  let mutationRefreshSelection = null;
  ${aliasMutationFinisher}
  globalThis.runMutationRefreshProbe = async () => {
    aliasEditorContext = { selectedList: { alias_list_id: 7 }, scanListScope: null, revision: 1 };
    aliasEditorPageController = {
      isCurrent: () => true,
      canRefreshMutation: () => true,
      refresh: async () => {
        mutationRefreshSelection = [...aliasEditorSelection];
        return true;
      }
    };
    aliasEditorSelection = new Set([11, 12]);
    await finishAliasMutation({ setDirty: () => {} }, { revision: 2 });
    return { refreshedSelection: mutationRefreshSelection, remainingSelection: [...aliasEditorSelection] };
  };
`, context);

const transferDefaults = context.transferListDefaults({ unmatched_talkgroup_policy: {
  recordable: true, scan_list_ids: [2], broadcast_configuration_ids: ['stream-1']
} }, {
  scan_lists: [{ id: 1, name: 'Primary' }, { id: 2, name: 'Dispatch' }],
  streams: [{ configuration_id: 'stream-1', name: 'Provider' }]
});
assert.deepEqual(JSON.parse(JSON.stringify(transferDefaults)), {
  recordable: true, scanLists: ['Dispatch'], streams: ['Provider']
}, 'RadioReference imports should present the selected alias list defaults by configured name.');
assert.equal(context.transferListDefaultsSummary(transferDefaults),
  'Recording: On · Scan lists: Dispatch · Streaming: Provider');
assert.equal(context.transferAssignmentNames(false, ['Shown'], ['Exact']), null);
assert.deepEqual(Array.from(context.transferAssignmentNames(true, [], [])), [],
  'An enabled empty override must explicitly select no destinations.');
assert.deepEqual(Array.from(context.transferAssignmentNames(true, ['Shown', 'Duplicate'],
  ['Exact', 'Duplicate'])), ['Shown', 'Duplicate', 'Exact'],
  'Displayed and exact-name destinations must be unioned without duplicates.');
assert.equal(context.transferDetectedFormat(
  '\uFEFFDecimal,Hex,Alpha Tag,Mode,Description,Tag,Category\n1,1,Dispatch,D,,,Fire'), 'RADIOREFERENCE');
assert.equal(context.transferDetectedFormat(
  'format_version,alias_list,name,description,group,color,icon,matcher_type,protocol,value'), 'VCE');
assert.equal(context.transferDetectedFormat('name,description\nDispatch,County'), '',
  'Unknown CSV headers must require an explicit format choice.');
assert.equal(context.transferExportToken(), '000102030405060708090a0b0c0d0e0f',
  'Each download must use a 128-bit lowercase hexadecimal browser nonce.');
assert.equal(context.transferExportDownloadHref(7, 'all', '0123456789abcdef0123456789abcdef'),
  '/api/v1/admin/alias-lists/7/transfer?export_token=0123456789abcdef0123456789abcdef');
assert.equal(context.transferExportDownloadHref(7, 'filtered', '0123456789abcdef0123456789abcdef'),
  '/api/v1/exports/alias-list.csv?list=7&scope=filtered&export_token=0123456789abcdef0123456789abcdef');
assert.equal(context.transferExportIsReady(
  'other=value; sdrtrunk_alias_export_ready_0123456789abcdef0123456789abcdef=1',
  '0123456789abcdef0123456789abcdef'), true);
assert.equal(context.transferExportIsReady(
  'sdrtrunk_alias_export_ready_1123456789abcdef0123456789abcdef=1',
  '0123456789abcdef0123456789abcdef'), false,
  'A stale ready marker must not complete a newer export attempt.');
const concurrentReadyCookies =
  'sdrtrunk_alias_export_ready_0123456789abcdef0123456789abcdef=1; ' +
  'sdrtrunk_alias_export_ready_fedcba9876543210fedcba9876543210=1';
assert.equal(context.transferExportIsReady(concurrentReadyCookies,
  '0123456789abcdef0123456789abcdef'), true);
assert.equal(context.transferExportIsReady(concurrentReadyCookies,
  'fedcba9876543210fedcba9876543210'), true,
  'Concurrent all-list and filtered downloads must keep independent ready markers.');

const transferModal = functionSource("function openAliasTransferModal(selectedList, action = 'Import')");
const transferExportHref = functionSource("function aliasTransferExportHref(listId, scope = 'all')");
assert.match(transferExportHref, /admin\/alias-lists\/\$\{listId\}\/transfer/,
  'The established administrator-only all-list export route must remain compatible.');
assert.match(transferExportHref, /api\/v1\/exports\/alias-list\.csv/);
assert.match(transferExportHref, /last_activity_after/);
assert.match(transferExportHref, /scan_list_id/);
assert.doesNotMatch(transferExportHref, /limit|offset/,
  'Filtered transfer exports must not inherit the current browser page.');
assert.doesNotMatch(transferExportHref, /route\.get\('sort'\)|route\.get\('direction'\)/,
  'Importable exports use durable alias-ID order instead of presentation order.');
assert.match(transferModal, /options\.streams_truncated === true/);
assert.match(transferModal, /Add an exact configured name not shown/);
assert.match(transferModal, /Up to 500 destinations are listed/);
assert.match(transferModal, /Drop a CSV file here/);
assert.match(transferModal, /Add new aliases and update matches/);
assert.match(transferModal, /Replace this list’s aliases/);
assert.match(transferModal, /Download CSV/);
assert.match(transferModal, /All aliases in this Alias List/);
assert.match(transferModal, /Current filtered results/);
assert.match(transferModal, /not only the visible page/);
assert.match(transferModal, /destinationSummary\.append\(/);
assert.match(transferModal, /step\.append\(node\('span'/);
assert.match(transferModal, /exportSummary\.append\(/);
assert.doesNotMatch(transferModal, /node\('div', 'alias-transfer-destination',\s*node\(/);
assert.doesNotMatch(transferModal, /node\('li', '', node\(/);
assert.doesNotMatch(transferModal, /node\('div', 'alias-transfer-export-summary',\s*node\(/);
assert.match(transferModal, /preview\.counts\.deleted > 0/);
assert.match(transferModal, /exportFrame/);
assert.match(transferModal, /Preparing the complete CSV/);
assert.match(transferModal, /aliasTransferExportDownloadHref/);
assert.match(transferModal, /aliasTransferExportIsReady\(document\.cookie, token\)/);
assert.match(transferModal, /Download started\. The browser verifies the complete file length/);
assert.match(transferModal, /sign-in or export access may have changed/);
assert.match(transferModal, /window\.clearInterval\(exportStartPoll\)/,
  'Closing the modal must stop the ready-marker poll.');
assert.doesNotMatch(transferModal, /fetch\(endpoint/);
assert.doesNotMatch(transferModal, /createObjectURL/);
assert.match(transferModal, /timeoutMs: ALIAS_BULK_REQUEST_TIMEOUT_MS/g);

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

async function verifyAdditionalAliasLifecycles() {
  await verifyAsyncSelectionLifecycle();
  const result = await context.runMutationRefreshProbe();
  assert.deepEqual(Array.from(result.refreshedSelection), [],
    'An in-place mutation refresh must see a cleared selection before it redraws the toolbar.');
  assert.deepEqual(Array.from(result.remainingSelection), [],
    'A completed mutation must leave the Alias Editor selection empty.');
}

verifyAdditionalAliasLifecycles().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
