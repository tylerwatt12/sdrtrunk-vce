// Reuse map: playback's shared disclosure groups and flat fact lists display
// supplied audio measurements without interpreting them as codec metadata.
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
  if (tones.length) {
    const rows = [['Source', 'Audio detection']];
    tones.forEach((tone, index) => rows.push([`Tone ${index + 1}`,
      `${Math.round(tone.frequency_hz * 10) / 10} Hz · ${Math.round(tone.duration_milliseconds)} ms · at ${Math.round(tone.offset_milliseconds)} ms`]));
    groups.push(['Detected tones', rows]);
  }
  return groups;
}
