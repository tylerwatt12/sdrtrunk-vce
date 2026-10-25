'use strict';
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function checkLocalityCache(file) {
  const { createRadioReferenceLocalityCache } = await import(pathToFileURL(file).href);
  const key = 'sdrtrunk-vce-radioreference-localities-v1';
  const month = 30 * 24 * 60 * 60 * 1000;
  let timestamp = 1_800_000_000_000;
  let loads = 0;
  const saved = new Map();
  const storage = { getItem: key => saved.get(key), setItem: (key, value) => saved.set(key, value) };
  const cache = () => createRadioReferenceLocalityCache({ storage, now: () => timestamp });
  const countries = [{ id: 1, name: 'United States', abbreviation: 'US', password: 'not-persisted' }];
  const load = async () => { loads++; return countries; };
  let current = cache();
  await current.load('countries', load);
  assert.equal(loads, 1);
  assert.equal(saved.get(key).includes('password'), false, 'Persist only public locality fields.');
  current = cache();
  const cached = await current.load('countries', load);
  assert.equal(loads, 1, 'Fresh locality lists survive recreation and reload.');
  cached[0].name = 'Edited by caller';
  assert.equal((await current.load('countries', load))[0].name, 'United States');
  timestamp += month - 1;
  await current.load('countries', load);
  assert.equal(loads, 1, 'A hit does not reset the original expiry.');
  timestamp++;
  await current.load('countries', load);
  assert.equal(loads, 2, 'Refresh on expiry, including its exact boundary.');
  await current.load('states:1', async () => [{ id: 39, name: 'Ohio', abbreviation: 'OH' }]);
  await current.load('states:2', async () => [{ id: 101, name: 'Ontario', abbreviation: 'ON' }]);
  await current.load('counties:39', async () => [{ id: 49, name: 'Franklin County' }]);
  assert.equal((await cache().load('states:2', () => assert.fail('Wrong parent cache')))[0].name, 'Ontario');
  assert.equal((await cache().load('counties:39', () => assert.fail('Missing county cache')))[0].name, 'Franklin County');

  let resolve;
  let concurrentLoads = 0;
  const pending = current.load('counties:101', () => {
    concurrentLoads++;
    return new Promise(done => { resolve = done; });
  });
  const second = current.load('counties:101', () => assert.fail('Duplicate lookup'));
  await Promise.resolve();
  resolve([{ id: 1001, name: 'Sample County' }]);
  assert.deepEqual(await pending, await second);
  assert.equal(concurrentLoads, 1, 'Overlapping selectors share one request.');

  let failures = 0;
  const failed = async () => { failures++; throw new Error('Unavailable'); };
  await assert.rejects(current.load('states:3', failed), /Unavailable/);
  await assert.rejects(current.load('states:3', failed), /Unavailable/);
  assert.equal(failures, 2, 'Failures must remain retryable.');
  assert.deepEqual(await current.load('states:3', async () => [{ id: 201, name: 'Recovered region' }]),
    [{ id: 201, name: 'Recovered region' }]);
  await current.load('states:3', () => assert.fail('A recovered region must be cached.'));
  await assert.rejects(current.load('systems:1', load), /Unknown RadioReference locality/);

  for (const raw of ['broken', '{}', JSON.stringify({ version: 2, entries: [] }),
    JSON.stringify({ version: 1, entries: [{ key: 'countries', storedAt: timestamp + 1, items: countries }] }),
    JSON.stringify({ version: 1, entries: [{ key: 'countries', storedAt: timestamp, items: [{ id: 1 }] }] }),
    'x'.repeat(256 * 1024 + 1)]) {
    saved.set(key, raw);
    let misses = 0;
    await cache().load('countries', async () => { misses++; return countries; });
    assert.equal(misses, 1, 'Invalid or future-dated data falls back to loading.');
  }
  const blocked = createRadioReferenceLocalityCache({ now: () => timestamp, storage: {
    getItem: () => { throw new Error('Blocked'); }, setItem: () => { throw new Error('Full'); }
  } });
  await blocked.load('countries', load);
  const before = loads;
  await blocked.load('countries', load);
  assert.equal(loads, before, 'Storage failure still permits in-memory reuse.');
  saved.clear();
  current = cache();
  for (const items of [[], [{ id: -1, name: 'Invalid' }], [{ id: 2147483648, name: 'Invalid' }],
    Array.from({ length: 501 }, () => countries[0])]) {
    let misses = 0;
    const malformed = async () => { misses++; return items; };
    await current.load('countries', malformed);
    await current.load('countries', malformed);
    assert.equal(misses, 2, 'Empty or malformed responses must not poison the cache.');
  }
  for (let parent = 1; parent <= 80; parent++) {
    await current.load(`states:${parent}`, async () => [{ id: parent, name: `Region ${parent}` }]);
  }
  let record = JSON.parse(saved.get(key));
  assert.equal(record.entries.length, 64, 'Bound the number of parent catalogs.');
  assert.equal(record.entries.some(entry => entry.key === 'states:1'), false);
  for (let parent = 1; parent <= 8; parent++) {
    await current.load(`counties:${parent}`, async () => Array.from({ length: 500 }, (_, index) =>
      ({ id: index + 1, name: 'County '.padEnd(256, 'x') })));
    assert.ok(saved.get(key).length <= 256 * 1024, 'Bound the total persisted size.');
  }
  record = JSON.parse(saved.get(key));
  assert.equal(record.entries.at(-1).key, 'counties:8');
  for (let parent = 9; parent <= 10; parent++) {
    await current.load(`counties:${parent}`, async () => Array.from({ length: 250 }, (_, index) =>
      ({ id: index + 1, name: '郡'.repeat(200) })));
    assert.ok(new TextEncoder().encode(saved.get(key)).byteLength <= 256 * 1024,
      'Non-ASCII locality names must respect the byte limit.');
  }
  console.log('RadioReference locality persistence, expiry, isolation, bounds and retry checks passed.');
}

module.exports = { checkLocalityCache };
if (require.main === module) checkLocalityCache(path.resolve(process.argv[2] ||
  'stats-web/assets/features/radioreference-locality-cache.js')).catch(error => {
  console.error(error); process.exitCode = 1;
});
