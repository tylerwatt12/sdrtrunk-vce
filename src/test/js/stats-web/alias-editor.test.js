'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const applicationPath = process.argv[2];
assert.ok(applicationPath, 'The app.js path is required.');
const application = fs.readFileSync(applicationPath, 'utf8');
assert.match(application, /api\('\/api\/v1\/listen\/map\/icons'/,
  'Alias icon previews must use the receiver allowlisted standard-icon catalog.');
assert.equal((application.match(/iconField\.append\(aliasIconPreview\(icon\)\)/g) || []).length, 2,
  'Both single and bulk Alias appearance editors must show a standard-icon preview.');
assert.match(application, /catalog\.get\(name\) \|\| 'no-icon'/,
  'Unknown or custom Alias icons must use the bundled no-icon preview.');

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
  FormData,
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
  const ALIAS_LIST_NAME_MAX_LENGTH = 128;
  const ALIAS_BULK_SELECTION_LIMIT = 10_000;
  const ALIAS_BULK_REQUEST_TIMEOUT_MS = 60_000;
  const ALIAS_TRANSFER_EXPORT_READY_COOKIE_PREFIX = 'sdrtrunk_alias_export_ready_';
  const ALIAS_CREATE_ROUTE_KEYS = [];
  let route = new URLSearchParams('');
  function href(view, values = {}) {
    const parameters = new URLSearchParams({ view });
    Object.entries(values).forEach(([key, value]) => {
      if (value !== null && value !== undefined && value !== '') parameters.set(key, String(value));
    });
    return '/?' + parameters.toString();
  }
  function aliasAdminAllowed() { return true; }
  let aliasEditorSelection = new Set();
  let aliasEditorSelectionScope = null;
  let aliasEditorSelectionRequest = 0;
  let aliasEditorLastSelectionIndex = null;
  ${functionSource('function aliasListId(row)')}
  ${functionSource('function aliasListRenamePayload(selectedList, value)')}
  ${functionSource('function canonicalConfigurationId(value)')}
  ${functionSource('function aliasEditorLists(configurationRows, metadataRows = [])')}
  ${functionSource('function aliasListChannelUsage(channels)')}
  ${functionSource('function aliasListChannelPreview(row, usage)')}
  ${functionSource('function aliasOptionLimit(options, name)')}
  ${functionSource('function aliasCloneOptionValue(value, configured, cloning, optionsTruncated)')}
  ${functionSource('function aliasStreamOptionSelected(selected, configured, editing, optionsTruncated)')}
  ${functionSource('function aliasEditorDefaultOrder(view)')}
  ${functionSource('function aliasEditorOrder(view, query = currentAliasRoute())')}
  ${functionSource('function currentAliasRoute()')}
  ${functionSource('function aliasEditorViewHref(selectedList, target, currentView = aliasEditorView(selectedList), route = currentAliasRoute())')}
  ${functionSource('function aliasEditorHasActiveFilters(scanListScope = false, route = currentAliasRoute())')}
  ${functionSource('function aliasEditorResultCount(page, filtered)')}
  ${functionSource('function aliasTransferListDefaults(selectedList, options = {})')}
  ${functionSource('function aliasTransferListDefaultsSummary(defaults)')}
  ${functionSource('function aliasTransferAssignmentNames(enabled, selectedNames = [], exactNames = [])')}
  ${functionSource('function aliasTransferDetectedFormat(csv)')}
  ${functionSource('function aliasTransferExportHref(listId)')}
  ${functionSource("function aliasActivityReportHref(listId, scope = 'all')")}
  ${functionSource('function aliasTransferExportToken()')}
  ${functionSource('function aliasTransferExportDownloadHref(listId, token)')}
  ${functionSource('function aliasTransferExportIsReady(cookieHeader, token)')}
  ${functionSource('function aliasTransferImportRequest({ file, ...options })')}
  ${functionSource("function exportCsvFileName(response, fallback = 'export.csv')")}
  ${functionSource('function reorderedAliasToneRows(rows, index, direction)')}
  ${functionSource('function fullScanListMembershipRequest(editRevision, operation, aliasListId = null)')}
  ${functionSource('function protocol(value)')}
  ${functionSource('function protocolFamily(row)')}
  ${functionSource('function isP25(row)')}
  ${functionSource('function hex(value, width = 0)')}
  ${functionSource("function p25CanonicalSubscriber(row, prefix = '')")}
  ${functionSource('function canonicalSubscriberText(identity)')}
  ${functionSource('function aliasNumericValue(value)')}
  ${functionSource('function aliasHexValue(value)')}
  ${functionSource('function aliasDecimalValue(value)')}
  ${functionSource('function aliasMatcherCanonicalField(field)')}
  ${functionSource('function aliasMatcherKey(value)')}
  ${functionSource('function aliasMatcherFieldValue(matcher, field)')}
  ${functionSource('function aliasMatcherFieldLimit(descriptor, field, bound)')}
  ${functionSource('function aliasMatcherDescriptor(options, keyOrType, protocol = \'\', variant = \'\')')}
  ${functionSource('function aliasMatcherDefault(descriptor, options = {})')}
  ${functionSource('function aliasMatcherPayload(form, descriptor)')}
  ${functionSource('function routedAliasPrefill(selectedList, options)')}
  ${functionSource('function aliasMatcherSummary(matcher)')}
  ${functionSource('function aliasMatcherBoundText(field, value)')}
  ${functionSource('function aliasSelectionScopeKey(kind, filters = {})')}
  ${functionSource('function completeAliasSelection(response, maximum = ALIAS_BULK_SELECTION_LIMIT)')}
  ${functionSource('function extendedAliasSelection(selection, additions, maximum = ALIAS_BULK_SELECTION_LIMIT)')}
  ${functionSource('function validatedAliasSelectionIds(selection, maximum = ALIAS_BULK_SELECTION_LIMIT)')}
  ${functionSource('function syncAliasPageSelectionHeader(tableHost, rows)')}
  ${functionSource('function aliasPageSelectionHeader(rows, onSelectionChange)')}
  ${functionSource('function resetAliasEditorSelection(scope = null)')}
  ${functionSource('function synchronizeAliasEditorSelectionScope(scope)')}
  ${functionSource('function clearInactiveAliasSelection(activeTable)')}
  ${functionSource('function clearAliasSelectionOutsideEditor(view)')}
  ${functionSource('async function selectAllMatchingAliases(filters, scope, button, onSelectionChange)')}
  globalThis.editorLists = aliasEditorLists;
  globalThis.renamePayload = aliasListRenamePayload;
  globalThis.channelUsage = aliasListChannelUsage;
  globalThis.channelPreview = aliasListChannelPreview;
  globalThis.optionLimit = aliasOptionLimit;
  globalThis.cloneOptionValue = aliasCloneOptionValue;
  globalThis.streamOptionSelected = aliasStreamOptionSelected;
  globalThis.editorDefaultOrder = aliasEditorDefaultOrder;
  globalThis.editorViewHref = aliasEditorViewHref;
  globalThis.hasActiveFilters = aliasEditorHasActiveFilters;
  globalThis.resultCount = aliasEditorResultCount;
  globalThis.setRoute = (value) => { route = new URLSearchParams(value); };
  globalThis.transferListDefaults = aliasTransferListDefaults;
  globalThis.transferListDefaultsSummary = aliasTransferListDefaultsSummary;
  globalThis.transferAssignmentNames = aliasTransferAssignmentNames;
  globalThis.transferDetectedFormat = aliasTransferDetectedFormat;
  globalThis.transferExportHref = aliasTransferExportHref;
  globalThis.activityReportHref = aliasActivityReportHref;
  globalThis.transferExportToken = aliasTransferExportToken;
  globalThis.transferExportDownloadHref = aliasTransferExportDownloadHref;
  globalThis.transferExportIsReady = aliasTransferExportIsReady;
  globalThis.transferImportRequest = aliasTransferImportRequest;
  globalThis.exportFileName = exportCsvFileName;
  globalThis.reorderTones = reorderedAliasToneRows;
  globalThis.fullMembershipRequest = fullScanListMembershipRequest;
  globalThis.matcherDefault = aliasMatcherDefault;
  globalThis.matcherPayload = aliasMatcherPayload;
  globalThis.routedPrefill = (query, selectedList, options) => {
    route = new URLSearchParams(query);
    return routedAliasPrefill(selectedList, options);
  };
  globalThis.matcherSummary = aliasMatcherSummary;
  globalThis.matcherBoundText = aliasMatcherBoundText;
  globalThis.selectionScopeKey = aliasSelectionScopeKey;
  globalThis.completeSelection = completeAliasSelection;
  globalThis.extendSelection = extendedAliasSelection;
  globalThis.validatedSelectionIds = validatedAliasSelectionIds;
  globalThis.syncPageHeader = syncAliasPageSelectionHeader;
  globalThis.pageSelectionHeader = aliasPageSelectionHeader;
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

const renameSource = { alias_list_id: 7, name: 'County', name_edit_revision: 'list-name-7-v1' };
assert.deepEqual(JSON.parse(JSON.stringify(context.renamePayload(renameSource, '  County Dispatch  '))),
  { name: 'County Dispatch', edit_revision: 'list-name-7-v1' });
assert.equal(context.renamePayload(renameSource, 'A'.repeat(128)).name.length, 128);
assert.throws(() => context.renamePayload(renameSource, '   '), /Enter a name/);
assert.throws(() => context.renamePayload(renameSource, 'A'.repeat(129)), /128 characters/);
assert.throws(() => context.renamePayload({ alias_list_id: 7 }, 'County'), /Reload the page/);
assert.deepEqual(renameSource, { alias_list_id: 7, name: 'County', name_edit_revision: 'list-name-7-v1' },
  'Preparing a rename keeps the stored name and identity unchanged until the write succeeds.');

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
const aliasListMetadata = adminLists.slice(0, 100).map((row) => ({
  alias_list_id: row.alias_list_id,
  name: `Metadata ${row.alias_list_id}`,
  family: 'WRONG',
  alias_count: row.alias_list_id * 100,
  assigned_channel_count: row.alias_list_id * 2,
  unmatched_talkgroup_policy: { recordable: 'must not replace configuration state' }
}));
const merged = context.editorLists(adminLists, aliasListMetadata);
assert.equal(merged.length, 150, 'The complete Alias Editor catalog must drive list visibility.');
const capabilityList = context.editorLists([{ alias_list_id: 1, name: 'Analog', family: 'NBFM' }],
  [{ alias_list_id: 1, capabilities: { observed_group_discovery: false, configured_assignment: true } }])[0];
assert.equal(capabilityList.capabilities.observed_group_discovery, false,
  'Shared server capabilities must reach the Alias Editor instead of being discarded with catalog metadata.');
const channelsByList = context.channelUsage([
  { configuration_id: '00000000-0000-0000-0000-000000000002', alias_list_id: 2, name: 'Dispatch 10' },
  { configuration_id: '00000000-0000-0000-0000-000000000001', alias_list_id: 2, name: 'Dispatch 2' },
  { configuration_id: '00000000-0000-0000-0000-000000000001', alias_list_id: 2, name: 'Dispatch 2' },
  { configuration_id: '00000000-0000-0000-0000-000000000003', alias_list_id: 1, name: 'Fire' },
  { configuration_id: 'invalid', alias_list_id: 2, name: 'Ignored' }
]);
assert.deepEqual(Array.from(channelsByList.get(2), (channel) => channel.name),
  ['Dispatch 2', 'Dispatch 10'], 'Assigned channel names should be unique and naturally sorted.');
assert.equal(context.channelPreview({ alias_list_id: 2, assigned_channel_count: 2 },
  { channelsByList }), 'Used by Dispatch 2, Dispatch 10');
assert.equal(context.channelPreview({ alias_list_id: 3, assigned_channel_count: 4 },
  { channelsByList }), '4 channels', 'Counts should remain available when channel names cannot be loaded.');
const first = merged.find((row) => row.alias_list_id === 1);
assert.equal(first.name, 'Admin 001', 'Statistics metadata must not replace editor-owned list identity.');
assert.equal(first.family, 'P25');
assert.deepEqual(JSON.parse(JSON.stringify(first.unmatched_talkgroup_policy)), { recordable: false });
assert.equal(first.alias_count, 100, 'Statistics metadata should enrich the matching editor row.');
assert.equal(first.assigned_channel_count, 2);
const beyondMetadataPage = merged.find((row) => row.alias_list_id === 150);
assert.equal(beyondMetadataPage.alias_count, 1500,
  'Counts supplied by the complete editor catalog must survive beyond the metadata page.');
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
  { sort: 'signaling_observation_count', direction: 'desc' },
  'The Activity view must initially rank aliases by most signaling.');
assert.deepEqual(JSON.parse(JSON.stringify(context.editorDefaultOrder('configure'))),
  { sort: 'name', direction: 'asc' },
  'Configuration views must retain their alphabetical default.');
const selectedAliasList = { alias_list_id: 7 };
const viewRoute = (target, currentView) => new URL(context.editorViewHref(selectedAliasList,
  target, currentView), 'https://example.test').searchParams;
const sharedViewState = new URLSearchParams({
  view: 'aliases', list: '7', aliasTab: 'configure', q: 'fire', type: 'talkgroup',
  matcher: 'TALKGROUP', group: 'Dispatch', scanListId: '4', record: 'enabled',
  stream: 'present', evidence: 'observed', use: 'used', lastActivityAfter: '42',
  lastActivityBefore: '99', offset: '100', sort: 'group', direction: 'desc',
  alias: '72', createAlias: '1', createListId: '7', unrelated: 'discard'
});
context.setRoute(sharedViewState.toString());
const activityRoute = viewRoute('activity', 'configure');
for (const key of ['q', 'type', 'matcher', 'group', 'scanListId', 'record', 'stream',
  'evidence', 'use', 'lastActivityAfter', 'lastActivityBefore', 'offset', 'sort', 'direction']) {
  assert.equal(activityRoute.get(key), sharedViewState.get(key),
    `Switching column views must retain ${key}.`);
}
assert.equal(activityRoute.get('view'), 'aliases');
assert.equal(activityRoute.get('list'), '7');
assert.equal(activityRoute.get('aliasTab'), 'activity');
for (const key of ['alias', 'createAlias', 'createListId', 'unrelated']) {
  assert.equal(activityRoute.has(key), false,
    `Switching column views must not carry transient ${key} state.`);
}
context.setRoute('view=aliases&list=7&aliasTab=configure&q=fire&offset=100');
const defaultActivityRoute = viewRoute('activity', 'configure');
assert.equal(defaultActivityRoute.get('sort'), null,
  'Switching from Configure must allow the Activity default without inventing an explicit alphabetical choice.');
assert.equal(defaultActivityRoute.get('direction'), null);
assert.equal(defaultActivityRoute.get('offset'), null,
  'A different default order starts at the first page, so the most signaling is visible first.');
const defaultCustomFromConfigure = viewRoute('custom', 'configure');
assert.equal(defaultCustomFromConfigure.get('sort'), null);
assert.equal(defaultCustomFromConfigure.get('offset'), '100',
  'Views with the same effective default can keep their current page.');
context.setRoute('view=aliases&list=7&aliasTab=activity&q=fire&offset=100');
const defaultCustomRoute = viewRoute('custom', 'activity');
assert.equal(defaultCustomRoute.get('sort'), null,
  'An inferred Activity default must not become an explicit sort in another view.');
assert.equal(defaultCustomRoute.get('direction'), null);
assert.equal(defaultCustomRoute.get('offset'), null);
context.setRoute('view=aliases&list=7&aliasTab=discover&q=channel');
const returnFromDiscover = viewRoute('configure', 'discover');
assert.deepEqual([...returnFromDiscover.entries()],
  [['view', 'aliases'], ['list', '7'], ['aliasTab', 'configure']],
  'Returning from Discover must start a fresh Alias search instead of reusing its different query.');
const aliasRenderer = functionSource('async function renderAliases(presentation = null)');
const aliasDataLoader = functionSource('async function requestAliasEditorData(selectedList, view, request = {})');
const aliasViewTabs = functionSource('function aliasEditorViewTabs(selectedList, onSwitch = null, route = currentAliasRoute())');
const aliasFilterToolbar = functionSource('function aliasEditorFilterToolbar(aliasPage, options = null, route = currentAliasRoute())');
const aliasDiscoverToolbar = functionSource('function observedGroupIdentityToolbar(selectedList, route = currentAliasRoute())');
const aliasExportLink = functionSource('function exportCsvLink(dataset, context = {}, options = {})');
const aliasDetailLink = functionSource('function aliasDetailLink(row)');
const aliasMutationFinisher = functionSource('async function finishAliasMutation(modal, result, routeChanges = {})');
assert.match(aliasViewTabs, /href: aliasEditorViewHref\(selectedList, 'configure', active, route\)/);
assert.match(aliasViewTabs, /href: aliasEditorViewHref\(selectedList, 'activity', active, route\)/);
assert.match(aliasViewTabs, /href: aliasEditorViewHref\(selectedList, 'custom', active, route\)/);
assert.match(aliasViewTabs, /event\.preventDefault\(\);\s*void onSwitch\(entry\.id\)/,
  'An ordinary view click must switch the existing Alias table in place.');
assert.match(aliasViewTabs, /anchor\('Discover', href\('aliases', \{ list: id, aliasTab: 'discover' \}\)/,
  'Discover must have a separate link to its own search and results.');
assert.match(aliasRenderer, /aliasEditorViewTabs\(selectedList, \(nextView\) => switchView\?\.\(nextView\), route\)/,
  'The three column-view controls must be wired to the current Alias table.');
assert.match(aliasRenderer, /switchView = async \(nextView, navigationRequest = null, query = currentAliasRoute\(\)\) => \{[\s\S]*?aliasEditorWorkspaceController\.navigate\([\s\S]*?setAliasEditorViewTabs\(viewTabs, view, selectedList, query\);[\s\S]*?renderTable\(\)/,
  'Switching views must update the route, selected control, and existing table without rendering the page again.');
assert.match(aliasDataLoader, /\.\.\.aliasEditorOrder\(view, query\)/,
  'Requests and tab navigation must share the same effective sorting.');
assert.match(functionSource('function aliasEditorOrder(view, query = currentAliasRoute())'), /sort: query\.get\('sort'\) \|\|/,
  'Explicit routed sorting must take precedence over the view default.');
assert.match(aliasRenderer, /defaultSort: defaultOrder\.sort/,
  'The table indicator must match the order requested from the server.');
assert.match(aliasRenderer, /admin\/alias-lists\?include_counts=false/,
  'Activity renders must not recount the complete in-memory Alias model.');
assert.match(aliasRenderer, /apiPage\('\/api\/v1\/alias-lists\?limit=500',/,
  'The editor must enrich its configuration lists with bounded activity metadata.');
assert.match(aliasDataLoader, /optionParameters\.include_group_names = false/,
  'Activity renders must not rebuild global group-name suggestions.');
assert.match(aliasFilterToolbar, /selectFilter\('Evidence', 'evidence'/,
  'The Activity filters must expose the server-side evidence state filter.');
assert.match(aliasFilterToolbar, /covered_no_evidence/);
assert.match(aliasFilterToolbar, /not_collected/);
assert.match(aliasFilterToolbar, /unsupported/);
assert.match(aliasFilterToolbar, /aliasEditorFilterInput\('q'/,
  'Alias Editor filter searches must use the shared input control styling.');
assert.match(aliasFilterToolbar, /primary\.append\(search, searchButton, advancedButton\)/,
  'The default Alias Editor row must expose Search and the advanced disclosure together.');
assert.match(aliasFilterToolbar, /browsingWorkflows\.createFilterDisclosure\(\{/,
  'Alias filters must use the shared disclosure and mobile sheet lifecycle.');
assert.match(aliasFilterToolbar, /panel: advancedFilters,\s*button: advancedButton/);
assert.match(aliasFilterToolbar, /initialExpanded: activeAdvanced\.length > 0/,
  'Applied advanced filters must control the initial desktop disclosure state.');
assert.match(aliasFilterToolbar, /if \(activeAdvanced\.length\) advancedButton\.append\(uiPill\(/,
  'The disclosure must show a count when advanced filters are active.');
assert.match(aliasFilterToolbar, /clearAction: clearFilters/,
  'Both the filter bar and mobile sheet must use the same clear action.');
assert.match(aliasFilterToolbar, /anchor\('Clear filters'/,
  'Clearing applied search and advanced filters must be available from the compact row.');
assert.match(aliasFilterToolbar, /'alias-filter-active-summary ui-section-note'/);
assert.match(aliasFilterToolbar, /activeAdvanced\.join\(' · '\)/,
  'Collapsed advanced filters must summarize their active names and values.');
assert.ok(aliasFilterToolbar.indexOf("form.append(node('p', 'alias-filter-active-summary ui-section-note'") <
  aliasFilterToolbar.indexOf('form.append(advancedFilters)'),
  'The active filter summary must stay visible outside the collapsible panel.');
assert.match(aliasFilterToolbar, /aliasEditorFilterInput\('group'/,
  'Alias Editor group filters must use the shared input control styling.');
assert.match(aliasFilterToolbar, /aliasEditorFilterInput\('', aliasLocalDateTimeValue/g,
  'Alias Editor date filters must use the shared input control styling.');
assert.match(aliasFilterToolbar, /ui-button ui-button-primary/,
  'Alias Editor filter actions must use the shared primary button styling.');
context.setRoute('');
assert.equal(context.hasActiveFilters(), false);
context.setRoute('q=fire');
assert.equal(context.hasActiveFilters(), true, 'Basic search must count as an active filter.');
context.setRoute('scanListId=7');
assert.equal(context.hasActiveFilters(true), false,
  'The scan-list-members page must not count its fixed scan list as a removable filter.');
assert.equal(context.hasActiveFilters(false), true);
context.setRoute('record=enabled');
assert.equal(context.hasActiveFilters(true), true,
  'An advanced filter must remain clearable when the panel is collapsed.');
assert.equal(context.resultCount({ total_count: 0, rows: [] }, false), '0 aliases');
assert.equal(context.resultCount({ total_count: 3, rows: [] }, true), '3 matching aliases');
context.setRoute('');
assert.match(aliasDiscoverToolbar, /aliasEditorFilterInput\('q'/,
  'Alias Editor discovery searches must use the shared input control styling.');
assert.match(aliasDiscoverToolbar, /ui-button ui-button-primary/,
  'Alias Editor discovery actions must use the shared primary button styling.');
assert.match(aliasExportLink, /options\.loading/,
  'Alias table exports must support an explicit loading state.');
assert.match(aliasExportLink, /ui-button ui-button-secondary ui-icon-button export-csv-action/,
  'CSV exports must use the shared icon-button treatment.');
assert.match(aliasExportLink, /iconGlyph\('icon-share'\)/,
  'CSV exports must show the share glyph.');
assert.match(aliasExportLink, /link\.title = label/,
  'CSV exports must explain their action on hover.');
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
assert.match(aliasMutationFinisher, /aliasEditorWorkspaceController\.navigate\(currentHref\(\)/,
  'Mutations changing the active list must use the same dynamic workspace navigation.');
assert.match(aliasMutationFinisher, /if \(aliasEditorContext\?\.scanListScope\) delete mutationRouteChanges\.list/,
  'Scan-list mutations must not leave a stale alias-list route behind.');
assert.ok(aliasMutationFinisher.indexOf('resetAliasEditorSelection(localRefresh ? aliasEditorSelectionScope : null)') <
  aliasMutationFinisher.indexOf('await aliasEditorPageController.refresh()'),
  'Alias mutations must clear selection before refreshing the table.');
assert.ok(aliasMutationFinisher.indexOf("route.delete('offset')") <
  aliasMutationFinisher.indexOf('await aliasEditorPageController.refresh()'),
  'The refreshed table must request the same page shown in the updated route.');

vm.runInContext(`
  function closeReadOnlyModal() { return true; }
  function currentHref() { return '/?view=aliases'; }
  async function render() {}
  let aliasEditorWorkspaceController = null;
  let mutationRefreshSelection = null;
  ${aliasMutationFinisher}
  globalThis.runMutationRefreshProbe = async (scope) => {
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
    aliasEditorSelectionScope = scope;
    await finishAliasMutation({ setDirty: () => {} }, { revision: 2 });
    return { refreshedSelection: mutationRefreshSelection, remainingSelection: [...aliasEditorSelection],
      remainingScope: aliasEditorSelectionScope };
  };
`, context);

const transferDefaults = context.transferListDefaults({ new_alias_behavior: {
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
assert.equal(context.transferExportDownloadHref(7, '0123456789abcdef0123456789abcdef'),
  '/api/v1/admin/alias-lists/7/transfer?export_token=0123456789abcdef0123456789abcdef');
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
  'Concurrent configuration downloads must keep independent ready markers.');

const originalFile = new File(['Decimal,Hex,Alpha Tag\n1201,4b1,Dispatch\n'], 'aliases.csv', { type: 'text/csv' });
const rawImport = context.transferImportRequest({ file: originalFile, format: 'RADIOREFERENCE',
  mode: 'UPDATE_ADD', action: 'preview', offset: 100,
  defaults: { streams: ['Équipe + Dispatch 🚒'], scan_lists: ['Unités'], recordable: true } });
assert.equal(rawImport.body.get('file'), originalFile, 'The complete CSV stays in the browser File without a text copy.');
assert.equal(rawImport.headers, undefined, 'The browser must choose the multipart boundary.');
assert.deepEqual(Array.from(rawImport.body.keys()), ['options', 'file']);
assert.deepEqual(JSON.parse(rawImport.body.get('options')), { format: 'RADIOREFERENCE',
  mode: 'UPDATE_ADD', action: 'preview', offset: 100,
  defaults: { streams: ['Équipe + Dispatch 🚒'], scan_lists: ['Unités'], recordable: true } });
const largeImportOptions = { defaults: {
  streams: Array.from({ length: 500 }, (_, index) => `${index} ${'🚒'.repeat(124)}`),
  scan_lists: Array.from({ length: 500 }, (_, index) => `${index} ${'🚑'.repeat(124)}`) } };
const largeMetadata = context.transferImportRequest({ file: originalFile, ...largeImportOptions });
assert.deepEqual(JSON.parse(largeMetadata.body.get('options')), largeImportOptions,
  'Existing large multibyte destination overrides must be transmitted without a header-size restriction.');

const transferModal = functionSource("function openAliasTransferModal(selectedList, action = 'Import')");
const transferExportHref = functionSource('function aliasTransferExportHref(listId)');
const activityReportHref = functionSource("function aliasActivityReportHref(listId, scope = 'all')");
assert.match(transferExportHref, /admin\/alias-lists\/\$\{listId\}\/transfer/,
  'The established administrator-only all-list export route must remain compatible.');
assert.match(activityReportHref, /api\/v1\/exports\/aliases\.csv/);
assert.match(activityReportHref, /last_activity_after/);
assert.match(activityReportHref, /scan_list_id/);
assert.doesNotMatch(activityReportHref, /limit|offset/,
  'Filtered activity reports must not inherit the current browser page.');
assert.doesNotMatch(transferExportHref, /route\.get\('sort'\)|route\.get\('direction'\)/,
  'Importable exports use durable alias-ID order instead of presentation order.');
assert.match(transferModal, /options\.streams_truncated === true/);
assert.match(transferModal, /Add an exact configured name not shown/);
assert.match(transferModal, /Up to 500 destinations are listed/);
assert.match(transferModal, /Drop a CSV file here/);
assert.match(transferModal, /Add new aliases and update matches/);
assert.match(transferModal, /Replace this list’s aliases/);
assert.match(transferModal, /Importable configuration/);
assert.match(transferModal, /Activity report/);
assert.match(transferModal, /Cannot be imported/);
assert.match(transferModal, /Export CSV/);
assert.match(transferModal, /All aliases in this Alias List/);
assert.match(transferModal, /Current filtered results/);
assert.match(transferModal, /not only the visible page/);
assert.match(transferModal, /node\('input', 'ui-choice-radio'\)/,
  'Export scope must use the shared single-choice radio treatment.');
assert.match(transferModal, /destinationSummary\.append\(/);
assert.match(transferModal, /step\.append\(node\('span'/);
assert.doesNotMatch(transferModal, /node\('div', 'alias-transfer-destination',\s*node\(/);
assert.doesNotMatch(transferModal, /node\('li', '', node\(/);
assert.match(transferModal, /preview\.counts\.deleted > 0/);
assert.match(transferModal, /browsingWorkflows\.createBrowsingPager\([\s\S]*Import review pages/,
  'Alias transfer review pagination must use the shared count and action placement');
assert.match(transferModal, /exportFrame/);
assert.match(transferModal, /Preparing the complete CSV/);
assert.match(transferModal, /aliasTransferExportDownloadHref/);
assert.match(transferModal, /aliasTransferExportIsReady\(document\.cookie, token\)/);
assert.match(transferModal, /Download started\. The browser verifies the complete file length/);
assert.match(transferModal, /sign-in or export access may have changed/);
assert.match(transferModal, /window\.clearInterval\(exportStartPoll\)/,
  'Closing the modal must stop the ready-marker poll.');
assert.doesNotMatch(transferModal, /fetch\(endpoint/);
assert.match(transferModal, /reportController\?\.abort\(\)/,
  'Closing the export dialog must cancel an activity report that is still being prepared.');
assert.match(transferModal, /timeoutMs: ALIAS_BULK_REQUEST_TIMEOUT_MS/g);
assert.doesNotMatch(transferModal, /selected\.text\(\)/,
  'Preview and apply must not read and encode the complete CSV on the UI thread.');

context.setRoute('q=Dispatch&type=talkgroup&group=Fire&scanListId=2&record=on&' +
  'lastActivityAfter=2026-10-01&sort=name&direction=desc&limit=25&offset=50');
const reportQuery = new URL(context.activityReportHref(7, 'filtered'), 'http://localhost').searchParams;
assert.deepEqual(Object.fromEntries(reportQuery), {
  list: '7', type: 'talkgroup', group: 'Fire', scan_list_id: '2', record: 'on',
  last_activity_after: '2026-10-01', q: 'Dispatch', sort: 'name', direction: 'desc'
}, 'Filtered activity reports must include all matching results in the current sort order.');
assert.deepEqual(Object.fromEntries(new URL(context.activityReportHref(7), 'http://localhost').searchParams), {
  list: '7', sort: 'name', direction: 'desc'
}, 'Whole-list activity reports must ignore current search and filters.');
assert.equal(context.transferExportHref(7), '/api/v1/admin/alias-lists/7/transfer',
  'Importable configuration exports must always use the complete-list route without report filters.');
context.setRoute('');

const firstTone = { tone: 'A' };
const secondTone = { tone: 'B' };
const thirdTone = { tone: 'C' };
const tones = [firstTone, secondTone, thirdTone];
assert.deepEqual(Array.from(context.reorderTones(tones, 1, -1), (row) => row.tone), ['B', 'A', 'C']);
assert.deepEqual(Array.from(context.reorderTones(tones, 1, 1), (row) => row.tone), ['A', 'C', 'B']);
assert.deepEqual(Array.from(context.reorderTones(tones, 0, -1), (row) => row.tone), ['A', 'B', 'C']);
assert.deepEqual(tones.map((row) => row.tone), ['A', 'B', 'C'], 'Reordering must not mutate the input array.');

assert.deepEqual(JSON.parse(JSON.stringify(context.fullMembershipRequest('scan-version-7', 'add', 42))), {
  edit_revision: 'scan-version-7', operation: 'add', alias_scope: { alias_list_id: 42 }
});
assert.deepEqual(JSON.parse(JSON.stringify(context.fullMembershipRequest('scan-version-8', 'remove'))), {
  edit_revision: 'scan-version-8', operation: 'remove', alias_scope: {}
});
assert.equal(context.matcherSummary({ type: 'talkgroup_range', protocol: 'P25', minimum: 10, maximum: 20 }),
  'Talkgroup Range · P25 · 10–20');
assert.equal(context.matcherSummary({ type: 'tone_sequence', tones: [
  { tone: 'DTMF_1', duration: 2 }, { tone: 'DTMF_2', duration: 3 }
] }), 'Tone Sequence · DTMF_1 ×2 → DTMF_2 ×3');
assert.equal(context.matcherSummary({ type: 'p25_subscriber_identity', home_wacn: 0xBEE00,
  home_system_id: 0x348, subscriber_id: 9_601_699 }),
  'P25 Subscriber Identity · BEE00.348.9601699');
const canonicalDescriptor = {
  type: 'P25_SUBSCRIBER_IDENTITY', fields: ['home_wacn', 'home_system_id', 'subscriber_id'],
  home_wacn_minimum: 0, home_wacn_maximum: 0xFFFFF,
  home_system_id_minimum: 0, home_system_id_maximum: 0xFFF,
  subscriber_id_minimum: 1, subscriber_id_maximum: 0xFFFFFC
};
assert.deepEqual(JSON.parse(JSON.stringify(context.matcherDefault(canonicalDescriptor))), {
  type: 'P25_SUBSCRIBER_IDENTITY', homeWacn: 0, homeSystemId: 0, subscriberId: 1
});
const canonicalForm = { elements: {
  matcherType: { value: 'P25_SUBSCRIBER_IDENTITY', dataset: {} },
  'matcher-homeWacn': { value: 'BEE00' },
  'matcher-homeSystemId': { value: '348' },
  'matcher-subscriberId': { value: '9601699' }
} };
assert.deepEqual(JSON.parse(JSON.stringify(context.matcherPayload(canonicalForm, canonicalDescriptor))), {
  type: 'P25_SUBSCRIBER_IDENTITY', home_wacn: 0xBEE00,
  home_system_id: 0x348, subscriber_id: 9_601_699
});
assert.equal(context.matcherBoundText('homeWacn', 0xFFFFF), 'FFFFF');
assert.equal(context.matcherBoundText('homeSystemId', 0xFFF), 'FFF');
assert.equal(context.matcherBoundText('subscriberId', 0xFFFFFC), '16777212');
const nxdnTalkgroupDescriptor = {
  type: 'talkgroup', protocol: 'nxdn', fields: ['value'], minimum: 0, maximum: 65535
};
assert.equal(context.matcherDefault(nxdnTalkgroupDescriptor).value, 0);
assert.deepEqual(JSON.parse(JSON.stringify(context.matcherPayload({ elements: {
  matcherType: { value: 'talkgroup:nxdn', dataset: {} }, 'matcher-value': { value: '0' }
} }, nxdnTalkgroupDescriptor))), { type: 'talkgroup', protocol: 'nxdn', value: 0 },
  'The Alias editor submits NXDN talkgroup zero as a numeric identifier.');
const aliasFields = vm.runInNewContext(`${functionSource('function aliasMatcherFieldValue(matcher, field)')}\n${
  functionSource('function aliasMatcherCanonicalField(field)')}\n${
  functionSource('function aliasMatcherFields(host, descriptor, matcher, options)')}\n({aliasMatcherFields});`, {
  identifierNumber: value => String(value),
  aliasTextInput: (name, value, type) => ({ name, value: String(value), type }),
  aliasFormField: (label, input, help) => ({ label, input, help })
});
const zeroFieldHost = { children: [], replaceChildren() { this.children = []; },
  append(child) { this.children.push(child); } };
aliasFields.aliasMatcherFields(zeroFieldHost, nxdnTalkgroupDescriptor, { value: 0 }, {});
assert.equal(zeroFieldHost.children[0].input.value, '0');
assert.equal(zeroFieldHost.children[0].help, '0 through 65535',
  'Alias-editor guidance displays the server-advertised NXDN minimum of zero.');

const routedList = {
  alias_list_id: 41,
  unmatched_talkgroup_policy: { recordable: true, broadcast_configuration_ids: ['stream'], scan_list_ids: [7] }
};
const routedOptions = { matchers: [{
  type: 'p25_subscriber_identity', fields: ['home_wacn', 'home_system_id', 'subscriber_id'],
  home_wacn_minimum: 0, home_wacn_maximum: 0xFFFFF,
  home_system_id_minimum: 0, home_system_id_maximum: 0xFFF,
  subscriber_id_minimum: 1, subscriber_id_maximum: 0xFFFFFC
}, { type: 'radio', protocol: 'p25', variant: 'phase_1', minimum: 1, maximum: 0xFFFFFF }] };
const nxdnZeroRoute = '?createAlias=1&createType=talkgroup&createProtocol=nxdn&createValue=0&createName=Security';
assert.equal(context.routedPrefill(nxdnZeroRoute, routedList,
  { matchers: [nxdnTalkgroupDescriptor] }).matcher.value, 0,
  'Creating an alias from an NXDN talkgroup-zero drilldown preserves the exact identifier.');
assert.equal(context.routedPrefill(nxdnZeroRoute, routedList,
  { matchers: [{ ...nxdnTalkgroupDescriptor, minimum: 1 }] }), null,
  'Routed alias validation still follows the server-advertised minimum.');
const canonicalPrefill = context.routedPrefill(
  '?createAlias=1&createType=p25_subscriber_identity&createHomeWacn=BEE00&createHomeSystemId=348' +
    '&createSubscriberId=501&createWorkingAddress=501&createName=Portable+501', routedList, routedOptions);
assert.deepEqual(JSON.parse(JSON.stringify(canonicalPrefill)), {
  alias_list_id: 41, name: 'Portable 501', description: '', group: '', color: 0, icon_name: null,
  recordable: false, broadcast_configuration_ids: [], scan_list_ids: [], stream_as_talkgroup: null,
  matcher: { type: 'p25_subscriber_identity', home_wacn: 0xBEE00, home_system_id: 0x348,
    subscriber_id: 501 },
  working_address: 501
}, 'Canonical Live routing must prefill the permanent tuple and keep an equal explicit WUID as context only.');
const ordinaryPrefill = context.routedPrefill(
  '?createAlias=1&createType=radio&createProtocol=p25&createVariant=phase_1&createValue=501',
  routedList, routedOptions);
assert.deepEqual(JSON.parse(JSON.stringify(ordinaryPrefill.matcher)), {
  type: 'radio', protocol: 'p25', variant: 'phase_1', value: 501
}, 'An ordinary P25 radio route must remain an ordinary local matcher.');
assert.equal(context.routedPrefill(
  '?createAlias=1&createType=p25_subscriber_identity&createHomeWacn=BEE00&createHomeSystemId=348' +
    '&createSubscriberId=501&createWorkingAddress=not-a-radio', routedList, routedOptions), null,
  'Malformed working context must fail closed instead of silently changing the routed identity.');

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

context.node = () => ({
  checked: false, indeterminate: false, disabled: false, listeners: {}, attributes: {},
  setAttribute(name, value) { this.attributes[name] = value; },
  addEventListener(name, listener) { this.listeners[name] = listener; }
});
const pageRows = [{ alias_id: 31 }, { alias_id: 32 }];
let pageHeader;
const pageHost = { querySelector: () => pageHeader };
const pageMessages = [];
const pageChanged = (message, error) => {
  pageMessages.push({ message, error });
  context.syncPageHeader(pageHost, pageRows);
};
context.seedSelection([31, 99], selectionScope, 0);
pageHeader = context.pageSelectionHeader(pageRows, pageChanged);
context.syncPageHeader(pageHost, pageRows);
assert.equal(pageHeader.attributes['aria-label'], 'Select all aliases on this page');
assert.equal(pageHeader.indeterminate, true, 'A partly selected page should show a mixed header checkbox.');
pageHeader.checked = true;
pageHeader.listeners.change();
assert.deepEqual([...context.selectionState().ids], [31, 99, 32],
  'The header checkbox should add this page without clearing selections on another page.');
assert.equal(pageHeader.checked, true);
assert.equal(pageHeader.indeterminate, false);
pageHeader.checked = false;
pageHeader.listeners.change();
assert.deepEqual([...context.selectionState().ids], [99],
  'Clearing the header checkbox should remove only this page.');

context.seedSelection(Array.from({ length: 10_000 }, (_, index) => index + 1), selectionScope, 0);
const overflowRows = [{ alias_id: 10_001 }];
let overflowHeader;
const overflowHost = { querySelector: () => overflowHeader };
overflowHeader = context.pageSelectionHeader(overflowRows, (message, error) => {
  pageMessages.push({ message, error });
  context.syncPageHeader(overflowHost, overflowRows);
});
overflowHeader.checked = true;
overflowHeader.listeners.change();
assert.equal(context.selectionState().ids.length, 10_000,
  'The header must retain the previous selection when adding a page would exceed the limit.');
assert.equal(overflowHeader.checked, false, 'A failed page selection must restore the header state.');
assert.equal(pageMessages.at(-1).error, true);

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

async function verifyScanListDialogVersionSnapshot() {
  for (const removeAll of [false, true]) {
    const elements = [];
    const writes = [];
    const element = (tag, _className = '', textContent = '') => {
      const attributes = new Map();
      const value = { tag, textContent, disabled: false, handlers: {},
        classList: { add() {}, remove() {} },
        append() {}, replaceChildren() {}, setAttribute: (key, value) => attributes.set(key, value),
        getAttribute: (key) => attributes.get(key) ?? null, removeAttribute: (key) => attributes.delete(key),
        querySelectorAll: () => elements.filter((item) => ['button', 'input', 'select'].includes(item.tag)),
        addEventListener(type, handler) { this.handlers[type] = handler; } };
      elements.push(value);
      return value;
    };
    const probe = { node: element, number: String, aliasMutationSelectionIds: () => [11, 12],
      aliasModalFooter: (...controls) => ({ controls }), ALIAS_BULK_REQUEST_TIMEOUT_MS: 60_000,
      openReadOnlyModal: () => ({ close() {}, setBusy() {}, focus() {} }),
      finishAliasMutation: async () => {},
      requestJson: async (url, options) => { writes.push({ url, body: options.body }); return {}; } };
    vm.createContext(probe);
    const actionSource = fs.readFileSync(path.resolve(path.dirname(applicationPath),
      'core/action-workflows.js'), 'utf8').replaceAll('export function ', 'function ');
    vm.runInContext(`${actionSource}
      ${functionSource('function openScanListMemberRemoveModal(scanList)')}
      ${functionSource('function fullScanListMembershipRequest(editRevision, operation, aliasListId = null)')}
      ${functionSource('function openFullScanListMembershipModal(scanList, operation, presentation = null)')}`, probe);
    const scanList = { id: 7, name: 'County', alias_count: 2, edit_revision: 'opened-memberships' };
    if (removeAll) probe.openFullScanListMembershipModal(scanList, 'remove');
    else probe.openScanListMemberRemoveModal(scanList);
    Object.assign(scanList, { alias_count: 3, edit_revision: 'refreshed-behind-dialog' });
    const trigger = elements.find((value) => removeAll ? value.tag === 'form' :
      value.tag === 'button' && value.textContent === 'Remove 2 Aliases');
    await trigger.handlers[removeAll ? 'submit' : 'click']({ preventDefault() {} });
    assert.equal(writes.length, 1);
    assert.equal(writes[0].body.edit_revision, 'opened-memberships',
      'A background refresh must not advance an open membership confirmation to an unreviewed version');
  }
}

async function verifyAdditionalAliasLifecycles() {
  await verifyAsyncSelectionLifecycle();
  await verifySharedFormWorkflowLifecycle();
  await verifyAliasSaveLifecycle();
  await verifyScanListDialogVersionSnapshot();
  const result = await context.runMutationRefreshProbe(selectionScope);
  assert.deepEqual(Array.from(result.refreshedSelection), [],
    'An in-place mutation refresh must see a cleared selection before it redraws the toolbar.');
  assert.deepEqual(Array.from(result.remainingSelection), [],
    'A completed mutation must leave the Alias Editor selection empty.');
  assert.equal(result.remainingScope, selectionScope,
    'An in-place mutation refresh must retain the scope used by Select All Matching.');

  const messages = [];
  context.api = async () => ({ alias_ids: [51, 52], count: 2 });
  await context.selectAllMatching(selectionFilters, selectionScope,
    { textContent: 'Select All Matching', disabled: false, isConnected: true },
    (...message) => messages.push(message));
  assert.deepEqual(Array.from(context.selectionState().ids), [51, 52],
    'Select All Matching must select the returned aliases after saving and refreshing in place.');
  assert.match(messages.at(-1)[0], /Selected all 2 matching aliases/);
}

function workflowClassList() {
  const values = new Set();
  return {
    add: (...tokens) => tokens.forEach((token) => values.add(token)),
    remove: (...tokens) => tokens.forEach((token) => values.delete(token)),
    contains: (token) => values.has(token)
  };
}

async function verifySharedFormWorkflowLifecycle() {
  const workflowSource = fs.readFileSync(path.resolve(path.dirname(applicationPath), 'core/form-workflows.js'),
    'utf8').replace('export function createFormWorkflow', 'function createFormWorkflow');
  const createFormWorkflow = vm.runInNewContext(`${workflowSource}; createFormWorkflow;`);
  const input = { value: 'Original', disabled: false };
  const locked = { disabled: true };
  const submit = { classList: workflowClassList(), disabled: false, hidden: false };
  const feedback = { classList: workflowClassList(), textContent: '', hidden: true,
    setAttribute(name, value) { this[name] = value; } };
  const listeners = new Map();
  const attributes = new Map();
  const form = { classList: workflowClassList(), elements: [input, locked, submit],
    addEventListener: (name, listener) => listeners.set(name, listener), reportValidity: () => true,
    setAttribute: (name, value) => attributes.set(name, value),
    removeAttribute: (name) => attributes.delete(name) };
  const modal = { dirty: false, busy: false,
    setDirty(value) { this.dirty = value; }, setBusy(value) { this.busy = value; } };
  let confirmed = input.value;
  const workflow = createFormWorkflow({ form, submit, feedback, modal,
    changed: () => input.value !== confirmed });
  const edit = (value) => { input.value = value; listeners.get('input')(); };

  assert.equal(submit.hidden, true, 'A clean form has no Save action');
  assert.equal(submit.disabled, true);
  edit('Draft');
  assert.equal(form.classList.contains('is-dirty'), true);
  assert.equal(feedback.textContent, 'Unsaved changes');
  assert.equal(feedback.classList.contains('ui-form-state'), true);
  assert.equal(feedback.classList.contains('ui-feedback'), false, 'Dirty feedback stays compact');
  assert.equal(submit.hidden, false);
  edit('Original');
  assert.equal(form.classList.contains('is-dirty'), false);
  assert.equal(feedback.textContent, '', 'Reverting to the confirmed value clears draft feedback');
  assert.equal(feedback.hidden, true);
  assert.equal(submit.hidden, true);

  edit('Draft');
  let release;
  let writes = 0;
  const pending = workflow.save(async () => {
    writes += 1;
    await new Promise((resolve) => { release = resolve; });
    return input.value;
  }, { success: 'Legacy saved message.', onSuccess: (value) => { confirmed = value; } });
  assert.equal(modal.busy, true);
  assert.equal(input.disabled, true);
  assert.equal(submit.disabled, true);
  assert.equal(attributes.get('aria-busy'), 'true');
  assert.equal(await workflow.save(() => { writes += 1; }), false);
  assert.equal(writes, 1, 'Duplicate submission cannot repeat a pending save');
  release();
  assert.equal(await pending, true);
  assert.equal(feedback.textContent, '', 'Success clears even a legacy caller-provided saved message');
  assert.equal(feedback.hidden, true);
  assert.equal(form.classList.contains('is-dirty'), false, 'The comparator sees the onSuccess confirmed value');
  assert.equal(submit.hidden, true);
  assert.equal(submit.disabled, true);
  assert.equal(modal.dirty, false);
  assert.equal(modal.busy, false);
  assert.equal(input.disabled, false);
  assert.equal(locked.disabled, true, 'A permanently disabled control stays disabled');
  assert.equal(attributes.has('aria-busy'), false);

  edit('Retry draft');
  assert.equal(await workflow.save(async () => { throw new Error('Unavailable. Try again.'); }), false);
  assert.equal(input.value, 'Retry draft', 'Failure preserves the draft');
  assert.equal(form.classList.contains('is-dirty'), true);
  assert.equal(submit.disabled, false);
  assert.equal(submit.hidden, false);
  assert.equal(feedback.role, 'alert');
  assert.equal(feedback.classList.contains('ui-feedback-error'), true);
  edit('Draft');
  assert.equal(feedback.textContent, '', 'Reverting the failed draft clears its error');
  assert.equal(feedback.hidden, true);
  assert.equal(submit.hidden, true);

  const actionWorkflow = createFormWorkflow({ form, submit, feedback, changed: () => false,
    allowUnchangedSubmit: true, hideCleanSubmit: false });
  assert.equal(submit.disabled, false, 'A direct action does not require an unsaved draft');
  assert.equal(form.classList.contains('is-dirty'), false);
  assert.equal(await actionWorkflow.save(async () => true), true);
  assert.equal(submit.disabled, false, 'The next direct action stays available after success');
  assert.equal(submit.hidden, false);
}

async function verifyAliasSaveLifecycle() {
  const editor = functionSource("async function openAliasEditorModal(mode = 'create', id = null, prefill = null, presentation = null)");
  const setup = editor.slice(editor.indexOf('    let savedPayloadKey'),
    editor.indexOf("    modal.dialog.querySelector('.modal-header h2')"));
  const workflowSource = fs.readFileSync(path.resolve(path.dirname(applicationPath), 'core/form-workflows.js'),
    'utf8').replace('export function createFormWorkflow', 'function createFormWorkflow');
  const source = { alias_id: 101, alias_list_id: 7, name: 'Dispatch', description: null, group: 'Fire',
    color: 0, icon_name: null, recordable: true, broadcast_configuration_ids: ['stream-b', 'stream-a'],
    scan_list_ids: [3, 1], matcher: { type: 'talkgroup', protocol: 'APCO25', value: 1201 } };
  const fields = { aliasListId: { value: '7' }, name: { value: source.name }, description: { value: '' },
    group: { value: 'Fire' }, color: { value: '0' }, iconName: { value: '' },
    recordable: { checked: true }, streamAsTalkgroup: { value: '' },
    matcherType: { value: 'talkgroup:APCO25', dataset: { originalSelection: 'talkgroup:APCO25',
      originalType: 'talkgroup', originalProtocol: 'APCO25' } }, 'matcher-value': { value: '1201' } };
  const controls = Object.assign(Object.values(fields), fields);
  controls.forEach((control) => { control.disabled = false; });
  const handlers = new Map();
  const form = { classList: workflowClassList(), elements: controls, addEventListener: (type, handler) => {
    handlers.set(type, [...handlers.get(type) || [], handler]);
  }, setAttribute() {}, removeAttribute() {}, reportValidity: () => Boolean(fields.name.value.trim()),
  querySelectorAll: () => [{ value: 'stream-a' }, { value: 'stream-b' }] };
  const dispatch = async (type) => {
    for (const handler of handlers.get(type) || []) await handler({ preventDefault() {} });
  };
  const feedback = { children: [], replaceChildren(...children) { this.children = children; },
    append(...children) { this.children.push(...children); } };
  const save = { classList: workflowClassList(), disabled: false, hidden: false };
  const modal = { dirty: false, busy: false, setDirty(value) { this.dirty = value; },
    setBusy(value) { this.busy = value; } };
  const writes = [];
  let resolveWrite;
  let committed = source;
  let rendered = source;
  let refreshes = 0;
  const saveContext = {
    form, save, errorHost: feedback, modal, source, guide: false, editing: true, mode: 'edit', id: 101, prefill: null,
    options: {}, document: { createTextNode: (text) => ({ textContent: text }) },
    node: (_tag, _className, text) => ({ textContent: text, append() {}, setAttribute() {} }),
    aliasEditorPayload: () => ({ ...source, name: fields.name.value, recordable: fields.recordable.checked,
      broadcast_configuration_ids: ['stream-a', 'stream-b'], scan_list_ids: [1, 3] }),
    aliasEditorPageController: { isCurrent: () => true, canRefreshMutation: () => true,
      refresh: async () => { refreshes += 1; rendered = { ...committed }; return true; } },
    aliasEditorWorkspaceController: null,
    aliasEditorContext: { selectedList: { alias_list_id: 7 }, revision: 1 },
    aliasEditorSelectionScope: 'test', resetAliasEditorSelection() {},
    route: new URLSearchParams('view=aliases&list=7&alias=101'), ALIAS_CREATE_ROUTE_KEYS: [],
    aliasListId: (row) => row?.alias_list_id,
    editRevision: 'alias-version-1', currentHref: () => '/?view=aliases&list=7',
    closeReadOnlyModal: () => { saveContext.closed = true; },
    window: { history: { replaceState() {} } },
    requestJson: (url, options) => {
      writes.push({ url, options });
      return new Promise((resolve, reject) => { resolveWrite = (failure = null) => {
        if (failure) reject(failure);
        else { committed = { ...options.body.alias }; resolve({ revision: 2 }); }
      }; });
    }
  };
  vm.createContext(saveContext);
  vm.runInContext(`${workflowSource}
    ${functionSource('function aliasMutationError(host, error, retry = null)')}
    ${functionSource('function aliasFormWorkflow(form, submit, feedback, modal, reload = null, changed = null)')}
    ${functionSource('function aliasCallHandlingValue(value = {})')}
    ${functionSource('function aliasEditorPayloadKey(value)')}
    ${aliasMutationFinisher}
    ${setup}
    globalThis.payloadKey = aliasEditorPayloadKey;`, saveContext);

  const key = saveContext.payloadKey;
  assert.equal(key(source), key({ ...source, description: '', scan_list_ids: [1, 3],
    broadcast_configuration_ids: ['stream-a', 'stream-b'] }), 'Membership/stream order and empty optional text are not edits.');
  for (const [field, value] of Object.entries({ alias_list_id: 8, name: 'Other', description: 'Details',
    group: 'Other', color: 1, icon_name: 'Car', recordable: false, scan_list_ids: [],
    broadcast_configuration_ids: [], stream_as_talkgroup: 9,
    matcher: { type: 'talkgroup', protocol: 'APCO25', value: 1202 } })) {
    assert.notEqual(key(source), key({ ...source, [field]: value }), `${field} must participate in edit detection.`);
  }
  const tones = { ...source, matcher: { type: 'tones', tones: [
    { tone: 'a', duration: 1 }, { tone: 'b', duration: 2 }] } };
  assert.notEqual(key(tones), key({ ...tones, matcher: { ...tones.matcher,
    tones: [...tones.matcher.tones].reverse() } }), 'Tone order must remain meaningful.');
  assert.equal(save.disabled, true);
  await dispatch('submit');
  assert.equal(writes.length, 0, 'Submitting an unchanged edit must not write.');
  fields.name.value = 'Dispatch changed';
  await dispatch('input');
  fields.name.value = 'Dispatch';
  await dispatch('input');
  await dispatch('submit');
  assert.equal(writes.length, 0, 'Undoing an edit must restore the no-save state.');

  fields.name.value = 'Dispatch changed';
  await dispatch('input');
  const pending = dispatch('submit');
  assert.equal(writes.length, 1);
  assert.equal(modal.busy, true);
  assert.ok(controls.every((control) => control.disabled));
  await dispatch('submit');
  assert.equal(writes.length, 1, 'A pending save must not submit twice.');
  resolveWrite();
  await pending;
  assert.equal(refreshes, 1);
  assert.equal(rendered.name, 'Dispatch changed', 'Save completion must refresh committed values.');
  assert.equal(saveContext.aliasEditorContext.revision, 2);
  assert.equal(modal.dirty, false);
  assert.equal(saveContext.closed, true);

  saveContext.closed = false;
  fields.name.value = 'Unsaved draft';
  await dispatch('input');
  const timeoutPending = dispatch('submit');
  resolveWrite(Object.assign(new Error('The receiver did not respond in time.'), { code: 'request_timeout' }));
  await timeoutPending;
  assert.equal(fields.name.value, 'Unsaved draft');
  assert.equal(modal.dirty, true);
  assert.equal(modal.busy, false);
  assert.equal(saveContext.closed, false);
  assert.equal(writes.length, 2, 'Timeout feedback must not replay the save.');
  assert.equal(refreshes, 1, 'Timeout feedback must not poll for the result.');
  assert.equal(feedback.children[0].textContent, 'The receiver did not respond in time.');

  saveContext.aliasEditorPageController.refresh = async () => { throw new Error('Refresh unavailable'); };
  saveContext.aliasEditorWorkspaceController = { isCurrent: () => true, navigate: async () => false,
    feedback: { replaceChildren(...children) { this.children = children; } } };
  const refreshPending = dispatch('submit');
  resolveWrite();
  await refreshPending;
  assert.equal(modal.dirty, false);
  assert.match(saveContext.aliasEditorWorkspaceController.feedback.children[0].textContent,
    /^Changes saved, but updated information could not be loaded\. Refresh the page\.$/);
}

verifyAdditionalAliasLifecycles().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
