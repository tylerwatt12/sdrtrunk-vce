'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(process.argv[2] || 'stats-web/assets/app.js', 'utf8');
const start = source.indexOf('function callMatchingSnapshot(value)');
const end = source.indexOf('function callMatchingHistoryPage(', start);
assert.ok(start >= 0 && end > start);
const context = vm.createContext({});
vm.runInContext(`const CALL_MATCHING_HISTORY_LIMIT = 100; ${source.slice(start, end)}`, context);
const decision = (sequence) => ({ decision_sequence: sequence, outcome: 'MERGED',
  copy_count: 32, details_available: false, legs: [{ selected: true, channel_name: 'Named site' }] });
const snapshot = (duplicates, extra = {}) => ({ session_id: 'receiver-session', resolver: {},
  queue: {}, diagnostic_status: {}, history: {}, duplicates, ...extra });
const merge = (previous, next) => JSON.parse(JSON.stringify(context.mergeCallMatchingSnapshot(previous, next)));
const first = merge(null, snapshot([decision(1), decision(2)], { cursor: 2 }));
assert.deepEqual(first.duplicates.map((item) => item.decision_sequence), [2, 1]);
const unchanged = merge(first, snapshot([], { incremental: true, cursor: 2, first_sequence: 1 }));
assert.deepEqual(unchanged.duplicates, first.duplicates);
const appended = merge(first, snapshot([decision(3)], { incremental: true, cursor: 3, first_sequence: 2 }));
assert.deepEqual(appended.duplicates.map((item) => item.decision_sequence), [3, 2]);
assert.equal(appended.duplicates[1].legs[0].channel_name, 'Named site');
assert.equal(appended.duplicates[1].copy_count, 32);
const reset = merge(appended, snapshot([decision(1)], { incremental: true, session_id: 'replacement' }));
assert.deepEqual(reset.duplicates.map((item) => item.decision_sequence), [1]);
assert.deepEqual(merge(appended, snapshot([], { incremental: false })).duplicates, []);
