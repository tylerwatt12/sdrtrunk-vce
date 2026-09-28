'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { readStylesheetSource } = require('./stylesheet-source');
const vm = require('node:vm');

const core = path.resolve(process.argv[2] || path.resolve(__dirname, '../../../../stats-web/assets/core'));
const appSource = fs.readFileSync(path.resolve(core, '../app.js'), 'utf8');
const appCssSource = readStylesheetSource(path.resolve(core, '../app.css'));
const indexSource = fs.readFileSync(path.resolve(core, '../../index.html'), 'utf8');
const wordmarkSource = fs.readFileSync(path.resolve(core, '../vce-wordmark.svg'), 'utf8');
const manifest = JSON.parse(fs.readFileSync(path.resolve(core, '../site.webmanifest'), 'utf8'));
const playerSource = fs.readFileSync(path.resolve(core, '../web-call-player.js'), 'utf8');
const radioReferenceImportSource = fs.readFileSync(
  path.resolve(core, '../features/radioreference-import.js'), 'utf8');
const aliasListCreateSource = fs.readFileSync(
  path.resolve(core, '../features/alias-list-create.js'), 'utf8');

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
  const parameters = source.indexOf('(', start);
  const open = source.indexOf('{', closingDelimiter(source, parameters) + 1);
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
  const [routes, preferences, preferenceSchema, tableLayouts, tableDefaults, pageTitles, entityRefs, playerModule] =
    await Promise.all([
    'routes', 'user-preferences', 'preference-schema', 'table-layout', 'table-defaults', 'page-title', 'entity-ref',
    '../web-call-player'
  ].map(loadModule));
  const stableId = /^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$/;
  assert.match(functionBinding(appSource, 'tabs'), /ui-page-nav ui-segmented/);
  assert.match(functionBinding(appSource, 'tabs'), /aria-current', 'page'/);
  assert.match(functionBinding(appSource, 'section'), /section ui-section/);
  assert.match(functionBinding(appSource, 'metrics'), /summary-band ui-metric-grid/);
  assert.match(appCssSource, /\.ui-page-nav \{[^}]*flex-wrap: wrap/s);
  assert.doesNotMatch(appCssSource, /\.tabs a\.active/);
  assert.match(appSource, /const tableType = tableLayouts\.tableId\(options\.type\)/);
  assert.match(appSource, /tableLayouts\.registerSchema\(tableSchemaRegistry, tableType, declaredColumns\)/);
  assert.match(appSource, /tableDefaults\.layout\(tableType, declaredColumns\)/);
  assert.match(appSource, /tableDefaults\.width\(tableType, column, variant\)/);
  assert.doesNotMatch(appSource, /defaultHiddenColumns|TABLE_DEFAULT_COLUMN_WIDTHS|TABLE_COLUMN_DEFAULT_WIDTHS/);
  assert.doesNotMatch(appSource, /\{ id: '[^']+'[^\n]*\bwidth: \d+/);
  const sharedTableSource = appSource.slice(appSource.indexOf('function table('),
    appSource.indexOf('function tableSection('));
  assert.match(sharedTableSource, /const chooser = node\('div', 'table-layout-menu'\)/);
  assert.match(sharedTableSource, /options\.layoutMenuHost instanceof Node/);
  assert.match(sharedTableSource, /options\.layoutMenuHost\.append\(chooser\)/);
  assert.doesNotMatch(sharedTableSource, /options\.layoutMenuHost \|\| wrapper/);
  assert.doesNotMatch(sharedTableSource, /table-layout-menu-inline/);
  assert.match(sharedTableSource, /addColumnResizers\(element, columns/);
  assert.doesNotMatch(sharedTableSource, /if \(userPreferenceController\.snapshot\(\)\.loaded\)/);
  assert.match(functionBinding(appSource, 'saveAnonymousTableLayout'),
    /localStorage\.setItem\(ANONYMOUS_TABLE_LAYOUTS_STORAGE_KEY/);
  assert.match(appSource, /localStorage\.getItem\(ANONYMOUS_TABLE_LAYOUTS_STORAGE_KEY\)/);
  assert.doesNotMatch(radioReferenceImportSource, /\{ id: '[^']+'[^\n]*\bwidth: \d+/);
  assert.match(appSource, /typeof column\.renderHeader === 'function'/);
  assert.match(appSource, /column\.renderHeader\(\{ column, tableType, controller: tableController \}\)/);
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
  assert.match(functionBinding(appSource, 'aliasScanListChoices'), /aliasAssignmentToggle\(/);
  assert.match(functionBinding(appSource, 'aliasAssignmentToggle'), /uiToggleField\(/);
  assert.match(functionBinding(appSource, 'aliasAssignmentToggle'), /ui-toggle-field-compact/);
  assert.match(appCssSource, /\.ui-choice-card:has\(\.ui-choice-radio:checked\)/);
  assert.match(appCssSource, /\.ui-toggle-field-compact \{/);
  assert.match(appSource,
    /ui-button ui-button-secondary ui-icon-button ui-icon-button-compact table-layout-trigger/);
  assert.match(appCssSource, /\.ui-icon-button-compact \{[^}]*--icon-action-size: var\(--control-height-compact\)/s);
  assert.match(functionBinding(appSource, 'showUserPreferenceError'), /'Retry'/);
  assert.match(functionBinding(appSource, 'showUserPreferenceError'), /'Dismiss'/);
  assert.match(appSource, /activeReadOnlyModal === modalState && closeReadOnlyModal\(\)/);
  assert.match(appSource, /if \(!force && active\.isBusy\?\.\(\)\) return false/);
  const updatePreferencesSource = functionBinding(appSource, 'updateUserPreferences');
  assert.match(updatePreferencesSource, /result\?\.state === 'stale'/);
  assert.match(updatePreferencesSource, /error\.code = 'preference_session_changed'/);
  assert.match(updatePreferencesSource, /error\?\.code !== 'preference_conflict'/);
  assert.match(updatePreferencesSource, /showUserPreferenceError\(error, retry, true\)/);
  const preferenceMutationSession = { authenticated: false };
  let preferenceMutationControllerUpdates = 0;
  let preferenceMutationErrors = 0;
  let preferenceMutationClears = 0;
  let preferenceMutationControllerError = null;
  let preferenceMutationControllerProfile = JSON.parse(JSON.stringify(preferenceSchema.defaults));
  const preferenceMutationController = {
    update: async (mutator) => {
      preferenceMutationControllerUpdates += 1;
      if (preferenceMutationControllerError) throw preferenceMutationControllerError;
      const draft = JSON.parse(JSON.stringify(preferenceMutationControllerProfile));
      const returned = mutator(draft);
      preferenceMutationControllerProfile = preferenceSchema.validate(returned === undefined ? draft : returned);
      return { preferences: preferenceMutationControllerProfile };
    }
  };
  const preferenceMutationHarness = vm.runInNewContext(`(() => {
    let anonymousUserPreferences = JSON.parse(JSON.stringify(preferenceSchema.defaults));
    async function updateUserPreferences(mutator, allowRetry = true)
      ${functionBinding(appSource, 'updateUserPreferences')}
    return {
      update: updateUserPreferences,
      anonymous: () => anonymousUserPreferences
    };
  })()`, {
    accessSession: preferenceMutationSession,
    userPreferenceController: preferenceMutationController,
    preferenceSchema,
    clearUserPreferenceError: () => { preferenceMutationClears += 1; },
    showUserPreferenceError: () => { preferenceMutationErrors += 1; },
    settleUserPreferenceMutation: () => null
  });
  const publicScannerPreferences = await preferenceMutationHarness.update((profile) => {
    profile.scanner.detail_mode = 'advanced';
  });
  assert.equal(publicScannerPreferences.scanner.detail_mode, 'advanced',
    'Public Scanner display choices must remain active in the anonymous browser profile');
  assert.equal(preferenceMutationHarness.anonymous().scanner.detail_mode, 'advanced');
  assert.equal(preferenceMutationControllerUpdates, 0,
    'Public display preferences must not call the signed-in preference endpoint');
  assert.equal(preferenceMutationErrors, 0,
    'Public display preferences must not report a My Settings save failure');
  assert.equal(preferenceMutationClears, 1,
    'A successful public mutation must clear any stale signed-in preference notice');
  preferenceMutationSession.authenticated = true;
  preferenceMutationControllerError = new Error('User preferences are not loaded.');
  await assert.rejects(() => preferenceMutationHarness.update((profile) => {
    profile.scanner.detail_mode = 'simple';
  }), /User preferences are not loaded/);
  assert.equal(preferenceMutationHarness.anonymous().scanner.detail_mode, 'advanced',
    'A signed-in preference failure must not overwrite the anonymous fallback profile');
  assert.equal(preferenceMutationErrors, 1,
    'A signed-in preference failure must remain visible instead of silently falling back');
  preferenceMutationControllerError = null;
  const signedInPreferences = await preferenceMutationHarness.update((profile) => {
    profile.scanner.detail_mode = 'engineer';
  });
  assert.equal(signedInPreferences.scanner.detail_mode, 'engineer');
  assert.equal(preferenceMutationControllerUpdates, 2,
    'Signed-in display preferences must still use server-backed persistence');
  const setThemeSource = functionBinding(appSource, 'setTheme');
  assert.match(setThemeSource, /settleUserPreferenceMutation/,
    'Theme choices must use the same authentication-aware preference mutation path');
  assert.doesNotMatch(setThemeSource, /userPreferenceController\.snapshot|anonymousUserPreferences/);
  const playbackPreferenceAccessSource = functionBinding(appSource, 'synchronizePlaybackAccess');
  assert.match(playbackPreferenceAccessSource,
    /setPreferenceWriter\(\(playback\) => \{\s*return updateUserPreferences/,
    'Playback choices must use the same authentication-aware preference mutation path');
  assert.doesNotMatch(playbackPreferenceAccessSource,
    /setPreferenceWriter\(\(playback\) => \{\s*if \(!userPreferenceController/);
  assert.match(functionBinding(appSource, 'applyUserPreferenceSnapshot'),
    /snapshot\.loaded \|\| snapshot\.identity === null/,
    'Returning to public access must clear a stale signed-in My Settings notice');
  assert.match(functionBinding(appSource, 'showUserPreferenceError'), /saveFailed \?/);
  assert.match(functionBinding(appSource, 'settleUserPreferenceMutation'), /\.catch\(\(\) => null\)/);
  assert.match(functionBinding(appSource, 'saveTableLayoutPreference'),
    /return settleUserPreferenceMutation[\s\S]*\}, false\)/);
  assert.doesNotMatch(appSource, /void updateUserPreferences\(/);
  assert.match(indexSource,
    /id="preference-status" class="preference-status ui-notice" role="status" aria-live="polite" hidden/);
  assert.match(indexSource, /id="global-status" class="visually-hidden"/);
  assert.match(indexSource, /<title>VCE<\/title>/);
  assert.match(indexSource,
    /<meta name="theme-color" content="#202a33" media="\(prefers-color-scheme: light\)">/);
  assert.match(indexSource,
    /<meta name="theme-color" content="#090e13" media="\(prefers-color-scheme: dark\)">/);
  assert.match(indexSource,
    /<link rel="icon" href="\/assets\/vce-favicon\.svg\?v=1" type="image\/svg\+xml">/);
  assert.match(indexSource,
    /<link rel="icon" href="\/assets\/vce-icon-32\.png\?v=1" type="image\/png" sizes="32x32">/);
  assert.match(indexSource,
    /<link rel="apple-touch-icon" href="\/assets\/vce-apple-touch-icon\.png\?v=1" sizes="180x180">/);
  assert.match(indexSource, /<link rel="manifest" href="\/assets\/site\.webmanifest\?v=1">/);
  assert.match(indexSource,
    /<a class="brand" href="\/\?view=dashboard" aria-label="VCE home"><img class="brand-logo" src="\/assets\/vce-wordmark\.svg\?v=2" alt=""><\/a>/);
  assert.match(wordmarkSource, /<svg[^>]+viewBox="[^"]+"/);
  assert.match(wordmarkSource, /<path[^>]+fill="#f2f5f7"/);
  assert.doesNotMatch(wordmarkSource, /data:image/,
    'The VCE header wordmark must remain a true vector asset.');
  assert.equal(manifest.name, 'VCE');
  assert.equal(manifest.short_name, 'VCE');
  assert.equal(manifest.start_url, '/?view=dashboard');
  assert.ok(manifest.icons.some((icon) => icon.src === '/assets/vce-icon-192.png?v=1'
    && icon.sizes === '192x192' && icon.purpose === 'any'));
  assert.ok(manifest.icons.some((icon) => icon.src === '/assets/vce-icon-512.png?v=1'
    && icon.sizes === '512x512' && icon.purpose === 'any'));
  assert.ok(manifest.icons.some((icon) => icon.src === '/assets/vce-icon-maskable-512.png?v=1'
    && icon.sizes === '512x512' && icon.purpose === 'maskable'));
  for (const icon of ['vce-icon-192.png', 'vce-icon-512.png', 'vce-icon-maskable-512.png']) {
    assert.ok(fs.existsSync(path.resolve(core, '..', icon)), `${icon} must exist beside the web manifest`);
  }
  assert.match(appCssSource, /\.brand \{[^}]*width: 88px;[^}]*min-width: 88px;/s);
  assert.match(appCssSource, /\.brand-logo \{[^}]*width: 100%;[^}]*display: block;/s);
  assert.match(appCssSource,
    /@media \(max-width: 560px\)[\s\S]*?\.topbar \{[^}]*grid-template-columns: var\(--icon-action-size\) minmax\(78px, 1fr\) max-content;[^}]*gap: 10px;[^}]*padding: 5px 8px;[\s\S]*?\.brand \{[^}]*width: 78px;[^}]*min-width: 78px;/);
  assert.match(appCssSource, /\.preference-status \{/);
  assert.match(functionBinding(appSource, 'showUserPreferenceError'),
    /preference-status ui-notice ui-notice-danger/);
  assert.doesNotMatch(appSource, /logging-notice/);
  assert.doesNotMatch(appCssSource, /\.logging-notice\b|\.preference-status\.preference-error/);
  const settingsSource = functionBinding(appSource, 'renderSettings');
  assert.match(settingsSource, /A read-only overview of every personal preference/);
  assert.match(settingsSource, /userPreferenceSummaryCards\(current\)/);
  assert.match(settingsSource, /Reset All Personal Preferences/);
  assert.match(settingsSource, /openResetUserPreferences/);
  assert.match(settingsSource, /openStatusIconSettings\('#status-icon-settings'\)/);
  assert.match(settingsSource, /statusIconRequested/);
  assert.doesNotMatch(settingsSource, /href\('settings', \{ section: 'status-icon' \}\)/);
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
  assert.match(appSource, /activateScannerSettings\(scannerSettings\)/);
  const scannerSettingsActivationSource = functionBinding(appSource, 'activateScannerSettings');
  assert.match(scannerSettingsActivationSource, /!snapshot\.loaded && !accessSession\.authenticated/);
  assert.match(scannerSettingsActivationSource, /showLoginModal\(returnFocusSelector\)/);
  assert.match(scannerSettingsActivationSource, /snapshot = await synchronizeUserPreferences\(\)/);
  assert.match(scannerSettingsActivationSource, /if \(!button\.isConnected\) return/);
  assert.match(scannerSettingsActivationSource, /openScannerSettings\(returnFocusSelector\)/);
  const scannerActivationSession = { authenticated: false };
  let scannerActivationSnapshot = { loaded: false };
  let scannerActivationLoginTarget = null;
  let scannerActivationOpenTarget = null;
  let scannerActivationSyncs = 0;
  const activateScannerSettings = vm.runInNewContext(
    `(async function(button) ${scannerSettingsActivationSource})`, {
      accessSession: scannerActivationSession,
      userPreferenceController: { snapshot: () => scannerActivationSnapshot },
      showLoginModal: (target) => { scannerActivationLoginTarget = target; },
      synchronizeUserPreferences: async () => {
        scannerActivationSyncs += 1;
        return { loaded: true };
      },
      setIconButton: () => {},
      openScannerSettings: (target) => { scannerActivationOpenTarget = target; }
    });
  const scannerActivationButton = {
    id: 'scanner-settings', isConnected: true, disabled: false,
    classList: { add: () => {}, remove: () => {} },
    replaceChildren: () => {}, setAttribute: () => {}, removeAttribute: () => {}
  };
  await activateScannerSettings(scannerActivationButton);
  assert.equal(scannerActivationLoginTarget, '#scanner-settings',
    'Public Scanner settings must open the sign-in flow and preserve its focus return target');
  assert.equal(scannerActivationSyncs, 0);
  assert.equal(scannerActivationOpenTarget, null);
  scannerActivationSession.authenticated = true;
  scannerActivationLoginTarget = null;
  await activateScannerSettings(scannerActivationButton);
  assert.equal(scannerActivationSyncs, 1);
  assert.equal(scannerActivationOpenTarget, '#scanner-settings');
  scannerActivationOpenTarget = null;
  scannerActivationButton.isConnected = false;
  await activateScannerSettings(scannerActivationButton);
  assert.equal(scannerActivationOpenTarget, null,
    'A delayed Scanner settings load must not open after navigation removes its trigger');
  const liveChannelsSource = functionBinding(appSource, 'liveChannelsSection');
  const selectedViewActionSource = functionBinding(appSource, 'liveSelectedViewAction');
  const liveSettingsActivationSource = functionBinding(appSource, 'activateLivePresentationSettings');
  assert.match(liveChannelsSource, /layoutMenuHost: titleActions/);
  assert.match(liveChannelsSource,
    /iconButton\('icon-live-presentation', 'Live presentation settings'/);
  assert.match(liveChannelsSource, /titleActions\.append\(presentationSettings\)/);
  assert.doesNotMatch(liveChannelsSource,
    /if \(userPreferenceController\.snapshot\(\)\.loaded\) \{[\s\S]*presentationSettings/);
  assert.match(selectedViewActionSource, /ui-icon-button section-title-icon live-selected-view-action/);
  assert.match(selectedViewActionSource, /return setIconButton\(action, iconId, label\)/);
  assert.doesNotMatch(selectedViewActionSource, /node\('span'/);
  assert.match(liveSettingsActivationSource, /!snapshot\.loaded && !accessSession\.authenticated/);
  assert.match(liveSettingsActivationSource, /showLoginModal\(returnFocusSelector\)/);
  assert.match(liveSettingsActivationSource, /snapshot = await synchronizeUserPreferences\(\)/);
  assert.match(liveSettingsActivationSource, /if \(!button\.isConnected\) return/);
  assert.match(liveSettingsActivationSource, /openLivePresentationSettings\(returnFocusSelector\)/);
  assert.match(liveChannelsSource, /section\('Live Channels', host, titleActions\)/);
  assert.match(liveChannelsSource, /let requestedChannel = route\.get\('channel'\)/);
  assert.match(liveChannelsSource, /liveRequestedChannelMatch\(value, requestedChannel\)/);
  assert.match(liveChannelsSource, /if \(requestedMatch\.row\) \{[\s\S]*selectRow\(displayed, requestedRow\)/,
    'A conventional Live channel deep link must select the matching row');
  assert.match(liveChannelsSource,
    /requestedChannel = null;\s+route\.delete\('channel'\);\s+window\.history\.replaceState/,
    'A Live channel deep link must be consumed after its initial selection');
  assert.match(liveChannelsSource, /tabBar\.setAttribute\('role', 'tablist'\)/);
  assert.match(liveChannelsSource, /tabBar\.setAttribute\('aria-orientation', 'vertical'\)/);
  assert.match(liveChannelsSource, /select\.setAttribute\('role', 'tab'\)/);
  assert.match(liveChannelsSource, /livePickerNavigationIndex\(event\.key, index, buttons\.length\)/);
  assert.match(liveChannelsSource, /savedUiState\.picker_collapsed === true/);
  assert.match(liveChannelsSource,
    /iconButton\('icon-chevron-down', 'Collapse live view picker',[\s\S]*live-picker-collapse/);
  assert.match(liveChannelsSource, /selectedViewLead\.append\(pickerCollapse, selectedViewCopy\)/);
  assert.match(liveChannelsSource, /selectedViewHeader\?\.replaceChildren\(selectedViewLead, titleActions\)/);
  assert.match(liveChannelsSource, /pickerCollapse\.setAttribute\('aria-controls', picker\.id\)/);
  assert.doesNotMatch(liveChannelsSource, /pickerActions\.(?:append|prepend)\(pickerCollapse\)/);
  assert.match(liveChannelsSource, /storeLiveUiState\(\{ picker_collapsed: pickerCollapsed \}\)/);
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
  assert.match(resizerSource, /addEventListener\('dblclick'/);
  assert.match(resizerSource, /event\.key === 'Enter'/);
  assert.match(resizerSource, /measureTableColumnContentWidth\(element, header, index\)/);
  assert.match(resizerSource, /resizedWidths\[index\] === startingWidths\[index\]/);
  assert.match(resizerSource, /addEventListener\('lostpointercapture', cancel\)/);
  assert.match(resizerSource, /window\.addEventListener\('blur', cancel/);
  assert.match(resizerSource, /finally \{\s+endLayoutMutation\(\)/);
  class ResizeTarget {
    constructor() { this.listeners = new Map(); }
    addEventListener(type, listener) {
      if (!this.listeners.has(type)) this.listeners.set(type, new Set());
      this.listeners.get(type).add(listener);
    }
    removeEventListener(type, listener) { this.listeners.get(type)?.delete(listener); }
    emit(type, properties = {}) {
      for (const listener of [...(this.listeners.get(type) || [])]) listener(properties);
    }
    setAttribute() {}
    setPointerCapture() {}
  }
  const resizeHeader = new ResizeTarget();
  resizeHeader.getBoundingClientRect = () => ({ width: 100 });
  resizeHeader.append = (handle) => { resizeHeader.handle = handle; };
  const resizeWindow = new ResizeTarget();
  let resizePending = false;
  let resizeLayout = { column_widths: {} };
  let restoredWidths = 0;
  const savedWidths = [];
  const resizerFunctionSource = appSource.slice(appSource.indexOf('function addColumnResizers('),
    appSource.indexOf('function cleanupTableLayoutMenu(')).trim();
  const addResizers = vm.runInNewContext(`(${resizerFunctionSource})`, {
    node: () => new ResizeTarget(), window: resizeWindow,
    TABLE_WIDTH_MINIMUM: 48, TABLE_WIDTH_MAXIMUM: 1200,
    tableColumnKey: (column) => column.id,
    tableLayouts: { resize: (layout, key, width) =>
      ({ ...layout, column_widths: { ...layout.column_widths, [key]: width } }) },
    tableDefaults: { fit: () => false }, setTableColumnWidths: () => {},
    saveTableLayoutPreference: async (_type, layout) => {
      savedWidths.push(layout.column_widths.action);
      return true;
    }
  });
  addResizers({}, [{ id: 'action', label: 'Action' }], [], [resizeHeader], 'actions',
    () => resizeLayout, (layout) => { resizeLayout = layout; },
    () => { if (resizePending) return false; resizePending = true; return true; },
    () => { resizePending = false; }, () => {}, () => { restoredWidths += 1; });
  const dragStart = { button: 0, clientX: 100, pointerId: 1,
    preventDefault() {}, stopPropagation() {} };
  resizeHeader.handle.emit('pointerdown', dragStart);
  resizeHeader.handle.emit('pointermove', { clientX: 130 });
  resizeHeader.handle.emit('lostpointercapture');
  assert.equal(resizePending, false, 'lost capture must release the layout lock');
  assert.equal(restoredWidths, 1, 'lost capture must restore the saved widths');
  resizeHeader.handle.emit('pointerdown', dragStart);
  resizeHeader.handle.emit('pointermove', { clientX: 140 });
  resizeHeader.handle.emit('pointerup', { clientX: 140 });
  await new Promise(setImmediate);
  assert.equal(resizePending, false, 'the next drag must finish normally');
  assert.deepEqual(savedWidths, [140]);
  const autofitSource = functionBinding(appSource, 'measureTableColumnContentWidth');
  assert.match(autofitSource, /\.\.\.element\.tBodies/);
  assert.match(autofitSource, /!cell\.classList\.contains\('empty'\)/);
  assert.match(autofitSource, /measurement\.getBoundingClientRect\(\)\.width/);
  assert.match(autofitSource, /Math\.max\(TABLE_WIDTH_MINIMUM, Math\.min\(TABLE_WIDTH_MAXIMUM, width\)\)/);
  assert.match(appSource, /let layoutMutationPending = false/);
  assert.match(appCssSource, /\.resizable-table\.table-layout-busy \.column-resizer/);
  assert.match(appCssSource, /\.table-column-autofit-measurement \{/);
  assert.match(appSource, /element\.classList\.add\('ui-mobile-cards'\)/,
    'mobile card tables must opt into the shared responsive presentation');
  assert.match(appSource, /dataRows = prepend \? dataRows\.slice\(0, limit\) : dataRows\.slice\(-limit\)/);
  assert.match(appSource, /rows: \(\) => dataRows\.slice\(\)/);
  assert.match(appSource, /trigger\.setAttribute\('popovertarget', panelId\)/);
  assert.match(appSource, /const trigger = iconButton\('icon-columns', 'Choose table columns'/);
  assert.match(appSource, /const displayLabel = byId\.get\(id\)\.fullLabel \|\| byId\.get\(id\)\.label \|\| id/);
  assert.match(appSource, /visibility\.setAttribute\('aria-label', `Show \$\{displayLabel\} column`\)/);
  assert.doesNotMatch(appSource, /inline \? '' : 'Columns'/);
  assert.match(appSource, /panel\.setAttribute\('popover', 'auto'\)/);
  assert.match(appSource, /const panel = node\('div', 'ui-popover table-layout-panel'\)/);
  assert.match(appSource, /const visibility = node\('input', 'ui-selection-check'\)/);
  assert.match(appSource, /bindAnchoredDropdown\(trigger, panel, activeRenderController\?\.signal\)/);
  const dropdownBinding = functionBinding(appSource, 'bindAnchoredDropdown');
  assert.match(dropdownBinding, /new AbortController\(\)/);
  assert.match(dropdownBinding, /addEventListener\('resize'/);
  assert.match(dropdownBinding, /addEventListener\('scroll'/);
  assert.match(dropdownBinding, /setAttribute\('aria-expanded'/);
  assert.match(dropdownBinding, /panel\.style\.maxHeight = ''/);
  assert.match(dropdownBinding, /panel\.hidePopover\(\)/);
  assert.match(appCssSource, /\.table-layout-menu \{[^}]*display: flex[^}]*flex: 0 0 auto/s);
  assert.doesNotMatch(appCssSource, /\.table-layout-menu-inline/);
  assert.match(appCssSource, /\.ui-table-titlebar \{[^}]*min-height: var\(--control-height-compact\)/s);
  assert.match(appCssSource, /\.ui-popover \{[^}]*position: fixed[^}]*inset: auto[^}]*margin: 0/s);
  assert.match(appCssSource, /\.table-layout-panel \{[^}]*--ui-popover-max-height:/s);
  assert.doesNotMatch(appCssSource, /\.admin-toggle-control input\s*\{/);
  assert.doesNotMatch(appCssSource, /\.scan-list-admin-form input\[type="checkbox"\]/);
  assert.doesNotMatch(appCssSource, /button\.secondary\.danger-outline/);
  assert.match(functionBinding(appSource, 'openScanListAdminModal'),
    /ui-button ui-button-primary', editing \? 'Save Scan List'/);
  assert.match(functionBinding(appSource, 'renderAdminSupportReport'),
    /ui-button ui-button-primary', 'Generate Support Bundle'/);
  assert.match(functionBinding(appSource, 'userTierControl'),
    /node\('select', 'ui-select admin-tier-select'\)/);
  assert.match(functionBinding(appSource, 'accessPolicyTierControl'),
    /node\('select', 'ui-select admin-tier-select'\)/);
  assert.doesNotMatch(appCssSource, /\.admin-tier-select\s*\{[^}]*background:/s);
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
  assert.doesNotMatch(appCssSource, /button:not\([^\n]+:not\(\.auth-session-button\)/);
  assert.match(appCssSource, /\.ui-button-header \{/);
  assert.match(appCssSource, /\.settings-card-grid \{[^}]*align-items: stretch/s);
  assert.doesNotMatch(appCssSource, /\.settings-card-grid \{[^}]*max-width/s);
  assert.match(appCssSource, /\.settings-card \{[^}]*height: 100%/s);
  assert.match(appCssSource, /\.settings-form-footer \{[^}]*grid-column: 1 \/ -1[^}]*justify-self: stretch/s);
  assert.doesNotMatch(appCssSource, /\.settings-form-footer \{[^}]*max-width/s);
  const aliasMembershipOperation = functionBinding(appSource, 'aliasBulkBinaryOperation');
  assert.match(aliasMembershipOperation, /node\('button', 'ui-segmented-option', label\)/);
  assert.match(aliasMembershipOperation, /alias-membership-operation ui-segmented/);
  const toggleField = functionBinding(appSource, 'uiToggleField');
  assert.match(toggleField, /node\('div', 'ui-toggle-field'\)/);
  assert.match(toggleField, /uiToggle\(checked, accessibleLabel\)/);
  assert.match(appCssSource, /\.ui-segmented-option \{[^}]*display: inline-flex[^}]*margin: 0/s);
  assert.match(appCssSource, /\.ui-selection-check \{[^}]*appearance: none/s);
  assert.match(appCssSource, /\.ui-selection-check:checked::before/);
  assert.match(appCssSource, /\.ui-selection-check:indeterminate::before/);
  assert.match(appCssSource, /\.ui-toggle-field \{[^}]*display: flex[^}]*justify-content: space-between/s);
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
  assert.doesNotMatch(playbackAccessSource,
    /setPreferenceWriter\(\(playback\) => \{\s*if \(!userPreferenceController/,
    'Playback choices must not silently fall back when signed-in preferences fail to load');
  const receiverSettingsRequestSource = functionBinding(appSource, 'requestReceiverSettings');
  assert.match(receiverSettingsRequestSource, /headers\['If-Match'\] = `"\$\{revision\}"`/);
  const receiverSettingsSource = functionBinding(appSource, 'renderAdminReceiverBehaviorSettings');
  assert.match(receiverSettingsSource, /error\?\.code === 'receiver_settings_conflict'/);
  assert.match(receiverSettingsSource, /apply\(error\.current\)/);
  assert.match(receiverSettingsSource, /Current server values were reloaded/);
  assert.match(receiverSettingsSource, /ui-button ui-button-primary/);
  assert.match(functionBinding(appSource, 'renderAdminSpectrumSnapSettings'), /ui-button ui-button-primary/);
  assert.match(functionBinding(appSource, 'renderAdminRadioReferenceSettings'),
    /ui-button ui-button-primary[\s\S]+ui-button ui-button-danger/);
  assert.match(functionBinding(appSource, 'renderAdminRadioReferenceSettings'),
    /createRadioReferenceImportWorkspace\([\s\S]+workspace\.append\(importWorkspace\.element\)/);
  assert.match(functionBinding(appSource, 'renderAdminRadioReferenceSettings'),
    /openReadOnlyModal\('RadioReference settings'[\s\S]+gate\.hidden = connected/);
  assert.match(radioReferenceImportSource, /selectedTalkgroups: new Set\(\)/);
  assert.match(radioReferenceImportSource, /Selections persist across filters and pages/);
  assert.match(radioReferenceImportSource, /Clear selection/);
  assert.match(radioReferenceImportSource, /Review selected/);
  assert.match(radioReferenceImportSource, /Import all system talkgroups/);
  assert.match(radioReferenceImportSource,
    /\['CONTROL'[\s\S]+\['CONTROL_AND_ALTERNATES'[\s\S]+\['SELECTED'[\s\S]+\['ALL'/);
  assert.match(radioReferenceImportSource, /Create one combined channel/);
  assert.match(radioReferenceImportSource, /Import one channel at a time/);
  assert.match(radioReferenceImportSource, /detectedSiteModulation/);
  assert.match(radioReferenceImportSource, /description\.includes\('simul'\)/);
  assert.match(radioReferenceImportSource, /const SITE_LIMIT = 50/);
  assert.match(radioReferenceImportSource, /siteCatalog[\s\S]+talkgroupCatalog[\s\S]+bookmarks/);
  assert.match(radioReferenceImportSource, /search\.addEventListener\('input'/);
  assert.doesNotMatch(radioReferenceImportSource, /System, agency, city, or county/);
  assert.match(radioReferenceImportSource, /Preview no longer valid/);
  assert.match(radioReferenceImportSource, /href\('channel-setup', \{ channel: configurationId \}\)/);
  assert.match(radioReferenceImportSource, /catalog_id: state\.talkgroupCatalogId/);
  assert.match(radioReferenceImportSource, /preferredAliasListId/);
  assert.match(radioReferenceImportSource, /aliasListRevision: 0/);
  assert.match(radioReferenceImportSource, /getRevision: currentAliasListRevision/);
  assert.match(radioReferenceImportSource,
    /currentAliasListRevision[\s\S]+admin\/alias-lists\?include_counts=false/,
    'Inline creation must refresh the Alias List revision after other import mutations');
  assert.match(radioReferenceImportSource,
    /onCreated: \(\{ aliasList, revision \}\)[\s\S]+dispatchEvent\(new Event\('change', \{ bubbles: true \}\)\)/);
  assert.equal((radioReferenceImportSource.match(/aliasListField\('/g) || []).length, 3,
    'RadioReference imports should define one shared Alias List field and use it for sites, talkgroups, and conventional channels');
  assert.match(radioReferenceImportSource, /return normalized === 'NBFM' \? 'Default Analog' : `Default \$\{normalized\}`/);
  assert.match(radioReferenceImportSource,
    /kind: 'conventional'[\s\S]+alias_list_id: Number\(aliases\.value\)/);
  assert.doesNotMatch(radioReferenceImportSource, /use the compatible default Alias List/);
  assert.match(aliasListCreateSource, /export async function createAliasList/);
  assert.match(aliasListCreateSource, /family: normalized\.toLowerCase\(\)/,
    'Alias List creation must use the API lowercase family contract');
  assert.match(aliasListCreateSource, /panel\.addEventListener\('input', \(event\) => event\.stopPropagation\(\)\)/,
    'Typing an inline Alias List name must not dirty or submit the parent editor');
  assert.match(aliasListCreateSource, /event\.key === 'Enter'/,
    'The inline creator must intercept Enter instead of submitting its parent form');
  assert.doesNotMatch(aliasListCreateSource, /name\.required\s*=\s*true/,
    'The inline creator name must not participate in parent-form validation while collapsed');
  assert.match(aliasListCreateSource, /panel\.addEventListener\('keydown'/,
    'Escape must close the inline creator from any of its controls');
  assert.doesNotMatch(aliasListCreateSource, /node\('form'/,
    'The inline creator must not nest a form inside channel or import forms');
  assert.doesNotMatch(radioReferenceImportSource, /Open Alias', href\('aliases'/);
  assert.match(radioReferenceImportSource, /browseCatalog/);
  assert.match(radioReferenceImportSource,
    /listHost\.replaceChildren\(directoryState\(feedback\('Loading directory results…', 'loading'\)\)\)/,
    'RadioReference directory feedback should be inset without changing populated result geometry');
  assert.match(radioReferenceImportSource, /RadioReference fields changing/);
  assert.match(radioReferenceImportSource, /mobileCards: true/);
  assert.match(radioReferenceImportSource, /imports\/site\/preview/);
  assert.match(radioReferenceImportSource, /imports\/conventional\/preview/);
  assert.match(radioReferenceImportSource, /imports\/talkgroups\/preview/);
  assert.doesNotMatch(radioReferenceImportSource, /For Each Frequency|Create Selected Channels/);
  const adminUsersRenderer = functionBinding(appSource, 'renderAdminUsers');
  const adminAccessRenderer = functionBinding(appSource, 'renderAdminAccess');
  assert.match(adminUsersRenderer,
    /admin-operation-status ui-notice[\s\S]+ui-button ui-button-primary/);
  assert.match(adminUsersRenderer, /type: 'admin-users', sortable: false, mobileCards: true/,
    'Web-account rows must use the shared labeled-card layout on narrow screens');
  assert.match(adminAccessRenderer, /admin-operation-status ui-notice/);
  assert.match(adminAccessRenderer, /type: 'admin-access', sortable: false, mobileCards: true/,
    'Access-policy rows must use the shared labeled-card layout on narrow screens');
  assert.match(adminAccessRenderer, /adminWorkflowNote\('Access is layered'/);
  const adminNavigation = functionBinding(appSource, 'adminSettingsTree');
  assert.match(adminNavigation, /admin-settings-picker/);
  assert.match(adminNavigation, /node\('optgroup'\)/);
  assert.match(adminNavigation,
    /select\.addEventListener\('change', \(\) => navigateTo\(href\('admin', \{ tab: select\.value \}\)\)\)/,
    'The compact administration picker must navigate through the same tab routes as the desktop tree');
  assert.match(functionBinding(appSource, 'userIdentityCell'), /uiPill\('Primary', 'success'\)/);
  assert.match(functionBinding(appSource, 'userActions'),
    /iconButton\('icon-edit'[\s\S]+iconButton\('icon-trash'[\s\S]+ui-button ui-button-danger-quiet ui-icon-button/);
  const dashboardCallChart = functionBinding(appSource, 'dashboardCallActivityChart');
  assert.match(dashboardCallChart, /dashboard-control-group ui-segmented/);
  assert.match(dashboardCallChart, /ui-segmented-option dashboard-filter-button/);
  assert.match(dashboardCallChart, /ui-button ui-button-secondary activity-series-button/);
  assert.match(functionBinding(appSource, 'dashboardActivityMix'),
    /ui-button ui-button-secondary activity-series-button dashboard-activity-legend-button/);
  assert.match(functionBinding(appSource, 'dashboardActivityRadioPager'),
    /pager ui-pager dashboard-activity-radio-pager[\s\S]+ui-button ui-button-secondary/);
  const dashboardActivity = functionBinding(appSource, 'renderDashboardActivity');
  assert.match(dashboardActivity,
    /radioStatus\.textContent = '';\s*radioStatus\.hidden = true;[\s\S]+Loading \$\{actionLabel\.toLowerCase\(\)\} source radios…/,
    'Dashboard activity must show one loading or prompt treatment instead of duplicate status rows');
  const tunerSpectrumPanel = functionBinding(appSource, 'tunerSpectrumPanel');
  assert.match(tunerSpectrumPanel, /ui-button ui-button-secondary ui-icon-button/);
  assert.match(tunerSpectrumPanel, /ui-button ui-button-secondary tuner-spectrum-options-summary/);
  assert.match(tunerSpectrumPanel,
    /node\('summary', 'ui-button ui-button-secondary', 'More measurements'\)/);
  assert.match(tunerSpectrumPanel, /const optionToggle = \(checked, label, detail\)[\s\S]+uiToggle\(checked, label\)/);
  assert.match(tunerSpectrumPanel, /tuner-spectrum-options-header/);
  assert.match(tunerSpectrumPanel, /uiSelectFrame\(targetSelect\)/);
  assert.match(functionBinding(appSource, 'renderActivity'), /node\('div', 'pager ui-pager'\)/);
  assert.match(functionBinding(appSource, 'receiverHealthResolvedPager'),
    /pager ui-pager receiver-health-resolved-pager/);
  const coverageSource = functionBinding(appSource, 'renderAliasCoverageDirectory');
  const embeddedDirectoryPanel = functionBinding(appSource, 'radioDirectoryEmbeddedPanel');
  assert.match(embeddedDirectoryPanel, /radio-directory-panel-body/);
  assert.match(embeddedDirectoryPanel,
    /body\.append\(radioDirectoryPerspectiveControl\(view\), directory\)/);
  assert.match(coverageSource, /radioDirectoryEmbeddedPanel\('coverage', directory\.element\)/);
  assert.match(coverageSource, /alias-coverage ui-catalog data-workspace[\s\S]+uiSelectFrame\(listSelect\)/);
  assert.doesNotMatch(coverageSource, /aliasCoverageGuidance\(\)/);
  assert.match(coverageSource, /aliasCoverageScope\(overview\)/);
  assert.match(coverageSource, /alias-coverage-table-header/);
  assert.match(coverageSource, /const tableActions = sectionActionHost\(\)/);
  assert.match(coverageSource, /tableHeader\.append\(tableHeading, tabs, tableActions\)/);
  assert.match(coverageSource, /layoutMenuHost: tableActions/);
  assert.match(coverageSource, /directory\.host\.replaceChildren\(present\(model\)\)/);
  assert.match(coverageSource, /bare: true/);
  const radioDirectorySource = functionBinding(appSource, 'renderNestedRadioDirectory');
  assert.match(radioDirectorySource, /radioDirectoryEmbeddedPanel\('systems', loading\.element\)/);
  assert.match(radioDirectorySource, /bare: true/);
  assert.match(radioDirectorySource, /renderAliasCoverageDirectory\(renderContext, embedded\)/);
  assert.match(radioDirectorySource,
    /radioDirectoryCardSection\('Trunked Systems', trunkedSystems, 'system'/);
  assert.match(radioDirectorySource,
    /radioDirectoryCardSection\('Conventional Channels', conventionalSystems, 'conventional'/);
  assert.match(radioDirectorySource, /radioDirectoryConventionalGroups\(conventionalRows\)/);
  assert.doesNotMatch(radioDirectorySource, /\btable\s*\(/,
    'The read-only Radio Directory must render quiet cards instead of a data table');
  const aliasTransferSource = functionBinding(appSource, 'openAliasTransferModal');
  assert.match(aliasTransferSource, /node\('div', 'ui-table-actions'\)/);
  assert.match(aliasTransferSource, /node\('header', 'ui-table-titlebar'\)/);
  assert.match(aliasTransferSource, /layoutMenuHost: changeActions/);
  assert.match(functionBinding(appSource, 'radioDirectorySystemCard'),
    /ui-surface radio-directory-system-card[\s\S]+uiIconTile\('icon-trunked'\)/);
  assert.match(functionBinding(appSource, 'radioDirectoryConventionalSystemCard'),
    /ui-surface radio-directory-system-card radio-directory-conventional-card[\s\S]+uiIconTile\('icon-conventional', 'blue'\)/);
  assert.match(functionBinding(appSource, 'radioDirectoryConventionalRow'),
    /radio-directory-site-row radio-directory-conventional-row/);
  assert.match(functionBinding(appSource, 'radioDirectoryStatus'), /uiStatus\(/);
  assert.match(appCssSource, /\.ui-surface \{[^}]*background: var\(--surface\)/s);
  assert.match(appCssSource, /\.ui-status::before \{[^}]*border-radius: 50%/s);
  assert.match(appCssSource, /\.ui-icon-tile \{[^}]*background: var\(--accent-soft\)/s);
  assert.match(appCssSource,
    /\.radio-directory-system-grid \{[\s\S]+grid-template-columns: repeat\(2, minmax\(0, 1fr\)\)/);
  const renderLiveSource = functionBinding(appSource, 'renderLive');
  assert.doesNotMatch(appSource, /function liveActivityHistoryNotice\(/);
  assert.doesNotMatch(renderLiveSource, /historyNotice/);
  assert.match(renderLiveSource, /node\('div', 'live-right-workspace'\)/);
  assert.match(renderLiveSource, /const workspaceResizer = liveWorkspaceResizer\(rightWorkspace\)/);
  assert.match(renderLiveSource, /pageConnections\.add\(workspaceResizer\)/);
  assert.match(renderLiveSource,
    /split\.classList\.toggle\('picker-collapsed', channels\.pickerCollapsed\)/);
  assert.match(renderLiveSource,
    /rightWorkspace\.append\(channels\.element, workspaceResizer\.element, eventsPanel\.element\)/);
  assert.match(renderLiveSource, /split\.append\(channels\.picker, rightWorkspace\)/);
  assert.match(renderLiveSource, /beginPage\(renderContext, split\)/);
  const liveResizerSource = functionBinding(appSource, 'liveWorkspaceResizer');
  assert.match(liveResizerSource, /role', 'separator'/);
  assert.match(liveResizerSource, /aria-orientation', 'horizontal'/);
  assert.match(liveResizerSource, /details_panel_percent: detailsPercent/);
  assert.match(liveResizerSource, /if \(activePointerCleanup \|\| event\.button !== 0/);
  assert.match(liveResizerSource, /moveEvent\.pointerId !== pointerId/);
  assert.match(liveResizerSource,
    /upEvent\?\.pointerId !== undefined && upEvent\.pointerId !== pointerId/);
  assert.match(liveResizerSource, /separator\.setPointerCapture\(event\.pointerId\)/);
  assert.match(liveResizerSource, /separator\.removeEventListener\('pointercancel', cancel\)/);
  assert.match(liveResizerSource, /separator\.removeEventListener\('lostpointercapture', cancel\)/);
  assert.match(liveResizerSource, /window\.removeEventListener\('blur', cancel\)/);
  assert.match(liveResizerSource, /\['ArrowUp', 'ArrowDown', 'Home', 'End'\]/);
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
  assert.match(functionBinding(appSource, 'renderAdminReceiverBehaviorSettings'),
    /adminWorkflowNote\('Live display timing only'/,
    'Receiver-wide Live timing must explain its scope before the settings surface');
  assert.match(functionBinding(appSource, 'renderAdminSpectrumSnapSettings'),
    /adminWorkflowNote\('Spectrum display, not reception'/,
    'Spectrum scope settings must explain that they do not retune the receiver');
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
  const scanListRenderer = functionBinding(appSource, 'renderAdminScanLists');
  const scanListCard = functionBinding(appSource, 'adminScanListCard');
  const scanListActions = functionBinding(appSource, 'adminScanListActions');
  assert.doesNotMatch(scanListRenderer, /\btable\s*\(/,
    'Scan Lists must remain a card catalog instead of returning to a generic data table');
  assert.doesNotMatch(scanListRenderer, /layoutMenuHost|admin-scan-lists/,
    'Scan List cards must not expose table column customization');
  assert.match(scanListRenderer, /admin-scan-list-search/);
  assert.match(scanListRenderer, /admin-scan-list-filter/);
  assert.match(scanListRenderer, /search\.addEventListener\('input', applyFilters\)/);
  assert.match(scanListRenderer, /activeFilter === 'available'/);
  assert.match(scanListRenderer, /admin-scan-list-count/);
  assert.match(scanListRenderer, /admin-scan-list-filter-empty scan-list-card-empty ui-empty-state/);
  assert.match(scanListCard,
    /node\('article', 'admin-scan-list-card scan-list-card ui-surface'\)/);
  assert.match(scanListCard, /card\.setAttribute\('aria-labelledby', titleId\)/);
  assert.match(scanListCard, /node\('h2', '', name\)/);
  assert.match(scanListCard, /badge\('Hidden from listeners', 'state-stale'\)/);
  assert.match(scanListCard, /badge\('Available to listeners'\)/);
  assert.match(scanListCard, /'Routes unmatched calls here'/);
  assert.match(scanListActions, /anchor\('Manage Members'/);
  assert.match(appSource,
    /`Manage \$\{number\(count\)\} assigned alias\$\{count === 1 \? '' : 'es'\} for \$\{scanList\.name\}`/);
  const callMatchingRenderer = functionBinding(appSource, 'renderAdminCallMatching');
  assert.match(callMatchingRenderer, /type: 'call-matching-duplicates'/);
  assert.match(callMatchingRenderer, /mobileCards: true/);
  assert.match(callMatchingRenderer, /sortable: false/);
  assert.match(callMatchingRenderer, /callMatchingHistoryPage\(latest\.duplicates, historyPage\)/);
  assert.match(callMatchingRenderer, /callMatchingHistoryPager\(page, \(nextPage\) =>/);
  const tableCalls = functionCalls(appSource, 'table');
  assert.equal(tableCalls.length, 17, 'Every application table call must be audited');
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
    'aliasCustomConfigurationColumns',
    'aliasEditorSourceBreakdownColumns', 'aliasEditorBaseColumns', 'scanListMemberColumns',
    'dashboardIdentityColumns', 'radioSystemRadioColumns', 'p25ChannelFrequencyColumns',
    'trunkedChannelFrequencyColumns', 'p25ChannelNeighborColumns', 'trunkedChannelNeighborColumns',
    'activityColumns', 'channelDirectoryColumns', 'aliasCoverageAliasesColumns',
    'aliasCoverageUnassignedColumns', 'channelGroupIdentityColumns', 'channelRadioColumns'
  ].forEach((name) => {
    const ids = [...functionBinding(appSource, name).matchAll(/\bid\s*:\s*'([^']+)'/g)]
      .map((match) => match[1]);
    assert.ok(ids.length, `${name} must declare column IDs`);
    ids.forEach((id) => assert.match(id, stableId, `${name} has invalid column ID ${id}`));
    assert.equal(new Set(ids).size, ids.length, `${name} repeats a column ID`);
  });
  [
    'radioSystemChannelColumns', 'dashboardCallSourceColumns',
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

  const scannerCallRenderKey = vm.runInNewContext(
    `(function(call, state, site) ${functionBinding(appSource, 'scannerCallRenderKey')})`, {
      scannerDetailMode: 'normal', scannerMatchedScanLists: () => ''
    });
  const scannerCall = { call_id: 'call-1', started_at_ms: 100 };
  const scannerState = { stopped: false, paused: false };
  const withoutChannel = scannerCallRenderKey(scannerCall, scannerState, null);
  const channelA = scannerCallRenderKey(scannerCall, scannerState, {
    configuration_id: 'channel-a', channel_kind: 'CONVENTIONAL',
    entity_ref: { kind: 'channel', key: 'channel-a' }
  });
  const channelB = scannerCallRenderKey(scannerCall, scannerState, {
    configuration_id: 'channel-b', channel_kind: 'TRUNKED',
    entity_ref: { kind: 'channel', key: 'channel-b' }
  });
  assert.notEqual(withoutChannel, channelA,
    'Asynchronous channel metadata must invalidate captured scanner navigation handlers');
  assert.notEqual(channelA, channelB,
    'Changing channel identity must invalidate captured scanner navigation handlers');
  const scannerRenderer = functionBinding(appSource, 'renderScanner');
  assert.ok(scannerRenderer.indexOf('currentChannel = null;') <
    scannerRenderer.indexOf('renderScannerCall(display, state, currentChannel);'),
  'A call transition must clear old channel metadata before rendering its navigation handlers');
  assert.match(appSource,
    /const SCANNER_DETAIL_LEVELS = Object\.freeze\(\{ simple: 0, normal: 1, advanced: 2, engineer: 3 \}\)/,
    'Scanner detail modes must retain four ordered disclosure levels');
  assert.match(scannerRenderer, /scanner-workspace scanner-console ui-surface/);
  assert.match(scannerRenderer, /scanner-console-main/);
  assert.match(scannerRenderer, /scanner-now-playing/);
  assert.match(scannerRenderer, /scanner-scan-lists scanner-scan-rail/);
  assert.match(scannerRenderer, /scanner-coverage-action/);
  assert.match(scannerRenderer, /page\.dataset\.scannerMode = scannerDetailMode/);
  assert.match(scannerRenderer, /chassis\.dataset\.scannerMode = scannerDetailMode/);
  assert.match(scannerRenderer, /display\.dataset\.scannerMode = scannerDetailMode/);
  assert.match(scannerRenderer, /button\.setAttribute\('aria-pressed', String\(scannerDetailMode === id\)\)/,
    'Scanner detail mode buttons must expose their selected state');
  assert.match(scannerRenderer, /scanner-scan-check/);
  assert.match(scannerRenderer, /scanner-scan-copy/);
  assert.doesNotMatch(scannerRenderer, /scannerControl\(|scanner-controls|scanner-utility-row/,
    'Scanner must reuse the shared playback transport instead of duplicating oversized controls');
  const scannerCallRenderer = functionBinding(appSource, 'renderScannerCall');
  assert.match(scannerCallRenderer,
    /detailLevel >= SCANNER_DETAIL_LEVELS\.normal[\s\S]*scanner-audio-wave/,
    'Simple Scanner mode must omit the audio spectrum');
  assert.match(scannerCallRenderer,
    /!analog && detailLevel >= SCANNER_DETAIL_LEVELS\.normal/,
    'Simple Scanner mode must omit the secondary target card');
  assert.match(functionBinding(appSource, 'scannerParticipant'), /scannerField\('Group', group, 1\)/,
    'Normal Scanner mode must add participant grouping');
  assert.match(functionBinding(appSource, 'scannerParticipant'),
    /scannerField\('ID', alias \? identifier : null, 2/,
    'Advanced Scanner mode must add raw identifiers without duplicating fallback identities');
  assert.match(scannerCallRenderer, /scannerField\('Decoder', call\.decoder, 2/,
    'Advanced Scanner mode must add decoder diagnostics');
  assert.match(scannerCallRenderer, /scannerDetailMode === 'engineer'[\s\S]*scannerCallQuality\(call\)/,
    'Engineer Scanner mode must retain raw call-quality diagnostics');
  assert.match(functionBinding(appSource, 'scannerField'),
    /value === null \|\| value === undefined \|\| String\(value\)\.trim\(\) === ''/,
    'Compact Scanner modes must omit unavailable fields instead of rendering placeholders');
  assert.match(functionBinding(appSource, 'placePlaybackBar'),
    /details:not\(\.playback-control-menu\)[\s\S]*panel\.open = false/,
    'Scanner must keep secondary shared playback panels closed in the compact console');
  assert.doesNotMatch(functionBinding(appSource, 'initializePlaybackHeader'),
    /scanner-expanded/,
    'Responsive Scanner playback popovers must coordinate and dismiss like the shared header controls');
  assert.match(functionBinding(appSource, 'applyUserPreferenceSnapshot'),
    /setScannerDetailMode\(pendingScannerDetailMode \|\| preferences\.scanner\.detail_mode\)/,
    'Preference rollbacks must restore the visible Scanner detail mode');
  assert.match(functionBinding(appSource, 'setScannerDetailMode'),
    /scannerDetailModeRenderer\(normalized\)/,
    'Scanner detail mode changes must synchronize the rendered mode controls and content');
  assert.doesNotMatch(scannerRenderer, /scanButtons\.replaceChildren\(/,
    'Scanner state refreshes must not replace focused scan-list controls');
  assert.match(scannerRenderer, /scanner-scan-button\[data-scan-list-id\]/);
  assert.match(scannerRenderer, /scanButtons\.insertBefore\(button, current \|\| null\)/,
    'Scanner scan-list controls must reconcile stable keyed buttons in place');
  assert.match(scannerRenderer,
    /const mutation = \+\+scannerDetailModeMutation[\s\S]*mutation !== scannerDetailModeMutation/,
    'Queued Scanner mode saves must not repaint over a newer local choice');
  assert.match(scannerRenderer,
    /settleUserPreferenceMutation\(\(preferences\) => \{\s*preferences\.scanner\.detail_mode = selectedMode/,
    'Scanner detail modes must use the shared signed-in or anonymous preference mutation path');

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
  const radioSystemAssignmentLabel = vm.runInNewContext(
    `(function(row) ${functionBinding(appSource, 'radioSystemAssignmentLabel')})`);
  const radioSystemsDirectoryDetails = vm.runInNewContext(
    `(function(row) ${functionBinding(appSource, 'radioSystemsDirectoryDetails')})`, {
      channelDirectoryDetails, isP25: (row) => row.protocol === 'P25', hex,
      trunkedVariant, identityDomainLabel, identifierNumber, semanticLabel,
      isSavedChannelRadioSystem, protocolFamily: (row) => row.protocol, radioSystemAssignmentLabel
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
  assert.equal(radioSystemsDirectoryDetails({ protocol: 'DMR', assignment_state: 'CURRENT',
    variant: 'TIER_III', model: 'small', network_id: 42, radio_system_key: 'dmr:tier3:small:42' }),
  'Current receiver assignment · Tier III · Small · Network 42');
  assert.equal(radioSystemsDirectoryDetails({ protocol: 'NXDN', assignment_state: 'HISTORICAL',
    variant: 'TYPE_C', address_domain: 'nxdn_type_c',
    radio_system_key: 'nxdn-c:channel:728d2d66-de4e-476b-a696-919f32dd4d12' }),
  'Historical activity · Scoped to this saved channel · Type-C');

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
  const receiverHealthText = vm.runInNewContext(
    `(function(value, fallback = '—') ${functionBinding(appSource, 'receiverHealthText')})`);
  const receiverHealthResourceScale = vm.runInNewContext(
    `(function(row) ${functionBinding(appSource, 'receiverHealthResourceScale')})`, {
      receiverHealthText, RECEIVER_HEALTH_GC_BAR_MAXIMUM_MILLISECONDS: 1_000
    });
  assert.deepEqual(JSON.parse(JSON.stringify(receiverHealthResourceScale({
    label: 'VCE processor use', value: 42.5, unit: '%'
  }))), { available: true, maximum: 100, value: 42.5 });
  assert.deepEqual(JSON.parse(JSON.stringify(receiverHealthResourceScale({
    label: 'Time spent freeing memory', value: 250, unit: 'ms in last sample'
  }))), { available: true, maximum: 1000, value: 250 });
  assert.deepEqual(JSON.parse(JSON.stringify(receiverHealthResourceScale({
    label: 'Time spent freeing memory', value: 1400, unit: 'ms in last sample'
  }))), { available: true, maximum: 1000, value: 1000 });
  assert.deepEqual(JSON.parse(JSON.stringify(receiverHealthResourceScale({
    label: 'VCE processor use', value: 'n/a', unit: '%'
  }))), { available: false, maximum: 100, value: 0 });

  const radioTableType = vm.runInNewContext(
    `(function(baseType, columns) ${functionBinding(appSource, 'radioTableType')})`, { tableLayouts });
  assert.equal(radioTableType('radios', [{ id: 'radio' }]), 'radios.base');
  assert.equal(radioTableType('radios', [{ id: 'radio' }, { id: 'talker-alias' }]),
    'radios.talker-alias');
  assert.equal(radioTableType('radios', [
    { id: 'radio' }, { id: 'talker-alias' }, { id: 'affiliation' }, { id: 'confirmed-channel' }
  ]), 'radios.talker-alias-affiliation-channel');
  assert.match(appSource, /radioTableType\('group-identity-radios', columns\)/);
  assert.match(appSource, /signalingMetrics\(signalingActionRows\(response\.action_counts\)/);
  assert.match(functionBinding(appSource, 'signalingMetrics'), /values\.filter\(\(\[, count\]\) => Number\(count\) > 0\)/);
  assert.equal(tableDefaults.width('channel-frequencies-p25', { id: 'tags' }), 130);
  assert.equal(tableDefaults.width('channel-frequency-bands', { id: 'last-seen' }), 116);

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
  assert.equal(registry['radio-system'].parent, 'dashboard');
  assert.equal(registry.channel.parent, 'dashboard');
  assert.equal(registry['channel-setup'].allowed(), true);
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
    version: 7,
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
  const upgradedAnonymousTables = vm.runInNewContext(
    `((value) => ${functionBinding(appSource, 'upgradeAnonymousTableLayouts')})({ sample: {
      schema: ['name'], column_order: ['name'], column_widths: {}, hidden_columns: []
    } })`);
  assert.deepEqual(JSON.parse(JSON.stringify(upgradedAnonymousTables.sample.collapsed_groups)), []);
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
      schema: ['name'], column_order: ['name'], column_widths: {}, hidden_columns: ['name'],
      collapsed_groups: []
    }
  } }), /at least one visible column/);
  assert.throws(() => preferenceSchema.validate({ ...decodedDefaults, tables: {
    sample: {
      schema: [], column_order: [], column_widths: {}, hidden_columns: [], collapsed_groups: []
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
  const collapsedLayout = tableLayouts.setGroupCollapsed(changedLayout, 'p25-phase1', true);
  assert.deepEqual(collapsedLayout.collapsed_groups, ['p25-phase1']);
  assert.deepEqual(tableLayouts.setGroupCollapsed(collapsedLayout, 'p25-phase1', false).collapsed_groups, []);
  assert.throws(() => tableLayouts.setGroupCollapsed(changedLayout, 'P25 Phase 1', true), /valid stable ID/);
  const restored = tableLayouts.normalize(columns, tableLayouts.persisted(changedLayout));
  assert.deepEqual(restored.columns.map(({ id }) => id), ['name', 'status']);
  const reset = tableLayouts.normalize([...columns, { id: 'new-column' }], tableLayouts.persisted(changedLayout));
  assert.deepEqual(reset.column_order, ['name', 'frequency', 'status', 'new-column']);
  assert.equal(reset.reset, true);
  assert.equal(reset.reset_reason, 'schema-changed');
  const invalidWidth = tableLayouts.normalize(columns, {
    schema: ['name', 'frequency', 'status'], column_order: ['name', 'frequency', 'status'],
    column_widths: { name: 12 }, hidden_columns: [], collapsed_groups: []
  });
  assert.equal(invalidWidth.reset_reason, 'invalid-widths');
  assert.equal(tableLayouts.normalize(columns, {
    schema: ['name', 'frequency', 'status'], column_order: ['name', 'frequency', 'status'],
    column_widths: {}, hidden_columns: ['name', 'frequency', 'status'], collapsed_groups: []
  }).reset_reason, 'all-columns-hidden');
  assert.equal(tableLayouts.tableId('live.channels'), 'live.channels');
  assert.equal(tableDefaults.width('live-channels', { id: 'decode-health' }, 'detailed'), 260);
  assert.equal(tableDefaults.width('live-channels', { id: 'decode-health' }), 105);
  assert.equal(tableDefaults.width('radioreference-sites', { id: 'site' }), 340);
  assert.deepEqual(tableDefaults.fittedWidths('radioreference-sites', [
    { id: 'site' }, { id: 'system' }, { id: 'frequencies' }
  ], [340, 200, 150], {}, 1700), [1350, 200, 150]);
  assert.deepEqual(tableDefaults.fittedWidths('radioreference-sites', [
    { id: 'site' }, { id: 'system' }, { id: 'frequencies' }
  ], [420, 200, 150], { site: 420 }, 1700), [1350, 200, 150]);
  assert.equal(tableDefaults.width('example', { id: 'calls' }), 66);
  const compactColumns = [{ id: 'descriptor' }, { id: 'downlink' }, { id: 'state' }];
  assert.deepEqual(tableDefaults.layout('channel-frequencies-p25', [
    ...compactColumns, { id: 'callsign' }, { id: 'tdma' }, { id: 'data-observations' }
  ]).hidden_columns, ['callsign', 'tdma', 'data-observations']);
  assert.deepEqual(tableDefaults.fittedWidths('channel-frequencies-p25', compactColumns,
    [104, 94, 86], {}, 400), [220, 94, 86]);
  assert.deepEqual(tableDefaults.fittedWidths('channel-frequencies-p25', compactColumns,
    [130, 94, 86], { descriptor: 130 }, 400), [220, 94, 86]);
  assert.deepEqual(tableDefaults.fittedWidths('channel-frequency-bands',
    [{ id: 'band' }, { id: 'base' }], [48, 94], {}, 400), [48, 352]);
  assert.deepEqual(tableDefaults.fittedWidths('channel-frequency-bands',
    [{ id: 'band' }, { id: 'base' }], [48, 94], { base: 94 }, 400), [48, 352]);
  assert.deepEqual(tableDefaults.fittedWidths('channel-frequency-bands',
    [{ id: 'band' }, { id: 'base' }, { id: 'last-seen' }], [48, 94, 116], { base: 94 }, 400),
    [48, 94, 258]);
  assert.deepEqual(tableDefaults.fittedWidths('channel-frequency-bands',
    [{ id: 'band' }, { id: 'base' }, { id: 'last-seen' }], [48, 94, 116],
    { base: 94, 'last-seen': 116 }, 400), [48, 158, 194]);
  assert.deepEqual(tableDefaults.fittedWidths('dashboard-call-sources', [
    { id: 'receiver' }, { id: 'mode' }, { id: 'logical-calls' },
    { id: 'recorded-logical-calls' }, { id: 'stream-submitted-logical-calls' }
  ], [900, 80, 85, 70, 90], {
    receiver: 900, mode: 80, 'logical-calls': 85,
    'recorded-logical-calls': 70, 'stream-submitted-logical-calls': 90
  }, 1900), [1575, 80, 85, 70, 90]);
  assert.deepEqual(tableDefaults.fittedWidths('channel-frequency-bands',
    [{ id: 'band' }, { id: 'state' }], [64, 82], {}, 400), [175, 225]);
  assert.deepEqual(tableDefaults.fittedWidths('channel-frequency-bands-override',
    [{ id: 'band' }, { id: 'base' }, { id: 'offset' }], [48, 94, 92], { offset: 92 }, 400),
    [48, 260, 92]);
  assert.equal(tableDefaults.width('channel-frequency-bands', { id: 'band' }), 64);
  assert.deepEqual(tableDefaults.layout('example', [{ id: 'calls' }, { id: 'name' }]), {
    schema: ['calls', 'name'], column_order: ['calls', 'name'],
    column_widths: {}, hidden_columns: [], collapsed_groups: []
  });
  const customAliasColumns = [
    { id: 'select' }, { id: 'alias' }, { id: 'description' }, { id: 'record' }
  ];
  assert.deepEqual(tableDefaults.layout('alias-editor-custom', customAliasColumns).hidden_columns, ['record']);
  assert.deepEqual(tableDefaults.layout('alias-editor-custom', [
    ...customAliasColumns, { id: 'required', essential: true }
  ]).hidden_columns, ['record']);
  const storedAnonymousLayouts = new Map();
  let anonymousStorageFails = false;
  let anonymousTableErrors = 0;
  const anonymousTables = vm.runInNewContext(`(() => {
    let anonymousUserPreferences = JSON.parse(JSON.stringify(preferenceSchema.defaults));
    function saveAnonymousTableLayout(tableType, layout = null)
      ${functionBinding(appSource, 'saveAnonymousTableLayout')}
    return {
      save: saveAnonymousTableLayout,
      current: () => anonymousUserPreferences
    };
  })()`, {
    preferenceSchema, tableLayouts,
    localStorage: { setItem: (key, value) => {
      if (anonymousStorageFails) throw new Error('Storage is unavailable');
      storedAnonymousLayouts.set(key, value);
    } },
    ANONYMOUS_TABLE_LAYOUTS_STORAGE_KEY: 'table-test',
    showUserPreferenceError: () => { anonymousTableErrors += 1; }
  });
  const personalized = tableLayouts.setHidden(tableLayouts.resize(
    tableLayouts.move(initialLayout, 'status', 'frequency'), 'name', 144), 'frequency', true);
  assert.ok(anonymousTables.save('sample', personalized));
  assert.equal(anonymousTables.current().tables.sample.column_widths.name, 144);
  assert.deepEqual(JSON.parse(storedAnonymousLayouts.get('table-test')).sample.hidden_columns, ['frequency']);
  assert.deepEqual(JSON.parse(storedAnonymousLayouts.get('table-test')).sample.collapsed_groups, []);
  anonymousStorageFails = true;
  const sessionOnlyLayout = tableLayouts.resize(personalized, 'name', 188);
  assert.ok(anonymousTables.save('sample', sessionOnlyLayout));
  assert.equal(anonymousTables.current().tables.sample.column_widths.name, 188,
    'Anonymous table changes must remain active when browser storage is unavailable');
  assert.equal(anonymousTableErrors, 0,
    'Browser storage failures must not be reported as a My Settings account failure');
  anonymousStorageFails = false;
  assert.ok(anonymousTables.save('sample'));
  assert.equal(anonymousTables.current().tables.sample, undefined);
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
  const constrainedColumns = [
    { id: 'select', essential: true, fixed: true }, { id: 'name' }, { id: 'status' }
  ];
  const constrained = tableLayouts.normalize(constrainedColumns, null);
  assert.deepEqual(constrained.essential_columns, ['select']);
  assert.deepEqual(constrained.fixed_columns, ['select']);
  assert.throws(() => tableLayouts.setHidden(constrained, 'select', true), /Essential table columns/);
  assert.throws(() => tableLayouts.move(constrained, 'select', 'name'), /Fixed table columns/);
  assert.throws(() => tableLayouts.move(constrained, 'name', 'select'), /across a fixed column/);
  assert.equal(tableLayouts.canMove(constrained, 'name', 'select'), false);
  assert.equal(tableLayouts.canMove(constrained, 'status', 'name'), true);
  assert.deepEqual(tableLayouts.move(constrained, 'status', 'name').column_order,
    ['select', 'status', 'name']);
  assert.equal(tableLayouts.normalize(constrainedColumns, {
    schema: ['select', 'name', 'status'], column_order: ['name', 'select', 'status'],
    column_widths: {}, hidden_columns: [], collapsed_groups: []
  }).reset_reason, 'fixed-column-moved');
  assert.equal(tableLayouts.normalize(constrainedColumns, {
    schema: ['select', 'name', 'status'], column_order: ['select', 'name', 'status'],
    column_widths: {}, hidden_columns: ['select'], collapsed_groups: []
  }).reset_reason, 'essential-column-hidden');

  assert.equal(pageTitles.derive({ routeId: 'scanner', pageTitle: 'Scanner',
    playerState: { playing: true, targetLabel: 'WEST', queuedCount: 2 } }), 'WEST (2)');
  assert.equal(pageTitles.derive({ routeId: 'scanner', pageTitle: 'Scanner',
    playerState: { playing: true, targetLabel: 'WEST', queuedCount: 0 } }), 'WEST');
  assert.equal(pageTitles.derive({ routeId: 'channel', pageTitle: 'Channel BEE00:941 01-01 (Control)',
    prependPlaying: true, playerState: { playing: true, targetLabel: 'WEST', queuedCount: 2 } }),
  'WEST (2) - VCE - Channel BEE00:941 01-01 (Control)');
  assert.equal(pageTitles.derive({ routeId: 'channel', pageTitle: 'Channel', prependPlaying: false,
    playerState: { playing: true, targetLabel: 'WEST', queuedCount: 2 } }), 'VCE - Channel');
  assert.equal(pageTitles.PRODUCT, 'VCE');
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
      schema: ['name'], column_order: ['name'], column_widths: {}, hidden_columns: [], collapsed_groups: []
    };
  });
  const settingsSave = controller.update((profile) => {
    profile.presentation.show_voice_decode_quality = false;
  });
  slowLayoutSave.resolve(response(200, { revision: 7, preferences: {
    ...controller.snapshot().preferences,
    tables: { sample: {
      schema: ['name'], column_order: ['name'], column_widths: {}, hidden_columns: [], collapsed_groups: []
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
