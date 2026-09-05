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
  assert.match(appSource, /activeReadOnlyModal === modalState && closeReadOnlyModal\(\)/);
  assert.match(appSource, /if \(!force && active\.isBusy\?\.\(\)\) return false/);
  const updatePreferencesSource = functionBinding(appSource, 'updateUserPreferences');
  assert.match(updatePreferencesSource, /result\?\.state === 'stale'/);
  assert.match(updatePreferencesSource, /error\.code = 'preference_session_changed'/);
  assert.match(updatePreferencesSource, /error\?\.code !== 'preference_conflict'/);
  assert.match(updatePreferencesSource, /showUserPreferenceError\(error, retry, true\)/);
  assert.match(functionBinding(appSource, 'showUserPreferenceError'), /saveFailed \?/);
  assert.match(functionBinding(appSource, 'settleUserPreferenceMutation'), /\.catch\(\(\) => null\)/);
  assert.match(functionBinding(appSource, 'saveTableLayoutPreference'),
    /return settleUserPreferenceMutation[\s\S]*\}, false\)/);
  assert.doesNotMatch(appSource, /void updateUserPreferences\(/);
  assert.match(indexSource,
    /id="preference-status" class="preference-status" role="status" aria-live="polite" hidden/);
  assert.match(indexSource, /id="global-status" class="visually-hidden"/);
  assert.match(appCssSource, /\.preference-status \{/);
  const settingsSource = functionBinding(appSource, 'renderSettings');
  assert.match(settingsSource, /A read-only overview of every personal preference/);
  assert.match(settingsSource, /userPreferenceSummaryCards\(current\)/);
  assert.match(settingsSource, /Reset All Personal Preferences/);
  assert.match(settingsSource, /openResetUserPreferences/);
  assert.doesNotMatch(settingsSource, /preferenceCheckbox\(|updateUserPreferences\(/);
  const summarySource = functionBinding(appSource, 'userPreferenceSummaryCards');
  assert.match(summarySource,
    /appearance\.theme|page_titles\.prepend_playing_call|playback\.volume|selected_scan_list_ids/);
  assert.match(summarySource,
    /target_grouping|target_burst_limit|scanner\.detail_mode|preferences\.presentation/);
  assert.match(summarySource,
    /preferences\.tuner|health_alerts\.disabled_codes|preferences\.tables/);
  const resetSource = functionBinding(appSource, 'openResetUserPreferences');
  assert.match(resetSource, /updateUserPreferences\(\(\) => preferenceSchema\.defaults, false\)/);
  assert.match(resetSource, /does not change the username, password/);
  assert.match(resetSource, /error\?\.code === 'preference_conflict'/);
  assert.match(resetSource, /if \(modal\.close\(\)\) void render\(\)/);
  assert.doesNotMatch(resetSource, /reset\.focus\(\)/);
  assert.doesNotMatch(settingsSource, /userPreferenceController\.replace\(/);
  const livePresentationSource = functionBinding(appSource, 'openLivePresentationSettings');
  assert.match(livePresentationSource, /openReadOnlyModal\('Live presentation'/);
  assert.match(livePresentationSource, /modal\.setDirty\(true\)/);
  assert.match(livePresentationSource, /modal\.setBusy\(true\)/);
  assert.match(livePresentationSource, /const submitted = \{/);
  assert.match(livePresentationSource, /preferences\.presentation = submitted;/);
  assert.match(livePresentationSource, /show_only_active_trunked_channels: activeOnly\.input\.checked/);
  assert.match(livePresentationSource, /retain_last_call_on_idle_rows: retainLastCall\.input\.checked/);
  assert.match(livePresentationSource, /clear_voice_quality_when_idle: clearIdleQuality\.input\.checked/);
  assert.doesNotMatch(livePresentationSource, /target_grouping|target_burst_limit|preferences\.playback/);
  assert.match(livePresentationSource, /if \(modal\.close\(\)\) void render\(\)/);
  assert.match(livePresentationSource, /error\?\.code === 'preference_session_changed'/);
  assert.match(livePresentationSource, /void render\(\)/);
  assert.match(livePresentationSource, /apply\(latest\.preferences\.presentation\)/);
  const scannerPlaybackSource = functionBinding(appSource, 'openScannerSettings');
  assert.match(scannerPlaybackSource, /openReadOnlyModal\('Scanner settings'/);
  assert.match(scannerPlaybackSource, /preferences\.playback\.target_grouping =/);
  assert.match(scannerPlaybackSource, /preferences\.playback\.target_burst_limit =/);
  assert.match(scannerPlaybackSource, /preferences\.page_titles\.prepend_playing_call =/);
  assert.doesNotMatch(scannerPlaybackSource, /preferences\.presentation/);
  assert.match(appSource, /id = 'scanner-settings'/);
  assert.match(appSource, /openScannerSettings\('#scanner-settings'\)/);
  const liveChannelsSource = functionBinding(appSource, 'liveChannelsSection');
  assert.match(liveChannelsSource, /layoutMenuHost: titleActions/);
  assert.match(liveChannelsSource, /iconGlyph\('icon-live-presentation'\)/);
  assert.match(liveChannelsSource, /section\('Live Channels', host, titleActions\)/);
  assert.match(appSource, /table\(tableController\.rows\(\), declaredColumns/);
  assert.match(appSource, /rebuildTable\(null, reopenLayoutMenu, restoreLayoutFocus\)/);
  assert.match(appSource, /layoutMenuOpen: reopenLayoutMenu/);
  assert.match(appSource, /panel\.showPopover\(\)/);
  assert.match(appSource, /layoutMenuFocus: restoreLayoutFocus/);
  assert.match(appSource, /focusTarget instanceof HTMLElement/);
  assert.match(appSource, /control\.dataset\.layoutWasDisabled = String\(control\.disabled\)/);
  const resizerSource = functionBinding(appSource, 'addColumnResizers');
  assert.match(resizerSource, /if \(!saved\) onSaveFailure\?\.\(\)/);
  assert.match(resizerSource, /setCurrentLayout\(nextLayout\)/);
  assert.match(resizerSource, /if \(!beginLayoutMutation\(\)\) return/);
  assert.match(resizerSource, /endLayoutMutation\(\)/);
  assert.match(appSource, /let layoutMutationPending = false/);
  assert.match(appCssSource, /\.resizable-table\.table-layout-busy \.column-resizer/);
  assert.match(appSource, /dataRows = prepend \? dataRows\.slice\(0, limit\) : dataRows\.slice\(-limit\)/);
  assert.match(appSource, /rows: \(\) => dataRows\.slice\(\)/);
  assert.match(appSource, /trigger\.setAttribute\('popovertarget', panelId\)/);
  assert.match(appSource, /trigger\.append\(iconGlyph\('icon-columns'\)\)/);
  assert.match(appSource, /const displayLabel = byId\.get\(id\)\.fullLabel \|\| byId\.get\(id\)\.label \|\| id/);
  assert.match(appSource, /visibility\.setAttribute\('aria-label', `Show \$\{displayLabel\} column`\)/);
  assert.doesNotMatch(appSource, /inline \? '' : 'Columns'/);
  assert.match(appSource, /panel\.setAttribute\('popover', 'auto'\)/);
  assert.match(appSource, /bindAnchoredDropdown\(trigger, panel, activeRenderController\?\.signal\)/);
  const dropdownBinding = functionBinding(appSource, 'bindAnchoredDropdown');
  assert.match(dropdownBinding, /new AbortController\(\)/);
  assert.match(dropdownBinding, /addEventListener\('resize'/);
  assert.match(dropdownBinding, /addEventListener\('scroll'/);
  assert.match(dropdownBinding, /setAttribute\('aria-expanded'/);
  assert.match(dropdownBinding, /panel\.style\.maxHeight = ''/);
  assert.match(dropdownBinding, /panel\.hidePopover\(\)/);
  assert.match(appCssSource, /\.table-layout-menu \{[^}]*margin: 0 8px 6px auto[^}]*padding-top: 8px/s);
  assert.match(appCssSource, /\.table-layout-panel \{[^}]*position: fixed[^}]*inset: auto[^}]*margin: 0/s);
  const dropdownPlacement = vm.runInNewContext(
    `(function(anchorRect, panelRect, viewport) ${functionBinding(appSource, 'anchoredDropdownPlacement')})`);
  assert.deepEqual(JSON.parse(JSON.stringify(dropdownPlacement({ right: 600, bottom: 100 }, { width: 300 },
    { width: 800, height: 600 }))), { left: 300, top: 106, maxHeight: 480 });
  assert.equal(dropdownPlacement({ right: 100, bottom: 20 }, { width: 300 },
    { width: 800, height: 600 }).left, 8, 'A wide dropdown must stay inside the left viewport gutter');
  assert.deepEqual(JSON.parse(JSON.stringify(dropdownPlacement(
    { right: 600, top: 560, bottom: 590 }, { width: 300, height: 300 },
    { width: 800, height: 600 }))), { left: 300, top: 254, maxHeight: 480 },
  'A dropdown may move above its trigger only when there is no usable room below it');
  assert.match(appCssSource, /button:not\([^\n]+:not\(\.auth-session-button\)/);
  assert.match(appCssSource, /\.settings-card-grid \{[^}]*align-items: stretch/s);
  assert.doesNotMatch(appCssSource, /\.settings-card-grid \{[^}]*max-width/s);
  assert.match(appCssSource, /\.settings-card \{[^}]*height: 100%/s);
  assert.match(appCssSource, /\.settings-form-footer \{[^}]*grid-column: 1 \/ -1[^}]*justify-self: stretch/s);
  assert.doesNotMatch(appCssSource, /\.settings-form-footer \{[^}]*max-width/s);
  const aliasMembershipOperation = functionBinding(appSource, 'aliasBulkBinaryOperation');
  assert.match(aliasMembershipOperation, /node\('button', 'secondary', label\)/);
  const settingsCardGrid = vm.runInNewContext(
    `(function(...cards) ${functionBinding(appSource, 'settingsCardGrid')})`, {
      node: (tag, className) => ({
        tag, className, children: [], append(...items) { this.children.push(...items); }
      })
    });
  const firstSettingsCard = { id: 'first' };
  const secondSettingsCard = { id: 'second' };
  const renderedSettingsGrid = settingsCardGrid(firstSettingsCard, secondSettingsCard);
  assert.equal(renderedSettingsGrid.className, 'settings-card-grid');
  assert.deepEqual(renderedSettingsGrid.children, [firstSettingsCard, secondSettingsCard],
    'Settings cards must be appended as elements instead of converted to text');
  const playbackAccessSource = functionBinding(appSource, 'synchronizePlaybackAccess');
  assert.match(playbackAccessSource, /if \(!userPreferenceController\.snapshot\(\)\.loaded\) return/);
  const receiverSettingsRequestSource = functionBinding(appSource, 'requestReceiverSettings');
  assert.match(receiverSettingsRequestSource, /headers\['If-Match'\] = `"\$\{revision\}"`/);
  const receiverSettingsSource = functionBinding(appSource, 'renderAdminReceiverBehaviorSettings');
  assert.match(receiverSettingsSource, /error\?\.code === 'receiver_settings_conflict'/);
  assert.match(receiverSettingsSource, /apply\(error\.current\)/);
  assert.match(receiverSettingsSource, /Current server values were reloaded/);
  assert.doesNotMatch(appSource, /row\.id \?\? row\.scan_list_id|row\.scan_list_id \?\? row\.id/);
  const decodeReceiverSettings = vm.runInNewContext(
    `(function(value) ${functionBinding(appSource, 'decodeReceiverSettingsEnvelope')})`);
  assert.deepEqual(JSON.parse(JSON.stringify(decodeReceiverSettings({
    revision: 2,
    settings: {
      traffic_grant_age_out_milliseconds: 1000
    }
  }))), {
    revision: 2,
    settings: {
      traffic_grant_age_out_milliseconds: 1000
    }
  });
  assert.throws(() => decodeReceiverSettings({
    revision: 0,
    settings: {
      traffic_grant_age_out_milliseconds: 1000
    }
  }), /invalid Receiver Settings/);
  const receiverSettingsRequests = [];
  const receiverSettingsResponses = [];
  const requestReceiverSettings = vm.runInNewContext(
    `(async function(method = 'GET', settings = null, revision = null) ${
      functionBinding(appSource, 'requestReceiverSettings')})`, {
      decodeReceiverSettingsEnvelope: decodeReceiverSettings,
      jsonDocumentFetch: async (url, options) => {
        receiverSettingsRequests.push([url, options]);
        return receiverSettingsResponses.shift();
      }
    });
  const requestedReceiverSettings = {
    traffic_grant_age_out_milliseconds: 1200
  };
  receiverSettingsResponses.push(response(200, { revision: 4, settings: requestedReceiverSettings }));
  assert.deepEqual(JSON.parse(JSON.stringify(await requestReceiverSettings('PUT', requestedReceiverSettings, 3))),
    { revision: 4, settings: requestedReceiverSettings });
  assert.equal(receiverSettingsRequests.at(-1)[0], '/api/v1/admin/receiver-settings');
  assert.equal(receiverSettingsRequests.at(-1)[1].headers['If-Match'], '"3"');
  assert.equal(receiverSettingsRequests.at(-1)[1].body, JSON.stringify(requestedReceiverSettings));
  const currentReceiverSettings = {
    traffic_grant_age_out_milliseconds: 900
  };
  receiverSettingsResponses.push(response(409, { revision: 5, settings: currentReceiverSettings }));
  await assert.rejects(requestReceiverSettings('PUT', requestedReceiverSettings, 4), (error) => {
    assert.equal(error.code, 'receiver_settings_conflict');
    assert.deepEqual(JSON.parse(JSON.stringify(error.current)),
      { revision: 5, settings: currentReceiverSettings });
    return true;
  });
  const tableCalls = functionCalls(appSource, 'table');
  assert.equal(tableCalls.length, 16, 'Every application table call must be audited');
  assert.match(appSource,
    /else if \(!options\.serverSort && options\.sortable !== false\)/,
    'Server-paged tables must not offer current-page-only sorting for derived columns');
  tableCalls.forEach((call) => {
    assert.ok(call.arguments.length >= 4, `Table call at ${call.index} is missing its options argument`);
    const options = call.arguments[3];
    assert.match(options, /(?:\btype\s*:|\.\.\.options)/,
      `Table call at ${call.index} is missing a stable table type`);
    const literal = options.match(/\btype\s*:\s*'([^']+)'/);
    if (literal) assert.match(literal[1], stableId, `Invalid table type ${literal[1]}`);
  });
  [
    'aliasCatalogCoreColumns', 'aliasCustomConfigurationColumns',
    'aliasEditorSourceBreakdownColumns', 'aliasEditorBaseColumns', 'scanListMemberColumns',
    'dashboardIdentityColumns', 'radioSystemRadioColumns', 'p25ChannelFrequencyColumns',
    'trunkedChannelFrequencyColumns', 'p25ChannelNeighborColumns', 'trunkedChannelNeighborColumns',
    'activityColumns', 'channelDirectoryColumns', 'channelGroupIdentityColumns',
    'channelRadioColumns'
  ].forEach((name) => {
    const ids = [...functionBinding(appSource, name).matchAll(/\bid\s*:\s*'([^']+)'/g)]
      .map((match) => match[1]);
    assert.ok(ids.length, `${name} must declare column IDs`);
    ids.forEach((id) => assert.match(id, stableId, `${name} has invalid column ID ${id}`));
    assert.equal(new Set(ids).size, ids.length, `${name} repeats a column ID`);
  });
  [
    'radioSystemChannelColumns', 'dashboardHealthColumns', 'dashboardCallSourceColumns',
    'dashboardActivityRadioColumns', 'groupIdentityColumns'
  ].forEach((name) => {
    const ids = [...arrayBinding(appSource, name).matchAll(/\bid\s*:\s*'([^']+)'/g)]
      .map((match) => match[1]);
    assert.ok(ids.length, `${name} must declare column IDs`);
    ids.forEach((id) => assert.match(id, stableId, `${name} has invalid column ID ${id}`));
    assert.equal(new Set(ids).size, ids.length, `${name} repeats a column ID`);
  });
  const hex = (value, width) => Number.isInteger(value) ?
    value.toString(16).toUpperCase().padStart(width, '0') : '';
  const identifierNumber = (value) => value !== null && value !== undefined && value !== '' &&
    Number.isFinite(Number(value)) && Number(value) >= 0 ? String(Math.trunc(Number(value))) : '';
  const number = (value) => Number(value || 0).toLocaleString('en-US');
  const channelDirectoryRfIdentity = vm.runInNewContext(
    `(function(row) ${functionBinding(appSource, 'channelDirectoryRfIdentity')})`, {
      isP25: (row) => row.protocol === 'P25', protocolFamily: (row) => row.protocol,
      identifierNumber, hex
    });
  const channelDirectoryDetails = vm.runInNewContext(
    `(function(row) ${functionBinding(appSource, 'channelDirectoryDetails')})`, {
      channelDirectoryRfIdentity, number
    });
  const channelDescriptors = vm.runInNewContext(`(${arrayBinding(appSource, 'radioSystemChannelColumns')})`, {
    channelNameSummary: () => '', channelLabel: () => '', channelDirectoryDetails,
    frequency: () => '', dateTime: () => ''
  });
  const channelDetailsColumn = channelDescriptors.find((column) => column.id === 'details');
  assert.equal(channelDetailsColumn.render({ protocol: 'P25', rfss: 1, site_id: 1, nac: 0x293, bands: 2 }),
    'RFSS 01 · Site 01 · NAC 293 · 2 band plans');
  assert.equal(channelDetailsColumn.render({ protocol: 'DMR', site_id: 1 }), 'Site 1');
  assert.equal(channelDetailsColumn.render({ protocol: 'P25', site: 1 }), '',
    'Legacy site fields must not be inferred');

  const dashboardChannelKind = vm.runInNewContext(
    `(function(row) ${functionBinding(appSource, 'dashboardChannelKind')})`);
  assert.equal(dashboardChannelKind({ channel_kind: 'trunked' }), 'TRUNKED');
  assert.equal(dashboardChannelKind({ channel_kind: 'conventional' }), 'CONVENTIONAL');
  assert.equal(dashboardChannelKind({ channel_type: 'trunked' }), '',
    'Legacy channel_type topology must not be inferred');
  assert.equal(dashboardChannelKind({ channel_type: 'traffic' }), '',
    'Protocol channel types must not be interpreted as channel topology');

  const dashboardChannelContext = vm.runInNewContext(
    `(function(row) ${functionBinding(appSource, 'dashboardChannelContext')})`, {
      dashboardChannelKind: (row) => String(row.channel_kind || '').toUpperCase(),
      isP25: (row) => row.protocol === 'P25', radioSystemLabel: (row) => row.system || '',
      protocolFamily: (row) => row.protocol, identifierNumber, hex
    });
  assert.equal(dashboardChannelContext({ protocol: 'P25', channel_kind: 'trunked', system: 'BEE00-941',
    rfss: 1, site_id: 2, nac: 0x293 }), 'BEE00-941 · RFSS 01 · Site 02 · NAC 293');
  assert.equal(dashboardChannelContext({ protocol: 'NXDN', channel_kind: 'trunked', system: 'County',
    site_id: 4, ran: 7 }), 'County · Site 4 · RAN 7');
  assert.equal(dashboardChannelContext({ protocol: 'DMR', channel_kind: 'trunked', system: 'Metro',
    site_id: 9 }), 'Metro · Site 9');

  const decoderLabel = vm.runInNewContext(
    `(function(value, compact = false) ${functionBinding(appSource, 'decoderLabel')})`);
  const channelMode = vm.runInNewContext(
    `(function(row) ${functionBinding(appSource, 'channelMode')})`, {
      protocolFamily: (row) => row.protocol, decoderLabel
    });
  assert.equal(channelMode({ protocol: 'DMR', decoder: 'DMR' }), 'DMR');
  assert.equal(channelMode({ protocol: 'P25', decoder: 'P25_PHASE1' }), 'P25 · P25 P1');

  const timeslotLabel = vm.runInNewContext(
    `(function(value) ${functionBinding(appSource, 'timeslotLabel')})`, { identifierNumber });
  assert.equal(timeslotLabel(-1), '');
  assert.equal(timeslotLabel(0), 'Slot 0');

  const trunkedVariant = vm.runInNewContext(
    `(function(row) ${functionBinding(appSource, 'trunkedVariant')})`);
  const identityDomainLabel = vm.runInNewContext(
    `(function(row) ${functionBinding(appSource, 'identityDomainLabel')})`);
  const semanticLabel = vm.runInNewContext(
    `(function(value) ${functionBinding(appSource, 'semanticLabel')})`);
  const isSavedChannelRadioSystem = vm.runInNewContext(
    `(function(row) ${functionBinding(appSource, 'isSavedChannelRadioSystem')})`);
  const radioSystemsDirectoryDetails = vm.runInNewContext(
    `(function(row) ${functionBinding(appSource, 'radioSystemsDirectoryDetails')})`, {
      channelDirectoryDetails, isP25: (row) => row.protocol === 'P25', hex,
      trunkedVariant, identityDomainLabel, identifierNumber, semanticLabel,
      isSavedChannelRadioSystem, protocolFamily: (row) => row.protocol
    });
  assert.equal(radioSystemsDirectoryDetails({ protocol: 'NXDN', variant: 'TYPE_C',
    address_domain: 'nxdn_type_c', network_id: 1, system_id: 2,
    radio_system_key: 'nxdn-c:channel:728d2d66-de4e-476b-a696-919f32dd4d12' }),
  'Scoped to this saved channel · Type-C');
  assert.equal(radioSystemsDirectoryDetails({ protocol: 'DMR', variant: 'TIER_III', model: 'small',
    network_id: 42, radio_system_key: 'dmr:tier3:small:42' }),
  'Tier III · Small · Network 42');
  assert.equal(radioSystemsDirectoryDetails({ protocol: 'NXDN', variant: 'TYPE_C',
    location_category: 'local', system_id: 303, radio_system_key: 'nxdn-c:local:303' }),
  'Type-C · Local · System 303');

  const savedChannelScopeLabel = vm.runInNewContext(
    `(function(row) ${functionBinding(appSource, 'savedChannelScopeLabel')})`, {
      protocolFamily: (row) => row.protocol
    });
  const radioSystemLabel = vm.runInNewContext(
    `(function(row) ${functionBinding(appSource, 'radioSystemLabel')})`, {
      isP25: (row) => row.protocol === 'P25', hex, isSavedChannelRadioSystem,
      savedChannelScopeLabel, protocolFamily: (row) => row.protocol, semanticLabel, identifierNumber
    });
  assert.equal(radioSystemLabel({ protocol: 'DMR', model: 'small', network_id: 42,
    radio_system_key: 'dmr:tier3:small:42' }), 'DMR Tier III · Small model · Network 42');
  assert.equal(radioSystemLabel({ protocol: 'NXDN', location_category: 'local', system_id: 303,
    radio_system_key: 'nxdn-c:local:303' }), 'NXDN Type-C · Local · System 303');
  assert.equal(radioSystemLabel({ protocol: 'DMR',
    radio_system_key: 'dmr:channel:728d2d66-de4e-476b-a696-919f32dd4d12' }),
  'DMR saved channel scope');

  const distinctObservedVariant = vm.runInNewContext(
    `(function(channel) ${functionBinding(appSource, 'distinctObservedVariant')})`, { trunkedVariant });
  const distinctObservedClassification = vm.runInNewContext(
    `(function(observed, authoritative) ${functionBinding(appSource, 'distinctObservedClassification')})`,
    { semanticLabel });
  const dmrChannelDetailRows = vm.runInNewContext(
    `(function(channel) ${functionBinding(appSource, 'dmrChannelDetailRows')})`, {
      trunkedVariant, identifierNumber, semanticLabel, distinctObservedVariant,
      distinctObservedClassification
    });
  const dmrDetails = Object.fromEntries(dmrChannelDetailRows({
    protocol: 'DMR', variant: 'TIER_III', model: 'small', network_id: 42,
    site_variant: 'TIER_III', site_model: 'small', site_id: 9
  }));
  assert.equal(dmrDetails['Radio System Network'], '42');
  assert.equal(dmrDetails['Radio System Model'], 'Small');
  assert.equal(dmrDetails['Observed Site'], '9');
  assert.equal(Object.hasOwn(dmrDetails, 'System'), false);
  assert.equal(Object.hasOwn(dmrDetails, 'Observed Integrator'), false);
  assert.equal(Object.hasOwn(dmrDetails, 'Observed Site Variant'), false);
  assert.equal(Object.hasOwn(dmrDetails, 'Observed Site Model'), false);
  const changedDmrObservation = Object.fromEntries(dmrChannelDetailRows({
    protocol: 'DMR', variant: 'TIER_III', model: 'small', network_id: 42,
    site_variant: 'CAPACITY_PLUS', site_model: 'large', site_id: 9
  }));
  assert.equal(changedDmrObservation['Observed Site Variant'], 'Capacity Plus');
  assert.equal(changedDmrObservation['Observed Site Model'], 'Large');

  const nxdnChannelDetailRows = vm.runInNewContext(
    `(function(channel) ${functionBinding(appSource, 'nxdnChannelDetailRows')})`, {
      trunkedVariant, identifierNumber, semanticLabel, distinctObservedVariant,
      distinctObservedClassification, number: (value) => String(value)
    });
  const nxdnDetails = Object.fromEntries(nxdnChannelDetailRows({
    protocol: 'NXDN', variant: 'TYPE_C', location_category: 'local', system_id: 303,
    site_variant: 'TYPE_C', site_location_category: 'local', site_network_id: 7,
    site_system_id: 12, site_id: 5, ran: 4, services: []
  }));
  assert.equal(nxdnDetails['Radio System Category'], 'Local');
  assert.equal(nxdnDetails['Radio System ID'], '303');
  assert.equal(nxdnDetails['Observed Site'], '5');
  assert.equal(nxdnDetails['Observed Integrator'], '7');
  assert.equal(Object.hasOwn(nxdnDetails, 'Network'), false);
  assert.equal(Object.hasOwn(nxdnDetails, 'Observed Site Variant'), false);
  assert.equal(Object.hasOwn(nxdnDetails, 'Observed Site Category'), false);
  const changedNxdnObservation = Object.fromEntries(nxdnChannelDetailRows({
    protocol: 'NXDN', variant: 'TYPE_C', location_category: 'local', system_id: 303,
    site_variant: 'TYPE_D', site_location_category: 'regional', site_id: 5, services: []
  }));
  assert.equal(changedNxdnObservation['Observed Site Variant'], 'Type-D');
  assert.equal(changedNxdnObservation['Observed Site Category'], 'Regional');

  const channelLocationIdentity = vm.runInNewContext(
    `(function(channel) ${functionBinding(appSource, 'channelLocationIdentity')})`, {
      protocolFamily: (row) => row.protocol, semanticLabel, identifierNumber,
      hex
    });
  assert.equal(channelLocationIdentity({ protocol: 'DMR', model: 'small', network_id: 42,
    site_id: 9 }),
  'Small model · Network 42 · Site 9');
  assert.equal(channelLocationIdentity({ protocol: 'NXDN', location_category: 'local', system_id: 303,
    site_id: 5, site_network_id: 7, site_system_id: 12, ran: 4 }),
  'Local · System 303 · Site 5 · Integrator 7 · RAN 4');

  const nativeChannelDirectoryRfIdentity = vm.runInNewContext(
    `(function(row) ${functionBinding(appSource, 'channelDirectoryRfIdentity')})`, {
      isP25: (row) => row.protocol === 'P25', protocolFamily: (row) => row.protocol,
      identifierNumber, hex
    });
  assert.equal(nativeChannelDirectoryRfIdentity({ protocol: 'DMR', site_id: 9 }), 'Site 9');
  assert.equal(nativeChannelDirectoryRfIdentity({ protocol: 'NXDN', site_system_id: 12, site_id: 5, ran: 4 }),
    'Site 5 · RAN 4');

  const receiverHealthSeverity = vm.runInNewContext(
    `(function(value) ${functionBinding(appSource, 'receiverHealthSeverity')})`);
  const receiverHealthCount = vm.runInNewContext(
    `(function(value, fallback = 0) ${functionBinding(appSource, 'receiverHealthCount')})`);
  const normalizeReceiverHealthSnapshot = vm.runInNewContext(
    `(function(value) ${functionBinding(appSource, 'normalizeReceiverHealthSnapshot')})`,
    { receiverHealthSeverity, receiverHealthCount });
  const correctedHealth = normalizeReceiverHealthSnapshot({
    summary: { severity: 'healthy', active_count: 0, warning_count: 0, critical_count: 0 },
    active: [{ severity: 'critical', code: 'receiver-iq-drop' }], resolved: [], measurements: []
  });
  assert.deepEqual(JSON.parse(JSON.stringify(correctedHealth.summary)), {
    severity: 'critical', active_count: 1, warning_count: 0, critical_count: 1
  }, 'An active critical incident must never normalize to Healthy');
  const correctedWarningHealth = normalizeReceiverHealthSnapshot({
    summary: { severity: 'healthy', active_count: 0, warning_count: 0, critical_count: 0 },
    active: [{ severity: 'warning', code: 'receiver-output-drop' }], resolved: [], measurements: []
  });
  assert.deepEqual(JSON.parse(JSON.stringify(correctedWarningHealth.summary)), {
    severity: 'warning', active_count: 1, warning_count: 1, critical_count: 0
  }, 'An active warning incident must never normalize to Healthy');

  const radioTableType = vm.runInNewContext(
    `(function(baseType, columns) ${functionBinding(appSource, 'radioTableType')})`, { tableLayouts });
  assert.equal(radioTableType('radios', [{ id: 'radio' }]), 'radios.base');
  assert.equal(radioTableType('radios', [{ id: 'radio' }, { id: 'talker-alias' }]),
    'radios.talker-alias');
  assert.equal(radioTableType('radios', [
    { id: 'radio' }, { id: 'talker-alias' }, { id: 'affiliation' }, { id: 'confirmed-channel' }
  ]), 'radios.talker-alias-affiliation-channel');
  assert.match(appSource, /radioTableType\('group-identity-radios', columns\)/);
  assert.match(appSource, /type: 'system-action-observations'/);

  const player = Object.create(playerModule.WebCallPlayer.prototype);
  player.arrivalSequence = 0;
  player.maximumSelectedScanLists = 1;
  player.maximumQueued = 100;
  player.selectedScanListIds = new Set(['1', '99']);
  player.scanListById = new Map([['1', { id: '1', enabled: true }], ['2', { id: '2', enabled: true }]]);
  assert.deepEqual(player.activeSelectedScanListIds(), ['1']);
  assert.equal(player.maximumQueued, 100);
  const canonicalCall = player.normalizeCall({
    call_id: 'call-1', audio_url: '/api/v1/calls/call-1/audio', started_at_ms: 1,
    completed_at_ms: 2, scan_list_ids: [1], protocol: 'P25', radio_system_key: 'p25:00001:002',
    target_form: 'TALKGROUP', target_id: 1,
    playback_target: { key: 'system:p25:00001:002:v1-g-00001-002-1', kind: 'talkgroup',
      label: 'Talkgroup 1' }
  });
  assert.equal(canonicalCall._callId, 'call-1');
  assert.deepEqual(canonicalCall._matchedScanListIds, ['1']);
  assert.equal(player.callMatchesSelection(canonicalCall), true);
  assert.equal(player.callMatchesSelection({ _matchedScanListIds: [] }), false);
  assert.equal(player.normalizeCall({
    logical_call_id: 'legacy', audio_url: '/audio', start_timestamp_ms: 1,
    completed_at_ms: 2, matched_scan_list_ids: [1], conversation_key: 'target:1'
  }), null, 'Legacy call field aliases must fail instead of being inferred');
  player.updateScanListStatus = () => {};
  player.filterQueueForSelectedLists = () => {};
  player.renderScanLists = () => {};
  player.synchronizeSubscription = () => {};
  player.render = () => {};
  player.setScanLists([{ id: 1, name: 'Dispatch', default: true },
    { scan_list_id: 2, name: 'Legacy' }], { maximum_selected_scan_lists: 1 });
  assert.deepEqual(player.scanLists, [{ id: '1', name: 'Dispatch', description: '', enabled: true, default: true }]);
  assert.deepEqual([...player.selectedScanListIds], ['1'],
    'Saved selections that are no longer published must be removed at the catalog boundary');

  const handlers = Object.fromEntries(routes.definitions.map(({ id }) => [id, () => id]));
  const registry = routes.createRegistry(handlers, ({ id }) => id !== 'admin');
  assert.equal(routes.resolve(registry, '?view=scanner').id, 'scanner');
  assert.equal(routes.resolve(registry, '?view=missing'), null);
  assert.equal(registry.admin.allowed(), false);
  assert.equal(registry['radio-system'].parent, 'radio-systems');
  assert.equal(registry.channel.parent, 'channels');
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
    version: 6,
    appearance: { theme: 'light' },
    page_titles: { prepend_playing_call: false },
    playback: {
      volume: 1, selected_scan_list_ids: [], target_grouping: true, target_burst_limit: 4
    },
    scanner: { detail_mode: 'normal' },
    presentation: {
      show_encryption_details: true, show_control_decode_quality: true,
      show_voice_decode_quality: true, decode_quality_display_mode: 'percentage', live_detail_row_limit: 200,
      show_only_active_trunked_channels: false, retain_last_call_on_idle_rows: false,
      clear_voice_quality_when_idle: false
    },
    tuner: {
      floor_db: -140, ceiling_db: 0, waterfall_speed: 1, snap_frequency: true, smooth_fft: true,
      highlight_waterfall_channels: false, show_idle_channels: false, profile: 'balanced'
    },
    health_alerts: { disabled_codes: [] },
    tables: {}
  });
  assert.equal(decodedDefaults.scanner.detail_mode, 'normal');
  assert.deepEqual(decodedDefaults.playback.selected_scan_list_ids, []);
  assert.equal(decodedDefaults.playback.target_grouping, true);
  assert.equal(decodedDefaults.playback.target_burst_limit, 4);
  const sixteenScanLists = Array.from({ length: 16 }, (_unused, index) => index + 1);
  assert.deepEqual(preferenceSchema.validate({ ...decodedDefaults, playback: {
    ...decodedDefaults.playback, selected_scan_list_ids: sixteenScanLists
  } }).playback.selected_scan_list_ids, sixteenScanLists);
  assert.throws(() => preferenceSchema.validate({ ...decodedDefaults, mystery: true }), /unknown or missing/);
  assert.throws(() => preferenceSchema.validate({ ...decodedDefaults,
    appearance: { theme: 'system' } }), /appearance.theme/);
  assert.throws(() => preferenceSchema.validate({ ...decodedDefaults,
    playback: { ...decodedDefaults.playback, selected_scan_list_ids: ['1'] } }), /Selected scan lists/);
  assert.throws(() => preferenceSchema.validate({ ...decodedDefaults, playback: {
    ...decodedDefaults.playback, selected_scan_list_ids: [...sixteenScanLists, 17]
  } }), /Selected scan lists/);
  assert.throws(() => preferenceSchema.validate({ ...decodedDefaults,
    playback: { ...decodedDefaults.playback, target_burst_limit: 21 } }), /target_burst_limit/);
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
  assert.equal(tableLayouts.tableId('live.channels'), 'live.channels');
  assert.throws(() => tableLayouts.tableId('Live Channels'), /valid stable ID/);
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
  assert.equal(pageTitles.derive({ routeId: 'channel', pageTitle: 'Channel BEE00:941 01-01 (Control)',
    prependPlaying: true, playerState: { playing: true, targetLabel: 'WEST', queuedCount: 2 } }),
  'WEST (2) - sdrtrunk-vce - Channel BEE00:941 01-01 (Control)');
  assert.equal(pageTitles.derive({ routeId: 'channel', pageTitle: 'Channel', prependPlaying: false,
    playerState: { playing: true, targetLabel: 'WEST', queuedCount: 2 } }), 'sdrtrunk-vce - Channel');
  assert.equal(pageTitles.safeText('A\u202e\n B'), 'A B');

  assert.equal(entityRefs.href({ kind: 'radio_system', key: 'p25:bee00:941' }),
    '/?view=radio-system&radio_system_key=p25%3Abee00%3A941');
  assert.equal(entityRefs.href({
    kind: 'talkgroup', radio_system_key: 'p25:bee00:49f', identity_key: 'v1-g-bee00-49f-56735'
  }), '/?view=group-identity&radio_system_key=p25%3Abee00%3A49f&identity_key=v1-g-bee00-49f-56735');
  const channelUuid = 'fd6dd61b-a7d8-4fa0-9b7d-c46382827ca8';
  assert.equal(entityRefs.href({ kind: 'channel', key: channelUuid }),
    `/?view=channel&configuration_id=${channelUuid}`);
  assert.equal(entityRefs.href({
    kind: 'patch_group', radio_system_key: 'p25:00001:002', identity_key: 'v1-p-00001-002-12'
  }), '/?view=group-identity&radio_system_key=p25%3A00001%3A002&identity_key=v1-p-00001-002-12');
  assert.equal(entityRefs.href({
    kind: 'talkgroup', radio_system_key: '', identity_key: 'v1-g-00001-002-12'
  }), null);
  assert.equal(entityRefs.href({ kind: 'channel', key: '' }), null);
  assert.equal(entityRefs.href({ kind: 'channel', key: channelUuid.toUpperCase() }), null);
  assert.equal(entityRefs.href({ kind: 'channel', key: '728d2d66-de4e-476b-a696' }), null);
  assert.equal(entityRefs.href({ kind: 'channel', key: channelUuid, radio_system_key: 'extra' }), null);
  assert.equal(entityRefs.href({ kind: 'radio', radio_system_key: 'p25:00001:002',
    identity_key: 'v1-r-00001-002-12', key: 'extra' }), null);
  assert.equal(entityRefs.href({ kind: 'radio', radio_system_key: 'p25:00001:002',
    identity_key: 'v1-r-00001-002-0' }), null);
  assert.equal(entityRefs.href({ kind: 'patch_group', radio_system_key: 'dmr:channel:' + channelUuid,
    identity_key: 'v1-p-x-x-12' }), null);

  const canonicalRadioSystemKeys = [
    'p25:00000:000', 'p25:fffff:fff',
    `dmr:channel:${channelUuid}`,
    'dmr:tier3:tiny:0', 'dmr:tier3:tiny:511',
    'dmr:tier3:small:0', 'dmr:tier3:small:127',
    'dmr:tier3:large:0', 'dmr:tier3:large:15',
    'dmr:tier3:huge:0', 'dmr:tier3:huge:3',
    'nxdn-c:global:1', 'nxdn-c:global:1022',
    'nxdn-c:regional:1', 'nxdn-c:regional:16382',
    'nxdn-c:local:1', 'nxdn-c:local:131070',
    `nxdn-c:channel:${channelUuid}`, `nxdn-d:channel:${channelUuid}`
  ];
  for (const systemKey of canonicalRadioSystemKeys) {
    assert.notEqual(entityRefs.href({ kind: 'radio_system', key: systemKey }), null, systemKey);
    const identityKey = systemKey.startsWith('p25:') ? 'v1-r-00000-000-1' : 'v1-r-x-x-1';
    assert.notEqual(entityRefs.href({ kind: 'radio', radio_system_key: systemKey,
      identity_key: identityKey }), null, `scoped ${systemKey}`);
  }

  const invalidRadioSystemKeys = [
    null, 1, {}, '', ' ', '\u00a0',
    ' p25:bee00:49f', 'p25:bee00:49f ', 'p25:bee00:49f\t', 'p25:bee00:49f\n',
    'P25:bee00:49f', 'p25:BEE00:49f', 'p25:bee00:49F',
    'p25:bee0:49f', 'p25:0bee00:49f', 'p25:bee00:49', 'p25:bee00:049f',
    'p25:100000:000', 'p25:00000:1000', 'p25:beeg0:49f', 'p25:bee00:49f:extra',
    `p25:channel:${channelUuid}`,
    `dmr:channel:${channelUuid.toUpperCase()}`, 'dmr:channel:1-1-1-1-1',
    'dmr:channel:not-a-uuid',
    'dmr:tier3:TINY:1', 'dmr:tier3:unknown:1', 'dmr:tier3:tiny:-1',
    'dmr:tier3:tiny:-0', 'dmr:tier3:tiny:+1', 'dmr:tier3:tiny:01',
    'dmr:tier3:tiny:1.0', 'dmr:tier3:tiny:1e0', 'dmr:tier3:tiny:0x1',
    'dmr:tier3:tiny:999999999999999999999999999999999999',
    'dmr:tier3:tiny:512', 'dmr:tier3:small:128', 'dmr:tier3:large:16',
    'dmr:tier3:huge:4', 'dmr:tier3:tiny:1:extra',
    'nxdn-c:GLOBAL:1', 'nxdn-c:reserved:1', 'nxdn-c:global:0', 'nxdn-c:global:-1',
    'nxdn-c:global:-0', 'nxdn-c:global:+1', 'nxdn-c:global:01',
    'nxdn-c:global:1023', 'nxdn-c:regional:16383', 'nxdn-c:local:131071',
    'nxdn-c:local:999999999999999999999999999999999999',
    'nxdn-d:global:1', `nxdn-c:channel:${channelUuid.toUpperCase()}`,
    `nxdn-d:channel:${channelUuid.toUpperCase()}`, `nxdn:channel:${channelUuid}`,
    `unknown:channel:${channelUuid}`
  ];
  for (const systemKey of invalidRadioSystemKeys) {
    assert.equal(entityRefs.href({ kind: 'radio_system', key: systemKey }), null, String(systemKey));
    assert.equal(entityRefs.href({ kind: 'radio', radio_system_key: systemKey,
      identity_key: 'v1-r-x-x-1' }), null, `scoped ${String(systemKey)}`);
  }

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
    assert.equal(submitted.presentation.show_voice_decode_quality, false);
    return response(200, { revision: 8, preferences: submitted });
  });
  const layoutSave = controller.update((profile) => {
    profile.tables.sample = {
      schema: ['name'], column_order: ['name'], column_widths: {}, hidden_columns: []
    };
  });
  const settingsSave = controller.update((profile) => {
    profile.presentation.show_voice_decode_quality = false;
  });
  slowLayoutSave.resolve(response(200, { revision: 7, preferences: {
    ...controller.snapshot().preferences,
    tables: { sample: {
      schema: ['name'], column_order: ['name'], column_widths: {}, hidden_columns: []
    } }
  } }));
  await Promise.all([layoutSave, settingsSave]);
  assert.ok(controller.snapshot().preferences.tables.sample);
  assert.equal(controller.snapshot().preferences.presentation.show_voice_decode_quality, false);

  queuedResponses.push((_url, options) => {
    const submitted = JSON.parse(options.body);
    assert.equal(options.headers['If-Match'], '"8"');
    assert.deepEqual(submitted, decodedDefaults);
    return response(200, { revision: 9, preferences: submitted });
  });
  await controller.update(() => preferenceSchema.defaults);
  assert.deepEqual(controller.snapshot().preferences, decodedDefaults);

  const serverCurrent = { ...decodedDefaults, scanner: { detail_mode: 'engineer' } };
  queuedResponses.push(response(409, { error: 'stale' }),
    response(200, { revision: 10, preferences: serverCurrent }));
  await assert.rejects(controller.update((profile) => { profile.scanner.detail_mode = 'normal'; }),
    (error) => error.code === 'preference_conflict');
  assert.equal(controller.snapshot().revision, 10);
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
