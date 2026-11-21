'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { createEncryptionKeySnapshot } = require('./visual/fixtures/encryption-keys.cjs');

async function main() {
  const file = path.resolve(process.argv[2] || path.join(__dirname, '../../../../stats-web/assets/features/encryption-keys.js'));
  const { decodeEncryptionKeys, requestEncryptionKeys, encryptionKeyPage, normalizeEncryptionKeyHex,
    parseEncryptionKeyId } = await import(pathToFileURL(file).href);
  const snapshot = createEncryptionKeySnapshot();
  assert.equal(decodeEncryptionKeys(snapshot), snapshot);
  const unavailable = { ...snapshot.keys[0], algorithm_id: 255, key_hex: null };
  assert.equal(decodeEncryptionKeys(createEncryptionKeySnapshot([unavailable])).keys[0], unavailable,
    'Ordinary stored unknown algorithms and missing key material remain manageable.');
  for (const value of [null, {}, { ...snapshot, revision: 'bad' }, { ...snapshot, protocols: [] },
    { ...snapshot, keys: [{ ...snapshot.keys[0], key_id: -1 }] },
    { ...snapshot, keys: [{ ...snapshot.keys[0], protocol: 'UNKNOWN' }] },
    { ...snapshot, keys: [snapshot.keys[0], snapshot.keys[0]] }]) {
    assert.throws(() => decodeEncryptionKeys(value), /Reload keys/);
  }
  assert.equal(normalizeEncryptionKeyHex('01 23:45-67_89'), '0123456789');
  for (const [raw, expected] of [['0', 0], ['65535', 65535], ['0x00AA', 170], ['0xffff', 65535]]) {
    assert.equal(parseEncryptionKeyId(raw), expected);
  }
  for (const value of ['', '-1', '65536', '0x10000', '12.5', '1e2', 'AABB']) assert.ok(Number.isNaN(parseEncryptionKeyId(value)));
  const many = Array.from({ length: 32 }, (_, index) => ({ ...snapshot.keys[0], id: `key-${index}`,
    label: `Dispatch ${String(index).padStart(2, '0')}`, scope: index === 31 ? 'West operations' : null, key_id: index }));
  const full = createEncryptionKeySnapshot(many);
  assert.equal(encryptionKeyPage(full).rows.length, 25);
  assert.equal(encryptionKeyPage(full, '', 1).rows.length, 7);
  assert.equal(encryptionKeyPage(full, '', 100).page, 1);
  assert.equal(encryptionKeyPage(full, 'West operations').rows[0].id, 'key-31');
  assert.equal(encryptionKeyPage(full, 'Dispatch 31').rows[0].id, 'key-31');
  assert.equal(encryptionKeyPage(full, 'des-ofb').total, 32);
  assert.equal(encryptionKeyPage(full, '0x1f').rows[0].id, 'key-31');
  assert.equal(encryptionKeyPage(snapshot, 'DMR').total, 1);
  assert.equal(encryptionKeyPage(snapshot, '0x81').total, 1);
  assert.equal(encryptionKeyPage(snapshot, 'All matching calls').total, 1);
  assert.equal(encryptionKeyPage(createEncryptionKeySnapshot([{ ...snapshot.keys[0], label: null }]), 'APCO25 key 42').total, 1);
  const calls = [];
  let response = { ok: true, status: 200, json: async () => snapshot };
  const fetchDocument = async (url, options) => { calls.push({ url, options }); return response; };
  assert.deepEqual(await requestEncryptionKeys(fetchDocument), snapshot);
  assert.equal(calls.at(-1).url, '/api/v1/admin/encryption-keys');
  assert.equal(calls.at(-1).options.method, 'GET');
  const value = Object.fromEntries(['label', 'enabled', 'protocol', 'algorithm_id', 'key_id', 'key_hex', 'scope']
    .map(field => [field, snapshot.keys[0][field]]));
  for (const method of ['POST', 'PUT', 'DELETE']) {
    const id = method === 'POST' ? '' : 'opaque key+id';
    assert.equal(await requestEncryptionKeys(fetchDocument, { method, id, value, revision: snapshot.revision }), snapshot);
    const call = calls.at(-1);
    assert.equal(call.options.headers['If-Match'], `"${snapshot.revision}"`);
    assert.equal(call.url, `/api/v1/admin/encryption-keys${id ? '/opaque%20key%2Bid' : ''}`);
    if (method === 'DELETE') assert.equal(call.options.body, undefined);
    else assert.deepEqual(JSON.parse(call.options.body), value);
  }
  const before = calls.length;
  await assert.rejects(requestEncryptionKeys(fetchDocument, { method: 'POST', value }), /Reload keys/);
  assert.equal(calls.length, before);
  const current = createEncryptionKeySnapshot(null, 'b'.repeat(64));
  response = { ok: false, status: 409, json: async () => current };
  await assert.rejects(requestEncryptionKeys(fetchDocument, { method: 'PUT', id: 'key', value, revision: snapshot.revision }),
    error => error.status === 409 && error.current === current);
  response = { ok: false, status: 503, json: async () => ({ error: { message: 'Keys could not be saved. Try again.' } }) };
  await assert.rejects(requestEncryptionKeys(fetchDocument, { method: 'PUT', id: 'key', value, revision: snapshot.revision }),
    error => error.status === 503 && /Try again/.test(error.message));
  response = { ok: true, status: 200, json: async () => ({ invalid: 'result' }) };
  await assert.rejects(requestEncryptionKeys(fetchDocument), error => error.code === 'invalid_response');
  console.log('Encryption key catalog, complete-set search, decimal/hex IDs, plaintext request, revision and failure contracts passed.');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
