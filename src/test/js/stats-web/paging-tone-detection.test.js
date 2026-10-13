'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function main() {
  const source = path.resolve(process.argv[2] || path.join(__dirname, '../../../../stats-web/assets/features/paging-tone-detection.js'));
  const { decodePagingToneDetection, requestPagingToneDetection } = await import(pathToFileURL(source).href);
  const revision = 'a'.repeat(64);
  const initial = { revision, enabled: true, status: 'ready' };
  assert.deepEqual(decodePagingToneDetection(initial), initial);
  assert.deepEqual(decodePagingToneDetection({ revision, enabled: false, status: 'unavailable' }),
    { revision, enabled: false, status: 'unavailable' });
  for (const invalid of [null, [], { ...initial, revision: ['a'.repeat(64)] },
    { ...initial, revision: 'bad' }, { ...initial, enabled: 1 },
    { ...initial, status: 'missing' }, { ...initial, status: 'unavailable' }]) {
    assert.throws(() => decodePagingToneDetection(invalid), /Reload status/);
  }
  const calls = [];
  let response = { ok: true, status: 200, json: async () => initial };
  const fetchDocument = async (url, options) => { calls.push({ url, options }); return response; };
  assert.deepEqual(await requestPagingToneDetection(fetchDocument), initial);
  assert.equal(calls.at(-1).url, '/api/v1/admin/paging-tone-detection');
  assert.equal(calls.at(-1).options.method, 'GET');
  response = { ok: true, status: 200, json: async () => ({ ...initial, enabled: false }) };
  assert.equal((await requestPagingToneDetection(fetchDocument, { action: 'enabled', revision, value: false })).enabled, false);
  assert.equal(calls.at(-1).url, '/api/v1/admin/paging-tone-detection/enabled');
  assert.equal(calls.at(-1).options.method, 'PUT');
  assert.equal(calls.at(-1).options.headers['If-Match'], `"${revision}"`);
  assert.equal(calls.at(-1).options.headers['Content-Type'], 'application/json');
  assert.deepEqual(JSON.parse(calls.at(-1).options.body), { value: false });
  const count = calls.length;
  await assert.rejects(requestPagingToneDetection(fetchDocument, { action: 'enabled', value: false }), /Reload/);
  await assert.rejects(requestPagingToneDetection(fetchDocument, { action: 'enabled', revision, value: 'false' }), /On or Off/);
  assert.equal(calls.length, count, 'Invalid local settings must not issue writes.');
  const changed = { ...initial, revision: 'b'.repeat(64), enabled: false };
  response = { ok: false, status: 409, json: async () => changed };
  await assert.rejects(requestPagingToneDetection(fetchDocument, { action: 'enabled', revision, value: false }),
    error => error.status === 409 && error.current.revision === changed.revision && error.current.enabled === false);
  response = { ok: false, status: 500, json: async () => ({ error: { message: 'The setting could not be saved. Try again.' } }) };
  await assert.rejects(requestPagingToneDetection(fetchDocument, { action: 'enabled', revision, value: false }),
    error => error.status === 500 && /could not be saved/.test(error.message));
  const stalled = (_url, { signal }) => new Promise((_resolve, reject) => {
    const abort = () => reject(Object.assign(new Error('Aborted'), { name: 'AbortError' }));
    if (signal.aborted) abort(); else signal.addEventListener('abort', abort, { once: true });
  });
  await assert.rejects(requestPagingToneDetection(stalled, { timeoutMs: 1 }), error => error.code === 'request_timeout');
  const controller = new AbortController();
  const cancelled = requestPagingToneDetection(stalled, { signal: controller.signal }); controller.abort();
  await assert.rejects(cancelled, error => error.name === 'AbortError');
  console.log('Paging-tone snapshot, independent endpoint, revision, failure, timeout, and cancellation contracts passed.');
}

main().catch(error => { console.error(error); process.exitCode = 1; });
