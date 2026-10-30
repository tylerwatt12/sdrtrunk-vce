/* Copyright (C) 2026 Dennis Sheirer. Licensed under GPL-3.0-or-later. */
'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { inventory, partition, sameInventory, literalFileFilter, refreshedWeights, argumentsForCli } =
  require('./web-visual-shards.cjs');

function report(file, titles) {
  return { suites: [{ title: file, file, specs: titles.map(title => ({ title, tests: [{ projectName: 'chromium' }] })) }] };
}
function weights(files) {
  const data = { files: Object.fromEntries([...files].map(([file, cases], index) =>
    [file, { durationMs: (index + 1) * 1000, caseCount: cases.length, source: 'measured' }])) };
  return refreshedWeights(files, data);
}

test('whole-file partitions preserve every case once and do not depend on discovery order', () => {
  const files = new Map(Array.from({ length: 25 }, (_, index) => [`file-${index}.spec.js`, ['case-a', 'case-b'].map(name => `${index}/${name}`)]));
  const data = weights(files);
  const groups = partition(files, data);
  assert.equal(groups.length, 4);
  assert.ok(groups.every(group => group.workers === 2 && group.files.length > 0));
  assert.deepEqual(groups, partition(new Map([...files].reverse()), data));
  const assigned = groups.flatMap(group => group.files);
  assert.equal(assigned.length, files.size);
  assert.equal(new Set(assigned).size, files.size);
  assert.equal(groups.reduce((sum, group) => sum + group.caseCount, 0), 50);
});

test('new and deleted files fail closed until an explicit refresh', () => {
  const files = inventory(report('one.spec.js', ['a']));
  const data = weights(files);
  assert.throws(() => partition(new Map([...files, ['new.spec.js', ['new']]]), data), /inventory changed.*new.spec.js/);
  data.files['removed.spec.js'] = { durationMs: 100, caseCount: 1, source: 'measured' };
  assert.throws(() => partition(files, data), /removed.spec.js/);
  const refreshed = refreshedWeights(new Map([...files, ['new.spec.js', ['new']]]), data);
  assert.equal(refreshed.files['new.spec.js'].source, 'estimated');
  assert.equal(refreshed.files['new.spec.js'].caseCount, 1);
  assert.ok(!Object.hasOwn(refreshed.files, 'removed.spec.js'));
});

test('discovery and selection guards reject duplicate, empty and missing tests', () => {
  assert.throws(() => inventory(report('same.spec.js', ['repeat', 'repeat'])), /Duplicate discovered test/);
  assert.throws(() => inventory({ suites: [] }), /no tests/);
  const expected = inventory(report('one.spec.js', ['a', 'b']));
  sameInventory(expected, expected, 'valid');
  assert.throws(() => sameInventory(inventory(report('one.spec.js', ['a'])), expected, 'missing'), /differ/);
  assert.throws(() => inventory(report('../outside.spec.js', ['a'])), /outside/);
});

test('file selection escapes regular expressions and does not match nested same-name files', () => {
  const root = path.resolve('/visual');
  const filter = new RegExp(literalFileFilter('file+[one].spec.js', root));
  assert.ok(filter.test(path.join(root, 'file+[one].spec.js')));
  assert.ok(!filter.test(path.join(root, 'nested/file+[one].spec.js')));
  assert.ok(!filter.test(path.join(root, 'filexoneXspecYjs')));
});

test('case growth refreshes the checked identity hash and scales recorded duration', () => {
  const original = new Map([['one.spec.js', ['a']]]);
  const files = new Map([['one.spec.js', ['a', 'b']]]);
  const data = weights(original);
  assert.throws(() => partition(files, data), /semantic test inventory changed/);
  assert.throws(() => partition(new Map([['one.spec.js', ['renamed']]]), data), /semantic test inventory changed/);
  const refreshed = refreshedWeights(files, data);
  const group = partition(files, refreshed).find(shard => shard.files.length);
  assert.equal(group.caseCount, 2);
  assert.equal(group.modeledDurationMs, 2000);
  assert.equal(refreshed.files['one.spec.js'].caseCount, 1, 'Keep the measured rate when the suite grows.');
  refreshed.files['one.spec.js'].durationMs = NaN;
  assert.throws(() => partition(files, refreshed), /Invalid duration/);
});

test('CLI rejects invalid shards and accidental partial-run arguments', () => {
  assert.equal(argumentsForCli(['--shard=4/4']).shard, '4/4');
  assert.equal(argumentsForCli(['--refresh', '--reports', 'reports']).reports, 'reports');
  for (const args of [[], ['--shard', '0/4'], ['--shard', '1/3'], ['--shard', '1/4', '--grep', 'one'], ['--plan', '--refresh'], ['--refresh', '--reports']]) {
    assert.throws(() => argumentsForCli(args));
  }
});
