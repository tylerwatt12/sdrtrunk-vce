'use strict';
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function main() {
  const { reconcileDirectSubscription, syncDirectWorkerDevice } = await import(pathToFileURL(path.resolve(__dirname,
    '../../../../stats-web/assets/features/alerts-direct.js')).href);
  const device = { id: 'synthetic-device', manage_token: 'synthetic-device-token', mode: 'direct', status: 'connected' };
  const writes = []; const workerMessages = [];
  const subscription = { expirationTime: null, toJSON: () => ({ endpoint: 'https://push.example.net/renewed', keys: { p256dh: 'synthetic', auth: 'synthetic' } }) };
  const registration = { active: { postMessage: (value, ports) => { workerMessages.push(value); ports[0].postMessage({ ok: true }); } },
    pushManager: { getSubscription: async () => subscription, subscribe() { throw new Error('Automatic subscribe is forbidden'); } } };
  const browser = { MessageChannel, Notification: { permission: 'granted', requestPermission() { throw new Error('Automatic prompting is forbidden'); } },
    navigator: { serviceWorker: { getRegistration: async () => registration } } };
  const result = await reconcileDirectSubscription(browser, device, async value => writes.push(value));
  assert.equal(result.state, 'connected');
  assert.deepEqual(writes, [{ subscription: subscription.toJSON() }]);
  assert.deepEqual(workerMessages[0].device, { device_id: device.id, manage_token: device.manage_token });
  assert.equal((await reconcileDirectSubscription(browser, device, async () => ({ status: 'reconnect_required' }))).state, 'reconnect');
  browser.Notification.permission = 'denied';
  assert.equal((await reconcileDirectSubscription(browser, device, async () => assert.fail())).state, 'reconnect');
  browser.Notification.permission = 'granted';
  registration.pushManager.getSubscription = async () => null;
  assert.equal((await reconcileDirectSubscription(browser, device, async () => assert.fail())).state, 'reconnect');
  registration.pushManager.getSubscription = async () => ({ ...subscription, expirationTime: Date.now() - 1 });
  assert.equal((await reconcileDirectSubscription(browser, device, async () => assert.fail())).state, 'reconnect');
  registration.pushManager.getSubscription = async () => subscription;
  assert.equal((await reconcileDirectSubscription(browser, device, async () => { throw Object.assign(new Error('invalid'), { status: 403 }); })).state, 'reconnect');
  assert.equal((await reconcileDirectSubscription(browser, device, async () => { throw new Error('offline'); })).state, 'retry');
  await syncDirectWorkerDevice(browser.navigator, null);
  assert.equal(workerMessages.at(-1).device, null);
  console.log('Direct endpoint reconciliation preserves device settings and never automatically prompts or subscribes.');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
