'use strict';
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function main() {
  const storage = await import(pathToFileURL(path.resolve(__dirname,
    '../../../../stats-web/assets/features/alerts-device-storage.js')).href);
  const values = new Map();
  const local = { getItem: key => values.get(key), setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) };
  const saved = storage.saveAlertDevice(local, { id: 'pending-id', manage_token: 'synthetic-demo-token', mode: 'proxy',
    status: 'pending', expires_at_ms: 1200, email: 'discard@example.net', enrollment_url: 'https://discard.example.net' });
  assert.deepEqual(storage.readAlertDevice(local), saved);
  assert.equal(JSON.stringify([...values.values()]).includes('discard'), false, 'Only anonymous connection credentials are retained');
  storage.forgetAlertDevice(local);
  assert.equal(storage.readAlertDevice(local), null);
  local.setItem('vce-alert-device-v1', '{invalid');
  assert.equal(storage.readAlertDevice(local), null);
  local.setItem('vce-alert-device-v1', JSON.stringify({ id: 'incomplete' }));
  assert.equal(storage.readAlertDevice(local), null);
  assert.equal(storage.readAlertDevice({ getItem() { throw new Error('blocked'); } }), null);
  assert.throws(() => storage.saveAlertDevice({ setItem() { throw new Error('quota'); } }, saved), /Allow site storage/);
  assert.throws(() => storage.saveAlertDevice(local, { id: 'incomplete' }), /incomplete/);
  console.log('Anonymous alert device persistence, pending resumption, malformed data, and blocked storage checks passed.');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
