'use strict';
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function checkGuideHistory(assetsRoot = 'stats-web/assets') {
  const { createGuideMemory, guideStorageKey } = await import(pathToFileURL(path.resolve(assetsRoot, 'guides/persistence.js')).href);
  const definitions = [{ id: 'welcome', version: 1 }, { id: 'admin', version: 3 }, { id: 'aliases', version: 5 }];
  const alice = { account: 'alice', dismissalScope: 'account-1-100', authenticated: true };
  const bob = { account: 'bob', dismissalScope: 'account-2-200', authenticated: true };
  const response = dismissed => ({ ok: true, status: 200, json: async () => ({ data: { version: 1, dismissed } }) });
  const tick = () => new Promise(resolve => setImmediate(resolve));
  const storage = values => {
    const entries = new Map(values);
    return { entries, getItem: key => entries.get(key), setItem: (key, value) => entries.set(key, value) };
  };
  const backend = (initial = {}) => {
    const state = { dismissed: { ...initial }, requests: [], failGet: false, failPut: false, hook: null };
    state.fetch = async (url, options) => {
      assert.equal(url, '/api/v1/me/tour-dismissals');
      state.requests.push(options);
      if (options.method === 'GET' && state.failGet || options.method === 'PUT' && state.failPut)
        throw new Error('Example receiver unavailable');
      if (state.hook) return state.hook(options);
      if (options.method === 'PUT') {
        const document = JSON.parse(options.body);
        assert.equal(document.version, 1);
        for (const [id, version] of Object.entries(document.dismissed))
          state.dismissed[id] = Math.max(state.dismissed[id] || 0, version);
      }
      return response({ ...state.dismissed });
    };
    return state;
  };
  const make = (server, browser, extra = {}) => {
    const timers = new Map(), errors = [];
    let serial = 0;
    const memory = createGuideMemory(browser, { definitions, fetchDocument: server.fetch,
      onError: (error, detail) => errors.push({ error, ...detail }),
      setTimer(callback) { timers.set(++serial, callback); return serial; },
      clearTimer(id) { timers.delete(id); }, ...extra });
    return { memory, timers, errors };
  };

  const shared = backend({ welcome: 1, admin: 3, aliases: 4 });
  const first = make(shared, storage());
  assert.equal(await first.memory.synchronize(alice), true);
  assert.equal(first.memory.has(alice, definitions[1]), true, 'An empty browser honors saved account history.');
  assert.equal(first.memory.has(alice, definitions[2]), false, 'A revised tour still becomes available.');
  first.memory.remember(alice, definitions[2]);
  await first.memory.retry(alice);
  assert.equal(shared.dismissed.aliases, 5);
  const second = make(shared, storage());
  await second.memory.synchronize(alice);
  assert.equal(second.memory.has(alice, definitions[2]), true, 'A second browser and cleared storage use the account record.');
  first.memory.destroy(); second.memory.destroy();

  const inheritedName = backend({ constructor: 2, welcome: 1 });
  const inherited = make(inheritedName, storage(), { definitions: [...definitions, { id: 'constructor', version: 2 }] });
  await inherited.memory.synchronize(alice);
  assert.equal(inherited.memory.has(alice, { id: 'constructor', version: 2 }), true);
  inherited.memory.remember(alice, definitions[1]);
  await inherited.memory.retry(alice);
  assert.equal(inheritedName.dismissed.constructor, 2, 'A valid ID that also names an Object property keeps its numeric version.');
  inherited.memory.destroy();

  const legacy = storage([
    [guideStorageKey('alice', alice.dismissalScope, 'welcome', 1), '1'],
    [guideStorageKey('alice', alice.dismissalScope, 'aliases', 4), '1'],
    [guideStorageKey('alice', alice.dismissalScope, 'aliases', 6), '1'],
    [guideStorageKey('bob', alice.dismissalScope, 'admin', 3), '1'],
    [guideStorageKey('alice', 'different-installation', 'admin', 3), '1'],
    [guideStorageKey('alice', alice.dismissalScope, 'unknown-page', 1), '1']
  ]);
  const migrating = backend({ admin: 3 });
  const imported = make(migrating, legacy);
  await imported.memory.synchronize(alice);
  await imported.memory.retry(alice);
  assert.deepEqual(migrating.dismissed, { admin: 3, welcome: 1, aliases: 4 },
    'Migration imports only real known dismissals for this account and scope; future/foreign keys do not mark tours done.');
  assert.equal(legacy.entries.size, 6, 'Legacy records remain intact after migration.');
  assert.equal(imported.memory.has(alice, definitions[2]), false);
  imported.memory.destroy();

  const unavailable = backend(); unavailable.failGet = true;
  const failedLoad = make(unavailable, storage([[guideStorageKey('alice', alice.dismissalScope, 'admin', 3), '1']]));
  assert.equal(await failedLoad.memory.synchronize(alice), false);
  assert.equal(failedLoad.memory.has(alice, definitions[1]), false, 'A failed server read never treats browser records as authority.');
  assert.equal(failedLoad.errors.at(-1).kind, 'load');
  unavailable.failGet = false;
  await failedLoad.errors.at(-1).retry();
  assert.equal(unavailable.dismissed.admin, 3);
  failedLoad.memory.destroy();

  const offline = backend(); offline.failGet = true;
  const offlineMemory = make(offline, storage());
  await offlineMemory.memory.synchronize(alice);
  offlineMemory.memory.remember(alice, definitions[1]);
  await tick();
  assert.equal(offlineMemory.errors.at(-1).kind, 'save');
  assert.equal(offlineMemory.timers.size, 1, 'A manual dismissal retries even when the initial history read failed.');
  offline.failGet = false;
  await offlineMemory.errors.at(-1).retry();
  assert.equal(offline.dismissed.admin, 3);
  offlineMemory.memory.destroy();

  const failing = backend();
  const pendingStore = storage();
  const pending = make(failing, pendingStore);
  await pending.memory.synchronize(alice);
  failing.failPut = true;
  pending.memory.remember(alice, definitions[1]);
  await tick();
  assert.equal(pending.errors.at(-1).kind, 'save');
  assert.equal(pending.memory.has(alice, definitions[1]), true, 'The same session remembers its dismissal while retrying.');
  assert.equal(pendingStore.entries.get(guideStorageKey('alice', alice.dismissalScope, 'admin', 3)), '1');
  assert.equal(pending.timers.size, 1);
  failing.failPut = false;
  await pending.errors.at(-1).retry();
  assert.equal(failing.dismissed.admin, 3);
  assert.equal(pending.timers.size, 0);
  pending.memory.destroy();
  const afterReload = make(failing, pendingStore);
  await afterReload.memory.synchronize(alice);
  assert.equal(afterReload.memory.has(alice, definitions[1]), true);
  afterReload.memory.destroy();

  const mismatched = backend();
  const missingAck = make(mismatched, storage());
  await missingAck.memory.synchronize(alice);
  mismatched.hook = () => response({});
  missingAck.memory.remember(alice, definitions[1]);
  await tick();
  assert.match(missingAck.errors.at(-1).error.message, /did not confirm/);
  assert.equal(missingAck.timers.size, 1, 'An incomplete acknowledgement leaves the dismissal pending for retry.');
  missingAck.memory.destroy();
  assert.equal(missingAck.timers.size, 0);

  const delayed = backend();
  let resolveAlice;
  delayed.hook = () => new Promise(resolve => { resolveAlice = resolve; });
  const switching = make(delayed, storage());
  const aliceRead = switching.memory.synchronize(alice);
  const aliceSignal = delayed.requests[0].signal;
  delayed.hook = null;
  assert.equal(await switching.memory.synchronize(bob), true);
  assert.equal(aliceSignal.aborted, true);
  resolveAlice(response({ welcome: 1, admin: 3 }));
  assert.equal(await aliceRead, false);
  assert.equal(switching.memory.has(bob, definitions[1]), false, 'A late response cannot contaminate another account.');
  assert.equal(switching.memory.has(alice, definitions[1]), false);
  switching.memory.remember(bob, definitions[0]);
  await switching.memory.retry(bob);
  assert.deepEqual(JSON.parse(delayed.requests.at(-1).body).dismissed, { welcome: 1 });
  switching.memory.destroy();

  const queued = backend();
  const queuedMemory = make(queued, storage());
  await queuedMemory.memory.synchronize(alice);
  let resolveSave;
  queued.hook = () => new Promise(resolve => { resolveSave = resolve; });
  queuedMemory.memory.remember(alice, definitions[0]);
  await tick();
  const oldSave = queued.requests.at(-1);
  queued.hook = null;
  await queuedMemory.memory.synchronize(bob);
  assert.equal(oldSave.signal.aborted, true, 'Changing accounts aborts an in-flight old-account save.');
  resolveSave(response({ welcome: 1 }));
  await tick();
  assert.equal(queuedMemory.memory.has(bob, definitions[0]), false);
  queuedMemory.memory.destroy();

  const retryingAccount = backend();
  const staleRetry = make(retryingAccount, storage());
  await staleRetry.memory.synchronize(alice);
  retryingAccount.failPut = true;
  staleRetry.memory.remember(alice, definitions[1]);
  await tick();
  const retryAlice = staleRetry.errors.at(-1).retry;
  retryingAccount.failPut = false;
  await staleRetry.memory.synchronize(bob);
  const beforeOldActions = retryingAccount.requests.length;
  assert.equal(await retryAlice(), false, 'An old Retry action cannot reactivate a previous account.');
  staleRetry.memory.remember(alice, definitions[1]);
  await tick();
  assert.equal(retryingAccount.requests.length, beforeOldActions);
  assert.equal(staleRetry.memory.has(bob, definitions[1]), false);
  staleRetry.memory.destroy();
  console.log('Account tour history, migration, retry, versioning, and stale-account isolation checks passed.');
}
module.exports = { checkGuideHistory };
if (require.main === module) checkGuideHistory(process.argv[2]).catch(error => { console.error(error); process.exitCode = 1; });
