'use strict';

const path = require('node:path');
const { pathToFileURL } = require('node:url');

const systemKey = 'p25:00001:001';
const channelId = '00000000-0000-4000-8000-000000000017';
const started = Date.parse('2026-09-28T10:08:42Z');
const recording = {
  id: 17, start_ms: started, end_ms: started + 42_000, duration_ms: 42_000,
  size_bytes: 184320, alias_list_id: 1, system_key: systemKey,
  system_name: 'Metro Public Safety', channel_id: channelId, channel_name: 'North Ridge Channel',
  talkgroup_id: 1201, talkgroup_alias: 'Fire Dispatch',
  talkgroup_description: 'County fire dispatch operations', talkgroup_group: 'Fire',
  source_id: 30914, source_alias: 'Engine 4', source_description: 'Fire engine response crew',
  source_group: 'Field units', source_ota_alias: 'ENG 4',
  source_home_wacn: 1, source_home_system_id: 1, source_home_id: 30914,
  target_home_wacn: 1, target_home_system_id: 1, target_home_id: 1201,
  site_name: 'North Ridge', call_type: 'GROUP', protocol: 'APCO25_PHASE2', voice_type: 'CLEAR',
  frequency_hz: 851_012_500, timeslot: 1, nac: 293, wacn: 1, system_id: 1, rfss_id: 1, site_id: 2,
  audio_from: { wacn: 1, system_id: 1, rfss_id: 1, site_id: 2 },
  also_received_on: [{ wacn: 1, system_id: 1, rfss_id: 1, site_id: 3 }],
  transcript_excerpt: 'Engine 4 has arrived at staging.',
  radio_system_entity_ref: { kind: 'radio_system', key: systemKey },
  channel_entity_ref: { kind: 'channel', key: channelId },
  target_entity_ref: { kind: 'talkgroup', radio_system_key: systemKey, identity_key: 'v1-g-00001-001-1201' },
  source_entity_ref: { kind: 'radio', radio_system_key: systemKey, identity_key: 'v1-r-00001-001-30914' }
};

const transcript = { status: 'COMPLETE', text: 'Dispatch, Engine 4 has arrived at staging. All crews are accounted for.',
  stored_at_ms: started + 60_000 };
const scanLists = [{ id: 1, name: 'County Fire and Emergency Medical Services',
  description: 'Fire dispatch and medical response', default: true, enabled: true }];
const liveCall = {
  call_id: 'dock-live-1', started_at_ms: started, completed_at_ms: started + 42_000,
  duration_ms: 42_000, audio_url: '/api/v1/calls/dock-live-1/audio', scan_list_ids: [1],
  playback_target: { kind: 'talkgroup', key: 'metro:tg:1201', label: 'Fire Dispatch', radio_system_key: systemKey },
  system: 'Metro Public Safety', channel: 'North Ridge Channel', decoder: 'P25 Phase 2',
  target_alias: 'Fire Dispatch', target_id: 1201, target_form: 'TALKGROUP',
  source_alias: 'Engine 4', source_id: 30914, source_form: 'RADIO',
  frequency_hz: 851_012_500, timeslot: 1
};

async function stubAudio(page) {
  await page.addInitScript(() => {
    window.audioTest = { recordings: [], liveSources: [], contexts: [] };
    class RecordingAudio extends EventTarget {
      constructor() { super(); this.paused = true; this.currentTime = 0; this.volume = 1; this._src = ''; }
      get duration() { return this._src ? 42 : Number.NaN; }
      get src() { return this._src; }
      set src(value) { this._src = new URL(value, location.href).href; }
      play() {
        this.paused = false;
        window.audioTest.recordings.push({ action: 'play', src: this.src });
        this.dispatchEvent(new Event('play'));
        return Promise.resolve();
      }
      pause() {
        this.paused = true;
        window.audioTest.recordings.push({ action: 'pause', src: this.src });
        this.dispatchEvent(new Event('pause'));
      }
      removeAttribute(name) { if (name === 'src') this._src = ''; }
      load() {}
    }
    window.Audio = RecordingAudio;
    class Context {
      constructor() {
        this.currentTime = 0; this.state = 'suspended'; this.destination = {};
        window.audioTest.contexts.push(this);
      }
      resume() { this.state = 'running'; return Promise.resolve(); }
      suspend() { this.state = 'suspended'; return Promise.resolve(); }
      close() { this.state = 'closed'; return Promise.resolve(); }
      createGain() { return { gain: { value: 1 }, connect() {}, disconnect() {} }; }
      createAnalyser() { return { frequencyBinCount: 256, connect() {}, disconnect() {},
        getByteFrequencyData(values) { values.fill(0); } }; }
      decodeAudioData() { return Promise.resolve({ duration: 42, sampleRate: 16000 }); }
      createBufferSource() {
        const source = { active: false, connect() {}, disconnect() {},
          start() { this.active = true; }, stop() { this.active = false; } };
        window.audioTest.liveSources.push(source);
        return source;
      }
    }
    window.AudioContext = Context;
    window.webkitAudioContext = Context;
  });
}

async function openAudioApp(page, options = {}) {
  const file = path.resolve(__dirname, '../../../../../../stats-web/assets/core/preference-schema.js');
  let preferences = structuredClone((await import(pathToFileURL(file).href)).defaults);
  preferences.appearance.theme = options.theme || 'light';
  if (Number.isFinite(options.volume)) preferences.playback.volume = options.volume;
  const rows = options.calls || Array.from({ length: 30 }, (_, index) => ({ ...recording,
    id: 17 + index, start_ms: started + index * 60_000, end_ms: started + index * 60_000 + 42_000,
    source_id: 30914 + index, source_alias: `Engine ${4 + index}` }));
  const state = { requests: [], preferenceWrites: [], preferenceRevision: 1, feedRequests: 0, feedDelivered: false,
    feedCallsEnabled: options.feedCallsEnabled !== false, transcript: options.transcript || transcript,
    session: { configured: true, authenticated: options.authenticated !== false,
      username: options.authenticated === false ? null : 'fixture-listener',
      tier: options.admin === false ? 'user' : 'admin', primary: options.admin !== false,
      csrf_token: 'test-only-csrf', capabilities: { 'web-access': true, dashboard: true, radio: true,
        live: true, 'call-audio': true, recordings: true, 'admin-tuners': true, 'admin-channels': true,
        'admin-aliases': true, 'admin-recordings': options.admin !== false,
        'admin-settings': options.admin !== false, 'user-settings': true, credits: true,
        ...options.capabilities } } };
  await stubAudio(page);
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url());
    const p = url.pathname;
    state.requests.push({ path: p, method: route.request().method(), query: Object.fromEntries(url.searchParams) });
    const fulfill = (data) => route.fulfill({ json: { data } });
    if (p === '/api/v1/auth/session') return fulfill(state.session);
    if (p === '/api/v1/me/preferences') {
      if (route.request().method() !== 'GET') {
        const write = route.request().postDataJSON();
        state.preferenceWrites.push(write);
        preferences = write.preferences || write;
        state.preferenceRevision++;
      }
      return route.fulfill({ json: { revision: state.preferenceRevision, preferences } });
    }
    if (p === '/api/v1/status') return fulfill({});
    if (p === '/api/v1/scan-lists') return fulfill({ revision: 1, scan_lists: scanLists });
    if (p === '/api/v1/calls/feed') {
      state.feedRequests++;
      const calls = !state.feedDelivered && state.feedCallsEnabled ? [liveCall,
        { ...liveCall, call_id: 'dock-live-2', started_at_ms: started + 60_000,
          completed_at_ms: started + 102_000, source_alias: 'Engine 5', source_id: 30915 }] : [];
      if (calls.length) state.feedDelivered = true;
      return fulfill({ cursor: '2', reset: false, calls });
    }
    if (/\/audio$/.test(p)) return route.fulfill({ contentType: 'audio/wav', body: Buffer.alloc(44) });
    if (p === '/api/v1/recordings/status') return fulfill({ mode: 'MANAGED', available: true, has_calls: true });
    if (p === '/api/v1/recordings/calls') {
      const matching = url.searchParams.get('sort') === 'asc' ? rows.filter((row) => row.start_ms >=
        Number(url.searchParams.get('from_ms') || 0)) : rows;
      const offset = Number(url.searchParams.get('cursor') || 0);
      const limit = Number(url.searchParams.get('limit') || 25);
      return fulfill({ calls: matching.slice(offset, offset + limit),
        next_cursor: offset + limit < matching.length ? String(offset + limit) : null, total: null });
    }
    if (/^\/api\/v1\/recordings\/calls\/\d+$/.test(p)) return fulfill({
      call: rows.find((row) => row.id === Number(p.split('/').at(-1))) || rows[0], transcription: state.transcript });
    if (/\/transcription\/retry$/.test(p)) {
      state.transcript = { status: 'PENDING', text: null };
      return fulfill(state.transcript);
    }
    if (p === '/api/v1/channel-catalog' || p === '/api/v1/admin/channels') return fulfill({ revision: 1, channels: [] });
    if (p === '/api/v1/dashboard') return fulfill({ last_seen_ms: started, call_activity: { totals: {}, buckets: [] },
      top_destinations: [], top_sources: [], source_activity_24h: [] });
    if (p === '/api/v1/tuners') return fulfill({ revision: 1, tuners: [] });
    if (p === '/api/v1/recordings/suggestions') return fulfill({ suggestions: [] });
    return fulfill({ rows: [], limit: 25, offset: 0, has_more: false, next_offset: null, total_count: 0 });
  });
  await page.goto(`/app.html?view=${options.view || 'recordings'}`);
  return state;
}

module.exports = { openAudioApp, recording, transcript, scanLists, liveCall };
