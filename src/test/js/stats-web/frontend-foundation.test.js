'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const core = path.resolve(process.argv[2] || path.resolve(__dirname, '../../../../stats-web/assets/core'));
const appSource = fs.readFileSync(path.resolve(core, '../app.js'), 'utf8');
const appCssSource = fs.readFileSync(path.resolve(core, '../app.css'), 'utf8');
const indexSource = fs.readFileSync(path.resolve(core, '../../index.html'), 'utf8');
const playerSource = fs.readFileSync(path.resolve(core, '../web-call-player.js'), 'utf8');

function closingDelimiter(source, start, open = '(', close = ')') {
  let depth = 0;
  let quote = '';
  let lineComment = false;
  let blockComment = false;
  for (let index = start; index < source.length; index += 1) {
    const character = source[index];
    const next = source[index + 1];
    if (lineComment) {
      if (character === '\n') lineComment = false;
      continue;
    }
    if (blockComment) {
      if (character === '*' && next === '/') {
        blockComment = false;
        index += 1;
      }
      continue;
    }
    if (quote) {
      if (character === '\\') index += 1;
      else if (character === quote) quote = '';
      continue;
    }
    if (character === '/' && next === '/') {
      lineComment = true;
      index += 1;
      continue;
    }
    if (character === '/' && next === '*') {
      blockComment = true;
      index += 1;
      continue;
    }
    if (character === '\'' || character === '"' || character === '`') {
      quote = character;
      continue;
    }
    if (character === open) depth += 1;
    else if (character === close && --depth === 0) return index;
  }
  throw new Error(`Unclosed ${open} at ${start}`);
}

function topLevelArguments(source) {
  const values = [];
  let start = 0;
  const depths = { '(': 0, '[': 0, '{': 0 };
  const closing = { ')': '(', ']': '[', '}': '{' };
  let quote = '';
  let lineComment = false;
  let blockComment = false;
  for (let index = 0; index < source.length; index += 1) {
    const character = source[index];
    const next = source[index + 1];
    if (lineComment) {
      if (character === '\n') lineComment = false;
      continue;
    }
    if (blockComment) {
      if (character === '*' && next === '/') {
        blockComment = false;
        index += 1;
      }
      continue;
    }
    if (quote) {
      if (character === '\\') index += 1;
      else if (character === quote) quote = '';
      continue;
    }
    if (character === '/' && next === '/') {
      lineComment = true;
      index += 1;
      continue;
    }
    if (character === '/' && next === '*') {
      blockComment = true;
      index += 1;
      continue;
    }
    if (character === '\'' || character === '"' || character === '`') {
      quote = character;
      continue;
    }
    if (Object.hasOwn(depths, character)) depths[character] += 1;
    else if (closing[character]) depths[closing[character]] -= 1;
    else if (character === ',' && Object.values(depths).every((depth) => depth === 0)) {
      values.push(source.slice(start, index).trim());
      start = index + 1;
    }
  }
  values.push(source.slice(start).trim());
  return values;
}

function functionCalls(source, name) {
  const calls = [];
  const expression = new RegExp(`\\b${name}\\s*\\(`, 'g');
  let match;
  while ((match = expression.exec(source))) {
    if (/function\s*$/.test(source.slice(Math.max(0, match.index - 20), match.index))) continue;
    const open = source.indexOf('(', match.index);
    const close = closingDelimiter(source, open);
    calls.push({ index: match.index, arguments: topLevelArguments(source.slice(open + 1, close)) });
    expression.lastIndex = close + 1;
  }
  return calls;
}

function arrayBinding(source, name) {
  const start = source.indexOf(`const ${name} = [`);
  assert.notEqual(start, -1, `${name} must be declared`);
  const open = source.indexOf('[', start);
  return source.slice(open, closingDelimiter(source, open, '[', ']') + 1);
}

function functionBinding(source, name) {
  const start = source.indexOf(`function ${name}(`);
  assert.notEqual(start, -1, `${name} must be declared`);
  const open = source.indexOf('{', start);
  return source.slice(open, closingDelimiter(source, open, '{', '}') + 1);
}

async function loadModule(name) {
  const source = fs.readFileSync(path.join(core, `${name}.js`), 'utf8');
  return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
}

function response(status, body) {
  return { ok: status >= 200 && status < 300, status, json: async () => body };
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((resolveValue, rejectValue) => {
    resolve = resolveValue;
    reject = rejectValue;
  });
  return { promise, resolve, reject };
}

async function main() {
  const [routes, preferences, preferenceSchema, tableLayouts, pageTitles, entityRefs, playerModule] =
    await Promise.all([
    'routes', 'user-preferences', 'preference-schema', 'table-layout', 'page-title', 'entity-ref',
    '../web-call-player'
  ].map(loadModule));
  const stableId = /^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$/;
  assert.match(appSource, /const tableType = tableLayouts\.tableId\(options\.type\)/);
  assert.match(appSource,
    /const defaultSchema = tableLayouts\.registerSchema\(tableSchemaRegistry, tableType, declaredColumns\)/);
  assert.match(appSource, /const wrapper = options\.wrapper \|\| node\('div'\)/);
  assert.match(appSource, /controller: tableController, wrapper/);
  assert.doesNotMatch(appSource, /wrapper\.replaceWith\(table\(/);
  assert.doesNotMatch(appSource, /Alias List ID/);
  assert.doesNotMatch(appSource, /\(#/);
  assert.doesNotMatch(appSource, /scopeAliasListLabel|credential_version|credentialVersion/);
  assert.doesNotMatch(appSource, /identity_detail_available|detail_available/);
  assert.doesNotMatch(appSource, /web-display-settings/);
  assert.doesNotMatch(playerSource,
    /logical_call_id|matched_scan_list_ids|start_timestamp_ms|default_selected|control_url|ack_url|listener_token/);
  assert.doesNotMatch(playerSource, /row\?\.scan_list_id|value\?\.maximumSelectedScanLists/);
  assert.doesNotMatch(appSource, /defaultSelected|scanList\?\.scan_list_id/);
  assert.match(functionBinding(appSource, 'aliasScanListChoices'), /Number\(scanList\?\.id\)/);
  assert.match(functionBinding(appSource, 'showUserPreferenceError'), /'Retry'/);
  assert.match(functionBinding(appSource, 'showUserPreferenceError'), /'Dismiss'/);
  assert.match(functionBinding(appSource, 'updateUserPreferences'),
    /showUserPreferenceError\(error, \(\) => settleUserPreferenceMutation\(mutator\)\)/);
  assert.match(functionBinding(appSource, 'settleUserPreferenceMutation'), /\.catch\(\(\) => null\)/);
  assert.match(functionBinding(appSource, 'saveTableLayoutPreference'), /return settleUserPreferenceMutation/);
  assert.doesNotMatch(appSource, /void updateUserPreferences\(/);
  assert.match(indexSource,
    /id="preference-status" class="preference-status" role="status" aria-live="polite" hidden/);
  assert.match(indexSource, /id="global-status" class="visually-hidden"/);
  assert.match(appCssSource, /\.preference-status \{/);
  const settingsSource = functionBinding(appSource, 'renderSettings');
  assert.match(settingsSource, /const latest = userPreferenceController\.snapshot\(\)/);
  assert.match(settingsSource, /await updateUserPreferences\(\(preferences\) =>/);
  assert.match(settingsSource,
    /preferences\.playback\.selected_scan_list_ids\.filter\(\(id\) => !availableScanListIds\.has\(id\)\)/);
  assert.doesNotMatch(settingsSource, /userPreferenceController\.replace\(/);
  assert.match(appSource, /table\(tableController\.rows\(\), declaredColumns/);
  assert.match(appSource, /dataRows = prepend \? dataRows\.slice\(0, limit\) : dataRows\.slice\(-limit\)/);
  assert.match(appSource, /rows: \(\) => dataRows\.slice\(\)/);
  const playbackAccessSource = functionBinding(appSource, 'synchronizePlaybackAccess');
  assert.match(playbackAccessSource, /if \(!userPreferenceController\.snapshot\(\)\.loaded\) return/);
  const siteSettingsRequestSource = functionBinding(appSource, 'requestSiteSettings');
  assert.match(siteSettingsRequestSource, /headers\['If-Match'\] = `"\$\{revision\}"`/);
  const siteSettingsSource = functionBinding(appSource, 'renderAdminSiteBehaviorSettings');
  assert.match(siteSettingsSource, /error\?\.code === 'site_settings_conflict'/);
  assert.match(siteSettingsSource, /apply\(error\.current\)/);
  assert.match(siteSettingsSource, /Current server values were reloaded/);
  assert.doesNotMatch(appSource, /row\.id \?\? row\.scan_list_id|row\.scan_list_id \?\? row\.id/);
  const decodeSiteSettings = vm.runInNewContext(
    `(function(value) ${functionBinding(appSource, 'decodeSiteSettingsEnvelope')})`);
  assert.deepEqual(JSON.parse(JSON.stringify(decodeSiteSettings({
    revision: 2,
    settings: {
      retain_idle_call_details: false,
      clear_voice_decode_quality_on_call_end: true,
      traffic_grant_age_out_milliseconds: 1000
    }
  }))), {
    revision: 2,
    settings: {
      retain_idle_call_details: false,
      clear_voice_decode_quality_on_call_end: true,
      traffic_grant_age_out_milliseconds: 1000
    }
  });
  assert.throws(() => decodeSiteSettings({
    revision: 0,
    settings: {
      retain_idle_call_details: false,
      clear_voice_decode_quality_on_call_end: true,
      traffic_grant_age_out_milliseconds: 1000
    }
  }), /invalid Site Settings/);
  const siteRequests = [];
  const siteResponses = [];
  const requestSiteSettings = vm.runInNewContext(
    `(async function(method = 'GET', settings = null, revision = null) ${
      functionBinding(appSource, 'requestSiteSettings')})`, {
      decodeSiteSettingsEnvelope: decodeSiteSettings,
      jsonDocumentFetch: async (url, options) => {
        siteRequests.push([url, options]);
        return siteResponses.shift();
      }
    });
  const requestedSiteSettings = {
    retain_idle_call_details: true,
    clear_voice_decode_quality_on_call_end: false,
    traffic_grant_age_out_milliseconds: 1200
  };
  siteResponses.push(response(200, { revision: 4, settings: requestedSiteSettings }));
  assert.deepEqual(JSON.parse(JSON.stringify(await requestSiteSettings('PUT', requestedSiteSettings, 3))),
    { revision: 4, settings: requestedSiteSettings });
  assert.equal(siteRequests.at(-1)[0], '/api/v1/admin/site-settings');
  assert.equal(siteRequests.at(-1)[1].headers['If-Match'], '"3"');
  assert.equal(siteRequests.at(-1)[1].body, JSON.stringify(requestedSiteSettings));
  const currentSiteSettings = {
    retain_idle_call_details: false,
    clear_voice_decode_quality_on_call_end: true,
    traffic_grant_age_out_milliseconds: 900
  };
  siteResponses.push(response(409, { revision: 5, settings: currentSiteSettings }));
  await assert.rejects(requestSiteSettings('PUT', requestedSiteSettings, 4), (error) => {
    assert.equal(error.code, 'site_settings_conflict');
    assert.deepEqual(JSON.parse(JSON.stringify(error.current)),
      { revision: 5, settings: currentSiteSettings });
    return true;
  });
  const tableCalls = functionCalls(appSource, 'table');
  assert.equal(tableCalls.length, 30, 'Every application table call must be audited');
  tableCalls.forEach((call) => {
    assert.ok(call.arguments.length >= 4, `Table call at ${call.index} is missing its options argument`);
    const options = call.arguments[3];
    assert.match(options, /(?:\btype\s*:|\.\.\.options)/,
      `Table call at ${call.index} is missing a stable table type`);
    const literal = options.match(/\btype\s*:\s*'([^']+)'/);
    if (literal) assert.match(literal[1], stableId, `Invalid table type ${literal[1]}`);
  });
  [
    'aliasCatalogCoreColumns', 'aliasCustomConfigurationColumns', 'aliasCatalogEnrichmentColumns',
    'aliasEditorScopeBreakdownColumns', 'aliasEditorBaseColumns', 'scanListMemberColumns',
    'dashboardIdentityColumns', 'systemRadioColumns', 'p25SiteChannelColumns',
    'trunkedSiteChannelColumns', 'p25SiteNeighborColumns', 'trunkedSiteNeighborColumns',
    'activityColumns', 'conventionalColumns', 'conventionalTalkgroupColumns',
    'conventionalRadioColumns'
  ].forEach((name) => {
    const ids = [...functionBinding(appSource, name).matchAll(/\bid\s*:\s*'([^']+)'/g)]
      .map((match) => match[1]);
    assert.ok(ids.length, `${name} must declare column IDs`);
    ids.forEach((id) => assert.match(id, stableId, `${name} has invalid column ID ${id}`));
    assert.equal(new Set(ids).size, ids.length, `${name} repeats a column ID`);
  });
  [
    'siteColumns', 'dashboardHealthColumns', 'dashboardCallSourceColumns',
    'dashboardActivityRadioColumns', 'talkgroupColumns'
  ].forEach((name) => {
    const ids = [...arrayBinding(appSource, name).matchAll(/\bid\s*:\s*'([^']+)'/g)]
      .map((match) => match[1]);
    assert.ok(ids.length, `${name} must declare column IDs`);
    ids.forEach((id) => assert.match(id, stableId, `${name} has invalid column ID ${id}`));
    assert.equal(new Set(ids).size, ids.length, `${name} repeats a column ID`);
  });
  const siteDescriptors = vm.runInNewContext(`(${arrayBinding(appSource, 'siteColumns')})`, {
    systemLink: () => '', systemLabel: () => '', siteNameSummary: () => '', siteLabel: () => '',
    frequency: () => '', dateTime: () => '',
    hex: (value, width) => Number.isInteger(value) ? value.toString(16).toUpperCase().padStart(width, '0') : ''
  });
  const siteIdColumn = siteDescriptors.find((column) => column.id === 'site');
  assert.equal(siteIdColumn.render({ site_id: 1 }), '01');
  assert.equal(siteIdColumn.render({ site: 1 }), '', 'Legacy site fields must not be inferred');

  const radioTableType = vm.runInNewContext(
    `(function(baseType, columns) ${functionBinding(appSource, 'radioTableType')})`, { tableLayouts });
  assert.equal(radioTableType('radios', [{ id: 'radio' }]), 'radios.base');
  assert.equal(radioTableType('radios', [{ id: 'radio' }, { id: 'talker-alias' }]),
    'radios.talker-alias');
  assert.equal(radioTableType('radios', [
    { id: 'radio' }, { id: 'talker-alias' }, { id: 'affiliation' }, { id: 'affiliated-site' }
  ]), 'radios.talker-alias-affiliation-site');
  assert.match(appSource, /radioTableType\('talkgroup-radios', columns\)/);
  assert.match(appSource, /type: 'system-action-observations'/);

  const player = Object.create(playerModule.WebCallPlayer.prototype);
  player.arrivalSequence = 0;
  player.maximumSelectedScanLists = 1;
  player.maximumQueued = 100;
  player.selectedScanListIds = new Set(['1', '99']);
  player.scanListById = new Map([['1', { id: '1', enabled: true }], ['2', { id: '2', enabled: true }]]);
  player.trimQueueToLimit = () => {};
  player.applyLimits({ maximum_selected_scan_lists: 1, waiting_calls_per_listener: 50 });
  assert.deepEqual([...player.selectedScanListIds], ['1', '99'],
    'Unavailable saved scan-list IDs must survive server limit updates');
  assert.deepEqual(player.activeSelectedScanListIds(), ['1']);
  assert.equal(player.maximumQueued, 50);
  const canonicalCall = player.normalizeCall({
    call_id: 'call-1', audio_url: '/api/v1/calls/call-1/audio', started_at_ms: 1,
    completed_at_ms: 2, scan_list_ids: [1], conversation_key: 'target:1'
  });
  assert.equal(canonicalCall._callId, 'call-1');
  assert.deepEqual(canonicalCall._matchedScanListIds, ['1']);
  assert.equal(player.callMatchesSelection(canonicalCall), true);
  assert.equal(player.callMatchesSelection({ _matchedScanListIds: [] }), false);
  assert.equal(player.normalizeCall({
    logical_call_id: 'legacy', audio_url: '/audio', start_timestamp_ms: 1,
    completed_at_ms: 2, matched_scan_list_ids: [1], conversation_key: 'target:1'
  }), null, 'Legacy call field aliases must fail instead of being inferred');
  assert.deepEqual(player.eventPayload({ data: '{"data":{"call_id":"wrapped"}}' }),
    { data: { call_id: 'wrapped' } }, 'Live transport owns the envelope and passes event data directly');
  player.updateScanListStatus = () => {};
  player.filterQueueForSelectedLists = () => {};
  player.renderScanLists = () => {};
  player.synchronizeSubscription = () => {};
  player.render = () => {};
  player.setScanLists([{ id: 1, name: 'Dispatch', default: true },
    { scan_list_id: 2, name: 'Legacy' }], { maximum_selected_scan_lists: 1 });
  assert.deepEqual(player.scanLists, [{ id: '1', name: 'Dispatch', description: '', enabled: true, default: true }]);

  const handlers = Object.fromEntries(routes.definitions.map(({ id }) => [id, () => id]));
  const registry = routes.createRegistry(handlers, ({ id }) => id !== 'admin');
  assert.equal(routes.resolve(registry, '?view=scanner').id, 'scanner');
  assert.equal(routes.resolve(registry, '?view=missing'), null);
  assert.equal(registry.admin.allowed(), false);
  assert.equal(registry.site.parent, 'systems');
  assert.throws(() => routes.createRegistry({ ...handlers, extra: () => {} }, () => true), /Unknown route/);
  assert.throws(() => routes.createRegistry({ ...handlers, scanner: null }, () => true), /Missing route/);

  const location = { origin: 'https://receiver.test', href: 'https://receiver.test/?view=dashboard' };
  assert.equal(routes.localTarget(location, '/?view=live').search, '?view=live');
  assert.equal(routes.localTarget(location, 'https://example.test/'), null);
  const history = [];
  const fakeWindow = { location, history: {
    pushState: (_state, _title, target) => history.push(['push', target]),
    replaceState: (_state, _title, target) => history.push(['replace', target])
  } };
  let navigated = null;
  assert.equal(routes.navigate(fakeWindow, '/?view=settings', (next) => { navigated = next; }), true);
  assert.equal(navigated.get('view'), 'settings');
  assert.deepEqual(history, [['push', '/?view=settings']]);

  const decodedDefaults = preferenceSchema.validate(JSON.parse(JSON.stringify(preferenceSchema.defaults)));
  assert.deepEqual(decodedDefaults, {
    version: 1,
    appearance: { theme: 'light' },
    page_titles: { prepend_playing_call: false },
    playback: { volume: 1, selected_scan_list_ids: [] },
    scanner: { detail_mode: 'normal' },
    presentation: {
      show_encryption_details: true, show_control_decode_quality: true,
      show_voice_decode_quality: true, decode_quality_display_mode: 'percentage', live_detail_row_limit: 200
    },
    tuner: {
      floor_db: -140, ceiling_db: 0, waterfall_speed: 1, snap_frequency: true, smooth_fft: true,
      highlight_waterfall_channels: false, profile: 'balanced'
    },
    tables: {}
  });
  assert.equal(decodedDefaults.scanner.detail_mode, 'normal');
  assert.deepEqual(decodedDefaults.playback.selected_scan_list_ids, []);
  assert.throws(() => preferenceSchema.validate({ ...decodedDefaults, mystery: true }), /unknown or missing/);
  assert.throws(() => preferenceSchema.validate({ ...decodedDefaults,
    appearance: { theme: 'system' } }), /appearance.theme/);
  assert.throws(() => preferenceSchema.validate({ ...decodedDefaults,
    playback: { volume: 1, selected_scan_list_ids: ['1'] } }), /Selected scan lists/);
  assert.throws(() => preferenceSchema.validate({ ...decodedDefaults, tables: {
    sample: {
      schema: ['name'], column_order: ['name'], column_widths: {}, hidden_columns: ['name']
    }
  } }), /at least one visible column/);
  assert.throws(() => preferenceSchema.validate({ ...decodedDefaults, tables: {
    sample: {
      schema: [], column_order: [], column_widths: {}, hidden_columns: []
    }
  } }), /at least one column/);

  const columns = [{ id: 'name' }, { id: 'frequency' }, { id: 'status' }];
  const initialLayout = tableLayouts.normalize(columns, null);
  assert.deepEqual(initialLayout.schema, ['name', 'frequency', 'status']);
  assert.equal(initialLayout.reset, false);
  const changedLayout = tableLayouts.setHidden(tableLayouts.resize(
    tableLayouts.move(initialLayout, 'status', 'frequency'), 'name', 17), 'frequency', true);
  assert.deepEqual(changedLayout.column_order, ['name', 'status', 'frequency']);
  assert.equal(changedLayout.column_widths.name, 48);
  assert.deepEqual(changedLayout.hidden_columns, ['frequency']);
  const restored = tableLayouts.normalize(columns, tableLayouts.persisted(changedLayout));
  assert.deepEqual(restored.columns.map(({ id }) => id), ['name', 'status']);
  const reset = tableLayouts.normalize([...columns, { id: 'new-column' }], tableLayouts.persisted(changedLayout));
  assert.deepEqual(reset.column_order, ['name', 'frequency', 'status', 'new-column']);
  assert.equal(reset.reset, true);
  assert.equal(reset.reset_reason, 'schema-changed');
  const invalidWidth = tableLayouts.normalize(columns, {
    schema: ['name', 'frequency', 'status'], column_order: ['name', 'frequency', 'status'],
    column_widths: { name: 12 }, hidden_columns: []
  });
  assert.equal(invalidWidth.reset_reason, 'invalid-widths');
  assert.equal(tableLayouts.normalize(columns, {
    schema: ['name', 'frequency', 'status'], column_order: ['name', 'frequency', 'status'],
    column_widths: {}, hidden_columns: ['name', 'frequency', 'status']
  }).reset_reason, 'all-columns-hidden');
  assert.equal(tableLayouts.tableId('live.systems'), 'live.systems');
  assert.throws(() => tableLayouts.tableId('Live Systems'), /valid stable ID/);
  assert.throws(() => tableLayouts.schema([{ id: 'same' }, { id: 'same' }]), /unique/);
  assert.throws(() => tableLayouts.schema([{ label: 'No ID' }]), /valid stable ID/);
  const schemaRegistry = new Map();
  assert.deepEqual(tableLayouts.registerSchema(schemaRegistry, 'sample', columns),
    ['name', 'frequency', 'status']);
  assert.deepEqual(tableLayouts.registerSchema(schemaRegistry, 'sample', columns),
    ['name', 'frequency', 'status']);
  assert.throws(() => tableLayouts.registerSchema(schemaRegistry, 'sample', [
    { id: 'name' }, { id: 'frequency' }
  ]), /one stable column schema/);
  assert.deepEqual(tableLayouts.registerSchema(schemaRegistry, 'sample-compact', [
    { id: 'name' }, { id: 'frequency' }
  ]), ['name', 'frequency']);
  const grouped = tableLayouts.normalize([
    { id: 'identity', group: 'Identity' }, { id: 'name', group: 'Identity' },
    { id: 'calls', group: 'Activity' }
  ], null);
  assert.throws(() => tableLayouts.move(grouped, 'identity', 'calls'), /within their group/);
  assert.throws(() => tableLayouts.setHidden(tableLayouts.setHidden(tableLayouts.setHidden(
    initialLayout, 'name', true), 'frequency', true), 'status', true), /at least one visible/);

  assert.equal(pageTitles.derive({ routeId: 'scanner', pageTitle: 'Scanner',
    playerState: { playing: true, targetLabel: 'WEST', queuedCount: 2 } }), 'WEST (2)');
  assert.equal(pageTitles.derive({ routeId: 'scanner', pageTitle: 'Scanner',
    playerState: { playing: true, targetLabel: 'WEST', queuedCount: 0 } }), 'WEST');
  assert.equal(pageTitles.derive({ routeId: 'site', pageTitle: 'Site BEE00:941 01-01 (Control)',
    prependPlaying: true, playerState: { playing: true, targetLabel: 'WEST', queuedCount: 2 } }),
  'WEST (2) - sdrtrunk-vce - Site BEE00:941 01-01 (Control)');
  assert.equal(pageTitles.derive({ routeId: 'site', pageTitle: 'Site', prependPlaying: false,
    playerState: { playing: true, targetLabel: 'WEST', queuedCount: 2 } }), 'sdrtrunk-vce - Site');
  assert.equal(pageTitles.safeText('A\u202e\n B'), 'A B');

  assert.equal(entityRefs.href({ kind: 'system', key: 'p25:BEE00:941:alias-list:1' }),
    '/?view=system&scope=p25%3ABEE00%3A941%3Aalias-list%3A1');
  assert.equal(entityRefs.href({
    kind: 'talkgroup', scope: 'p25:BEE00:49F:alias-list:1', id: 56735
  }), '/?view=talkgroup&scope=p25%3ABEE00%3A49F%3Aalias-list%3A1&id=56735');
  const siteUuid = '728d2d66-de4e-476b-a696-919f32dd4d12';
  const channelUuid = 'fd6dd61b-a7d8-4fa0-9b7d-c46382827ca8';
  assert.equal(entityRefs.href({ kind: 'site', key: siteUuid }), `/?view=site&guid=${siteUuid}`);
  assert.equal(entityRefs.href({ kind: 'conventional', key: channelUuid }),
    `/?view=conventional-detail&id=${channelUuid}`);
  assert.equal(entityRefs.href({ kind: 'patch_group', scope: 'scope', id: 12 }),
    '/?view=talkgroup&scope=scope&id=12&kind=patch_group');
  assert.equal(entityRefs.href({ kind: 'talkgroup', scope: '', id: 12 }), null);
  assert.equal(entityRefs.href({ kind: 'site', key: '' }), null);
  assert.equal(entityRefs.href({ kind: 'site', key: siteUuid.toUpperCase() }), null);
  assert.equal(entityRefs.href({ kind: 'conventional', key: '728d2d66-de4e-476b-a696' }), null);
  assert.equal(entityRefs.href({ kind: 'site', key: siteUuid, scope: 'extra' }), null);
  assert.equal(entityRefs.href({ kind: 'radio', scope: 'scope', id: 12, key: 'extra' }), null);
  assert.equal(entityRefs.href({ kind: 'radio', scope: 'scope', id: 0 }), null);

  const requests = [];
  const queuedResponses = [];
  const changes = [];
  const errors = [];
  const controller = new preferences.Controller({
    defaults: preferenceSchema.defaults,
    validate: preferenceSchema.validate,
    fetch: async (url, options) => {
      requests.push([url, options]);
      const next = queuedResponses.shift();
      return typeof next === 'function' ? next(url, options) : next?.promise || next;
    },
    onChange: (snapshot) => changes.push(snapshot),
    onError: (error) => errors.push(error)
  });
  assert.equal(controller.snapshot().identity, null);
  queuedResponses.push(response(200, { revision: 3, preferences: decodedDefaults }));
  await controller.activate('alice');
  assert.equal(controller.snapshot().revision, 3);
  const dark = { ...decodedDefaults, appearance: { theme: 'dark' } };
  queuedResponses.push(response(200, { revision: 4, preferences: dark }));
  await controller.update((profile) => { profile.appearance.theme = 'dark'; });
  assert.equal(requests.at(-1)[1].headers['If-Match'], '"3"');
  assert.equal(controller.snapshot().preferences.appearance.theme, 'dark');

  const slowThemeSave = deferred();
  queuedResponses.push(slowThemeSave, (_url, options) => {
    const submitted = JSON.parse(options.body);
    assert.equal(options.headers['If-Match'], '"5"');
    assert.equal(submitted.appearance.theme, 'light');
    assert.equal(submitted.playback.volume, 0.25);
    return response(200, { revision: 6, preferences: submitted });
  });
  const themeSave = controller.update((profile) => { profile.appearance.theme = 'light'; });
  const volumeSave = controller.update((profile) => { profile.playback.volume = 0.25; });
  slowThemeSave.resolve(response(200, {
    revision: 5,
    preferences: { ...dark, appearance: { theme: 'light' } }
  }));
  await Promise.all([themeSave, volumeSave]);
  assert.equal(controller.snapshot().preferences.appearance.theme, 'light');
  assert.equal(controller.snapshot().preferences.playback.volume, 0.25);

  const slowLayoutSave = deferred();
  queuedResponses.push(slowLayoutSave, (_url, options) => {
    const submitted = JSON.parse(options.body);
    assert.equal(options.headers['If-Match'], '"7"');
    assert.ok(submitted.tables.sample);
    assert.equal(submitted.scanner.detail_mode, 'advanced');
    return response(200, { revision: 8, preferences: submitted });
  });
  const layoutSave = controller.update((profile) => {
    profile.tables.sample = {
      schema: ['name'], column_order: ['name'], column_widths: {}, hidden_columns: []
    };
  });
  const settingsSave = controller.update((profile) => { profile.scanner.detail_mode = 'advanced'; });
  slowLayoutSave.resolve(response(200, { revision: 7, preferences: {
    ...controller.snapshot().preferences,
    tables: { sample: {
      schema: ['name'], column_order: ['name'], column_widths: {}, hidden_columns: []
    } }
  } }));
  await Promise.all([layoutSave, settingsSave]);
  assert.ok(controller.snapshot().preferences.tables.sample);
  assert.equal(controller.snapshot().preferences.scanner.detail_mode, 'advanced');

  const serverCurrent = { ...decodedDefaults, scanner: { detail_mode: 'engineer' } };
  queuedResponses.push(response(409, { error: 'stale' }),
    response(200, { revision: 8, preferences: serverCurrent }));
  await assert.rejects(controller.update((profile) => { profile.scanner.detail_mode = 'normal'; }),
    (error) => error.code === 'preference_conflict');
  assert.equal(controller.snapshot().revision, 8);
  assert.equal(controller.snapshot().preferences.scanner.detail_mode, 'engineer');

  const slow = deferred();
  queuedResponses.push(slow);
  const obsolete = controller.activate('alice');
  controller.reset(null);
  slow.resolve(response(200, { revision: 9, preferences: dark }));
  assert.deepEqual(await obsolete, { state: 'stale' });
  assert.equal(controller.snapshot().identity, null);
  assert.ok(changes.length >= 5);
  assert.equal(errors.at(-1).code, 'preference_conflict');
}

main().catch((error) => {
  console.error(error?.stack || error);
  process.exitCode = 1;
});
