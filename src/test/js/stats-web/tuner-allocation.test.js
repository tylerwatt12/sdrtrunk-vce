'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(process.argv[2] || 'stats-web/assets/app.js', 'utf8');
const start = source.indexOf('const TUNER_ALLOCATION_HISTORY_LIMIT = 100;');
const end = source.indexOf('async function renderAdminTunerAllocation(', start);
assert.ok(start >= 0 && end > start);
const context = vm.createContext({ number: String, frequency: (value) => (Number(value) / 1_000_000).toFixed(5) });
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
const excludedCenter = { ...tuner, minFrequencyHz: 860_000_000, maxFrequencyHz: 861_000_000 };
const excludedMap = plain(context.tunerAllocationMapGroups([excludedCenter], []));
assert.equal(excludedMap.length, 1);
assert.ok(excludedMap[0].minimum < tuner.frequencyHz && excludedMap[0].maximum > tuner.frequencyHz,
  'Current center stays visible when saved limits exclude its receiving window');
assert.equal(context.tunerAllocationFits(excludedCenter, { frequencyHz: tuner.frequencyHz, bandwidthHz: 12_500 }), false);
