'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

async function main() {
  const values = new Map();
  const database = { createObjectStore() {}, close() {}, transaction() {
    const transaction = { objectStore: () => ({
      get: key => operation(values.get(key)),
      put: (value, key) => { values.set(key, structuredClone(value)); return operation(key); },
      delete: key => { values.delete(key); return operation(undefined); }
    }) };
    function operation(result) { queueMicrotask(() => transaction.oncomplete?.()); return { result }; }
    return transaction;
  } };
  const indexedDB = { open: () => {
    const request = { result: database };
    queueMicrotask(() => { request.onupgradeneeded?.(); request.onsuccess?.(); });
    return request;
  } };
  const context = vm.createContext({ self: {} });
  vm.runInContext(fs.readFileSync(path.resolve(__dirname,
    '../../../../stats-web/assets/features/alerts-worker-storage.js'), 'utf8'), context);
  const store = context.self.VCEAlertWorkerStorage.createStore(indexedDB);
  assert.equal(await store.read(), null);
  await store.save({ device_id: 'synthetic-device', manage_token: 'synthetic-device-token', endpoint: 'discard', email: 'discard' });
  assert.deepEqual(JSON.parse(JSON.stringify(await store.read())), { device_id: 'synthetic-device', manage_token: 'synthetic-device-token' });
  assert.equal(JSON.stringify([...values.values()]).includes('discard'), false);
  await assert.rejects(store.save({ device_id: 'x', manage_token: 'short' }), /invalid/);
  await assert.rejects(store.save({ device_id: 'x'.repeat(81), manage_token: 'synthetic-device-token' }), /invalid/);
  await store.clear();
  assert.equal(await store.read(), null);
  await assert.rejects(context.self.VCEAlertWorkerStorage.createStore(null).read(), /unavailable/);
  console.log('Worker credentials are bounded, isolated, removable, and exclude account/endpoint fields.');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
