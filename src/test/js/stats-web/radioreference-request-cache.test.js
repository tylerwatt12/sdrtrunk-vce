'use strict';

const assert = require('node:assert/strict');
const { pathToFileURL } = require('node:url');
const path = require('node:path');

async function checkRadioReferenceRequestCache(modulePath) {
  const { createRadioReferenceRequestCache } = await import(pathToFileURL(modulePath).href);
  const cache = createRadioReferenceRequestCache({ maximumEntries: 2 });
  let calls = 0;
  let finish;
  const first = cache.load('sites:1', () => {
    calls++;
    return new Promise(resolve => { finish = resolve; });
  });
  const same = cache.load('sites:1', () => assert.fail('Pending sites must be shared.'));
  await Promise.resolve();
  assert.equal(calls, 1);
  finish({ items: [{ id: 1 }] });
  assert.deepEqual(await first, { items: [{ id: 1 }] });
  assert.equal(await same, await first);
  assert.equal(await cache.load('sites:1', () => assert.fail('Loaded sites must be reused.')), await first);

  await assert.rejects(cache.load('system:1', async () => { throw new Error('Unavailable'); }), /Unavailable/);
  assert.deepEqual(await cache.load('system:1', async () => ({ name: 'Recovered' })), { name: 'Recovered' });

  let failOld;
  const old = cache.load('sites:2', () => new Promise((resolve, reject) => { failOld = reject; }));
  await Promise.resolve();
  const fresh = cache.load('sites:2', async () => ({ items: [{ id: 2 }] }), { refresh: true });
  failOld(new Error('Superseded request'));
  await assert.rejects(old, /Superseded request/);
  assert.equal(await cache.load('sites:2', () => assert.fail('An old failure must not evict the refresh.')), await fresh);

  let finishOldAccount;
  const oldAccount = cache.load('system:2', () => new Promise(resolve => { finishOldAccount = resolve; }));
  await Promise.resolve();
  cache.clear();
  const newAccount = await cache.load('system:2', async () => ({ name: 'New account' }));
  finishOldAccount({ name: 'Old account' });
  await oldAccount;
  assert.equal(await cache.load('system:2', () => assert.fail('Old account data must not return after clear.')), newAccount);

  await cache.load('sites:3', async () => []);
  await cache.load('sites:4', async () => []);
  let reloaded = false;
  await cache.load('system:2', async () => { reloaded = true; return newAccount; });
  assert.equal(reloaded, true, 'A workspace must retain only its bounded set of directory responses.');

  let timestamp = 0;
  const expiring = createRadioReferenceRequestCache({ now: () => timestamp });
  const original = await expiring.load('system:3', async () => ({ name: 'Before update' }));
  timestamp = 5 * 60_000 - 1;
  assert.equal(await expiring.load('system:3', () => assert.fail('A fresh response must be reused.')), original);
  timestamp++;
  assert.deepEqual(await expiring.load('system:3', async () => ({ name: 'After update' })), { name: 'After update' },
    'A response expires at the five-minute boundary without extending its lifetime on a hit.');
  console.log('RadioReference workspace request coalescing, refresh, retry, isolation and bounds passed.');
}

module.exports = { checkRadioReferenceRequestCache };
if (require.main === module) checkRadioReferenceRequestCache(path.resolve(process.argv[2] ||
  'stats-web/assets/features/radioreference-import.js')).catch(error => {
  console.error(error); process.exitCode = 1;
});
