'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function main() {
  const source = path.resolve(process.argv[2] || path.join(__dirname, '../../../../stats-web/assets/features/call-filtering.js'));
  const { decodeCallFiltering, requestCallFiltering } = await import(pathToFileURL(source).href);
  const revision = 'a'.repeat(64);
  const initial = { revision, enabled: false, minimum_usable_voice_percent: null };
  const enabled = { revision, enabled: true, minimum_usable_voice_percent: 65 };
  for (const valid of [initial, enabled, { ...enabled, enabled: false },
    { ...enabled, minimum_usable_voice_percent: 1 }, { ...enabled, minimum_usable_voice_percent: 100 }]) {
    assert.deepEqual(decodeCallFiltering(valid), valid);
  }
  for (const invalid of [null, [], { ...initial, revision: [revision] }, { ...initial, revision: 'bad' },
    { ...initial, enabled: 0 }, { ...initial, enabled: true }, { ...enabled, minimum_usable_voice_percent: 0 },
    { ...enabled, minimum_usable_voice_percent: 101 }, { ...enabled, minimum_usable_voice_percent: 65.5 },
    { ...enabled, minimum_usable_voice_percent: '65' }, { revision, enabled: false }]) {
    assert.throws(() => decodeCallFiltering(invalid), /Reload settings/);
  }
  const calls = [];
  let response = { ok: true, status: 200, json: async () => initial };
  const fetchDocument = async (url, options) => { calls.push({ url, options }); return response; };
  assert.deepEqual(await requestCallFiltering(fetchDocument), initial);
  assert.equal(calls.at(-1).url, '/api/v1/admin/call-filtering');
  assert.equal(calls.at(-1).options.method, 'GET');
  response = { ok: true, status: 200, json: async () => enabled };
  assert.deepEqual(await requestCallFiltering(fetchDocument, { revision, value: enabled }), enabled);
  assert.equal(calls.at(-1).options.method, 'PUT');
  assert.equal(calls.at(-1).options.headers['If-Match'], `"${revision}"`);
  assert.equal(calls.at(-1).options.headers['Content-Type'], 'application/json');
  assert.deepEqual(JSON.parse(calls.at(-1).options.body), { enabled: true, minimum_usable_voice_percent: 65 });
  const count = calls.length;
  await assert.rejects(requestCallFiltering(fetchDocument, { value: enabled }), /Reload/);
  for (const value of [{ enabled: true, minimum_usable_voice_percent: null },
    { enabled: false, minimum_usable_voice_percent: 0 }, { enabled: true, minimum_usable_voice_percent: 65.5 }]) {
    await assert.rejects(requestCallFiltering(fetchDocument, { revision, value }), /1 to 100/);
  }
  assert.equal(calls.length, count, 'Invalid local settings never issue writes.');
  const changed = { ...enabled, revision: 'b'.repeat(64) };
  response = { ok: false, status: 409, json: async () => changed };
  await assert.rejects(requestCallFiltering(fetchDocument, { revision, value: enabled }),
    error => error.status === 409 && error.current.revision === changed.revision);
  response = { ok: false, status: 500, json: async () => ({ error: { message: 'Call filtering could not be saved. Try again.' } }) };
  await assert.rejects(requestCallFiltering(fetchDocument, { revision, value: enabled }),
    error => error.status === 500 && /could not be saved/.test(error.message));
  const stalled = (_url, { signal }) => new Promise((_resolve, reject) => {
    const abort = () => reject(Object.assign(new Error('Aborted'), { name: 'AbortError' }));
    if (signal.aborted) abort(); else signal.addEventListener('abort', abort, { once: true });
  });
  await assert.rejects(requestCallFiltering(stalled, { timeoutMs: 1 }), error => error.code === 'request_timeout');
  const controller = new AbortController();
  const cancelled = requestCallFiltering(stalled, { signal: controller.signal }); controller.abort();
  await assert.rejects(cancelled, error => error.name === 'AbortError');
  console.log('Call filtering bounds, off/null default, revision, failure, timeout, and cancellation contracts passed.');
}

main().catch(error => { console.error(error); process.exitCode = 1; });
