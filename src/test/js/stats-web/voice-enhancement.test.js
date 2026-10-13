'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function main() {
  const modulePath = path.resolve(process.argv[2] || path.join(__dirname, '../../../../stats-web/assets/features/voice-enhancement.js'));
  const { decodeVoiceEnhancement, validateModelZip, requestVoiceEnhancement, MODEL_ZIP_MAX_BYTES } =
    await import(pathToFileURL(modulePath).href);
  const { callAudioDetailGroups } = await import(pathToFileURL(path.join(path.dirname(modulePath), '../core/call-audio-details.js')).href);
  const revision = 'a'.repeat(64);
  const ready = { revision, enabled: false, status: 'ready', model: {
    id: 'voice-model-1', input_sample_rate: 8000, output_sample_rate: 16000,
    codecs: ['IMBE', 'AMBE_PLUS_2'], sha256: 'b'.repeat(64) } };
  assert.deepEqual(decodeVoiceEnhancement(ready), ready);
  assert.equal(decodeVoiceEnhancement({ revision, enabled: false, status: 'no_model', model: null }).model, null);
  assert.equal(decodeVoiceEnhancement({ ...ready, status: 'unavailable' }).status, 'unavailable');
  for (const value of [{ ...ready, revision: 'bad' }, { ...ready, model: null },
    { ...ready, model: { ...ready.model, output_sample_rate: 8000 } },
    { ...ready, model: { ...ready.model, codecs: ['IMBE'] } }]) {
    assert.throws(() => decodeVoiceEnhancement(value), /Reload/);
  }
  assert.match(validateModelZip(null), /Choose/);
  assert.match(validateModelZip({ name: 'weights.json', size: 32 }), /ZIP/);
  assert.match(validateModelZip({ name: 'model.zip', size: 0 }), /empty/);
  assert.match(validateModelZip({ name: 'model.zip', size: MODEL_ZIP_MAX_BYTES + 1 }), /16 MB/);
  assert.equal(validateModelZip({ name: 'MODEL.ZIP', size: MODEL_ZIP_MAX_BYTES }), '');

  const calls = [];
  let response = { ok: true, status: 200, json: async () => ready };
  const fetchDocument = async (url, options) => { calls.push({ url, options }); return response; };
  await requestVoiceEnhancement(fetchDocument);
  assert.equal(calls.at(-1).url, '/api/v1/admin/voice-enhancement');
  await requestVoiceEnhancement(fetchDocument, { action: 'enabled', revision, value: true });
  assert.equal(calls.at(-1).options.method, 'PUT');
  assert.equal(calls.at(-1).options.headers['If-Match'], `"${revision}"`);
  assert.deepEqual(JSON.parse(calls.at(-1).options.body), { value: true });
  const zip = new File([new Uint8Array([80, 75, 3, 4])], 'model.zip', { type: 'application/zip' });
  await requestVoiceEnhancement(fetchDocument, { action: 'model', revision, file: zip });
  assert.equal(calls.at(-1).url, '/api/v1/admin/voice-enhancement/model');
  assert.equal(calls.at(-1).options.method, 'POST');
  assert.equal(calls.at(-1).options.headers['Content-Type'], 'application/zip');
  assert.strictEqual(calls.at(-1).options.body, zip, 'Upload must retain the raw file body.');
  const beforeInvalid = calls.length;
  await assert.rejects(requestVoiceEnhancement(fetchDocument, { action: 'model', revision,
    file: { name: 'model.zip', size: MODEL_ZIP_MAX_BYTES + 1 } }), /16 MB/);
  await assert.rejects(requestVoiceEnhancement(fetchDocument, { action: 'enabled', revision: '', value: true }), /Reload/);
  assert.equal(calls.length, beforeInvalid, 'Rejected local inputs must not write to the server.');
  const changed = { ...ready, revision: 'c'.repeat(64), enabled: true };
  response = { ok: false, status: 409, json: async () => changed };
  await assert.rejects(requestVoiceEnhancement(fetchDocument, { action: 'enabled', revision, value: true }),
    error => error.status === 409 && error.current.revision === changed.revision);
  response = { ok: false, status: 422, json: async () => ({ error: { code: 'invalid_model', message: 'Choose a compatible model.' } }) };
  await assert.rejects(requestVoiceEnhancement(fetchDocument, { action: 'model', revision, file: zip }),
    error => error.status === 422 && error.code === 'invalid_model');
  const stalled = (_url, { signal }) => new Promise((_resolve, reject) => {
    const abort = () => reject(Object.assign(new Error('Aborted'), { name: 'AbortError' }));
    if (signal.aborted) abort(); else signal.addEventListener('abort', abort, { once: true });
  });
  await assert.rejects(requestVoiceEnhancement(stalled, { timeoutMs: 1 }), error => error.code === 'request_timeout');
  const controller = new AbortController();
  const aborted = requestVoiceEnhancement(stalled, { signal: controller.signal }); controller.abort();
  await assert.rejects(aborted, error => error.name === 'AbortError');

  const tone = { frequency_hz: 600.2, duration_milliseconds: 1020.1, offset_milliseconds: 80.4,
    confidence: 0.99, source: 'decoded_audio', method: 'p25_pcm_tone_v1' };
  assert.deepEqual(callAudioDetailGroups({ audio_sample_rate: 16000, voice_enhancement: true,
    voice_model: 'voice-model-1', detected_paging_tones: [tone] }), [
    ['Audio', [['Sample rate', '16 kHz'], ['Voice enhancement', 'On'], ['Voice model', 'voice-model-1']]],
    ['Detected tones', [['Source', 'Audio detection'], ['Tone 1', '600.2 Hz · 1020 ms · at 80 ms']]]
  ]);
  assert.deepEqual(callAudioDetailGroups({ audio_sample_rate: 8000, voice_enhancement: false, voice_model: 'stale' }),
    [['Audio', [['Sample rate', '8 kHz'], ['Voice enhancement', 'Off']]]]);
  assert.deepEqual(callAudioDetailGroups({ detected_paging_tones: [
    { ...tone, source: 'encoded_audio' }, { ...tone, frequency_hz: Number.NaN },
    { ...tone, duration_milliseconds: -1 }, { ...tone, confidence: 2 }, { ...tone, offset_milliseconds: null }
  ] }), []);
  assert.deepEqual(callAudioDetailGroups({}), []);
  console.log('Voice enhancement API, raw ZIP, conflict, cancellation, and measured audio detail contracts passed.');
}

main().catch(error => { console.error(error); process.exitCode = 1; });
