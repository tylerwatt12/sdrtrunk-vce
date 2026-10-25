'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function main() {
  const assets = path.resolve(process.argv[2] || 'stats-web/assets');
  const { spectrumSearchGroupKey, spectrumSearchIdentityFacts, spectrumSearchSystemName } =
    await import(pathToFileURL(path.join(assets, 'features/spectrum-search-data.js')).href);
  const { discoveryRadioReferenceResult, discoveryRadioReferenceSelection, discoverySnapshotIsCurrent, discoveryCleanupAlreadyClosed,
    createDiscoveryRadioReferenceWait, createDiscoveryCompletion, discoveryAliasImportMatch, discoveryAliasImportTargets, importDiscoveryAliases } =
    await import(pathToFileURL(path.join(assets, 'features/discovery-radioreference.js')).href);
  const { requestChannelAction, requestChannelAdminMutation } =
    await import(pathToFileURL(path.join(assets, 'core/channel-actions.js')).href);
  const app = fs.readFileSync(path.join(assets, 'app.js'), 'utf8');
  const spectrum = app.slice(app.indexOf('async function renderTunerSpectrum(workspaceOptions = {})'), app.indexOf('function radioDirectory',
    app.indexOf('async function renderTunerSpectrum(workspaceOptions = {})')));
  assert.doesNotMatch(spectrum, /openSpectrumSearchWizard|Find Trunked Systems|searchOwner|searchActive/);
  assert.match(app.slice(app.indexOf('async function renderModernChannelCatalog')), /Find Trunked Systems/);

  const p25 = { candidate_id: 'p25', identity: { wacn: 0xbee00, system: 0x12, rfss: 1, site: 3 } };
  const dmr = { candidate_id: 'dmr', protocol_id: 'dmr', variant: 'Tier III', trunked_evidence: {
    identity: { radio_system_key: 'dmr:tier3:small:12', network: 12, site: 3, color_code: 4 } } };
  const nxdn = { candidate_id: 'nxdn', protocol_id: 'nxdn', variant: 'Type-C', trunked_evidence: {
    identity: { radio_system_key: 'nxdn-c:local:12', system: 12, site: 3, ran: 9 } } };
  assert.equal(new Set([p25, dmr, nxdn].map(spectrumSearchGroupKey)).size, 3,
    'Repeated numeric system/site identities from different protocols must remain separate.');
  assert.notEqual(spectrumSearchGroupKey({ candidate_id: 'unknown-a' }),
    spectrumSearchGroupKey({ candidate_id: 'unknown-b' }), 'Unknown signals must not form a false common system.');
  assert.equal(spectrumSearchGroupKey({ ...dmr, alias_group_id: 'matched-dmr' }), 'matched-dmr');
  const hex = (value, width) => Number(value).toString(16).toUpperCase().padStart(width, '0');
  assert.deepEqual(spectrumSearchIdentityFacts(p25, hex), [
    ['WACN', '0xBEE00 (781824)'], ['System ID', '0x012 (18)'],
    ['RFSS', '0x01 (1)'], ['Site ID', '0x03 (3)'] ]);
  assert.ok(spectrumSearchIdentityFacts(dmr, hex).some(([name, value]) => name === 'Color code' && value === 4));
  assert.ok(spectrumSearchIdentityFacts(nxdn, hex).some(([name, value]) => name === 'RAN' && value === 9));
  assert.equal(spectrumSearchSystemName({ ...dmr, system_name: 'Transit' }), 'Transit');

  const node = (tag, className, text = '') => ({ tag, className, children: [text], attributes: {},
    classList: { add() {}, toggle() {} },
    setAttribute(name, value) { this.attributes[name] = value; },
    get firstElementChild() { return this.children.find((child) => child?.tag); },
    append(...children) { this.children.push(...children); },
    replaceChildren(...children) { this.children = children; } });
  const ui = { node, anchor: (label, url) => ({ label, url }), channelMHz: (value) => String(value / 1e6) };
  const directoryCandidates = [
    { candidate_id: 'north', group: 'county', radio_reference: { state: 'matched' } },
    { candidate_id: 'south', group: 'county', radio_reference: { state: 'pending' } },
    { candidate_id: 'transit', group: 'transit', radio_reference: { state: 'pending' } }
  ];
  const selectedDirectory = discoveryRadioReferenceSelection(directoryCandidates, ['north'], (value) => value.group);
  assert.deepEqual(selectedDirectory.map((value) => value.candidate_id), ['north', 'south'],
    'Readiness includes unselected siblings in the selected system, without blocking on unrelated systems.');
  const wait = createDiscoveryRadioReferenceWait(ui);
  const reviewAction = { disabled: false };
  assert.equal(wait.update(selectedDirectory, reviewAction), true);
  assert.equal(wait.element.hidden, false);
  assert.equal(reviewAction.disabled, true, 'Review and Add share the pending-name gate.');
  selectedDirectory[1].radio_reference.state = 'no_match';
  assert.equal(wait.update(selectedDirectory, reviewAction), false);
  assert.equal(reviewAction.disabled, false);
  assert.equal(wait.element.hidden, false);
  assert.match(JSON.stringify(wait.element), /No matching RadioReference record was found/);
  assert.match(JSON.stringify(wait.element), /Detected names will be used for the unmatched channels/);
  for (const [state, copy] of [['no_match', 'No matching RadioReference record was found'],
    ['unavailable', 'RadioReference names could not be loaded'], ['ambiguous', 'No unique RadioReference match was found']]) {
    wait.update([{ radio_reference: { state } }]);
    assert.match(wait.element.children[1].textContent, new RegExp(copy));
    assert.match(wait.element.children[1].textContent, /The detected names will be used/);
    if (state === 'no_match') assert.doesNotMatch(wait.element.children[1].textContent, /could not be loaded|unavailable/);
  }
  selectedDirectory[1].radio_reference.state = 'matched';
  wait.update(selectedDirectory, reviewAction);
  assert.equal(wait.element.hidden, true);
  const snapshot = { state: 'ready' };
  assert.equal(discoverySnapshotIsCurrent(snapshot, snapshot), true);
  assert.equal(discoverySnapshotIsCurrent(snapshot, { ...snapshot }), false,
    'An older status response must not overwrite a newer status or saved result.');
  for (const state of [{ active: false }, { busy: true }, { finished: true }])
    assert.equal(discoverySnapshotIsCurrent(snapshot, snapshot, state), false,
      'Both successful and failed setup responses are ignored after save or while a mutation is running.');
  const completion = createDiscoveryCompletion(ui, { channels: [{ name: 'North control', system: 'County',
    frequency_hz: 851012500, protocol: 'P25', status: 'running', alias_list_id: 21,
    alias_list_name: 'County dispatch aliases', alias_list_existing: true }], receiver: 'Test receiver', imports: {
    targets: [{ alias_list_id: 21, alias_list_name: 'County dispatch aliases', state: 'imported', added: 6650,
      updated: 0, unchanged: 0 }, { alias_list_id: 21, alias_list_name: 'County dispatch aliases',
      state: 'imported', added: 6650, updated: 0, unchanged: 0 }] } });
  const completionText = JSON.stringify(completion);
  assert.match(completionText, /6,650 talkgroups added/);
  assert.equal(completion.children.find((child) => child?.className === 'discovery-completion-imports')
    .children.filter((child) => child?.tag).length, 1,
    'Sites sharing one actual Alias List show one import result.');
  assert.match(completionText, /Existing Alias List/);
  assert.match(JSON.stringify(completion.children.find((child) => child?.className === 'discovery-completion-imports')),
    /County dispatch aliases/, 'A destination different from the channel and system remains visible beside its import result.');
  const completionDetails = completion.children.find((child) => child?.tag === 'details');
  assert.equal(Boolean(completionDetails.open), false);
  assert.match(JSON.stringify(completionDetails), /County dispatch aliases|Test receiver/);
  assert.match(JSON.stringify(completionDetails), /6650 added, 0 updated, 0 unchanged\./);
  const partial = createDiscoveryCompletion(ui, { channels: [{ name: 'Failed site', status: 'failed',
    error: 'Receiver changes could not load.' }], imports: { targets: [{ alias_list_id: 22, state: 'failed',
      message: 'Talkgroups could not be imported. Try again.' }] } });
  assert.match(JSON.stringify(partial), /Could not add|Receiver changes could not load/);
  assert.match(JSON.stringify(partial), /Talkgroups could not be imported/);
  const unchanged = createDiscoveryCompletion(ui, { imports: { targets: [{ state: 'imported',
    added: 0, updated: 0, unchanged: 20 }] } });
  assert.match(JSON.stringify(unchanged), /Talkgroups already up to date/);
  const empty = createDiscoveryCompletion(ui, { imports: { targets: [{ state: 'imported',
    added: 0, updated: 0, unchanged: 0 }] } });
  assert.match(JSON.stringify(empty), /No talkgroups available/);
  const lifecycleCalls = [];
  const savedStart = await requestChannelAction(async (url, options) => {
    lifecycleCalls.push([url, options]);
    return { results: [{ configuration_id: 'saved-channel', success: true, state: 'RUNNING' }] };
  }, { action: 'START', configurationIds: ['saved-channel'] });
  assert.equal(lifecycleCalls[0][0], '/api/v1/admin/channels/actions');
  assert.deepEqual(lifecycleCalls[0][1].body, { action: 'START', configuration_ids: ['saved-channel'] });
  assert.equal(lifecycleCalls[0][1].timeoutMs, 30000);
  assert.equal(savedStart.results[0].state, 'RUNNING');
  assert.doesNotMatch(JSON.stringify(lifecycleCalls), /discovery|session|revision/,
    'Starting a saved channel does not depend on temporary discovery state or a stale catalog revision.');
  await assert.rejects(requestChannelAction(async () => ({ results: [{ configuration_id: 'saved-channel',
    success: false, state: 'STOPPED', message: 'No receiver is available.' }] }),
  { action: 'START', configurationIds: ['saved-channel'] }), /No receiver is available/);
  const receiver = { tuner_id: 'receiver-a', browse_lease_id: 'owned-lease' };
  await requestChannelAction(async (_url, options) => {
    assert.deepEqual(options.body.receiver, receiver);
    return { results: [{ configuration_id: 'saved-channel', success: true, state: 'RUNNING' }],
      receiver: { browse_lease_id: null, previous_channels_not_resumed: [] } };
  }, { action: 'START', configurationIds: ['saved-channel'], receiver });
  for (const browseLeaseId of ['owned-lease', null]) {
    await assert.rejects(requestChannelAction(async () => ({ results: [{ configuration_id: 'saved-channel',
      success: false, state: 'STOPPED', message: 'Listening could not start.' }],
      receiver: { browse_lease_id: browseLeaseId, previous_channels_not_resumed: [] } }),
    { action: 'START', configurationIds: ['saved-channel'], receiver }), (cause) => {
      assert.equal(cause.receiver.browse_lease_id, browseLeaseId,
        'A failed START retains the actual lease outcome for safe retry without a separate release.');
      return true;
    });
  }
  await assert.rejects(requestChannelAction(async () => ({ results: [] }),
    { action: 'START', configurationIds: ['saved-channel'] }), /could not be confirmed/);
  const pendingStart = await requestChannelAction(async () => ({ results: [{ configuration_id: 'saved-channel',
    success: true, state: 'STOPPED' }] }), { action: 'START', configurationIds: ['saved-channel'] });
  assert.equal(pendingStart.results[0].state, 'STOPPED', 'An accepted action must not be converted into Running.');
  await assert.rejects(requestChannelAdminMutation(async () => ({ results: [{ configuration_id: 'catalog-channel',
    success: false, message: 'Channel is unavailable.' }] }), '/api/v1/admin/channels/actions'), /Channel is unavailable/,
  'Catalog mutations use the same per-channel failure handling as editor and discovery actions.');
  const rendered = discoveryRadioReferenceResult(ui, { radio_reference: { state: 'ambiguous',
    match: { system_name: 'Incorrect borrowed name' } } });
  assert.doesNotMatch(JSON.stringify(rendered), /Incorrect borrowed name/);
  assert.match(JSON.stringify(rendered), /No directory names were applied/);
  const malicious = discoveryRadioReferenceResult(ui, { radio_reference: { state: 'matched', match: {
    system_name: 'Transit', url: 'https://example.invalid/redirect', site_name: 'North',
    channels: [{ logical_channel_number: 5, frequency_hz: 451000000, primary_control: true }] } } });
  assert.doesNotMatch(JSON.stringify(malicious), /example.invalid/);
  assert.match(JSON.stringify(malicious), /Transit/);
  assert.doesNotMatch(JSON.stringify(malicious), /North|Channel 5|451 MHz/);
  const pending = discoveryRadioReferenceResult(ui, { radio_reference: { state: 'pending',
    message: 'Checking RadioReference system and site identity' } });
  pending.open = true;
  const loadingCopy = pending.children;
  assert.equal(discoveryRadioReferenceResult(ui, { radio_reference: { state: 'pending',
    message: 'Another backend phase' } }, pending), pending);
  assert.equal(pending.children, loadingCopy, 'Repeated pending polls must leave the visible content alone.');
  assert.match(JSON.stringify(pending), /Looking up the system name/);
  assert.doesNotMatch(JSON.stringify(pending), /Checking RadioReference|Another backend/);
  const summary = pending.firstElementChild;
  const matched = discoveryRadioReferenceResult(ui, { radio_reference: { state: 'matched',
    provenance: 'radioreference-exact-frequency-and-on-air-identity', match: {
      system_name: 'Transit', url: 'https://www.radioreference.com/db/sid/1', site_name: 'North',
      channels: [{ frequency_hz: 451000000 }] } } }, pending);
  assert.equal(matched, pending);
  assert.equal(matched.open, true, 'A completed match must preserve the expanded section.');
  assert.equal(matched.firstElementChild, summary);
  assert.match(JSON.stringify(matched), /Transit|radioreference.com/);
  assert.doesNotMatch(JSON.stringify(matched), /North|Channel|451|exact-frequency/);
  for (const state of ['manual', 'not_started', 'login_required', 'premium_required']) {
    assert.equal(discoveryRadioReferenceResult(ui, { radio_reference: { state } }), null,
      'An unstarted lookup must remain quiet; results must not offer a late account connection prompt.');
  }
  assert.equal(discoveryCleanupAlreadyClosed({ status: 409, code: 'discovery_conflict',
    message: 'Channel discovery expired; begin again' }), true);
  assert.equal(discoveryCleanupAlreadyClosed({ status: 409, code: 'discovery_conflict',
    message: 'The selected tuner changed' }), false, 'Cleanup must not swallow real ownership conflicts.');
  const matchedSystem = (id = 7) => ({ radio_reference: { state: 'matched', match: { rr_system_id: id } } });
  const settledMatch = [matchedSystem(), { radio_reference: { state: 'no_match' } }];
  assert.equal(discoveryAliasImportMatch(settledMatch), 7,
    'A confirmed match may cover a sibling with no individual directory record.');
  assert.equal(discoveryAliasImportMatch([matchedSystem(), { radio_reference: { state: 'pending' } }]), null,
    'A selected system must finish naming before its import can be offered.');
  assert.equal(discoveryAliasImportMatch([matchedSystem(7), matchedSystem(8)]), null);
  for (const id of [0, -1, 1.5, 'invalid', null]) assert.equal(discoveryAliasImportMatch([matchedSystem(id)]), null);
  for (const state of ['unavailable', 'no_match', 'ambiguous', 'manual', 'login_required', 'premium_required'])
    assert.equal(discoveryAliasImportMatch([{ radio_reference: { state } }]), null);
  const targetGroup = { groupId: 'north', values: settledMatch, aliasListId: 21,
    name: 'County aliases', systemName: 'County' };
  assert.deepEqual(discoveryAliasImportTargets([targetGroup]), [], 'A Premium directory connection is required.');
  const targets = discoveryAliasImportTargets([targetGroup, { ...targetGroup, groupId: 'south' },
    { ...targetGroup, groupId: 'new-north', aliasListId: 0, name: 'North aliases' },
    { ...targetGroup, groupId: 'new-south', aliasListId: 0, name: 'South aliases' }], { available: true });
  assert.equal(targets.length, 3, 'Only the same matched system and actual existing destination are coalesced.');
  assert.deepEqual(targets[0], { name: 'County aliases', systemName: 'County', rrSystemId: 7,
    matched: true, aliasGroupIds: ['north', 'south'] });
  assert.deepEqual(targets.slice(1).map((target) => target.aliasGroupIds), [['new-north'], ['new-south']],
    'Explicit new lists keep their own group destinations until Save returns actual IDs.');
  for (const aliasListId of [null, -1, 1.5, 'invalid'])
    assert.deepEqual(discoveryAliasImportTargets([{ ...targetGroup, aliasListId }], { available: true }), []);
  assert.deepEqual(discoveryAliasImportTargets([{ ...targetGroup, name: ' ' }], { available: true }), []);
  const finder = fs.readFileSync(path.join(assets, 'features/spectrum-search.js'), 'utf8');
  assert.doesNotMatch(app + finder, /createDiscoveryTalkgroupAvailability|talkgroups\/availability/,
    'Discovery must not download or check talkgroups before the channel is saved.');
  const discovery = app.slice(app.indexOf('function openSpectrumDiscoveryWizard(selection)'),
    app.indexOf('function tunerFrequencyAction(', app.indexOf('function openSpectrumDiscoveryWizard(selection)')));
  assert.match(discovery, /showStep\(2, 'Review channel'\)/);
  assert.match(discovery, /Import talkgroup names/);
  assert.doesNotMatch(discovery, /Name your channel|Give it a name you’ll recognize/);
  assert.doesNotMatch(discovery, /prepareSavedStart|Do you want to import talkgroups|Checking available talkgroups/);
  assert.match(discovery, /\.\.\.error\.receiver, running: false/,
    'Failed saved-channel starts must apply the atomic receiver outcome before onSaved updates lease ownership.');
  assert.doesNotMatch(discovery, /\/start[`'"]/,
    'A saved-channel retry uses the normal channel action endpoint rather than temporary RF discovery.');
  assert.match(discovery, /restart_required: true, start_error: error\.message,\s+browse_lease_id: selection\.browseLeaseId \|\| null/,
    'Publication failure before handoff preserves the captured Spectrum lease for its original owner.');
  assert.match(discovery, /!previous\?\.session_id \|\| previous.saved/,
    'Closing a completed Spectrum result must not delete an RF session.');
  const progress = [];
  const imports = [];
  const imported = await importDiscoveryAliases(async (url, options) => {
    imports.push(options.body.alias_list_id);
    return { complete: false, targets: [{ alias_list_id: 21, state: 'imported' },
      { alias_list_id: 22, state: 'pending' }] };
  }, '/import', { targets: [{ alias_list_id: 21, state: 'pending' }] }, (value) => progress.push(value));
  assert.deepEqual(imports, [21]);
  assert.deepEqual(imported.targets.map((target) => target.alias_list_id), [21],
    'A mixed system import keeps only the systems the user was offered.');
  console.log('Trunked discovery UI contracts passed.');
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
