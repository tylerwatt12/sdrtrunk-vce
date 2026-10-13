// Reuse map: Activity semantic summaries and playback's shared disclosures use
// the same tone values while keeping encoded observations and audio detection distinct.
export function formatPagingToneSummary(summary) {
  if (!summary || !['encoded', 'decoded_audio'].includes(summary.source) || !Array.isArray(summary.tones)) return null;
  const positive = value => typeof value === 'number' && Number.isFinite(value) && value > 0;
  const nonnegative = value => typeof value === 'number' && Number.isFinite(value) && value >= 0;
  const tones = [];
  for (const tone of summary.tones.slice(0, 32)) {
    if (!tone || !Array.isArray(tone.frequencies_hz) || tone.frequencies_hz.length > 2 ||
        !tone.frequencies_hz.every(positive) || !positive(tone.duration_milliseconds) ||
        tone.offset_milliseconds !== undefined && !nonnegative(tone.offset_milliseconds) ||
        tone.confidence !== undefined && (!nonnegative(tone.confidence) || tone.confidence > 1)) continue;
    const code = typeof tone.code === 'string' ? tone.code.trim().slice(0, 80) : '';
    if (!tone.frequencies_hz.length && (summary.source !== 'encoded' || !code)) continue;
    const values = [];
    if (summary.source === 'encoded' && code) values.push(code.replace(/_/g, ' '));
    if (tone.frequencies_hz.length) values.push(tone.frequencies_hz.map(value => Number(value.toFixed(1))).join(' + ') + ' Hz');
    values.push(`${Math.round(tone.duration_milliseconds)} ms`);
    if (tone.offset_milliseconds !== undefined) values.push(`at ${Math.round(tone.offset_milliseconds)} ms`);
    tones.push(values.join(' · '));
  }
  return tones.length ? { source: summary.source === 'encoded' ? 'Encoded' : 'Audio detection', tones } : null;
}

export function callAudioDetailGroups(call = {}) {
  const audio = [];
  if ([8000, 16000].includes(call.audio_sample_rate)) audio.push(['Sample rate', `${call.audio_sample_rate / 1000} kHz`]);
  if (typeof call.voice_enhancement === 'boolean') audio.push(['Voice enhancement', call.voice_enhancement ? 'On' : 'Off']);
  if (call.voice_enhancement === true && typeof call.voice_model === 'string' && call.voice_model.trim()) {
    audio.push(['Voice model', call.voice_model]);
  }
  const groups = audio.length ? [['Audio', audio]] : [];
  const tones = Array.isArray(call.detected_paging_tones) ? call.detected_paging_tones.filter(tone =>
    tone && tone.source === 'decoded_audio' && tone.method === 'p25_pcm_tone_v1' &&
    typeof tone.frequency_hz === 'number' && Number.isFinite(tone.frequency_hz) && tone.frequency_hz > 0 &&
    typeof tone.offset_milliseconds === 'number' && Number.isFinite(tone.offset_milliseconds) && tone.offset_milliseconds >= 0 &&
    typeof tone.duration_milliseconds === 'number' && Number.isFinite(tone.duration_milliseconds) && tone.duration_milliseconds > 0 &&
    typeof tone.confidence === 'number' && Number.isFinite(tone.confidence) && tone.confidence >= 0 && tone.confidence <= 1
  ).slice(0, 32) : [];
  const summary = formatPagingToneSummary(call.paging_tone_summary) || formatPagingToneSummary({
    source: 'decoded_audio', tones: tones.map(tone => ({ ...tone, frequencies_hz: [tone.frequency_hz] }))
  });
  if (summary) {
    const rows = [['Source', summary.source]];
    summary.tones.forEach((tone, index) => rows.push([`Tone ${index + 1}`, tone]));
    groups.push(['Paging tones', rows]);
  }
  return groups;
}
