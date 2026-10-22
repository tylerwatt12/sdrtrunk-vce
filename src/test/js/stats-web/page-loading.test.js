'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../../../stats-web/assets/app.js'), 'utf8');
const preferenceFetch = source.slice(source.indexOf('async function jsonDocumentFetch('),
  source.indexOf('\nfunction activeUserPreferences('));
const requestFetch = source.slice(source.indexOf('async function requestJson('),
  source.indexOf('\nfunction waitForRequestRetry('));
const settle = async () => { for (let n = 0; n < 4; n++) await Promise.resolve(); };

function context(fetch) {
  const timers = new Map();
  const value = { fetch, Headers, AbortController, activeRenderController: null, initialPageDeadline: 0, performance,
    reloadForWebClientRevision() {}, systemLabels: { rememberSystemNames: value => value },
    accessSession: { csrfToken: 'fixture-csrf' }, window: {
    setTimeout(callback, delay) { const id = timers.size + 1; timers.set(id, { callback, delay }); return id; },
    clearTimeout(id) { timers.delete(id); }
  } };
  vm.createContext(value); vm.runInContext(preferenceFetch + requestFetch, value);
  return { value, timers };
}

test('preference deadline includes a stalled response body and releases its timer', async () => {
  const { value, timers } = context(async (_url, options) => ({ ok: true, status: 200,
    text: () => new Promise((_resolve, reject) => options.signal.addEventListener('abort',
      () => reject(new DOMException('Aborted', 'AbortError')), { once: true })) }));
  const pending = value.jsonDocumentFetch('/api/v1/me/preferences');
  await settle(); assert.equal(timers.size, 1);
  const timer = [...timers.values()][0]; assert.equal(timer.delay, 10_000); timer.callback();
  await assert.rejects(pending, error => error.code === 'request_timeout' && error.path === '/api/v1/me/preferences');
  assert.equal(timers.size, 0);
});

test('preference transport preserves raw documents, serialized writes and HTTP failure status', async () => {
  let sent;
  const { value, timers } = context(async (_url, options) => {
    sent = options;
    return { ok: true, status: 200, text: async () => '{"revision":7,"preferences":{"theme":"dark"}}' };
  });
  const response = await value.jsonDocumentFetch('/api/v1/me/preferences', { method: 'PUT', body: '{"theme":"dark"}' });
  assert.equal(sent.body, '{"theme":"dark"}'); assert.equal(sent.headers.get('X-CSRF-Token'), 'fixture-csrf');
  assert.deepEqual(JSON.parse(JSON.stringify(await response.json())), { revision: 7, preferences: { theme: 'dark' } });
  await assert.rejects(response.json(), /already read/); assert.equal(timers.size, 0);
  value.fetch = async () => ({ ok: false, status: 409, text: async () => '{"error":"conflict"}' });
  const conflict = await value.jsonDocumentFetch('/api/v1/me/preferences');
  assert.equal(conflict.ok, false); assert.equal(conflict.status, 409);
  assert.deepEqual(JSON.parse(JSON.stringify(await conflict.json())), { error: 'conflict' });
});

test('API JSON body reads use the same ten-second deadline', async () => {
  const { value, timers } = context(async (_url, options) => ({ ok: true, status: 200,
    headers: new Headers({ 'Content-Type': 'application/json' }),
    json: () => new Promise((_resolve, reject) => options.signal.addEventListener('abort',
      () => reject(new DOMException('Aborted', 'AbortError')), { once: true })) }));
  const pending = value.requestJson('/api/v1/activity'); await settle();
  const timer = [...timers.values()][0]; assert.equal(timer.delay, 10_000); timer.callback();
  await assert.rejects(pending, error => error.code === 'request_timeout'); assert.equal(timers.size, 0);
});

test('confirmed access and initial status start page rendering without waiting for health', async () => {
  let confirmAccess, confirmStatus; const calls = [];
  const access = new Promise(resolve => { confirmAccess = resolve; });
  const status = new Promise(resolve => { confirmStatus = resolve; });
  let finishPage; const page = new Promise(resolve => { finishPage = resolve; });
  const value = { performance, initialPageDeadline: 0, refreshAccessSession: () => access,
    render: () => { calls.push('render'); return page; }, loadStatus: refresh => { calls.push(['status', refresh]); return status; },
    receiverHealthController: { refresh: () => { calls.push('health'); return new Promise(() => {}); } } };
  vm.createContext(value);
  const start = source.lastIndexOf('initialPageDeadline = performance.now() + 10_000;');
  vm.runInContext(source.slice(start, source.indexOf('let refreshCycle = null;', start)), value);
  assert.deepEqual(calls, []); confirmAccess(); await settle();
  assert.deepEqual(calls, [['status', false]]); confirmStatus(); await settle();
  assert.deepEqual(calls, [['status', false], 'render']);
  assert.ok(value.initialPageDeadline > 0); finishPage(); await settle();
  assert.deepEqual(calls, [['status', false], 'render', 'health']);
  assert.equal(value.initialPageDeadline, 0);
});

test('initial status makes one request and leaves recovery to the next confirmed refresh', async () => {
  let calls = 0, failures = 0;
  const value = { beginServiceStatusRequest() {}, rejectServiceStatusRequest() { failures++; }, acceptServiceStatus() {},
    api: async () => { calls++; throw new Error('Timed out'); } };
  vm.createContext(value);
  const start = source.indexOf('async function requestServiceStatus()');
  vm.runInContext(source.slice(start, source.indexOf('\nfunction detailedHistoryAvailable()', start)), value);
  await assert.rejects(value.requestServiceStatus(), /Timed out/);
  assert.equal(calls, 1); assert.equal(failures, 1);
});


test('auth, preferences, status and first-page data share one initial ten-second budget', async () => {
  let now = 100_000, requests = 0;
  const { value, timers } = context(async (_url, options) => {
    requests++;
    return { ok: true, status: 200, headers: new Headers({ 'Content-Type': 'application/json' }),
      json: async () => { now += 3_000; return { data: {} }; },
      text: async () => { now += 3_000; return '{"revision":1,"preferences":{}}'; } };
  });
  value.performance = { now: () => now }; value.initialPageDeadline = now + 10_000;
  await value.requestJson('/api/v1/auth/session', { page: false });
  await value.jsonDocumentFetch('/api/v1/me/preferences');
  await value.requestJson('/api/v1/status', { page: false });
  value.fetch = async (_url, options) => {
    requests++;
    return new Promise((_resolve, reject) => options.signal.addEventListener('abort',
      () => reject(new DOMException('Aborted', 'AbortError')), { once: true }));
  };
  const pending = value.requestJson('/api/v1/activity'); await settle();
  const timer = [...timers.values()][0]; assert.equal(timer.delay, 1_000);
  now += 1_000; timer.callback();
  await assert.rejects(pending, error => error.code === 'request_timeout');
  await assert.rejects(value.jsonDocumentFetch('/api/v1/me/preferences'), error => error.code === 'request_timeout');
  assert.equal(requests, 4, 'An expired preference fallback cannot start another ten-second request.');
  assert.equal(timers.size, 0);
  value.initialPageDeadline = 0;
  const retry = value.requestJson('/api/v1/activity'); await settle();
  const retryTimer = [...timers.values()][0]; assert.equal(retryTimer.delay, 10_000); retryTimer.callback();
  await assert.rejects(retry, error => error.code === 'request_timeout');
});
