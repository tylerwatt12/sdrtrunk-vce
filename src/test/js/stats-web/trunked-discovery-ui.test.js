'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function main() {
  const assets = path.resolve(process.argv[2] || 'stats-web/assets');
  const { spectrumSearchGroupKey, spectrumSearchIdentityFacts, spectrumSearchSystemName } =
    await import(pathToFileURL(path.join(assets, 'features/spectrum-search.js')).href);
  const { discoveryRadioReferenceResult } =
    await import(pathToFileURL(path.join(assets, 'features/discovery-radioreference.js')).href);
  const app = fs.readFileSync(path.join(assets, 'app.js'), 'utf8');
  const spectrum = app.slice(app.indexOf('async function renderTunerSpectrum()'), app.indexOf('function radioDirectory',
    app.indexOf('async function renderTunerSpectrum()')));
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
    ['WACN', 'BEE00'], ['System ID', '012'], ['RFSS', 1], ['Site ID', 3] ]);
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
  console.log('Trunked discovery UI contracts passed.');
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
