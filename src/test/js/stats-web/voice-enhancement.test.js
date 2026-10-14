'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function main() {
  const modulePath = path.resolve(process.argv[2] || path.join(__dirname, '../../../../stats-web/assets/features/voice-enhancement.js'));
  const { decodeVoiceEnhancement, validateModelZip, requestVoiceEnhancement, MODEL_ZIP_MAX_BYTES } =
    await import(pathToFileURL(modulePath).href);
  const { callAudioDetailGroups, formatPagingToneSummary } = await import(pathToFileURL(path.join(path.dirname(modulePath), '../core/call-audio-details.js')).href);
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
    ['Paging tones', [['Source', 'Audio detection'], ['Tone 1', '593.75 Hz · 1020 ms · at 80 ms']]]
  ]);
  assert.deepEqual(callAudioDetailGroups({ audio_sample_rate: 8000, voice_enhancement: false, voice_model: 'stale' }),
    [['Audio', [['Sample rate', '8 kHz'], ['Voice enhancement', 'Off']]]]);
  assert.deepEqual(callAudioDetailGroups({ detected_paging_tones: [
    { ...tone, source: 'encoded_audio' }, { ...tone, frequency_hz: Number.NaN },
    { ...tone, duration_milliseconds: -1 }, { ...tone, confidence: 2 }, { ...tone, offset_milliseconds: null }
  ] }), []);
  assert.deepEqual(callAudioDetailGroups({}), []);
  const encoded = { source: 'encoded', method: 'ambe_tone_v1', tones: [
    { frequencies_hz: [697, 1477], code: 'DTMF_3', duration_milliseconds: 120 },
    { frequencies_hz: [600.2], code: 'TONE_600', duration_milliseconds: 1020.1, offset_milliseconds: 80.4 },
    { frequencies_hz: [], code: 'RESERVED_255', duration_milliseconds: 20 }
  ] };
  assert.deepEqual(formatPagingToneSummary(encoded), { source: 'Encoded', tones: [
    'DTMF 3 · 697 + 1477 Hz · 120 ms', 'TONE 600 · 600.2 Hz · 1020 ms · at 80 ms', 'RESERVED 255 · 20 ms'
  ] });
  assert.deepEqual(callAudioDetailGroups({ paging_tone_summary: encoded, detected_paging_tones: [tone] }), [
    ['Paging tones', [['Source', 'Encoded'], ['Tone 1', 'DTMF 3 · 697 + 1477 Hz · 120 ms'],
      ['Tone 2', 'TONE 600 · 600.2 Hz · 1020 ms · at 80 ms'], ['Tone 3', 'RESERVED 255 · 20 ms']]]
  ], 'Unified encoded details take precedence without duplicating historical detections.');
  assert.deepEqual(formatPagingToneSummary({ source: 'decoded_audio', method: 'p25_pcm_tone_v1', tones: [
    { frequencies_hz: [600], duration_milliseconds: 1000, confidence: .97 }
  ] }), { source: 'Audio detection', tones: ['593.75 Hz · 1000 ms'] });
  const nominal = { source: 'decoded_audio', method: 'p25_pcm_ambe_tone_v2', tones: [
    { frequencies_hz: [562.5], duration_milliseconds: 900, offset_milliseconds: 514, confidence: .99 }
  ] };
  assert.deepEqual(formatPagingToneSummary(nominal), { source: 'Audio detection',
    tones: ['562.5 Hz · 900 ms · at 514 ms'] });
  const old = { ...nominal, method: 'p25_pcm_tone_v1', tones: [{ ...nominal.tones[0], frequencies_hz: [566.379] }] };
  assert.deepEqual(formatPagingToneSummary(old), formatPagingToneSummary(nominal),
    'New and older tone reports show the same single allowed frequency.');
  assert.equal(old.tones[0].frequencies_hz[0], 566.379, 'Formatting does not rewrite retained evidence.');
  assert.deepEqual(formatPagingToneSummary({ ...old, tones: [{ ...old.tones[0], frequencies_hz: [390.625] }] }),
    { source: 'Audio detection', tones: ['375 Hz · 900 ms · at 514 ms'] });
  assert.equal(formatPagingToneSummary({ ...old, tones: [{ ...old.tones[0], frequencies_hz: [156.249] }] }), null);
  assert.deepEqual(callAudioDetailGroups({ detected_paging_tones: [
    { ...tone, method: 'p25_pcm_ambe_tone_v2', frequency_hz: 593.75 }
  ] }), [['Paging tones', [['Source', 'Audio detection'], ['Tone 1', '593.75 Hz · 1020 ms · at 80 ms']]]]);
  assert.deepEqual(formatPagingToneSummary({ source: 'encoded', tones: [
    { frequencies_hz: [343.75], code: 'HZ_343_75', duration_milliseconds: 120 }
  ] }), { source: 'Encoded', tones: ['343.75 Hz · 120 ms'] });
  assert.equal(formatPagingToneSummary({ ...encoded, source: 'unknown' }), null);
  assert.equal(formatPagingToneSummary({ source: 'decoded_audio', tones: [{ frequencies_hz: [], code: 'DTMF_3', duration_milliseconds: 20 }] }), null);
  assert.equal(formatPagingToneSummary({ ...encoded, tones: [
    { frequencies_hz: [NaN], duration_milliseconds: 20 }, { frequencies_hz: [600, 700, 800], duration_milliseconds: 20 },
    { frequencies_hz: [600], duration_milliseconds: 0 }, { frequencies_hz: [600], duration_milliseconds: 20, offset_milliseconds: -1 },
    { frequencies_hz: [600], duration_milliseconds: 20, confidence: 2 }
  ] }), null);
  assert.equal(formatPagingToneSummary({ ...encoded, tones: Array.from({ length: 100 }, () => encoded.tones[0]) }).tones.length, 32);
  console.log('Voice enhancement API, raw ZIP, conflict, cancellation, and measured audio detail contracts passed.');
}

main().catch(error => { console.error(error); process.exitCode = 1; });
