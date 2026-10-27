// Fictional receiver responses and diagnostic frames for the actual Tuners UI.
// Each instance owns its state; no request opens hardware or leaves this module.
import { receiverDisplayName } from '../core/receiver-names.js?v=1';
const ROOT = '/api/v1/admin/tuners';
const clone = value => structuredClone(value);
const setting = (id, label, group, kind, value, extra = {}) => ({ id, label, group, kind, value,
  scope: 'tuner', availability: 'live', editable: true, dependencies: [], ...extra });
const channels = [
  { id: 'guide-county', name: 'County P25', system: 'County P25', site: 'North Site',
    frequency_hz: 851012500, kind: 'standard' },
  { id: 'guide-city', name: 'City P25', system: 'City P25', site: 'City Site',
    frequency_hz: 852112500, kind: 'standard' }
];
export function createTunersGuideData({ signal } = {}) {
  const requests = [], connections = new Set();
  const snapPresets = { countryCode: 'US', countryLabel: 'United States', scopes: [], countries: [] };
  const selected = { id: 'guide-airspy', name: 'Airspy R2', nickname: 'North antenna', tuner_class: 'AIRSPY', tuner_type: 'AIRSPY_R820T',
    status: 'ENABLED', available: true, operator_state: 'live', transition: null,
    channel_count: 2, frequency_hz: 851500000, configured_frequency_hz: 851500000,
    sample_rate_hz: 2500000, configured_sample_rate_hz: 2500000,
    stopped_channels: [], restore_result: null, maintenance_error: null,
    spectrum_target_id: 'guide-airspy-spectrum', spectrum_available: true,
    device_group: { id: 'guide-airspy-group', kind: 'single', role: 'member' }, settings: [
      setting('sample_rate', 'Sample rate', 'frequency', 'choice', 2500000,
        { availability: 'setup', options: [{ value: 2500000, label: '2.50 MHz' }, { value: 10000000, label: '10.00 MHz' }] }),
      setting('frequency_mhz', 'Center frequency', 'frequency', 'decimal', 851.5,
        { minimum: 24, maximum: 1800, step: 0.000001, unit: 'MHz' }),
      setting('center_frequency_locked', 'Lock center', 'frequency', 'boolean', false),
      setting('automatic_ppm', 'Automatic PPM correction', 'calibration', 'boolean', false),
      setting('frequency_correction_ppm', 'Frequency correction', 'calibration', 'decimal', 0.8,
        { minimum: -200, maximum: 200, step: 0.01, unit: 'ppm' }),
      setting('minimum_frequency_mhz', 'Minimum frequency', 'frequency', 'decimal', 850,
        { minimum: 24, maximum: 1800, step: 0.000001, unit: 'MHz' }),
      setting('maximum_frequency_mhz', 'Maximum frequency', 'frequency', 'decimal', 854,
        { minimum: 24, maximum: 1800, step: 0.000001, unit: 'MHz' }),
      setting('reset_frequency_extents', 'Reset frequency limits', 'frequency', 'action', null),
      setting('gain', 'Gain preset', 'gain', 'choice', 'CUSTOM', { options: [
        { value: 'CUSTOM', label: 'Custom' }, { value: 'LINEARITY', label: 'Linearity' }, { value: 'SENSITIVITY', label: 'Sensitivity' }] }),
      setting('lna_gain', 'LNA gain', 'gain', 'integer', 8, { minimum: 0, maximum: 14, step: 1 }),
      setting('mixer_gain', 'Mixer gain', 'gain', 'integer', 7, { minimum: 0, maximum: 15, step: 1 }),
      setting('if_gain', 'IF gain', 'gain', 'integer', 9, { minimum: 0, maximum: 15, step: 1 }),
      setting('lna_agc', 'LNA AGC', 'gain', 'boolean', false),
      setting('mixer_agc', 'Mixer AGC', 'gain', 'boolean', false)
    ] };
  const spare = { ...clone(selected), id: 'guide-rtl', name: 'RTL-SDR R820T2', nickname: '', tuner_class: 'RTL2832',
    tuner_type: 'RTL2832_R820T', operator_state: 'disabled', status: 'DISABLED', available: false,
    channel_count: 0, sample_rate_hz: 2400000, configured_sample_rate_hz: 2400000,
    spectrum_target_id: null, spectrum_available: false, settings: [],
    device_group: { id: 'guide-rtl-group', kind: 'single', role: 'member' } };
  const check = requestSignal => {
    if (signal?.aborted || requestSignal?.aborted) throw new DOMException('The tuner example was closed.', 'AbortError');
  };
  const documentTuner = () => {
    const row = clone(selected), setup = row.operator_state === 'setup';
    row.settings.forEach(control => {
      if (control.id === 'sample_rate' && !setup) { control.editable = false; control.unavailable_reason = 'Use Setup'; }
      if (control.id === 'frequency_mhz' && row.channel_count) {
        control.editable = false; control.unavailable_reason = 'Channels are using this tuner';
      }
    });
    return row;
  };
  const requestJson = async (path, options = {}) => {
    check(options.signal);
    const url = new URL(path, 'https://tuners-guide.invalid'), method = options.method || 'GET';
    const body = typeof options.body === 'string' ? JSON.parse(options.body) : options.body || {};
    requests.push({ path: url.pathname, method, body: clone(body) });
    if (method === 'GET' && url.pathname === ROOT) return { tuners: [documentTuner(), clone(spare)] };
    const nicknameTuner = [selected, spare].find(tuner => url.pathname === `${ROOT}/${tuner.id}/nickname`);
    if (method === 'PUT' && nicknameTuner) {
      nicknameTuner.nickname = String(body.nickname || '').trim();
      return { nickname: nicknameTuner.nickname };
    }
    if (method === 'GET' && url.pathname === '/api/v1/diagnostics/tuners') return { rows: selected.spectrum_available ? [{
      target_id: selected.spectrum_target_id, label: receiverDisplayName(selected), center_frequency_hz: selected.frequency_hz,
      sample_rate_hz: selected.sample_rate_hz }] : [] };
    if (method === 'PUT' && url.pathname === `${ROOT}/${selected.id}/state` &&
        ['live', 'setup', 'disabled'].includes(body.state)) {
      if (body.state !== 'live' && selected.channel_count) selected.stopped_channels = clone(channels);
      selected.operator_state = body.state;
      selected.status = body.state === 'disabled' ? 'DISABLED' : 'ENABLED';
      selected.available = body.state === 'live';
      selected.channel_count = 0;
      selected.spectrum_available = body.state !== 'disabled';
      selected.spectrum_target_id = selected.spectrum_available ? 'guide-airspy-spectrum' : null;
      if (body.state === 'live') selected.stopped_channels = [];
      connections.forEach(connection => connection.replay());
      return { status: 'applied' };
    }
    if (method === 'POST' && url.pathname === `${ROOT}/${selected.id}/restore`) {
      selected.operator_state = 'live'; selected.status = 'ENABLED'; selected.available = true;
      selected.channel_count = selected.stopped_channels.length;
      selected.stopped_channels = []; selected.restore_result = { failed: [] };
      selected.spectrum_available = true; selected.spectrum_target_id = 'guide-airspy-spectrum';
      connections.forEach(connection => connection.replay());
      return { status: 'accepted' };
    }
    throw new Error(`Unsupported tuner example request: ${method} ${url.pathname}`);
  };
  const api = (path, query = {}, options = {}) => requestJson(path, { ...options, method: 'GET' });
  const connectionFactory = (topic, initialParameters, callbacks) => {
    check();
    if (topic !== 'tuner_diagnostics') throw new Error('Unsupported tuner example stream.');
    let closed = false, timer = null, sequence = 0, parameters = initialParameters;
    const target = 'guide-airspy-spectrum';
    const replay = () => {
      if (closed || signal?.aborted) return;
      const live = selected.operator_state !== 'disabled';
      callbacks.onFrame({ type: 1, generation: 1, sequence: ++sequence,
        payload: new TextEncoder().encode(JSON.stringify({ target_id: target, stream_state: live ? 'live' : 'unavailable',
          profile: parameters.profile, center_frequency_hz: selected.frequency_hz,
          sample_rate_hz: selected.sample_rate_hz })) });
      if (!live || closed || signal?.aborted) return;
      const count = 1024, values = new Float32Array(count);
      for (let index = 0; index < count; index++) {
        const frequency = selected.frequency_hz - selected.sample_rate_hz / 2 + index * selected.sample_rate_hz / count;
        values[index] = -113 + 3 * Math.sin(index * 1.7 + sequence * 0.4) +
          channels.reduce((sum, channel) => sum + 68 * Math.exp(-Math.pow((frequency - channel.frequency_hz) / 5500, 2)), 0);
      }
      callbacks.onFrame({ type: 4, generation: 1, sequence: ++sequence, valueCount: count,
        centerFrequencyHz: selected.frequency_hz, sampleRate: selected.sample_rate_hz,
        sampleRateHz: selected.sample_rate_hz, observedAtEpochMs: Date.now(), encodedAtEpochMs: Date.now(),
        payload: new Uint8Array(values.buffer) });
    };
    const connection = { replay, update(next) { parameters = next; replay(); return true; },
      close() { if (closed) return; closed = true; clearInterval(timer); connections.delete(connection); },
      whenClosed: () => Promise.resolve() };
    connections.add(connection);
    queueMicrotask(() => {
      if (closed || signal?.aborted) return;
      callbacks.onOpen?.(); replay();
      if (!closed && !signal?.aborted) timer = setInterval(replay, 180);
    });
    return connection;
  };
  signal?.addEventListener('abort', () => connections.forEach(connection => connection.close()), { once: true });
  return { requestJson, api, connectionFactory, snapPresets, requests };
}
