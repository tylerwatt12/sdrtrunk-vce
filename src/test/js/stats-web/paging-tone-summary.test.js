'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname,
  '../../../../stats-web/assets/core/call-audio-details.js'), 'utf8');
const { formatPagingToneSummary } = vm.runInNewContext(source.replace(/^export /gm, '') + '\n({formatPagingToneSummary});');
const single = frequency => ({ frequencies_hz: [frequency], offset_milliseconds: 25, duration_milliseconds: 500 });

for (const frequency of [375, 382.3, 3487]) {
  const summary = formatPagingToneSummary({ source: 'decoded_audio', method: 'analog_pcm_tone_v1', tones: [single(frequency)] });
  assert.equal(summary.source, 'Audio detection');
  assert.equal(summary.tones[0], `${frequency} Hz · 500 ms · at 25 ms`, 'Analog nominal evidence keeps its stored frequency');
}
for (const method of ['p25_pcm_tone_v1', 'p25_pcm_ambe_tone_v2', undefined]) {
  const summary = formatPagingToneSummary({ source: 'decoded_audio', method, tones: [single(382.3)] });
  assert.equal(summary.tones[0], '375 Hz · 500 ms · at 25 ms', 'P25 and legacy measured-frequency presentation retain AMBE mapping');
}
assert.equal(formatPagingToneSummary({ source: 'decoded_audio', method: 'analog_pcm_tone_v1', tones: [single(100)] }), null,
  'Subaudible CTCSS values remain outside call tone details');
const encoded = formatPagingToneSummary({ source: 'encoded', method: 'ambe_tone_v1', tones: [{ ...single(375), code: 'HZ_375_00' }] });
assert.equal(encoded.source, 'Encoded');
assert.equal(encoded.tones[0], '375 Hz · 500 ms · at 25 ms');
const dtmf = formatPagingToneSummary({ source: 'decoded_audio', method: 'analog_pcm_tone_v1',
  tones: [{ frequencies_hz: [697, 1209], code: 'DTMF_1', duration_milliseconds: 100 }] });
assert.equal(dtmf.tones[0], 'DTMF 1 · 697 + 1209 Hz · 100 ms');

console.log('Analog paging nominal, legacy P25, encoded tone and DTMF summary contracts passed.');
