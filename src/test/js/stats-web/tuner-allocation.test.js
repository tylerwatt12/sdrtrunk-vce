'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(process.argv[2] || 'stats-web/assets/app.js', 'utf8');
const start = source.indexOf('const TUNER_ALLOCATION_HISTORY_LIMIT = 100;');
const end = source.indexOf('function tunerAllocationCoverage(', start);
assert.ok(start >= 0 && end > start);
const context = vm.createContext({ frequency: (value) => (Number(value) / 1_000_000).toFixed(5) });
vm.runInContext(source.slice(start, end), context);
const snapshot = (decisions = []) => ({ session_id: 'session', generated_at_ms: 123,
  sites: [{ id: 'named-site', name: 'Cleveland Simulcast', known_frequency_count: 30,
    covered_frequency_count: 27, frequencies: [{ frequency_hz: 852_012_500, covered: true }] }],
  tuners: [{ id: 'safe-1', name: 'RTL-SDR 1', frequency_hz: 852_750_000, dc_gap_hz: 25_000 }], decisions });
const row = (id, sequence = id) => ({ decision_id: id, sequence, repeat_count: 1 });
const plain = (value) => JSON.parse(JSON.stringify(value));
const result = plain(context.tunerAllocationSnapshot(snapshot([row(1), row(2, 8), row(3, 7), row(-1)])));
assert.deepEqual(result.decisions.map((item) => item.decisionId), [2, 3, 1]);
assert.equal(result.tuners[0].frequencyHz, 852_750_000);
assert.equal(result.sites[0].coveredFrequencyCount, 27);
assert.equal(result.sites[0].frequencies[0].frequencyHz, 852_012_500);
assert.equal(context.tunerAllocationSnapshot(snapshot(Array.from({ length: 150 }, (_, index) => row(index + 1)))).decisions.length, 100);
assert.throws(() => context.tunerAllocationSnapshot({}), /could not be loaded/);
const decisions = Array.from({ length: 45 }, (_, index) => row(index + 1));
assert.equal(context.tunerAllocationHistoryPage(decisions, -1).page, 0);
assert.equal(context.tunerAllocationHistoryPage(decisions, Infinity).page, 0);
assert.equal(context.tunerAllocationHistoryPage(decisions, 10).rows.length, 5);
assert.equal(context.tunerAllocationHistoryPage([], 2).pageCount, 1);
assert.equal(context.tunerAllocationWork([{ role: 'control' }, { role: 'call' }]), 'Control + calls');
assert.equal(context.tunerAllocationWork([{ role: 'conventional' }]), 'Conventional channel');
assert.equal(context.tunerAllocationWork([{ role: 'control' }, { role: 'conventional' }]), 'Control + conventional');
assert.equal(context.tunerAllocationWork([{ role: 'control' }, { role: 'conventional' }, { role: 'call' }]), 'Control + conventional + calls');
assert.equal(context.tunerAllocationWork([]), 'Unused');
assert.equal(context.tunerAllocationFrequency(null), 'Unavailable');
assert.equal(context.tunerAllocationFrequency(851_012_500), '851.01250 MHz');
