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
  ${functionSource('function channelCreationProtocolLabel(profile)')}
  ${functionSource('function channelDefaultAliasListName(aliasFamily)')}
  ${functionSource('function channelCreationDefaults(channel, profile, options)')}
  ${functionSource('function channelCreationPrefill(channel, profile, initialChannel)')}
  ${functionSource('function spectrumDiscoveryManualChannel(selection, probe = {})')}
  ${functionSource('function setUiToggle(input, checked)')}
  const CHANNEL_ENCRYPTED_SKIP_PATH = 'settings.ignore_encrypted_calls';
  ${functionSource('function channelEncryptedCallSkipLocked(field, protocolCatalog)')}
  ${functionSource('function channelEditorFieldValue(control, field)')}
  ${functionSource('function channelIsRemote(channel)')}
  ${functionSource('function channelEditorSections(profile, channel)')}
  ${functionSource('function channelEditorPayload(form, profile, channel = null)')}
  ${functionSource('function channelEditorSectionId(value)')}
  ${functionSource('function channelEditorSectionPlan(sections)')}
  ${functionSource('function channelSquelchQuality(noise)')}
  ${functionSource('function channelSquelchNoise(quality)')}
`, context);

assert.equal(vm.runInContext(
  "channelCreationProtocolLabel({ id: 'p25-phase2', label: 'P25 Phase 2' })", context),
  'P25 Phase 2 TDMA Control Channel (Uncommon)');
assert.equal(vm.runInContext(
  "channelCreationProtocolLabel({ id: 'p25-phase1', label: 'P25 Phase 1' })", context), 'P25 Phase 1');
const creationDefaults = JSON.parse(vm.runInContext(`JSON.stringify(channelCreationDefaults(
  { alias_list_id: 10, name: 'Channel' },
  { id: 'p25-phase1', alias_family: 'P25' },
  { alias_lists: [
    { id: 10, name: 'Regional', family: 'P25' },
    { id: 11, name: ' Default P25 ', family: 'P25' },
    { id: 12, name: 'Default DMR', family: 'DMR' }
  ] }
))`, context));
assert.equal(creationDefaults.alias_list_id, 11,
  'A new channel should prefer the protocol-compatible Default alias list');
assert.equal(vm.runInContext("channelDefaultAliasListName('NBFM')", context), 'Default Analog',
  'The analog protocol family must use its canonical factory Alias List name');
const unchangedDefaults = JSON.parse(vm.runInContext(`JSON.stringify(channelCreationDefaults(
  { alias_list_id: 10 }, { alias_family: 'P25' },
  { alias_lists: [{ id: 12, name: 'Default DMR', family: 'DMR' }] }
))`, context));
assert.equal(unchangedDefaults.alias_list_id, 10,
  'The template choice should remain when the selected protocol has no Default alias list');

const manualDraft = JSON.parse(vm.runInContext(`JSON.stringify(spectrumDiscoveryManualChannel(
  { frequencyHz: 774706250, tunerName: 'Receiver A', tunerId: 'private-id' },
  { c4fm: { valid_control_messages: 38, quality_pct: 3 },
    cqpsk: { valid_control_messages: 0, quality_pct: 0 },
    identity: { wacn: 123, system: 456, site: 7 } }
))`, context));
assert.deepEqual(manualDraft, { protocol_id: 'p25-phase1', source: {
  frequencies_hz: [774706250], preferred_frequency_hz: 774706250, preferred_tuner: 'Receiver A'
}, settings: { modulation: 'C4FM' } },
'Manual setup carries frequency and winning modulation without inventing verified identity');
for (const [winner, other] of [['cqpsk', 'c4fm'], ['c4fm', 'cqpsk']]) {
  const zeroScoreWinner = JSON.parse(vm.runInContext(`JSON.stringify(spectrumDiscoveryManualChannel(
    { frequencyHz: 774706250 }, ${JSON.stringify({
      [winner]: { valid_control_messages: 1, quality_pct: 0 },
      [other]: { valid_control_messages: 0, quality_pct: 0 }
    })}
  ).settings)`, context));
  assert.deepEqual(zeroScoreWinner, { modulation: winner.toUpperCase() },
    'A sole decoder with valid control evidence wins even at zero score');
}
assert.deepEqual(JSON.parse(vm.runInContext(`JSON.stringify(spectrumDiscoveryManualChannel(
  { frequencyHz: 774706250 }, { c4fm: { valid_control_messages: 38, quality_pct: 3 },
    cqpsk: { valid_control_messages: 38, quality_pct: 3 } }
).settings)`, context)), {}, 'An exact tie leaves modulation for manual selection');
const prefilled = JSON.parse(vm.runInContext(`JSON.stringify(channelCreationPrefill(
  { protocol_id: 'p25-phase1', name: 'Template', alias_list_id: 11, observed: {},
    source: { rotation_delay_ms: 500 }, settings: { traffic_channel_pool_size: 20, modulation: 'CQPSK' } },
  { id: 'p25-phase1' },
  { protocol_id: 'p25-phase1', observed: { p25_site_identity: 'unverified' }, system: 'Unverified system',
    source: { frequencies_hz: [774706250] }, settings: { modulation: 'C4FM' } }
))`, context));
assert.deepEqual(prefilled.source, { rotation_delay_ms: 500, frequencies_hz: [774706250] });
assert.deepEqual(prefilled.settings, { traffic_channel_pool_size: 20, modulation: 'C4FM' });
assert.equal(prefilled.alias_list_id, 11);
assert.deepEqual(prefilled.observed, {});
assert.equal(prefilled.system, undefined);

assert.equal(vm.runInContext(`channelEncryptedCallSkipLocked(
  { path: 'settings.ignore_encrypted_calls' }, { voice_decryption_module_loaded: true })`, context), true);
assert.equal(vm.runInContext(`channelEncryptedCallSkipLocked(
  { path: 'settings.ignore_encrypted_calls' }, { voice_decryption_module_loaded: false })`, context), false);
assert.equal(vm.runInContext(`channelEditorFieldValue(
  { checked: false, dataset: { channelPreservedValue: 'true' } }, { type: 'boolean' })`, context), true,
  'A forced-off encrypted-call control must submit its preserved saved preference');
assert.equal(vm.runInContext(`channelEditorFieldValue(
  { checked: false, dataset: {} }, { type: 'boolean' })`, context), false,
  'An ordinary boolean control must submit its displayed value');
const lockedToggle = JSON.parse(vm.runInContext(`JSON.stringify((() => {
  const state = { textContent: 'On' };
  const input = { checked: true, closest: () => ({ querySelector: () => state }) };
  setUiToggle(input, false);
  return { checked: input.checked, state: state.textContent };
})())`, context));
assert.deepEqual(lockedToggle, { checked: false, state: 'Off' },
  'Forcing the effective setting off must update both the checkbox and its rendered state label');
assert.match(application, /Encrypted calls are processed while voice decryption is loaded/);
assert.match(application, /setUiToggle\(dataControl, false\)/);
assert.match(application, /dataControl\.disabled = true/);

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
assert.match(channelCatalog, /revealRowGroups: \(\) => Boolean\(search\.value\.trim\(\)\)/);
assert.match(channelCatalog, /value: 'grouped', label: 'Grouped'/);
assert.match(channelCatalog, /value: 'auto-start', label: 'Startup order'/);
assert.match(channelCatalog, /activeCatalogView === 'grouped'/,
  'Only the grouped view should supply protocol row groups');
assert.match(channelCatalog, /activeCatalogView === 'auto-start' \? channelAutoStartOrder : channelProtocolOrder/);
assert.match(channelCatalog, /wrapper: channelTable \|\| undefined/,
  'Switching views should reuse the table host instead of rebuilding the page');
assert.match(channelCatalog, /requestChannelConfigurationJson\('\/api\/v1\/admin\/channels'/,
  'A startup-order mutation should reconcile against a confirmed catalog response');
assert.match(channelCatalog, /window\.scrollTo\(scrollPosition\.x, scrollPosition\.y\)/,
  'Startup-order reconciliation must restore the current page position');
assert.match(channelCatalog, /focus\(\{ preventScroll: true \}\)/,
  'Startup-order reconciliation must restore keyboard focus without moving the page');
assert.match(channelCatalog, /Select all matching channels/);
assert.match(channelCatalog, /label: 'All statuses'/);
assert.match(channelCatalog, /Filter channels by status/);
assert.doesNotMatch(channelCatalog, /value: 'trunked'|value: 'conventional'/,
  'Protocol grouping replaces the mixed topology selector');
const protocolContext = { protocolFamily: (row) => row.protocol };
vm.createContext(protocolContext);
vm.runInContext(`
  ${functionSource('function channelProtocolGroup(row)')}
  ${functionSource('function channelProtocolOrder(left, right)')}
  ${functionSource('function channelAutoStartOrder(left, right)')}
  ${functionSource('function channelCatalogAfterAutoStartMove(catalog, configurationId, direction)')}
`, protocolContext);
assert.deepEqual(JSON.parse(vm.runInContext(
  "JSON.stringify(channelProtocolGroup({ protocol_id: 'p25-phase2', protocol_label: 'P25 Phase 2' }))",
  protocolContext)),
{ key: 'p25-trunked', label: 'P25 Trunked' });
assert.deepEqual(JSON.parse(vm.runInContext(
  "JSON.stringify(channelProtocolGroup({ protocol_id: 'unsupported', protocol_label: 'Legacy Decoder' }))",
  protocolContext)), { key: 'unsupported.legacy-decoder', label: 'Legacy Decoder' });
assert.deepEqual(JSON.parse(vm.runInContext(`JSON.stringify([
  { name: 'Zulu', protocol_id: 'p25-phase2', protocol_label: 'P25 Phase 2' },
  { name: 'Bravo', protocol_id: 'nbfm', protocol_label: 'NBFM' },
  { name: 'Alpha', protocol_id: 'nbfm', protocol_label: 'NBFM' }
].sort(channelProtocolOrder).map((row) => row.name))`, protocolContext)), ['Zulu', 'Alpha', 'Bravo']);
const protocolGroups = JSON.parse(vm.runInContext(`JSON.stringify([
  { protocol_id: 'am', protocol_label: 'AM' },
  { protocol_id: 'unsupported', protocol_label: 'Legacy Decoder' },
  { protocol_id: 'nxdn', protocol_label: 'NXDN', channel_kind: 'CONVENTIONAL' },
  { protocol_id: 'dmr', protocol_label: 'DMR', channel_kind: 'CONVENTIONAL' },
  { protocol_id: 'p25-conventional', protocol_label: 'P25 Conventional' },
  { protocol_id: 'nbfm', protocol_label: 'NBFM' },
  { protocol_id: 'nxdn', protocol_label: 'NXDN', channel_kind: 'TRUNKED' },
  { protocol_id: 'p25-phase1', protocol_label: 'P25 Phase 1', name: 'Alpha' },
  { protocol_id: 'dmr', protocol_label: 'DMR', channel_kind: 'TRUNKED' },
  { protocol_id: 'p25-phase2', protocol_label: 'P25 Phase 2', name: 'Bravo' }
].sort(channelProtocolOrder).map(channelProtocolGroup))`, protocolContext));
assert.deepEqual(protocolGroups.map((group) => group.label), [
  'P25 Trunked', 'P25 Trunked', 'P25 Conventional', 'DMR Trunked', 'DMR Conventional',
  'NXDN Trunked', 'NXDN Conventional', 'FM', 'AM', 'Legacy Decoder'
], 'Channel groups follow receiver protocol order and leave compatibility groups last');
assert.equal(protocolGroups[0].key, protocolGroups[1].key,
  'Both P25 trunked profiles share one group');
const startupRows = JSON.parse(vm.runInContext(`JSON.stringify([
  { configuration_id: 'off-zulu', name: 'Zulu', protocol_id: 'p25', auto_start_order: null },
  { configuration_id: 'third', name: 'Third', protocol_id: 'nbfm', auto_start_order: 3 },
  { configuration_id: 'first', name: 'First', protocol_id: 'dmr', auto_start_order: 1 },
  { configuration_id: 'off-alpha', name: 'Alpha', protocol_id: 'am', auto_start_order: null },
  { configuration_id: 'second', name: 'Second', protocol_id: 'p25', auto_start_order: 2 }
].sort(channelAutoStartOrder).map((row) => row.configuration_id))`, protocolContext));
assert.deepEqual(startupRows, ['first', 'second', 'third', 'off-alpha', 'off-zulu'],
  'Startup-order view must ignore protocol, show enabled channels numerically, and keep disabled channels last');
const movedEarlier = JSON.parse(vm.runInContext(`JSON.stringify(channelCatalogAfterAutoStartMove({
  revision: 8,
  channels: [
    { configuration_id: 'first', auto_start_order: 1 },
    { configuration_id: 'second', auto_start_order: 2 },
    { configuration_id: 'third', auto_start_order: 3 },
    { configuration_id: 'off', auto_start_order: null }
  ]
}, 'third', 'EARLIER').channels.map((row) => [row.configuration_id, row.auto_start_order]))`,
protocolContext));
assert.deepEqual(movedEarlier, [['first', 1], ['second', 3], ['third', 2], ['off', null]],
  'Moving earlier must swap the selected channel with its immediate startup predecessor');
const enabledAtEnd = JSON.parse(vm.runInContext(`JSON.stringify(channelCatalogAfterAutoStartMove({
  channels: [
    { configuration_id: 'first', auto_start_order: 1 },
    { configuration_id: 'second', auto_start_order: 2 },
    { configuration_id: 'off', auto_start_order: null }
  ]
}, 'off', 'EARLIER').channels.map((row) => [row.configuration_id, row.auto_start_order]))`,
protocolContext));
assert.deepEqual(enabledAtEnd, [['first', 1], ['second', 2], ['off', 3]],
  'Moving a disabled channel earlier must enable it at the end of the startup queue');
const disabledAtEnd = JSON.parse(vm.runInContext(`JSON.stringify(channelCatalogAfterAutoStartMove({
  channels: [
    { configuration_id: 'first', auto_start_order: 1 },
    { configuration_id: 'second', auto_start_order: 2 }
  ]
}, 'second', 'LATER').channels.map((row) => [row.configuration_id, row.auto_start_order]))`,
protocolContext));
assert.deepEqual(disabledAtEnd, [['first', 1], ['second', null]],
  'Moving the final startup channel later must disable its auto-start setting');
vm.runInContext(`${functionSource('function radioDirectoryConventionalGroups(rows)')}`, protocolContext);
const conventionalGroups = JSON.parse(vm.runInContext(`JSON.stringify(radioDirectoryConventionalGroups([
  { name: 'Dispatch 2', system: 'County Radio', site: 'West', protocol_label: 'NBFM',
    frequencies_hz: [155200000], alias_list_id: 7, alias_list_name: 'Default Analog', processing_state: 'RUNNING' },
  { name: 'Dispatch 10', system: 'County Radio', site: 'East', protocol_label: 'NBFM',
    frequencies_hz: [155100000], alias_list_id: 7, alias_list_name: 'Default Analog', processing_state: 'STOPPED' },
  { name: 'Untitled', system: '', site: '', protocol_label: 'AM', frequencies_hz: [121900000],
    alias_list_id: 8, alias_list_name: 'Airport', processing_state: 'STOPPED' }
]))`, protocolContext));
assert.deepEqual(conventionalGroups.map((group) => group.system_name), ['County Radio', 'Other channels'],
  'Conventional channels should group by system and keep unassigned channels together at the end');
assert.deepEqual(conventionalGroups[0].children.map((row) => row.name), ['Dispatch 10', 'Dispatch 2'],
  'Conventional channels should sort by site, then channel name, then frequency within a system');
assert.equal(conventionalGroups[0].running_count, 1);
assert.deepEqual(conventionalGroups[0].alias_lists, [{ id: 7, name: 'Default Analog' }]);
const channelModal = functionSource('async function openChannelEditorModal(');
assert.match(channelModal, /requestJson\('\/api\/v1\/admin\/channels\/protocols'/,
  'Each editor open must refresh the current voice-decryption module state');
assert.doesNotMatch(channelModal, /prefetched\?\.protocols/,
  'The editor must not reuse a stale module-state snapshot from the channel catalog page');
assert.match(channelModal, /if \(!editing\) channel = channelCreationDefaults\(channel, profile, options\)/,
  'Creation defaults must not alter an existing channel');
assert.match(channelModal, /label: channelCreationProtocolLabel\(candidate\)/,
  'The creation protocol selector must use its purpose-specific labels');
assert.match(channelModal, /action, configuration_ids: \[configurationId\]/,
  'The editor must start or stop only its own channel');
assert.match(channelModal, /modal\.isDirty\(\)/,
  'Start and Stop must not discard unsaved channel settings');
assert.match(channelModal, /aliasListPopupTrigger\(\{/,
  'Channel setup must offer the shared Alias List creator');
assert.match(channelModal, /getRevision: currentAliasListRevision/,
  'Inline Alias List creation must use the Alias configuration revision');
assert.match(channelModal, /control\.dispatchEvent\(new Event\('change', \{ bubbles: true \}\)\)/,
  'A newly created Alias List must follow the normal channel-field change path');
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

const remoteProfile = { id: 'p25-phase1', sections: [
  { id: 'general', label: 'General', fields: [{ path: 'name', type: 'text' }] },
  { id: 'source', label: 'Source', fields: [
    { path: 'source.frequencies_hz', type: 'frequency_list' },
    { path: 'source.rotation_delay_ms', type: 'integer' }
  ] },
  { id: 'protocol', label: 'Decoder', fields: [
    { path: 'settings.modulation', type: 'enum' },
    { path: 'settings.traffic_channel_pool_size', type: 'integer' },
    { path: 'settings.ignore_data_calls', type: 'boolean' },
    { path: 'settings.ignore_encrypted_calls', type: 'boolean' },
    { path: 'settings.learn_announced_control_channels', type: 'boolean' },
    { path: 'settings.use_bandplan_override', type: 'boolean' }
  ] },
  { id: 'output', label: 'Logging & Recording', fields: [
    { path: 'event_logs', type: 'multi_select' }, { path: 'recorders', type: 'multi_select' }
  ] }
] };
const remoteChannel = {
  source: { source_type: 'REMOTE', sender_id: 'sender', feed_id: 'feed',
    frequencies_hz: [851_012_500], rotation_delay_ms: null },
  recorders: ['BASEBAND'],
  settings: { modulation: 'CQPSK', traffic_channel_pool_size: 20, ignore_data_calls: false,
    ignore_encrypted_calls: false, learn_announced_control_channels: true,
    use_bandplan_override: false }
};
context.remoteProfile = remoteProfile;
context.remoteChannel = remoteChannel;
context.CSS = { escape: (value) => value };
const remoteSections = JSON.parse(vm.runInContext(
  'JSON.stringify(channelEditorSections(remoteProfile, remoteChannel))', context));
assert.deepEqual(remoteSections.map((section) => [section.id, section.label,
  section.fields.map((field) => field.path)]), [
  ['general', 'General', ['name']],
  ['source', 'Remote source', []],
  ['protocol', 'Decoder', ['settings.traffic_channel_pool_size', 'settings.use_bandplan_override']],
  ['output', 'Event logs', ['event_logs']]
]);
assert.deepEqual(JSON.parse(vm.runInContext(
  `JSON.stringify(channelEditorSections(remoteProfile, { source: { source_type: 'TUNER' } }))`, context)),
  remoteProfile.sections, 'Local channel settings must remain available');
context.remoteForm = {
  querySelector(selector) {
    const path = selector.match(/data-channel-path="([^"]+)"/)?.[1];
    if (path === 'name') return { value: 'Remote renamed' };
    if (path === 'settings.traffic_channel_pool_size') return { value: '12' };
    if (path === 'settings.use_bandplan_override') return { checked: true, dataset: {} };
    if (path === 'event_logs') return { querySelectorAll: () => [{ value: 'CALL_EVENT' }] };
    throw new Error(`Unexpected remote field ${path}`);
  }
};
context.remoteEditorProfile = { ...remoteProfile, sections: remoteSections };
const remotePayload = JSON.parse(vm.runInContext(
  'JSON.stringify(channelEditorPayload(remoteForm, remoteEditorProfile, remoteChannel))', context));
assert.deepEqual(remotePayload.source, remoteChannel.source,
  'Editing receiver settings must preserve remote routing and its saved frequency');
assert.equal(remotePayload.name, 'Remote renamed');
assert.equal(remotePayload.settings.traffic_channel_pool_size, 12);
assert.equal(remotePayload.settings.modulation, 'CQPSK',
  'Hidden decoder settings must retain their saved values');
assert.equal(remotePayload.settings.learn_announced_control_channels, true);
assert.equal(remotePayload.settings.use_bandplan_override, true);
assert.deepEqual(remotePayload.event_logs, ['CALL_EVENT']);
assert.deepEqual(remotePayload.recorders, ['BASEBAND'],
  'An unrelated remote edit must not silently discard saved recorder selections');

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
