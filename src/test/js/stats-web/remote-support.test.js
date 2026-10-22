'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function main() {
  const source = path.resolve(process.argv[2] || path.join(__dirname,
    '../../../../stats-web/assets/features/remote-support.js'));
  const { decodeRemoteSupport, requestRemoteSupport, remoteSupportDurations, remoteSupportEnd, remoteSupportStopWarning } =
    await import(pathToFileURL(source).href);
  assert.deepEqual(remoteSupportDurations.map(([seconds]) => seconds), [1800, 3600, 21600, 86400, 0]);
  const initial = { enabled: false, connected: false, code: '', expires_at_ms: 0, unlimited: false, can_start: true };
  assert.equal(decodeRemoteSupport({ data: initial }).canStart, true);
  assert.equal(decodeRemoteSupport({ ...initial, can_start: false }).canStart, false);
  assert.equal(decodeRemoteSupport(initial).stopNotSaved, false);
  assert.equal(decodeRemoteSupport({ ...initial, stop_not_saved: true }).stopNotSaved, true);
  for (const invalid of [null, [], {}, { ...initial, enabled: 'true' }, { ...initial, connected: 1 },
    { ...initial, code: 123 }, { ...initial, expires_at_ms: '123' }, { ...initial, unlimited: 1 },
    { ...initial, can_start: 'false' }, { ...initial, stop_not_saved: 'true' },
    { ...initial, stop_not_saved: null }]) {
    assert.throws(() => decodeRemoteSupport(invalid), /status could not be read/);
  }
  const calls = [];
  let response = { ok: true, status: 200, json: async () => ({ data: initial }) };
  const fetchDocument = async (url, options) => { calls.push({ url, options }); return response; };
  assert.equal((await requestRemoteSupport(fetchDocument)).enabled, false);
  assert.equal(calls.at(-1).url, '/api/v1/remote-support');
  assert.equal(calls.at(-1).options.method, 'GET');
  for (const [duration] of remoteSupportDurations) {
    await requestRemoteSupport(fetchDocument, { action: 'start', duration });
    assert.equal(calls.at(-1).options.method, 'POST');
    assert.equal(calls.at(-1).options.headers['Content-Type'], 'application/json');
    assert.deepEqual(JSON.parse(calls.at(-1).options.body), { action: 'start', duration_seconds: duration });
  }
  await requestRemoteSupport(fetchDocument, { action: 'stop' });
  assert.deepEqual(JSON.parse(calls.at(-1).options.body), { action: 'stop' });
  const count = calls.length;
  for (const duration of [-1, 1, '21600', null]) {
    await assert.rejects(requestRemoteSupport(fetchDocument, { action: 'start', duration }), /Choose how long/);
  }
  assert.equal(calls.length, count);
  assert.equal(remoteSupportEnd({ unlimited: true }), 'No time limit');
  assert.match(remoteSupportEnd({ expiresAt: Date.UTC(2026, 9, 22, 18, 0) }, 'en-US'), /Access ends Oct 22, 2026/);
  response = { ok: false, status: 503, json: async () => ({ error: { message: 'Private implementation detail' } }) };
  await assert.rejects(requestRemoteSupport(fetchDocument, { action: 'start' }),
    error => error.status === 503 && error.message === 'Remote support could not be started. Try again.');
  response = { ok: false, status: 503, json: async () => ({ error: {
    code: 'remote_support_stop_not_saved', message: 'Private file details must not be displayed' } }) };
  await assert.rejects(requestRemoteSupport(fetchDocument, { action: 'stop' }), error =>
    error.status === 503 && error.code === 'remote_support_stop_not_saved' && error.message === remoteSupportStopWarning);
  const stalled = (_url, { signal }) => new Promise((_resolve, reject) => {
    const abort = () => reject(Object.assign(new Error('Aborted'), { name: 'AbortError' }));
    if (signal.aborted) abort(); else signal.addEventListener('abort', abort, { once: true });
  });
  await assert.rejects(requestRemoteSupport(stalled, { timeoutMs: 1 }), /took too long/);
  const controller = new AbortController();
  const cancelled = requestRemoteSupport(stalled, { signal: controller.signal }); controller.abort();
  await assert.rejects(cancelled, error => error.name === 'AbortError');
  console.log('Remote support durations, permission, requests, expiry, safe errors, and cancellation contracts passed.');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
