'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { recording, transcript, scanLists, liveCall } = require('./audio-dock-app.cjs');
const root = path.resolve(__dirname, '../../../../../..');
const now = Date.parse('2026-10-01T12:00:00Z');
const systemKey = 'p25:00001:001';
const channelId = '11111111-1111-4111-8111-111111111111';
const groupKey = 'v1-g-00001-001-1201';
const radioKey = 'v1-r-00001-001-30914';
const channelName = 'North County Simulcast';
const systemName = 'County Public Safety and Regional Emergency Services';
const list = { id: 1, alias_list_id: 1, name: 'County Public Safety', family: 'P25',
  alias_count: 2, assigned_channel_count: 1 };
const channel = { configuration_id: channelId, protocol: 'P25', protocol_id: 'p25-phase1',
  protocol_label: 'P25 Phase 1', channel_kind: 'TRUNKED', name: channelName,
  system: systemName, system_name: systemName, site: 'North', site_name: 'North',
  alias_list_id: 1, alias_list_name: list.name, frequencies_hz: [851012500],
  processing_state: 'RUNNING', editable: true, auto_start_order: 1,
  radio_system_key: systemKey, radio_system_entity_ref: { kind: 'radio_system', key: systemKey },
  capabilities: { quality: true, frequencies: true, group_identities: true, radios: true, activity: true },
  logical_call_count: 1487, recorded_logical_call_count: 128, encrypted_logical_call_count: 12,
  stream_submitted_logical_call_count: 128, first_seen_ms: now - 86400000, last_seen_ms: now };
const totals = { logical_call_count: 1487, recorded_logical_call_count: 128,
  channel_observation_count: 1802, stream_submitted_logical_call_count: 128,
  encrypted_logical_call_count: 12, active_observation_count: 1317, join_observation_count: 360,
  denial_observation_count: 5, register_observation_count: 328 };
const identity = { radio_system_key: systemKey, protocol: 'P25', wacn: 1, system_id: 1,
  system_name: systemName, radio_system_entity_ref: { kind: 'radio_system', key: systemKey },
  first_seen_ms: now - 86400000, last_seen_ms: now, alias_lists: [{ id: 1, name: list.name }],
  channels: 1, talkgroups: 180, radios: 1004, affiliated_radios: 291, channel_names: channelName,
  capabilities: { current_affiliations: true, radio_channel_presence: true,
    group_identities: true, radios: true, activity: true }, ...totals };
const group = { ...identity, native_id: 1201, group_identity_kind: 'talkgroup',
  alias_name: 'Fire Dispatch', alias_group: 'Fire', radios: 88, affiliated_radios: 9, affiliated_channels: 1 };
const radio = { ...identity, native_id: 30914, alias_name: 'Engine 4', groups: 4,
  affiliated_talkgroup_id: 1201, affiliated_talkgroup_alias_name: 'Fire Dispatch',
  affiliated_talkgroup_entity_ref: { kind: 'talkgroup', radio_system_key: systemKey, identity_key: groupKey } };
const aliases = [{ alias_id: 101, alias_list_id: 1, name: 'Fire Dispatch', group: 'Fire',
  matcher_type: 'TALKGROUP', identifier_display: 'TG 1201' },
{ alias_id: 102, alias_list_id: 1, name: 'Engine 4', group: 'Field units',
  matcher_type: 'RADIO', identifier_display: 'Radio 30914' }];
const quality = { ...channel, decode_health_pct: 98.6, signal_dbfs: -38.5,
  average_signal_dbfs: -39.2, minimum_signal_dbfs: -42, maximum_signal_dbfs: -37,
  sample_count: 12, quality_frequency_hz: 851012500, frequency_hz: 851012500,
  last_observed_ms: now, series: Array.from({ length: 12 }, (_, index) => ({
    time_ms: now - (11 - index) * 30000, last_observed_ms: now - (11 - index) * 30000,
    decode_health_pct: 96 + index / 5, average_signal_dbfs: -39, minimum_signal_dbfs: -42,
    maximum_signal_dbfs: -37, sample_count: 1, frequency_hz: 851012500 })) };
const pageOf = (rows, limit = 100) => ({ rows, total_count: rows.length, limit,
  offset: 0, has_more: false, next_offset: null });
const operational = { revision: 'a'.repeat(64), settings: {
  patch_group_streaming_option: 'PATCH_GROUP', audio_record_format: 'MP3', mp3_setting: 'CBR_16',
  mp3_input_audio_format: 'SR_16000', mp3_normalize_audio: false, stats_logging_enabled: true,
  stats_detailed_history_enabled: true, stats_logging_retention_days: 30 }, options: {
  patch_group_streaming_options: [{ value: 'PATCH_GROUP', label: 'Patch Group' }, { value: 'TALKGROUPS', label: 'Individual Talkgroups' }],
  audio_record_formats: [{ value: 'MP3', label: 'MP3' }, { value: 'WAVE', label: 'WAV' }],
  mp3_settings: [{ value: 'CBR_16', label: '16 kbps' }, { value: 'VBR_5', label: 'Variable' }],
  mp3_input_audio_formats_by_setting: { CBR_16: [{ value: 'SR_16000', label: '16 kHz' }], VBR_5: [{ value: 'SR_44100', label: '44.1 kHz' }] },
  minimum_stats_logging_retention_days: 1, maximum_stats_logging_retention_days: 365 } };
const stream = { configuration_id: 'county-calls', name: 'County Calls', provider: 'BROADCASTIFY_CALL',
  provider_label: 'Broadcastify Calls', enabled: true, state: 'CONNECTED', state_label: 'Connected',
  queued: 3, sent: 128, aged_off: 0, errors: 0, last_error: null, attention: false };
const health = { started_at_ms: now - 3600000, generated_at_ms: now,
  summary: { severity: 'healthy', active_count: 0, warning_count: 0, critical_count: 0 },
  active: [], resolved: [], measurements: [{ id: 'tuners', title: 'Tuners', rows: [
    { severity: 'healthy', scope: 'Airspy R2', label: 'Active channels', value: 1, unit: 'channels' } ] }] };
const applicationLogEntries = [
  ['INFO', 'County receiver started', ''],
  ['WARN', 'County tuner temporarily unavailable', ''],
  ['ERROR', 'Call upload failed', 'java.io.IOException: temporary network failure\n' +
    '\tat receiver.upload(Upload.java:17)\nCaused by: Connection reset']
].map(([level, message, details], index) => {
  const time = new Date(now + index * 1_000).toISOString();
  return { id: String(index + 1), time, level, source: 'io.github.dsheirer.receiver.Receiver',
    message, details, text: `${time} ${level} Receiver - ${message}${details ? `\n${details}` : ''}` };
});
const applicationLog = { log: 'current', file_name: 'sdrtrunk_app.log', available: true,
  entries: applicationLogEntries, updated_at: now + 2_000, max_entries: 500,
  truncated: false, latest_id: '3', gap: false, change_reason: '' };
const previousApplicationLog = { ...applicationLog, log: 'previous', file_name: null,
  available: false, entries: [], latest_id: null };
const liveSnapshot = { revision: 1, tables: [{ table_id: channelId, configuration_id: channelId,
  title: channelName, system_name: systemName, site_name: 'North', channel_running: true, rows: [
    { key: 'county-control', configuration_id: channelId, lcn: 1, frequency_hz: 851012500,
      status: 'IDLE', tags: ['CURRENT_CONTROL'], role: 'CURRENT_CONTROL', activation_order: 1 },
    { key: 'county-voice', configuration_id: channelId, lcn: 2, frequency_hz: 851037500,
      status: 'CALL', tags: ['VOICE'], activation_order: 2, talkgroup: 1201, talkgroup_alias: 'Fire Dispatch',
      source: 30914, source_alias: 'Engine 4' } ] }] };
const policies = [...fs.readFileSync(path.join(root, 'src/main/java/io/github/dsheirer/web/auth/WebCapability.java'), 'utf8')
  .matchAll(/^\s+[A-Z_]+\("([^"]+)", "([^"]+)", AccessTier\.(PUBLIC|USER|ADMIN)(, false)?\)/gm)]
  .map(([, id, display_name, tier, fixed]) => ({ id, display_name, required_tier: tier.toLowerCase(),
    default_tier: tier.toLowerCase(), configurable: !fixed }));

function setting(id, label, group, kind, value, overrides = {}) {
  return { id, label, group, kind, value, scope: 'tuner', availability: 'live', editable: true,
    dependencies: [], ...overrides };
}

function tuner(overrides = {}) {
  return {
    id: 'tuner-a', name: 'Airspy R2', tuner_class: 'AIRSPY', tuner_type: 'AIRSPY_R820T',
    status: 'ENABLED', available: true, operator_state: 'live', transition: null,
    channel_count: 0, frequency_hz: 851_012_500, configured_frequency_hz: 851_012_500,
    sample_rate_hz: 10_000_000, configured_sample_rate_hz: 10_000_000,
    stopped_channels: [], restore_result: null,
    planner: { model: 'airspy', rate_hz: 10_000_000 },
    spectrum_target_id: null, spectrum_available: false,
    device_group: { id: 'group-a', kind: 'single', role: 'member' },
    settings: [setting('lna_gain', 'LNA gain', 'gain', 'integer', 8,
      { minimum: 0, maximum: 15, step: 1, unit: 'dB' })],
    ...overrides
  };
}

function operatorSettings(overrides = {}) {
  const values = [
    setting('lna_gain', 'LNA gain', 'gain', 'integer', 8,
      { minimum: 0, maximum: 15, step: 1, unit: 'dB' }),
    setting('automatic_ppm', 'Automatic PPM', 'calibration', 'boolean', false),
    setting('frequency_correction_ppm', 'Frequency correction', 'calibration', 'decimal', 0,
      { minimum: -200, maximum: 200, step: 1, unit: 'ppm',
        dependencies: [{ setting_id: 'automatic_ppm', equals: false }],
        unavailable_reason: 'Turn off Auto PPM' }),
    setting('minimum_frequency_mhz', 'Minimum frequency', 'frequency', 'decimal', 24,
      { minimum: 24, maximum: 1800, step: 0.00001, unit: 'MHz' }),
    setting('maximum_frequency_mhz', 'Maximum frequency', 'frequency', 'decimal', 1800,
      { minimum: 24, maximum: 1800, step: 0.00001, unit: 'MHz' }),
    setting('reset_frequency_extents', 'Reset frequency limits', 'frequency', 'action', null),
    setting('frequency_mhz', 'Center frequency', 'frequency', 'decimal', 851.0125,
      { minimum: 24, maximum: 1800, step: 0.00001, unit: 'MHz',
        dependencies: [{ setting_id: 'center_frequency_locked', equals: false }],
        unavailable_reason: 'Unlock center' }),
    setting('center_frequency_locked', 'Lock center', 'frequency', 'boolean', false),
    setting('sample_rate', 'Sample rate', 'frequency', 'choice', '10 MHz',
      { options: [{ value: '10 MHz', label: '10.00 MHz' }, { value: '2.5 MHz', label: '2.50 MHz' }],
        availability: 'setup', unavailable_reason: 'Use Setup' }),
    setting('bias_t', 'Bias T', 'hardware', 'boolean', false)
  ];
  return values.map((value) => value.id in overrides ? { ...value, ...overrides[value.id] } : value);
}

function operatorTuner(overrides = {}) {
  return tuner({ settings: operatorSettings(), ...overrides });
}


function copy(index, selected) {
  return {
    copy_index: index, selected, decoder: 'P25 Phase 1',
    channel_name: selected ? 'Cuyahoga Simulcast' : 'Elyria',
    configuration_ref: index, frequency_hz: selected ? 851_012_500 : 852_012_500, timeslot: 1,
    wacn: 1, system: 1, rfss: 1, site: selected ? 1 : 6,
    start_timestamp: 1_700_000_000_000 + (selected ? 0 : 48),
    end_timestamp: 1_700_000_003_600 - (selected ? 0 : 10),
    duration_milliseconds: selected ? 3600 : 3542,
    expected_frame_count: 180, observed_frame_count: selected ? 173 : 170,
    usable_frame_count: selected ? 167 : 164,
    decoded_frame_count: selected ? 169 : 166,
    repeated_frame_count: selected ? 2 : 3,
    concealed_frame_count: selected ? 4 : 5,
    missing_frame_count: selected ? 9 : 11,
    fec_error_count: selected ? 3 : 6,
    fec_protected_bit_count: 10_000,
    quality_percent: selected ? 92.8 : 91.1,
    missing_and_concealed_rate: selected ? 0.072 : 0.089,
    repeated_frame_rate: selected ? 0.011 : 0.017,
    normalized_fec_error_rate: selected ? 0.0003 : 0.0006,
    retained_audio_sample_count: selected ? 28_800 : 28_336,
    ingress_loss: false, audio_truncated: false,
    overlap: {
      overlap_milliseconds: selected ? 3600 : 3542,
      shorter_copy_overlap_percent: 100,
      selected_copy_coverage_percent: selected ? 100 : 98.4,
      start_offset_from_selected_milliseconds: selected ? 0 : 48,
      end_offset_from_selected_milliseconds: selected ? 0 : -10
    }
  };
}

function duplicate(sequence = 71) {
  return {
    decision_sequence: sequence,
    decided_at_ms: 1_700_000_004_000 + sequence,
    outcome: 'MERGED',
    call_identity: {
      protocol: 'APCO25', decoder: 'P25 Phase 1',
      start_timestamp: 1_700_000_000_000, end_timestamp: 1_700_000_003_600,
      destination_value: '27101', destination_alias: 'County Fire Dispatch',
      source_value: '1204185', source_alias: 'Engine 4', encryption_state: 'CLEAR',
      unique_learned_site_count: 2
    },
    output_policy: { record_requested: true, stream_routing_key_count: 1, browser_offered: true },
    winner: {
      selected_copy_index: 1, runner_up_copy_index: 2, criterion: 'USABLE_FRAME_COUNT',
      winner_value: { display: '167 usable frames', numerator: 167, denominator: 180 },
      runner_up_value: { display: '164 usable frames', numerator: 164, denominator: 180 }
    },
    legs: [copy(1, true), copy(2, false)],
    evidence: {
      confirmed_duplicate_pair_count: 1, separated_pair_count: 0, uncertain_pair_count: 0,
      merge_proof_counts: { shared_voice_content: 1 }, rejection_reason_counts: {}
    },
    decision_reasons: []
  };
}

function snapshot(duplicates = [duplicate()]) {
  return {
    available: true, session_id: 'diagnostic-session',
    session_started_at_epoch_millis: 1_700_000_000_000,
    resolver: {
      session_id: 'coordinator-session', started_at_ms: 1_700_000_000_000,
      generated_at_ms: now, revision: 7, snapshot_age_ms: 0,
      health_state: 'HEALTHY', accepting: true, disposed: false,
      active_leg_count: 3, active_cohort_count: 1, retained_audio_sample_count: 28_800,
      counters: {
        merged_logical_calls: 147, merged_receiver_copies: 153,
        fail_open_logical_calls: 2, emitted_logical_calls: 210,
        diagnostic_decisions_rejected: 0
      }
    },
    queue: {
      ingress_depth: 2, regular_ingress_capacity: 504, total_ingress_capacity: 512,
      accepted_ingress: 1000, dropped_ingress: 0, dropped_lifecycle: 0,
      dropped_operations: 0, aborted_calls: 0
    },
    diagnostic_status: {
      accepting: true, decisions_observed: 147, records_rejected_after_close: 0
    },
    history: {
      duplicates_evicted: 6,
      duplicates_retained: duplicates.filter((item) => item.outcome === 'MERGED').length,
      limit: 100
    },
    duplicates
  };
}


async function installSiteStyleApplication(page, theme = 'light') {
  const defaults = (await import(pathToFileURL(path.join(root, 'stats-web/assets/core/preference-schema.js')).href)).defaults;
  const preferences = structuredClone(defaults);
  preferences.appearance.theme = theme;
  const state = { unexpected: [], requests: [], pageErrors: [] };
  page.on('pageerror', error => state.pageErrors.push(error.message));
  await page.clock.setFixedTime(new Date(now));
  await page.addInitScript((snapshotValue) => {
    const fetchOriginal = window.fetch.bind(window);
    window.fetch = (input, options) => {
      const url = new URL(typeof input === 'string' ? input : input.url, location.href);
      if (url.pathname !== '/api/v1/live/multiplex') return fetchOriginal(input, options);
      const frame = (topic, event, data) => {
        const payload = new TextEncoder().encode(JSON.stringify({ event, data }));
        const bytes = new Uint8Array(16 + payload.length), header = new DataView(bytes.buffer);
        header.setUint32(0, 0x534c4d58); header.setUint8(4, 2); header.setUint8(5, 1);
        header.setUint16(6, topic); header.setUint32(8, payload.length); bytes.set(payload, 16);
        return bytes;
      };
      return Promise.resolve(new Response(new ReadableStream({ start(controller) {
        controller.enqueue(frame(0, 'ready', { client_id: url.searchParams.get('client_id') }));
        controller.enqueue(frame(1, 'snapshot', snapshotValue));
      } }), { status: 200 }));
    };
  }, liveSnapshot);
  const icon = fs.readFileSync(path.join(root, 'src/main/resources/images/fire_truck.png'));
  const mapTile = '<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256"><rect width="256" height="256" fill="#e7eee3"/><path d="M0 80H256M0 180H256M60 0V256M170 0V256" stroke="#ffffff" stroke-width="9"/><path d="M0 80H256M0 180H256M60 0V256M170 0V256" stroke="#c8d5c2" stroke-width="1"/></svg>';
  await page.route('https://tile.openstreetmap.org/**', route => route.fulfill({ contentType: 'image/svg+xml', body: mapTile }));
  let feedDelivered = false;
  await page.route('**/api/v1/**', async route => {
    const request = route.request(), url = new URL(request.url()), p = decodeURIComponent(url.pathname);
    state.requests.push(p);
    const respond = data => route.fulfill({ json: { data } });
    if (p === '/api/v1/me/preferences') return route.fulfill({ json: { revision: 1, preferences } });
    if (p === '/api/v1/auth/session') return respond({ configured: true, authenticated: true,
      username: 'fixture-admin', tier: 'admin', primary: true, csrf_token: 'fixture-csrf',
      capabilities: Object.fromEntries(policies.map(({ id }) => [id, true])) });
    if (p === '/api/v1/status') return respond({ stats_logging: { summary_configured: true,
      summary_active: true, detailed_history_configured: true, detailed_history_active: true },
      database: { database_exists: true, database_bytes: 1048576, detailed_history_available: true, logger: [] } });
    if (p === '/api/v1/receiver-health') return respond(health);
    if (p === '/api/v1/application-log') return respond(
      url.searchParams.get('log') === 'previous' ? previousApplicationLog : applicationLog);
    if (p === '/api/v1/channel-catalog' || p === '/api/v1/admin/channels') return respond({ revision: 1, channels: [channel] });
    if (p === '/api/v1/admin/channels/options') return respond({ revision: 1, alias_lists: [list], tuners: [] });
    if (p === '/api/v1/admin/channels/protocols') return respond(require(path.join(root, 'src/main/resources/channel-protocols.json')));
    if (p === `/api/v1/admin/channels/${channelId}`) return respond({ revision: 1, processing_state: 'RUNNING',
      channel: { ...channel, source: { frequencies_hz: channel.frequencies_hz }, settings: {}, frequency_map: [],
        event_logs: [], recorders: [], auxiliary_decoders: [] } });
    if (p === `/api/v1/channels/${channelId}`) return respond({ channel });
    if (p === `/api/v1/channels/${channelId}/quality` || p === '/api/v1/quality') return respond({
      ...pageOf([quality]), from_ms: now - 3600000, to_ms: now, bucket_ms: 30000 });
    if (p === `/api/v1/channels/${channelId}/frequencies`) return respond(pageOf([{
      frequency_hz: 851012500, lcn: 1, current_tags: ['CURRENT_CONTROL'], observation_count: 12,
      last_seen_ms: now, logical_call_count: 128 }]));
    if (['group-identities', 'radios', 'neighbors'].some(child =>
      p === `/api/v1/channels/${channelId}/${child}`)) return respond(pageOf([]));
    if (p === `/api/v1/channels/${channelId}/frequency-bands`) return respond({
      band_source: 'OTA', home_bands: [], foreign_bands: [] });
    if (p === `/api/v1/channels/${channelId}/patch-groups`) return respond({
      groups: [], talkgroups: [], radios: [] });
    if (p === '/api/v1/radio-systems') return respond(pageOf([{ ...identity, sites: [channel] }]));
    if (p === `/api/v1/radio-systems/${systemKey}`) return respond(identity);
    if (p === `/api/v1/radio-systems/${systemKey}/channels`) return route.fulfill({ json: { data: [channel],
      meta: { limit: 25, offset: 0, has_more: false, total_count: 1 } } });
    if (p === `/api/v1/radio-systems/${systemKey}/group-identities/${groupKey}`) return respond(group);
    if (p === `/api/v1/radio-systems/${systemKey}/radios/${radioKey}`) return respond(radio);
    if ([`/api/v1/radio-systems/${systemKey}/activity`,
      `/api/v1/radio-systems/${systemKey}/group-identities/${groupKey}/activity`,
      `/api/v1/radio-systems/${systemKey}/radios/${radioKey}/activity`].includes(p)) {
      return respond({ totals, series: [] });
    }
    if (p === '/api/v1/activity') return respond({ ...pageOf([{ id: 1, observed_at_ms: now,
      action: 'GRANT', protocol: 'P25', channel_kind: 'TRUNKED_SITE', radio_system_key: systemKey,
      system_name: systemName, configuration_id: channelId, source_radio_id: 30914, source_native_id: 30914,
      source_identity_key: radioKey, source_alias_name: 'Engine 4', target_id: 1201, target_native_id: 1201,
      target_identity_key: groupKey, target_kind: 'talkgroup', target_alias_name: 'Fire Dispatch' }]),
      watermark_id: 1, next_after_id: 1 });
    if (p === '/api/v1/live/multiplex/control') return respond({ accepted: true });
    if (p === '/api/v1/scan-lists' || p === '/api/v1/admin/scan-lists') return respond({ revision: 1,
      scan_lists: scanLists.map(item => ({ ...item, alias_count: 2, unmatched_alias_list_count: 1, published: true })) });
    if (p === '/api/v1/calls/feed') {
      const calls = feedDelivered ? [] : [liveCall]; feedDelivered = true;
      return respond({ cursor: '1', reset: false, calls });
    }
    if (p === '/api/v1/recordings/status') return respond({ mode: 'MANAGED', available: true, has_calls: true });
    if (p === '/api/v1/recordings/calls') return respond({ calls: [recording], next_cursor: null, total: 1 });
    if (p === '/api/v1/recordings/calls/17') return respond({ call: recording, transcription: transcript });
    if (p === '/api/v1/recordings/suggestions') return respond({ suggestions: [] });
    if (p === '/api/v1/recordings/calls/17/audio' || p === '/api/v1/calls/dock-live-1/audio') {
      return route.fulfill({ contentType: 'audio/wav', body: Buffer.alloc(44) });
    }
    if (p === '/api/v1/admin/recordings/settings') return respond({ mode: 'MANAGED', managed_directory: '/recordings',
      retention_days: 30, transcription_enabled: false, transcription_url: '', transcription_model: '',
      transcription_min_duration_ms: 500, transcription_key_configured: false });
    if (p === '/api/v1/admin/recordings/status') return respond({ catalog: { call_count: 128, bytes: 1843200 },
      transcription: { pending: 3, completed: 125, failed: 0, active: false } });
    if (p === '/api/v1/alias-lists' || p === '/api/v1/identities/lists') return respond(pageOf([list]));
    if (p === '/api/v1/admin/alias-lists') return respond({ revision: 1, alias_lists: [list] });
    if (p === '/api/v1/aliases') return respond(pageOf(aliases));
    if (p === '/api/v1/admin/aliases/options') return respond({ revision: 1, alias_list: list,
      streams: [{ configuration_id: stream.configuration_id, name: stream.name }] });
    if (p === '/api/v1/admin/operational-preferences') return route.fulfill({ json: operational });
    if (p === '/api/v1/admin/streaming') return respond({ revision: '1:1:1', destinations: [stream] });
    if (p === '/api/v1/admin/receiver-settings') return route.fulfill({ json: {
      revision: 1, settings: { traffic_grant_age_out_milliseconds: 2000 } } });
    if (p === '/api/v1/spectrum-snap-presets' || p === '/api/v1/admin/spectrum-snap-presets') return route.fulfill({ json: {
      revision: 1, country_code: 'US', country_label: 'United States', countries: [{ code: 'US', label: 'United States' }], scopes: [] } });
    if (p === '/api/v1/admin/p25-bandplan-overrides') return route.fulfill({ json: { profiles: [{ wacn: 1, system: 1, rfss: 1, site: 2,
      bands: [{ identifier: 0, type: 'FDMA', base_frequency: 851006250, bandwidth: 12500,
        channel_spacing: 12500, transmit_offset: -45000000 }] }] } });
    if (p === '/api/v1/admin/users') return respond({ users: [{ username: 'admin', primary: true, tier: 'admin',
      password_changed_at_epoch_millis: now - 3600000 }, { username: 'field.operator', tier: 'user',
      primary: false, password_changed_at_epoch_millis: now - 3600000 }], maximum_users: 8 });
    if (p === '/api/v1/admin/access') return respond({ capabilities: policies });
    if (p === '/api/v1/admin/call-matching') return respond(snapshot());
    if (p === '/api/v1/admin/retained-statistics/sources') return route.fulfill({ json: { data: [
      { source_kind: 'radio_system', source_key: systemKey, label: systemName, protocol: 'P25',
        entity_ref: { kind: 'radio_system', key: systemKey } } ], meta: { limit: 50, offset: 0, has_more: false } } });
    if (p === '/api/v1/admin/retained-statistics/sites') return route.fulfill({ json: { data: [
      { configuration_id: channelId, label: channelName, site_key: 'fixture-site' } ], meta: { limit: 50, offset: 0, has_more: false } } });
    if (p === '/api/v1/admin/retained-statistics/results') return route.fulfill({ json: { data: [
      { label: 'All retained data', detail: systemName, target: { kind: 'scoped_data', source_kind: 'radio_system',
        source_key: systemKey, data_type: 'all', parts: ['current', 'summary', 'buckets', 'events'] } } ],
      meta: { limit: 25, offset: 0, has_more: false, total_count: 1 } } });
    if (p === '/api/v1/admin/remote-links') return respond({ revision: 'fixture',
      listener: { enabled: false, bind_address: '0.0.0.0', port: 53800, state: 'DISABLED', dependencies: [] },
      sender_connection: { enabled: false, state: 'DISABLED', credential_configured: false, exported_channel_configuration_ids: [] },
      senders: [{ sender_id: 'fixture-sender', display_name: 'Regional receiver', state: 'CONNECTED',
        last_seen_at_ms: now, default_alias_list_id: 1, feeds: [{ feed_id: 'north', display_name: channelName,
          protocol: 'P25_PHASE1', frequency_hz: 851012500, system_name: systemName, site_name: 'North',
          state: 'CONNECTED', channel_configuration_id: channelId, alias_list_id: 1, enabled: true }] }],
      alias_lists: [list], export_channel_options: [channel] });
    if (p === '/api/v1/admin/tuners') return respond({ tuners: [operatorTuner({ spectrum_target_id: 'target-a', spectrum_available: true })] });
    if (p === '/api/v1/admin/tuners/rf-analysis') return respond({ tuners: [{ id: 'tuner-a', model: 'airspy', rate_hz: 10000000 }], frequencies_hz: [851012500] });
    if (p === '/api/v1/admin/tuners/tuner-a/browse') return respond({ lease_id: 'fixture-browse',
      expires_at_epoch_ms: now + 3600000, can_tune: true, tuner: operatorTuner({ spectrum_target_id: 'target-a', spectrum_available: true }) });
    if (p === '/api/v1/diagnostics/tuners') return respond({ rows: [{ target_id: 'target-a', label: 'Airspy R2',
      center_frequency_hz: 851012500, sample_rate_hz: 10000000 }] });
    if (p === '/api/v1/admin/radioreference') return respond({ account: { state: 'SIGNED_OUT' }, credentials_stored: false });
    if (p === '/api/v1/listen/map') return respond({ generated_at_ms: now, dropped_observations: 0, evicted_entities: 0,
      entities: [{ id: 'engine-4', label: 'Engine 4', identifier: '30914', alias_list: list.name,
        system: systemName, icon: 'fire-truck', heading: 92, speed_kph: 41, positions: [
          { latitude: 41.502, longitude: -81.685, timestamp_ms: now },
          { latitude: 41.5, longitude: -81.69, timestamp_ms: now - 10000 } ] }] });
    if (p === '/api/v1/listen/map/icons/fire-truck') return route.fulfill({ contentType: 'image/png', body: icon });
    state.unexpected.push(`${request.method()} ${p}`);
    return route.fulfill({ status: 501, json: { error: { message: `Style fixture has no response for ${p}` } } });
  });
  return state;
}

module.exports = { installSiteStyleApplication, operatorTuner, systemKey, channelId, groupKey, radioKey, channelName, systemName };
