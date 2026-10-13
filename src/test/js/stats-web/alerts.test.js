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
  assert.equal(model.newAlertRule('UTC').action_button_enabled, false);
  assert.deepEqual(model.alertPopupAction({ action: { kind: 'live', entity_ref: null } }),
    { label: 'View live channels', href: '/?view=live' });
  assert.deepEqual(model.alertPopupAction({ action: { kind: 'radio', entity_ref: {
    kind: 'radio', radio_system_key: 'p25:abcde:123', identity_key: 'v1-r-abcde-123-1101' } } }),
    { label: 'View radio', href: '/?view=radio&radio_system_key=p25%3Aabcde%3A123&identity_key=v1-r-abcde-123-1101' });
  assert.deepEqual(model.alertPopupAction({ action: { kind: 'channel', entity_ref: {
    kind: 'channel', key: '12345678-1234-1234-1234-123456789abc' } } }),
    { label: 'View channel', href: '/?view=channel&configuration_id=12345678-1234-1234-1234-123456789abc' });
  for (const action of [null, { kind: 'admin', entity_ref: null },
    { kind: 'channel', entity_ref: { kind: 'channel', key: '/?view=admin' } },
    { kind: 'radio', entity_ref: { kind: 'radio', radio_system_key: 'p25:abcde:123', identity_key: 'https://example.com' } },
    { kind: 'live', entity_ref: { kind: 'admin', key: 'settings' } }]) {
    assert.equal(model.alertPopupAction({ action }), null, 'Disabled, malformed and administrator destinations have no button');
  }
  assert.equal(model.alertPopupAction({ click_action: 'listen', action: null }), null,
    'Notification tap behavior cannot enable a disabled popup action');
  assert.equal(model.newAlertRule('UTC').protocol_family, '');
  const sources = [{ value: 'p25', protocol_family: 'P25', system_key: 'county' },
    { value: 'dmr', protocol_family: 'DMR', system_key: 'regional' }];
  assert.deepEqual(model.alertSourceChoices(sources, 'P25').map(value => value.value), ['p25']);
  assert.deepEqual(model.alertSourceChoices(sources, '', 'regional').map(value => value.value), ['dmr']);
  assert.deepEqual(model.alertSourceChoices(sources, 'P25', 'regional'), []);
  assert.equal(model.alertActivityChoices('NBFM').some(([key]) => key === 'tone'), false);
  assert.equal(model.alertActivityChoices('NBFM').some(([key]) => key === 'dcs'), true);
  assert.equal(model.alertActivityChoices('P25').some(([key]) => key === 'tone'), true);
  assert.equal(model.alertActivityChoices('P25').some(([key]) => key === 'dcs'), false);
  assert.equal(model.alertActivityChoices('AM').some(([key]) => key === 'dcs'), true);
  assert.equal(model.alertActivityChoices('AM').some(([key]) => key === 'health'), true);
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
