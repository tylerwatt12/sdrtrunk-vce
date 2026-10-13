'use strict';

const path = require('node:path');
const { pathToFileURL } = require('node:url');

function model(id = 'voice-model-1') {
  return { id, input_sample_rate: 8000, output_sample_rate: 16000,
    codecs: ['IMBE', 'AMBE_PLUS_2'], sha256: 'd'.repeat(64) };
}

async function openVoiceEnhancementApp(page, options = {}) {
  const file = path.resolve(__dirname, '../../../../../../stats-web/assets/core/preference-schema.js');
  const preferences = structuredClone((await import(pathToFileURL(file).href)).defaults);
  preferences.appearance.theme = options.theme || 'light';
  const state = { current: { revision: 'a'.repeat(64), enabled: false, status: 'ready', model: model(),
    ...options.snapshot }, writes: [], nextFailure: null, held: null, loadFailure: false };
  const initialRead = options.holdStatus ? new Promise(resolve => { state.releaseStatus = resolve; }) : null;
  state.holdWrite = () => {
    let release;
    state.held = new Promise(resolve => { release = resolve; });
    return () => { release(); state.held = null; };
  };
  await page.route('**/api/v1/**', async route => {
    const request = route.request(), pathname = new URL(request.url()).pathname;
    if (pathname === '/api/v1/auth/session') return route.fulfill({ json: { data: {
      configured: true, authenticated: true, username: 'fixture-admin', tier: 'admin', primary: true,
      csrf_token: 'test-only-csrf', capabilities: { dashboard: true, 'admin-settings': true,
        'admin-recordings': true, 'receiver-health': true, 'admin-users': true, 'admin-access': true, credits: true }
    } } });
    if (pathname === '/api/v1/me/preferences') return route.fulfill({ json: { revision: 1, preferences } });
    if (pathname === '/api/v1/status') return route.fulfill({ json: { data: {} } });
    if (pathname === '/api/v1/admin/voice-enhancement') {
      if (initialRead) await initialRead;
      if (state.loadFailure) return route.abort('failed');
      return route.fulfill({ json: state.current });
    }
    if (pathname.startsWith('/api/v1/admin/voice-enhancement/')) {
      state.writes.push({ action: pathname.split('/').at(-1), method: request.method(),
        headers: request.headers(), body: request.postDataBuffer() });
      if (state.held) await state.held;
      if (state.nextFailure) {
        const failure = state.nextFailure; state.nextFailure = null;
        return failure === 'network' ? route.abort('failed') : route.fulfill({ status: failure,
          json: { error: { code: 'invalid_model', message: 'The change could not be saved.' } } });
      }
      if (request.headers()['if-match'] !== `"${state.current.revision}"`) return route.fulfill({ status: 409, json: state.current });
      const action = pathname.split('/').at(-1);
      state.current = { ...state.current, revision: (state.writes.length % 2 ? 'b' : 'e').repeat(64),
        ...(action === 'enabled' ? { enabled: request.postDataJSON().value } : { status: 'ready', model: model('voice-model-2') }) };
      return route.fulfill({ json: state.current });
    }
    if (pathname === '/api/v1/admin/operational-preferences') return route.fulfill({ json: {
      revision: 'f'.repeat(64), settings: { patch_group_streaming_option: 'PATCH_GROUP', audio_record_format: 'MP3',
        mp3_setting: 'CBR_16', mp3_input_audio_format: 'SR_16000', mp3_normalize_audio: false,
        stats_logging_enabled: true, stats_detailed_history_enabled: false, stats_logging_retention_days: 30 },
      options: { patch_group_streaming_options: [], audio_record_formats: [],
        mp3_settings: [{ value: 'CBR_16', label: '16 kbps' }],
        mp3_input_audio_formats_by_setting: { CBR_16: [{ value: 'SR_16000', label: '16 kHz' }] },
        minimum_stats_logging_retention_days: 1, maximum_stats_logging_retention_days: 365 }
    } });
    return route.fulfill({ status: 404, json: { error: { message: 'Unavailable' } } });
  });
  await page.goto('/app.html?view=admin&tab=audio-quality');
  return state;
}

module.exports = { openVoiceEnhancementApp, model };
