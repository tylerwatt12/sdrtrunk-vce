'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { pathToFileURL } = require('node:url');

async function main() {
  const root = path.resolve(__dirname, '../../../..');
  const model = await import(pathToFileURL(path.join(root, 'stats-web/assets/features/alerts-model.js')).href);
  const topics = [{ id: 'public', visible: true, enabled: true }, { id: 'paused', visible: true, enabled: false },
    { id: 'hidden', visible: false, enabled: true }];
  assert.deepEqual(model.visibleAlertTopics(topics).map(value => value.id), ['public', 'paused']);
  assert.deepEqual(model.availableAlertTopics(topics).map(value => value.id), ['public']);
  const events = topics.map(topic => ({ topic_id: topic.id }));
  assert.deepEqual(model.visibleAlertHistory(events, topics), events.slice(0, 2));
  assert.deepEqual(model.visibleAlertHistory(events, topics, 'hidden'), []);
  assert.equal(model.describeAlertConditions({ op: '', items: [], field: 'radio', operator: 'is', value: '12' }), 'Transmitting radio is 12');
  assert.equal(model.newAlertRule('UTC').click_action, 'details');
  assert.equal(model.ALERT_TRIGGERS.some(([key]) => key === 'audio'), false);
  for (const value of ['http://vce.example.com', 'https://127.0.0.1', 'https://vce.local',
    'https://user:password@vce.example.com', 'https://vce.example.com?token=x', 'https://vce.example.com/path']) {
    assert.equal(model.validAlertSiteUrl(value), false, value);
  }
  assert.equal(model.validAlertSiteUrl('https://vce.example.com:8443'), true);
  const iphone = { isSecureContext: true, Notification: {}, PushManager: {}, navigator: {
    userAgent: 'iPhone', serviceWorker: {} }, matchMedia: () => ({ matches: false }) };
  assert.equal(model.directPushSupport(iphone).install, true);
  iphone.navigator.standalone = true;
  assert.equal(model.directPushSupport(iphone).supported, true);
  assert.equal(model.directPushSupport({ ...iphone, isSecureContext: false }).supported, false);
  assert.deepEqual([...model.vapidKeyBytes('AQID')], [1, 2, 3]);

  const handlers = new Map(); const shown = []; const opened = []; const requests = []; let subscribed = 0;
  let workerDevice = { device_id: 'synthetic-device', manage_token: 'synthetic-device-token' };
  const renewed = { toJSON: () => ({ endpoint: 'https://push.example.net/new', keys: { p256dh: 'synthetic', auth: 'synthetic' } }) };
  const context = vm.createContext({ URL, importScripts() {}, fetch: async (url, options) => { requests.push({ url, options }); return { status: 200 }; },
    self: { location: { origin: 'https://vce.example.com' },
    VCEAlertWorkerStorage: { createStore: () => ({ read: async () => workerDevice, save: async value => { workerDevice = value; }, clear: async () => { workerDevice = null; } }) },
    addEventListener: (key, handler) => handlers.set(key, handler),
    registration: { pushManager: { subscribe: async options => { subscribed++; assert.equal(options.applicationServerKey, 'existing-key'); return renewed; } },
      showNotification: (title, details) => { shown.push({ title, details }); return Promise.resolve(); } },
    clients: { matchAll: async () => [], openWindow: async target => opened.push(target) } } });
  vm.runInContext(fs.readFileSync(path.join(root, 'stats-web/alerts-worker.js'), 'utf8'), context);
  let pending;
  handlers.get('push')({ data: { json: () => ({ title: 'Fire activity', body: 'Engine 12 transmitted', url: 'https://other.example.net' }) },
    waitUntil: value => { pending = value; } });
  await pending;
  assert.equal(shown[0].details.data.url, '/?view=topics');
  handlers.get('notificationclick')({ notification: { close() {}, data: { url: 'https://other.example.net' } },
    waitUntil: value => { pending = value; } });
  await pending;
  assert.deepEqual(opened, ['https://vce.example.com/?view=topics']);
  handlers.get('pushsubscriptionchange')({ oldSubscription: { options: { applicationServerKey: 'existing-key' } },
    waitUntil: value => { pending = value; } });
  await pending;
  assert.equal(subscribed, 1);
  assert.equal(requests[0].url, '/api/v1/alerts/subscriptions/me/refresh');
  assert.equal(requests[0].options.headers.Authorization, 'Bearer synthetic-device-token');
  assert.deepEqual(JSON.parse(requests[0].options.body), { subscription: renewed.toJSON() });
  assert.equal(requests[0].url.includes('synthetic'), false);
  handlers.get('pushsubscriptionchange')({ newSubscription: renewed, waitUntil: value => { pending = value; } });
  await pending;
  assert.equal(subscribed, 1, 'A subscription already renewed by the browser is reused');
  const acknowledgments = [];
  handlers.get('message')({ data: { type: 'vce-alert-device', device: null }, source: { url: 'https://other.example.net' },
    ports: [{ postMessage: value => acknowledgments.push(value) }], waitUntil: value => { pending = value; } });
  assert.ok(workerDevice, 'Another origin cannot clear a saved credential');
  handlers.get('message')({ data: { type: 'vce-alert-device', device: null }, source: { url: 'https://vce.example.com/?view=topics' },
    ports: [{ postMessage: value => acknowledgments.push(value) }], waitUntil: value => { pending = value; } });
  await pending;
  assert.equal(workerDevice, null);
  assert.equal(acknowledgments[0].ok, true);
  const before = requests.length;
  handlers.get('pushsubscriptionchange')({ newSubscription: renewed, waitUntil: value => { pending = value; } });
  await pending;
  assert.equal(requests.length, before, 'An unsubscribed worker cannot refresh a device');
  console.log('Alert visibility, direct eligibility, safe push clicks, and worker subscription renewal contracts passed.');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
