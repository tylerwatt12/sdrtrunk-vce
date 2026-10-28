'use strict';

const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function openPagingToneApp(page, options = {}) {
  const file = path.resolve(__dirname, '../../../../../../stats-web/assets/core/preference-schema.js');
  const preferences = structuredClone((await import(pathToFileURL(file).href)).defaults);
  preferences.appearance.theme = options.theme || 'light';
  const state = {};
  const paging = state.pagingTone = { current: { revision: '1'.repeat(64), enabled: true, status: 'ready',
    ...options.pagingSnapshot }, writes: [], nextFailure: null, held: null, loadFailure: false };
  const pagingRead = options.holdPagingStatus ? new Promise(resolve => { paging.releaseStatus = resolve; }) : null;
  paging.holdWrite = () => {
    let release; paging.held = new Promise(resolve => { release = resolve; });
    return () => { release(); paging.held = null; };
  };
  const filtering = state.callFiltering = { current: { revision: 'a'.repeat(64), enabled: false,
    minimum_usable_voice_percent: null, ...options.callFilteringSnapshot }, writes: [], nextFailure: null,
    held: null, loadFailure: false };
  const filteringRead = options.holdCallFilteringStatus ? new Promise(resolve => { filtering.releaseStatus = resolve; }) : null;
  filtering.holdWrite = () => {
    let release; filtering.held = new Promise(resolve => { release = resolve; });
    return () => { release(); filtering.held = null; };
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
    if (pathname === '/api/v1/admin/call-filtering') {
      if (request.method() === 'GET') {
        if (filteringRead) await filteringRead;
        if (filtering.loadFailure) return route.abort('failed');
        return route.fulfill({ json: filtering.current });
      }
      filtering.writes.push({ method: request.method(), headers: request.headers(), body: request.postDataJSON() });
      if (filtering.held) await filtering.held;
      if (filtering.nextFailure) {
        const failure = filtering.nextFailure; filtering.nextFailure = null;
        return failure === 'network' ? route.abort('failed') : route.fulfill({ status: failure,
          json: { error: { message: 'Call filtering settings could not be saved. Try again.' } } });
      }
      if (request.headers()['if-match'] !== `"${filtering.current.revision}"`) return route.fulfill({ status: 409, json: filtering.current });
      filtering.current = { revision: (filtering.writes.length % 2 ? 'c' : 'd').repeat(64), ...request.postDataJSON() };
      return route.fulfill({ json: filtering.current });
    }
    if (pathname === '/api/v1/admin/paging-tone-detection') {
      if (pagingRead) await pagingRead;
      if (paging.loadFailure) return route.abort('failed');
      return route.fulfill({ json: paging.current });
    }
    if (pathname === '/api/v1/admin/paging-tone-detection/enabled') {
      paging.writes.push({ method: request.method(), headers: request.headers(), body: request.postDataBuffer() });
      if (paging.held) await paging.held;
      if (paging.nextFailure) {
        const failure = paging.nextFailure; paging.nextFailure = null;
        return failure === 'network' ? route.abort('failed') : route.fulfill({ status: failure,
          json: { error: { message: 'Paging-tone detection could not be saved. Try again.' } } });
      }
      if (request.headers()['if-match'] !== `"${paging.current.revision}"`) return route.fulfill({ status: 409, json: paging.current });
      paging.current = { revision: (paging.writes.length % 2 ? '3' : '4').repeat(64),
        enabled: request.postDataJSON().value, status: 'ready' };
      return route.fulfill({ json: paging.current });
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

module.exports = { openPagingToneApp };
