'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function main() {
  const assets = path.resolve(process.argv[2] || 'stats-web/assets');
  const { spectrumSearchGroupKey, spectrumSearchIdentityFacts, spectrumSearchSystemName } =
    await import(pathToFileURL(path.join(assets, 'features/spectrum-search-data.js')).href);
  const { discoveryRadioReferenceResult, createDiscoveryTalkgroupAvailability, importDiscoveryAliases } =
    await import(pathToFileURL(path.join(assets, 'features/discovery-radioreference.js')).href);
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

  const node = (tag, className, text = '') => ({ tag, className, children: [text],
    get firstElementChild() { return this.children.find((child) => child?.tag); },
    append(...children) { this.children.push(...children); },
    replaceChildren(...children) { this.children = children; } });
  const ui = { node, anchor: (label, url) => ({ label, url }), channelMHz: (value) => String(value / 1e6) };
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
  for (const state of ['login_required', 'premium_required']) {
    assert.equal(discoveryRadioReferenceResult(ui, { radio_reference: { state } }), null,
      'Results must never offer a late account connection prompt.');
  }
  const availabilityCalls = [];
  let availabilityRecovered = false;
  const availableTalkgroups = createDiscoveryTalkgroupAvailability(async (url, options) => {
    availabilityCalls.push([url, options.timeoutMs, options.csrf]);
    const requestUrl = new URL(url, 'http://fixture.invalid');
    assert.equal(requestUrl.pathname, '/api/v1/admin/radioreference/systems/talkgroups/availability');
    assert.deepEqual([...requestUrl.searchParams.keys()], ['system_id']);
    const systemId = Number(requestUrl.searchParams.get('system_id'));
    if (systemId === 3 && !availabilityRecovered) throw new Error('Temporary directory failure');
    return { has_talkgroups: systemId !== 2 };
  });
  const targets = [1, 2, 3].map((rrSystemId) => ({ rrSystemId, matched: true }));
  await assert.rejects(availableTalkgroups([...targets, { rrSystemId: 4, matched: false },
    { rrSystemId: 0, matched: true }, { rrSystemId: 1.5, matched: true }]), /could not be checked/);
  assert.equal(availabilityCalls.length, 3);
  assert.ok(availabilityCalls.every(([, timeout, csrf]) => timeout === 65000 && csrf === false));
  await assert.rejects(availableTalkgroups(targets), /could not be checked/);
  assert.deepEqual(await availableTalkgroups(targets.slice(0, 2)), [targets[0]]);
  assert.equal(availabilityCalls.length, 4, 'Successful and empty availability checks are cached; failures can be retried.');
  availabilityRecovered = true;
  assert.deepEqual(await availableTalkgroups(targets), [targets[0], targets[2]]);
  assert.equal(availabilityCalls.length, 5, 'Retrying a failed system must keep the other systems cached.');

  let releaseAvailability;
  let pendingChecks = 0;
  const sharedAvailability = createDiscoveryTalkgroupAvailability(() => {
    pendingChecks += 1;
    return new Promise((resolve) => { releaseAvailability = resolve; });
  });
  const sharedTarget = { rrSystemId: 7, matched: true };
  const firstCheck = sharedAvailability([sharedTarget]);
  const duplicateCheck = sharedAvailability([sharedTarget, { ...sharedTarget, name: 'Another site' }]);
  assert.equal(pendingChecks, 1, 'Concurrent sites from the same system share one pending availability request.');
  releaseAvailability({ has_talkgroups: false });
  assert.deepEqual(await firstCheck, []);
  assert.deepEqual(await duplicateCheck, []);
  assert.deepEqual(await sharedAvailability([sharedTarget]), []);
  assert.equal(pendingChecks, 1, 'A confirmed empty system must not be checked again after Back.');

  const availabilityReplies = [{}, { has_talkgroups: 'false' }, { has_talkgroups: true }];
  const validAvailability = createDiscoveryTalkgroupAvailability(async () => availabilityReplies.shift());
  await assert.rejects(validAvailability([sharedTarget]), /could not be checked/,
    'A missing availability field must not silently skip the import.');
  await assert.rejects(validAvailability([sharedTarget]), /could not be checked/,
    'A nonboolean availability field must not be cached as an empty system.');
  assert.deepEqual(await validAvailability([sharedTarget]), [sharedTarget],
    'A malformed availability response must allow a successful retry.');
  assert.equal(availabilityReplies.length, 0);
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
