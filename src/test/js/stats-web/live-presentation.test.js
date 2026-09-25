'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.resolve(process.argv[2] ||
  path.resolve(__dirname, '../../../../stats-web/assets/app.js')), 'utf8');

function closingBrace(start) {
  let depth = 0;
  let quote = '';
  for (let index = start; index < source.length; index += 1) {
    const character = source[index];
    if (quote) {
      if (character === '\\') index += 1;
      else if (character === quote) quote = '';
      continue;
    }
    if (character === '\'' || character === '"' || character === '`') quote = character;
    else if (character === '{') depth += 1;
    else if (character === '}' && --depth === 0) return index;
  }
  throw new Error('Unclosed function');
}

function functionSource(name) {
  const start = source.indexOf(`function ${name}(`);
  assert.notEqual(start, -1, `Missing ${name}`);
  const open = source.indexOf('{', start);
  return source.slice(start, closingBrace(open) + 1);
}

function constantSource(name, ending) {
  const start = source.indexOf(`const ${name} =`);
  assert.notEqual(start, -1, `Missing ${name}`);
  const end = source.indexOf(ending, start);
  assert.notEqual(end, -1, `Unclosed ${name}`);
  return source.slice(start, end + ending.length);
}

const behavior = vm.runInNewContext(`(() => {
  ${constantSource('LIVE_IDLE_CALL_FIELDS', '];')}
  ${constantSource('LIVE_VOICE_QUALITY_FIELDS', '];')}
  ${functionSource('liveRowIsActive')}
  ${functionSource('livePresentedRow')}
  ${functionSource('livePresentedTableRows')}
  ${functionSource('liveIdentityRenderKey')}
  ${functionSource('liveDetailSelectionUnchanged')}
  ${functionSource('liveRequestedChannelMatch')}
  ${functionSource('livePickerNavigationIndex')}
  ${functionSource('liveDetailsPanelPercent')}
  ${functionSource('liveIdentityHasDisplayLabel')}
  ${functionSource('liveIdentityType')}
  ${functionSource('liveIdentityLabel')}
  ${functionSource('identityKind')}
  ${functionSource('rowGroupIdentityKind')}
  ${functionSource('groupIdentityLabel')}
  ${functionSource('activityTargetKind')}
  return { liveRowIsActive, livePresentedRow, livePresentedTableRows,
    liveIdentityRenderKey, liveDetailSelectionUnchanged, liveRequestedChannelMatch,
    livePickerNavigationIndex, liveDetailsPanelPercent, liveIdentityHasDisplayLabel,
    liveIdentityType, liveIdentityLabel,
    rowGroupIdentityKind, groupIdentityLabel, activityTargetKind };
})()`);

const preferences = {
  show_only_active_trunked_channels: true,
  retain_last_call_on_idle_rows: false,
  clear_voice_quality_when_idle: false
};
const row = (key, status, extra = {}) => ({ key, status, ...extra });
const keys = (rows) => JSON.parse(JSON.stringify(rows.map((value) => value.key)));

assert.equal(behavior.liveIdentityType({ target_form: 'PATCH_GROUP' }, 'target'), 'patch_group');
assert.equal(behavior.liveIdentityType({
  target_entity_ref: { kind: 'patch_group', radio_system_key: 'p25:bee00:49f',
    identity_key: 'v1-p-bee00-49f-4400' }
}, 'target'), 'patch_group');
assert.equal(behavior.liveIdentityLabel({ target_matcher: { type: 'patch_group' } }, 'target'), 'patch group');
assert.equal(behavior.liveIdentityLabel({ target_form: 'PATCH_GROUP' }, 'target', true), 'Patch Group');
assert.equal(behavior.liveIdentityType({}, 'target'), 'unknown');
assert.equal(behavior.liveIdentityLabel({}, 'target'), 'identity');
assert.equal(behavior.liveIdentityLabel({ target_form: 'UNKNOWN' }, 'target', true), 'Identity');
assert.equal(behavior.liveIdentityLabel({ target_form: 'TELEPHONE_NUMBER' }, 'target', true), 'Identity');
assert.equal(behavior.rowGroupIdentityKind({}), 'unknown');
assert.equal(behavior.rowGroupIdentityKind({ target_kind: 'telephone_number' }), 'unknown');
assert.equal(behavior.groupIdentityLabel({}), 'ID');
assert.equal(behavior.groupIdentityLabel({ target_kind: 'telephone_number' }, null, false), 'Identity');
assert.equal(behavior.activityTargetKind({ target_kind: 'patch_group' }), 'patch_group');
assert.equal(behavior.activityTargetKind({ target_kind: 'talkgroup' }), 'talkgroup');
assert.equal(behavior.activityTargetKind({ target_kind: 'radio' }), 'radio');
assert.equal(behavior.activityTargetKind({ target_kind: 'channel' }), '');

const sourceIdentity = {
  source_id: '1201', source_alias: 'Engine 1', source_aliases: [{ alias_id: 1, alias_list_id: 2 }],
  source_entity_ref: { kind: 'radio', identity_key: 'v1-r-bee00-49f-1201' },
  protocol: 'P25', signal_dbfs: -71, decode_health_pct: 99
};
assert.equal(behavior.liveIdentityRenderKey(sourceIdentity, 'source'),
  behavior.liveIdentityRenderKey({ ...sourceIdentity, signal_dbfs: -82, decode_health_pct: 91 }, 'source'),
  'Signal and decode updates must not replace an unchanged source cell');
assert.notEqual(behavior.liveIdentityRenderKey(sourceIdentity, 'source'),
  behavior.liveIdentityRenderKey({ ...sourceIdentity, source_id: '1202' }, 'source'));
const selected = { kind: 'CONTROL', role: 'CURRENT_CONTROL', logicalKey: 'CONTROL:channel',
  transportKey: 'channel:851012500:', rowKey: 'control', configurationId: 'channel',
  bindingFrequencyHz: 851012500, bindingTimeslot: null, label: 'Site', channelLabel: 'Site · LCN 1' };
assert.equal(behavior.liveDetailSelectionUnchanged(selected, { ...selected }), true);
assert.equal(behavior.liveDetailSelectionUnchanged(selected,
  { ...selected, transportKey: 'channel:852012500:', bindingFrequencyHz: 852012500 }), false);

assert.deepEqual(JSON.parse(JSON.stringify(behavior.liveRequestedChannelMatch({
  table_id: 'site-32', configuration_id: 'trunked-channel', rows: []
}, 'trunked-channel'))), { tableId: 'site-32', row: null },
  'A trunked Live deep link must match the table configuration');
const conventionalRow = { key: 'conventional-154.430', configuration_id: 'conventional-channel' };
assert.deepEqual(JSON.parse(JSON.stringify(behavior.liveRequestedChannelMatch({
  table_id: 'conventional', configuration_id: '', rows: [
    conventionalRow, { key: 'conventional-155.250', configuration_id: 'other-channel' }
  ]
}, ' conventional-channel '))), { tableId: 'conventional', row: conventionalRow },
  'A conventional Live deep link must match its row-level configuration');
assert.equal(behavior.liveRequestedChannelMatch({
  table_id: 'conventional', rows: [conventionalRow]
}, 'missing-channel'), null);
assert.equal(behavior.liveRequestedChannelMatch(null, 'conventional-channel'), null);
assert.equal(behavior.liveRequestedChannelMatch({ table_id: 'conventional' }, ''), null);

assert.equal(behavior.livePickerNavigationIndex('ArrowDown', 2, 3), 0);
assert.equal(behavior.livePickerNavigationIndex('ArrowRight', 0, 3), 1);
assert.equal(behavior.livePickerNavigationIndex('ArrowUp', 0, 3), 2);
assert.equal(behavior.livePickerNavigationIndex('ArrowLeft', 2, 3), 1);
assert.equal(behavior.livePickerNavigationIndex('Home', 2, 3), 0);
assert.equal(behavior.livePickerNavigationIndex('End', 0, 3), 2);
assert.equal(behavior.livePickerNavigationIndex('Enter', 0, 3), null);
assert.equal(behavior.livePickerNavigationIndex('ArrowDown', 0, 0), null);
assert.equal(behavior.livePickerNavigationIndex('ArrowDown', 0.5, 3), null);

assert.equal(behavior.liveDetailsPanelPercent(undefined), 25,
  'The Live details panel must have a stable default share');
assert.equal(behavior.liveDetailsPanelPercent(Number.NaN), 25);
assert.equal(behavior.liveDetailsPanelPercent(4), 15,
  'The resizer must retain a useful minimum for the details panel');
assert.equal(behavior.liveDetailsPanelPercent(90), 65,
  'The resizer must retain a useful minimum for the channel panel');
assert.equal(behavior.liveDetailsPanelPercent(33.6), 34,
  'Persisted panel shares must remain compact and deterministic');

assert.equal(behavior.liveIdentityHasDisplayLabel({ source_alias: 'Engine 4' }, 'source'), true,
  'A configured source alias must replace its numeric ID on compact Live rows');
assert.equal(behavior.liveIdentityHasDisplayLabel({ talker_alias: 'OTA Unit 312' }, 'source'), true,
  'An over-the-air source alias must replace its numeric ID on compact Live rows');
assert.equal(behavior.liveIdentityHasDisplayLabel({ source_alias_display: 'Composite label' }, 'source'), false,
  'Composite navigation metadata must not suppress the source ID');
assert.equal(behavior.liveIdentityHasDisplayLabel({ target_alias: 'Fire Dispatch' }, 'target'), true,
  'A configured target alias must replace its talkgroup ID on compact Live rows');
assert.equal(behavior.liveIdentityHasDisplayLabel({ talker_alias: 'Source only' }, 'target'), false,
  'Source-only OTA aliases must not suppress the target ID');
assert.equal(behavior.liveIdentityHasDisplayLabel({}, 'source'), false);

for (const status of ['CONTROL', 'ACTIVE', 'CALL', 'DATA', 'ENCRYPTED']) {
  assert.equal(behavior.liveRowIsActive(row(status, status, { activation_order: 1 })), true);
}
for (const status of ['IDLE', 'FADE', 'RESET', 'TEARDOWN']) {
  assert.equal(behavior.liveRowIsActive(row(status, status)), false);
}
assert.equal(behavior.liveRowIsActive(row('missing', 'CALL')), false,
  'An active-looking row without authoritative order must fail closed');
assert.equal(behavior.liveRowIsActive(row('unsafe', 'CALL', {
  activation_order: Number.MAX_SAFE_INTEGER + 1
})), false, 'An unsafe order must fail closed');

assert.deepEqual(keys(behavior.livePresentedTableRows({ table_id: 'site', rows: [
  row('control', 'CONTROL', { activation_order: 1 }),
  row('first', 'CALL', { activation_order: 2 }), row('idle', 'IDLE')
] }, preferences)), ['control', 'first']);
assert.deepEqual(keys(behavior.livePresentedTableRows({ table_id: 'site', rows: [
  row('new', 'CALL', { activation_order: 3 }), row('control', 'IDLE'),
  row('first', 'CALL', { activation_order: 2 })
] }, preferences)), ['first', 'new']);
assert.deepEqual(keys(behavior.livePresentedTableRows({ table_id: 'site', rows: [
  row('control', 'CONTROL', { activation_order: 4 }),
  row('new', 'CALL', { activation_order: 3 }),
  row('first', 'CALL', { activation_order: 2 })
] }, preferences)), ['first', 'new', 'control'],
'A coalesced snapshot must retain the server activation order when a control channel returns');
assert.deepEqual(keys(behavior.livePresentedTableRows({ table_id: 'site', rows: [
  row('control', 'CONTROL', { activation_order: 4 }),
  row('new', 'CALL', { activation_order: 3 }), row('first', 'IDLE')
] }, preferences)), ['new', 'control']);

const idle = row('conventional', 'IDLE', {
  source_id: '1201', source_alias: 'Engine 1', source_aliases: [{ alias_id: 1 }],
  target_id: '44', target_alias: 'Dispatch', talker_alias: 'CAR 1', encryption_details: 'AES',
  callsign: 'WPFF205', vc_quality_pct: 98, vc_decoded_frames: 50
});
const conventionalRows = behavior.livePresentedTableRows({ table_id: 'conventional', rows: [idle] },
  preferences);
assert.equal(conventionalRows.length, 1, 'Active-only filtering must never hide conventional channels');
assert.equal(conventionalRows[0].source_id, undefined);
assert.equal(conventionalRows[0].target_alias, undefined);
assert.equal(conventionalRows[0].callsign, 'WPFF205', 'Callsign describes the channel, not the completed call');
assert.equal(idle.source_id, '1201', 'Presentation must not mutate the shared Live row');
assert.equal(idle.vc_quality_pct, 98, 'Unrelated retained quality must remain on the source row');

const retained = behavior.livePresentedRow(idle, {
  retain_last_call_on_idle_rows: true, clear_voice_quality_when_idle: true
});
assert.equal(retained.source_id, '1201');
assert.equal(retained.target_alias, 'Dispatch');
assert.equal(retained.vc_quality_pct, undefined);
assert.equal(retained.vc_decoded_frames, undefined);

const untouched = behavior.livePresentedRow(idle, {
  retain_last_call_on_idle_rows: true, clear_voice_quality_when_idle: false
});
assert.equal(untouched, idle, 'Rows that need no presentation change should not be copied');

const channels = functionSource('liveChannelsSection');
const selectedViewAction = functionSource('liveSelectedViewAction');
const settingsActivation = functionSource('activateLivePresentationSettings');
assert.match(selectedViewAction, /ui-icon-button section-title-icon live-selected-view-action/,
  'Selected-view navigation must use the shared icon-button geometry');
assert.match(selectedViewAction, /return setIconButton\(action, iconId, label\)/,
  'Selected-view navigation must expose the shared accessible hover and focus hint');
assert.doesNotMatch(selectedViewAction, /node\('span'/,
  'Selected-view icon actions must not repeat long visible labels');
assert.match(channels, /titleActions\.append\(presentationSettings\)/,
  'Live presentation settings must remain visible regardless of preference-load state');
assert.doesNotMatch(channels,
  /if \(userPreferenceController\.snapshot\(\)\.loaded\) \{[\s\S]*presentationSettings/,
  'Preference loading must not remove the Live settings affordance');
assert.match(settingsActivation, /showLoginModal\(returnFocusSelector\)/,
  'Anonymous users must be offered sign-in when opening Live presentation settings');
assert.match(settingsActivation, /snapshot = await synchronizeUserPreferences\(\)/,
  'Signed-in users can retry a failed preference load from the Live settings action');
assert.doesNotMatch(channels, /activeRowOrders|activeOrders/,
  'Frontend ordering must come from the authoritative snapshot without duplicate state');
assert.match(channels, /liveTable\.tableController\.setSortable\(!activeFilter\)/,
  'Conventional tables stay sortable while active-only trunked tables retain activation order');
assert.match(channels, /liveDetailSelectionUnchanged\(selection, nextSelection\)/,
  'Repeated snapshots must not redispatch an unchanged selected row');
assert.match(channels, /liveTable\.tableController\.reconcileRows\(displayed\.rows\)/,
  'Live updates must reconcile stable row cells instead of rebuilding the table body');
assert.match(channels, /if \(!tableIds\.has\(tableId\)\) removeTable\(tableId\)/,
  'A resync must remove local tables absent from the authoritative snapshot');
assert.match(channels,
  /const nextSelection = liveDetailSelectionAfterRowsChanged\(displayed, selection\)/,
  'Live table updates must reconcile logical control intent before clearing a missing exact row');
assert.match(channels, /const requestedMatch = liveRequestedChannelMatch\(value, requestedChannel\)/,
  'Live deep links must resolve both table-level and row-level channel configurations');
assert.match(channels, /selectRow\(displayed, requestedRow\)/,
  'A conventional deep link must select the corresponding row');
assert.match(channels, /livePickerNavigationIndex\(event\.key, index, buttons\.length\)/,
  'The Live view picker must provide predictable keyboard navigation');
assert.match(channels, /storeLiveUiState\(\{ picker_collapsed: pickerCollapsed \}\)/,
  'The desktop picker collapse preference must persist across Live visits');
assert.match(channels, /picker\.closest\('\.live-split'\)\?\.classList\.toggle\('picker-collapsed', pickerCollapsed\)/,
  'Collapsing the picker must release its grid width to the selected channel workspace');
assert.match(channels, /selectedViewLead\.append\(pickerCollapse, selectedViewCopy\)/,
  'The picker disclosure must remain reachable in the selected-view heading when its sidebar is hidden');
assert.match(channels, /pickerCollapse\.setAttribute\('aria-controls', picker\.id\)/,
  'The picker disclosure must identify the panel that it hides');
assert.doesNotMatch(channels, /pickerActions\.(?:append|prepend)\(pickerCollapse\)/,
  'The picker disclosure must not remain inside the sidebar that it hides');
assert.match(channels, /mobileCards: true/,
  'Live activity must opt into its responsive card presentation');
assert.match(channels, /rowClass: activityRowClass/,
  'Live activity must expose semantic row state to its responsive presentation');
assert.match(channels,
  /channelTagSet\(row\.tags\)\.has\('CONVENTIONAL'\) \? '' : 'live-row-trunked'/,
  'Only trunked rows may hide their LCN in the compact mobile presentation');
assert.match(channels,
  /liveIdentityHasDisplayLabel\(row, 'source'\) \? 'live-row-has-source-label' : ''/,
  'Source-label availability must be reflected on each responsive row');
assert.match(channels,
  /liveIdentityHasDisplayLabel\(row, 'target'\) \? 'live-row-has-target-label' : ''/,
  'Target-label availability must be reflected on each responsive row');
assert.match(channels,
  /channelTagSet\(row\.tags\)\.has\('CONVENTIONAL'\) \? liveConventionalChannelValue\(row\) :[\s\S]*`LCN \$\{row\.lcn\}`/,
  'Conventional channel names must remain distinct from trunked LCN values');
assert.match(channels, /class="live-decode-quality-text"|node\('span', 'live-decode-quality-text', text\)/,
  'Decode quality retains an exact text value for desktop and accessibility');
assert.match(channels,
  /live-decode-quality-bars ui-quality-bars ui-quality-\$\{state\} ui-quality-level-\$\{level\}/,
  'Compact Live rows must represent decode quality with the shared signal-bar primitive');

const renderRow = functionSource('renderTableRow');
assert.match(renderRow, /cell\.dataset\.column = column\.id/,
  'Table cells expose stable semantic column identifiers for responsive layouts');
assert.match(renderRow, /row\.setAttribute\('aria-selected'/,
  'Selectable rows expose their selected state');
assert.match(renderRow, /row\.classList\.contains\('selected'\)/,
  'Selectable-row semantics follow the shared selected-row class');
assert.match(renderRow, /event\.key === 'Enter' \|\| event\.key === ' '/,
  'Selectable rows support keyboard activation');
assert.match(renderRow, /'ArrowDown', 'ArrowUp', 'Home', 'End'/,
  'Selectable rows support roving keyboard navigation');

const details = functionSource('liveEventsPanel');
assert.doesNotMatch(details, /live-details-summary|live-event-selection|live-events-toolbar/,
  'Selected-channel identity must not be repeated between the Live table and detail tabs');
assert.match(details, /iconButton\('icon-pause', 'Pause live details',[\s\S]*live-details-pause/,
  'The shared Live pause action must use the compact icon-button treatment');
assert.match(details, /iconButton\('icon-arrow-down', 'Collapse live details',[\s\S]*live-details-collapse/,
  'The Live details disclosure must use the compact icon-button treatment');
assert.match(details, /setIconButton\(pause, paused \? 'icon-play' : 'icon-pause'/,
  'The pause button icon and accessible hint must follow its state');
assert.match(details, /setIconButton\(collapse, collapsed \? 'icon-arrow-up' : 'icon-arrow-down'/,
  'The details disclosure icon and accessible hint must follow its state');
assert.match(details, /eventActions\.append\(eventColumnsHost, filters\.element\)/,
  'Columns and filters must share the active Events header actions');
assert.match(details, /layoutMenuHost: eventColumnsHost/,
  'The Events columns control must render directly in the compact details header');
assert.match(details, /eventPane\.append\(eventGap, eventsTable\)/,
  'The Events table must start immediately below the shared details header');
assert.match(details,
  /paneActionsHost\.append\(eventActions, messagesController\.actions, channelController\.actions\)/,
  'Each details tab must contribute actions to the one shared header');
assert.match(details, /typeof storedCollapsePreference === 'boolean'/,
  'An explicit details-tray preference must override the responsive default');
assert.match(details, /collapseMedia\.matches/,
  'The details tray starts collapsed on a narrow screen when no preference exists');
assert.match(details, /collapse\.setAttribute\('aria-controls', body\.id\)/,
  'The details disclosure identifies the controlled tray body');
assert.match(details, /collapseMedia\.addEventListener\('change', synchronizeResponsiveCollapse\)/,
  'The unsaved responsive default follows viewport changes');
assert.match(details, /collapseMedia\.removeEventListener\('change', synchronizeResponsiveCollapse\)/,
  'The responsive collapse listener is released with the Live page');
assert.match(details,
  /if \(persist && collapsed\) \{\s*setCollapsed\(false, collapsePreferenceExplicit\)/,
  'Selecting any details tab must expand a collapsed tray without replacing the responsive default');

const filters = functionSource('liveDetailFilterController');
assert.match(filters, /iconButton\('icon-filter', `Filter \$\{options\.noun\}`/,
  'Live detail filters must use the shared compact icon action');
assert.match(filters, /live-detail-filter-state ui-pill ui-pill-compact/,
  'The active Live event filter must be summarized as a compact pill');
assert.match(filters, /setIconButton\(trigger, 'icon-filter'/,
  'The filter hover hint must describe the current filter state');

const workspaceResizer = functionSource('liveWorkspaceResizer');
assert.match(workspaceResizer, /separator\.setAttribute\('role', 'separator'\)/,
  'The panel divider must expose separator semantics');
assert.match(workspaceResizer, /separator\.setAttribute\('aria-orientation', 'horizontal'\)/);
assert.match(workspaceResizer, /storeLiveUiState\(\{ details_panel_percent: detailsPercent \}\)/,
  'The chosen Live panel ratio must persist in the existing Live UI state');
assert.match(workspaceResizer, /\['ArrowUp', 'ArrowDown', 'Home', 'End'\]/,
  'The panel divider must support keyboard resizing');
assert.match(workspaceResizer, /separator\.setPointerCapture\(event\.pointerId\)/,
  'Pointer resizing must retain ownership until completion');
assert.match(workspaceResizer, /if \(activePointerCleanup \|\| event\.button !== 0/,
  'A second pointer must not replace an active resize gesture');
assert.match(workspaceResizer, /moveEvent\.pointerId !== pointerId/,
  'Only the pointer that started a resize may move the separator');
assert.match(workspaceResizer, /upEvent\?\.pointerId !== undefined && upEvent\.pointerId !== pointerId/,
  'Only the pointer that started a resize may finish the gesture');
for (const eventName of ['pointermove', 'pointerup', 'pointercancel', 'lostpointercapture']) {
  assert.match(workspaceResizer,
    new RegExp(`separator\\.removeEventListener\\('${eventName}'`),
    `The resizer must release its ${eventName} listener`);
}
assert.match(workspaceResizer, /window\.removeEventListener\('blur', cancel\)/,
  'The resizer must release its window lifecycle listener');
assert.match(workspaceResizer, /activePointerCleanup\?\.\(\)/,
  'Page cleanup must end an in-progress resize');

const renderLive = functionSource('renderLive');
assert.match(renderLive, /const workspaceResizer = liveWorkspaceResizer\(rightWorkspace\)/,
  'Live must create one resizer for its right-side workspace');
assert.match(renderLive, /pageConnections\.add\(workspaceResizer\)/,
  'The Live resizer must participate in page lifecycle cleanup');
assert.match(renderLive,
  /rightWorkspace\.append\(channels\.element, workspaceResizer\.element, eventsPanel\.element\)/,
  'The separator must render between the channel and details panels');
