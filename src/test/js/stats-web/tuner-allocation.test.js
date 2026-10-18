'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(process.argv[2] || 'stats-web/assets/app.js', 'utf8');
const start = source.indexOf('const TUNER_ALLOCATION_HISTORY_LIMIT = 100;');
const end = source.indexOf('async function renderAdminTunerAllocation(', start);
assert.ok(start >= 0 && end > start);
const context = vm.createContext({ number: String, frequency: (value) => (Number(value) / 1_000_000).toFixed(5) });
const protocolStart = source.indexOf('function channelProtocolGroup(');
const protocolEnd = source.indexOf('function channelAutoStartOrder(', protocolStart);
assert.ok(protocolStart >= 0 && protocolEnd > protocolStart);
vm.runInContext(source.slice(protocolStart, protocolEnd), context);
const frequencyStart = source.indexOf('function channelAdminFrequencyList(');
const frequencyEnd = source.indexOf('function uiActionButton(', frequencyStart);
assert.ok(frequencyStart >= 0 && frequencyEnd > frequencyStart);
vm.runInContext(source.slice(frequencyStart, frequencyEnd), context);
vm.runInContext(source.slice(start, end), context);
const snapshot = (decisions = []) => ({ session_id: 'session', generated_at_ms: 123,
  sites: [{ id: 'named-site', name: 'Albany County Simulcast', known_frequency_count: 11,
    covered_frequency_count: 9, frequencies: [{ frequency_hz: 851_737_500, covered: true }] }],
  tuners: [{ id: 'safe-1', name: 'RTL-SDR 1', frequency_hz: 855_362_500, dc_gap_hz: 10_000 }], decisions });
const row = (id, sequence = id) => ({ decision_id: id, sequence, repeat_count: 1 });
const plain = (value) => JSON.parse(JSON.stringify(value));
const result = plain(context.tunerAllocationSnapshot(snapshot([row(1), row(2, 8), row(3, 7), row(-1)])));
assert.deepEqual(result.decisions.map((item) => item.decisionId), [2, 3, 1]);
assert.equal(result.tuners[0].frequencyHz, 855_362_500);
assert.equal(result.sites[0].coveredFrequencyCount, 9);
assert.equal(result.sites[0].frequencies[0].frequencyHz, 851_737_500);
const sharedReceivers = snapshot();
sharedReceivers.tuners[0].receiving = [
  { frequency_hz: 856_212_500, role: 'call', site_id: 'named-site', channel_name: 'Albany County Simulcast' },
  { frequency_hz: 856_212_500, role: 'call', site_id: 'other-site', channel_name: 'Other site' },
  { frequency_hz: 855_187_500, role: 'conventional', site_id: 'weather', channel_name: 'Albany County Simulcast' },
  { frequency_hz: 855_062_500, role: 'call', site_id: null, channel_name: 'Albany County Simulcast' }
];
const receiving = plain(context.tunerAllocationSnapshot(sharedReceivers)).tuners[0].receiving;
assert.deepEqual(receiving.map((item) => item.siteId), ['named-site', 'other-site', 'weather', null],
  'Receiving channel identities remain separate across a shared frequency and a reused saved name');
assert.deepEqual(receiving.map((item) => item.role), ['call', 'call', 'conventional', 'call']);
assert.deepEqual(receiving.map((item) => context.tunerAllocationReceptionScope(item, 'named-site')),
  ['selected', 'other', 'other', 'unscoped'], 'Marker emphasis follows receiving channel identity');
assert.deepEqual(receiving.map((item) => context.tunerAllocationReceptionScope(item, 'other-site')),
  ['other', 'selected', 'other', 'unscoped'], 'Changing the selected site reclassifies shared tuner work');
assert.equal(context.tunerAllocationReceptionScope({ siteId: '', frequencyHz: 856_212_500,
  channelName: 'Albany County Simulcast' }, 'named-site'), 'unscoped',
  'A matching known frequency and saved name cannot supply a missing channel identity');
assert.equal(context.tunerAllocationSnapshot(snapshot(Array.from({ length: 150 }, (_, index) => row(index + 1)))).decisions.length, 100);
assert.throws(() => context.tunerAllocationSnapshot({}), /could not be loaded/);
const decisions = Array.from({ length: 45 }, (_, index) => row(index + 1));
assert.equal(context.tunerAllocationHistoryPage(decisions, -1).page, 0);
assert.equal(context.tunerAllocationHistoryPage(decisions, Infinity).page, 0);
assert.equal(context.tunerAllocationHistoryPage(decisions, 10).rows.length, 5);
assert.equal(context.tunerAllocationHistoryPage([], 2).pageCount, 1);
assert.equal(context.tunerAllocationWork([{ role: 'control' }, { role: 'call' }]), 'Control + call');
assert.equal(context.tunerAllocationWork([{ role: 'conventional' }]), 'Conventional channel');
assert.equal(context.tunerAllocationWork([{ role: 'control' }, { role: 'conventional' }]), 'Control + conventional');
assert.equal(context.tunerAllocationWork([{ role: 'control' }, { role: 'conventional' }, { role: 'call' }]), 'Control + conventional + call');
assert.equal(context.tunerAllocationWork([{ role: 'call' }]), '1 call');
assert.equal(context.tunerAllocationWork([{ role: 'call' }, { role: 'call' }]), '2 calls');
assert.equal(context.tunerAllocationWork([{ role: 'control' }, { role: 'call' }, { role: 'call' }]), 'Control + calls');
assert.equal(context.tunerAllocationWork([]), 'Unused');
assert.equal(context.tunerAllocationFrequency(null), 'Unavailable');
assert.equal(context.tunerAllocationFrequency(851_012_500), '851.01250 MHz');

const saved = (configuration_id, name, protocol_id, frequencies_hz, extra = {}) => ({
  configuration_id, name, protocol_id, protocol_label: protocol_id === 'am' ? 'AM' : 'P25',
  channel_kind: protocol_id === 'p25-phase1' ? 'TRUNKED' : 'CONVENTIONAL',
  system: 'Ohio MARCS-IP: Multi-Agency Radio Communications', site: 'Lorain',
  frequencies_hz, processing_state: 'STOPPED', remote_origin: null, ...extra
});
const catalog = { channels: [
  saved('atc-2', 'ATC', 'am', [125_975_000], { system: 'ATC', site: 'ATC' }),
  saved('grafton', 'MARCS-Grafton', 'p25-phase1', [773_081_250, 770_906_250]),
  saved('remote', 'Remote', 'p25-phase1', [773_500_000], { remote_origin: { remote: true } }),
  saved('unnamed', null, 'p25-conventional', [154_464_000], { system: 'Unknown', site: null }),
  saved('elyria', 'MARCS-Elyria', 'p25-phase1', [770_206_250, 770_606_250]),
  saved('atc-1', 'ATC', 'am', [125_850_000], { system: 'ATC', site: 'ATC' }),
  saved(null, 'No saved identity', 'am', [125_500_000])
] };
const catalogChannels = plain(context.tunerAllocationCatalogChannels(catalog));
assert.deepEqual(catalogChannels.map((item) => item.configuration_id),
  ['elyria', 'grafton', 'unnamed', 'atc-2', 'atc-1'],
  'Picker uses saved IDs and Channel manager protocol/name order while excluding remote reception');
assert.throws(() => context.tunerAllocationCatalogChannels({}), /could not be loaded/);
const grafton = catalogChannels.find((item) => item.configuration_id === 'grafton');
for (const term of ['grafton', 'Ohio MARCS', 'Lorain', '773.08125']) {
  assert.equal(context.tunerAllocationChannelMatches(grafton, term), true, `Search recognizes ${term}`);
}
assert.equal(context.tunerAllocationChannelMatches(grafton, 'Elyria'), false);
assert.equal(context.tunerAllocationChannelMatches(catalogChannels.find((item) => item.configuration_id === 'unnamed'),
  '154.464'), true, 'An unnamed saved channel remains searchable by RF frequency');
assert.equal(context.tunerAllocationChannelMatches(catalogChannels.find((item) => item.configuration_id === 'unnamed'),
  'Unnamed'), true, 'Search recognizes the fallback channel label shown in the picker');
assert.equal(context.tunerAllocationChannelContext(catalogChannels.find((item) => item.configuration_id === 'atc-1')),
  '', 'Repeated ATC name/system/site adds no redundant context');
assert.match(context.tunerAllocationChannelContext(grafton), /Ohio MARCS-IP.*Lorain/,
  'Configured name retains separate system and site context');

const pagedChannels = Array.from({ length: 27 }, (_, index) => saved(`paged-${index + 1}`,
  `Channel ${String(index + 1).padStart(2, '0')}`, index < 20 ? 'p25-conventional' : 'am',
  [154_000_000 + index * 25_000], { processing_state: index % 3 === 0 ? 'STOPPED' : 'RUNNING' }));
const firstCatalogPage = plain(context.tunerAllocationCatalogPage(pagedChannels));
assert.deepEqual(firstCatalogPage.counts, { all: 27, running: 18, stopped: 9 });
assert.equal(firstCatalogPage.total, 18, 'The catalog initially shows running channels');
assert.equal(firstCatalogPage.rows.length, 10, 'The catalog has ten channels per page');
assert.equal(firstCatalogPage.pageCount, 2);
assert.equal(firstCatalogPage.offset, 0);
assert.ok(firstCatalogPage.rows.every((channel) => channel.processing_state === 'RUNNING'));
const lastCatalogPage = plain(context.tunerAllocationCatalogPage(pagedChannels, { page: 99 }));
assert.equal(lastCatalogPage.page, 1, 'A stale page is clamped to the last matching page');
assert.equal(lastCatalogPage.offset, 10);
assert.equal(lastCatalogPage.rows.length, 8);
assert.equal(context.tunerAllocationCatalogPage(pagedChannels, { page: -1 }).page, 0);
assert.equal(context.tunerAllocationCatalogPage(pagedChannels, { page: Infinity }).page, 0);
const namedCatalogPage = plain(context.tunerAllocationCatalogPage(pagedChannels, { query: 'Channel 26', page: 1 }));
assert.deepEqual(namedCatalogPage.rows.map((channel) => channel.configuration_id), ['paged-26'],
  'Search reaches matching channels beyond the first unfiltered page');
assert.equal(namedCatalogPage.page, 0);
const protocolCatalogPage = plain(context.tunerAllocationCatalogPage(pagedChannels,
  { query: 'Channel 2', protocol: 'am', status: 'stopped' }));
assert.deepEqual(protocolCatalogPage.counts, { all: 7, running: 5, stopped: 2 },
  'Status counts follow search and protocol scope before applying the chosen status');
assert.deepEqual(protocolCatalogPage.rows.map((channel) => channel.configuration_id), ['paged-22', 'paged-25']);
const allCatalogPage = plain(context.tunerAllocationCatalogPage(pagedChannels, { status: 'all', page: 2 }));
assert.equal(allCatalogPage.total, 27);
assert.equal(allCatalogPage.rows.length, 7);
const emptyCatalogPage = plain(context.tunerAllocationCatalogPage(pagedChannels, { query: 'No matching channel', page: 5 }));
assert.deepEqual(emptyCatalogPage.counts, { all: 0, running: 0, stopped: 0 });
assert.equal(emptyCatalogPage.total, 0);
assert.equal(emptyCatalogPage.page, 0);
assert.equal(emptyCatalogPage.pageCount, 1);
assert.equal(emptyCatalogPage.offset, 0);
assert.deepEqual(emptyCatalogPage.rows, []);

// Match actual Albany geometry while testing receiver safety boundaries with
// explicit synthetic frequency limits; the saved report omitted those limits.
const tuner = { id: 'safe-1', name: 'RTL-SDR 1', frequencyHz: 855_362_500,
  usableBandwidthHz: 2_352_000, dcGapHz: 10_000, available: true,
  minFrequencyHz: 851_000_000, maxFrequencyHz: 861_000_000, supportsSharedWindow: true, receiving: [] };
const fits = (frequencyHz, extra = {}, bandwidthHz = 12_500) =>
  context.tunerAllocationFits({ ...tuner, ...extra }, { frequencyHz, bandwidthHz });
assert.equal(fits(854_537_500), true, 'Actual control fits tuner 1');
assert.equal(fits(856_212_500), true, 'Actual call fits tuner 1');
assert.equal(fits(853_737_500), false, 'Actual uncovered control stays outside tuner 1');
assert.equal(fits(854_537_500, { available: false }), false, 'Unavailable tuner never covers a frequency');
assert.equal(fits(854_537_500, { minFrequencyHz: null }), false, 'Unknown limits cannot promise a match');
assert.equal(fits(854_537_500, { minFrequencyHz: 854_535_000 }), false, 'Full channel must clear minimum');
assert.equal(fits(856_212_500, { maxFrequencyHz: 856_215_000 }), false, 'Full channel must clear maximum');
assert.equal(fits(854_186_500), false, 'Channel center at window edge is insufficient');
assert.equal(fits(854_192_750), true, 'Whole channel exactly inside window edge fits');
assert.equal(fits(855_362_500), false, 'A call at the shared window center crosses the unusable gap');
assert.equal(fits(855_373_750), false, 'A channel touching the unusable gap is excluded');
assert.equal(fits(855_373_751), true, 'A channel clearing the gap fits');
assert.equal(fits(855_362_500, { supportsSharedWindow: false, dcGapHz: 0 }), true,
  'A one-frequency tuner can receive its exact center');
assert.equal(fits(855_387_500, { supportsSharedWindow: false, dcGapHz: 0 }), false,
  'A one-frequency tuner cannot share nearby frequencies');

const allTuners = [tuner, { ...tuner, id: 'safe-2', name: 'RTL-SDR 2', frequencyHz: 852_212_500 },
  { ...tuner, id: 'safe-3', name: 'RTL-SDR 3', frequencyHz: 859_462_500 },
  { ...tuner, id: 'safe-4', name: 'RTL-SDR 4', frequencyHz: 856_962_500 }];
const actualKnown = [851_737_500, 852_187_500, 852_950_000, 853_737_500, 854_037_500, 854_537_500,
  856_212_500, 856_937_500, 857_937_500, 858_712_500, 860_212_500].map((frequencyHz) => ({ frequencyHz, bandwidthHz: 12_500 }));
assert.equal(actualKnown.filter((item) => allTuners.some((itemTuner) => context.tunerAllocationFits(itemTuner, item))).length, 9);
assert.deepEqual(allTuners.filter((item) => context.tunerAllocationFits(item, { frequencyHz: 856_212_500, bandwidthHz: 12_500 }))
  .map((item) => item.name), ['RTL-SDR 1', 'RTL-SDR 4'], 'Actual overlap identifies both current windows');
const map = plain(context.tunerAllocationMapGroups(allTuners, actualKnown));
assert.equal(map.length, 1, 'Albany current windows share one readable scale');
assert.equal(map[0].tuners.length, 4, 'Unused current windows stay visible');
assert.equal(map[0].frequencies.length, 11, 'Outside frequencies remain on the known strip');
const separate = plain(context.tunerAllocationMapGroups([...allTuners, { ...tuner, id: 'other-band',
  name: 'RTL-SDR 5', frequencyHz: 773_987_500, minFrequencyHz: 760_000_000, maxFrequencyHz: 800_000_000 }],
  [...actualKnown, { frequencyHz: 773_987_500, bandwidthHz: 12_500 }]));
assert.equal(separate.length, 2, '700 and 850 MHz windows use separate readable scales');
assert.equal(separate[0].frequencies.length, 1);
assert.equal(separate[1].frequencies.length, 11);
const separatedBands = plain(context.tunerAllocationMapGroups([...allTuners,
  { ...tuner, id: 'regional', name: 'RTL-SDR 5', frequencyHz: 773_987_500,
    minFrequencyHz: 760_000_000, maxFrequencyHz: 790_000_000 },
  { ...tuner, id: 'county-fire', name: 'RTL-SDR 6', frequencyHz: 150_000_000,
    minFrequencyHz: 140_000_000, maxFrequencyHz: 160_000_000 }], actualKnown));
assert.equal(separatedBands.length, 3, 'Widely separated tuner windows retain distinct frequency scales');
assert.deepEqual(separatedBands.map((group) => group.frequencies.length), [0, 0, 11],
  'Only the selected site supplies known frequencies, even when other bands receive channels');
assert.equal(separatedBands[1].start - separatedBands[0].end, 621_635_500);
assert.equal(separatedBands[2].start - separatedBands[1].end, 75_873_000);
const excludedCenter = { ...tuner, minFrequencyHz: 860_000_000, maxFrequencyHz: 861_000_000 };
const excludedMap = plain(context.tunerAllocationMapGroups([excludedCenter], []));
assert.equal(excludedMap.length, 1);
assert.ok(excludedMap[0].minimum < tuner.frequencyHz && excludedMap[0].maximum > tuner.frequencyHz,
  'Current center stays visible when saved limits exclude its receiving window');
assert.equal(context.tunerAllocationFits(excludedCenter, { frequencyHz: tuner.frequencyHz, bandwidthHz: 12_500 }), false);
