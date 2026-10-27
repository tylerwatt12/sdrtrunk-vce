// Fictional transport data for the production Browser Spectrum interface.
// No request, stream, or listening action in this provider contacts a receiver.
import { receiverDisplayName } from '../core/receiver-names.js?v=1';
const ROOT = '/api/v1/admin/tuners';
const DIAGNOSTICS = '/api/v1/diagnostics/tuners';
const CONTROL_HZ = 856662500;
const ANALOG_HZ = 856412500;
const VOICE_HZ = 856912500;
const SAMPLE_RATE_HZ = 2400000;
const FRAME_INTERVAL_MS = 160;
const HISTORY_FRAME_COUNT = 128;
const HISTORICAL_CENTER_HZ = 855662500;
const clone = value => structuredClone(value);

function receiver(id, name, frequencyHz, channelCount) {
  return { id, name, tuner_class: 'RTL2832', tuner_type: 'RTL2832_R820T', status: 'ENABLED',
    available: true, operator_state: 'live', transition: null, channel_count: channelCount,
    frequency_hz: frequencyHz, configured_frequency_hz: frequencyHz,
    sample_rate_hz: SAMPLE_RATE_HZ, configured_sample_rate_hz: SAMPLE_RATE_HZ,
    spectrum_target_id: `${id}-spectrum`, spectrum_available: true,
    stopped_channels: [], restore_result: null, maintenance_error: null,
    device_group: { id: `${id}-group`, kind: 'single', role: 'member' }, settings: [
      { id: 'frequency_mhz', label: 'Center frequency', group: 'frequency', kind: 'decimal',
        value: frequencyHz / 1000000, scope: 'tuner', availability: 'live', editable: true,
        minimum: 24, maximum: 1766, step: 0.000001, unit: 'MHz',
        dependencies: [{ setting_id: 'center_frequency_locked', equals: false }] },
      { id: 'center_frequency_locked', label: 'Lock center', group: 'frequency', kind: 'boolean',
        value: false, scope: 'tuner', availability: 'live', editable: true, dependencies: [] }
    ] };
}

export function createSpectrumGuideData({ signal, section } = {}) {
  const requests = [], connections = new Set(), activitySources = new Set(), timers = new Set();
  const tuners = [receiver('guide-spectrum-rtl-1', 'RTL-SDR 1', 856162500, 1),
    receiver('guide-spectrum-rtl-2', 'RTL-SDR 2', 857162500, 0)];
  const startedAt = Date.now(), leases = new Map();
  const seededTargets = new Set();
  const retainVoice = ['channel-marker', 'channel-details'].includes(section);
  const retainWaterfallHistory = section === 'waterfall-details';
  let disposed = false, nextLease = 0, frames = 0, snapshots = 0;
  const snapPresets = { revision: 1, countryCode: 'US', countryLabel: 'United States',
    countries: [{ code: 'US', label: 'United States' }], scopes: [
      { id: 'guide-us-800-public-safety', label: '800 MHz public safety',
        minHz: 851000000, maxHz: 869000000,
        snap: { kind: 'RASTER', originHz: 851012500, stepHz: 12500,
          matchToleranceHz: 0, frequenciesHz: [] } }
    ] };
  const check = requestSignal => {
    if (disposed || signal?.aborted || requestSignal?.aborted) {
      throw new DOMException('The Spectrum example was closed.', 'AbortError');
    }
  };
  const schedule = (callback, delay) => {
    const timer = setInterval(callback, delay);
    timers.add(timer);
    return timer;
  };
  const clear = timer => {
    if (timer === null) return;
    clearInterval(timer);
    timers.delete(timer);
  };
  const signalState = (observedAt = Date.now()) => {
    const phase = (Math.floor((observedAt - startedAt) / 350) % 24 + 24) % 24;
    return { analog: phase < 9 || phase >= 17 && phase < 21,
      voice: retainVoice || phase < 13 || phase >= 18 };
  };
  const requestJson = async (path, options = {}) => {
    check(options.signal);
    const url = new URL(path, 'https://spectrum-guide.invalid');
    const method = options.method || 'GET';
    const body = typeof options.body === 'string' ? JSON.parse(options.body) : options.body || {};
    requests.push({ path: url.pathname, method, body: clone(body) });
    if (method === 'GET' && url.pathname === ROOT) return { tuners: clone(tuners) };
    if (method === 'GET' && url.pathname === DIAGNOSTICS) return { rows: tuners.map(tuner => ({
      target_id: tuner.spectrum_target_id, label: receiverDisplayName(tuner),
      center_frequency_hz: tuner.frequency_hz, sample_rate_hz: tuner.sample_rate_hz
    })) };
    if (method === 'GET' && url.pathname === '/api/v1/admin/spectrum-discovery/eligibility') {
      const tuner = tuners.find(candidate => candidate.id === url.searchParams.get('tuner_id'));
      const frequencyHz = Number(url.searchParams.get('frequency_hz'));
      if (!tuner || !Number.isSafeInteger(frequencyHz) ||
          Math.abs(frequencyHz - tuner.frequency_hz) > tuner.sample_rate_hz / 2 ||
          [...url.searchParams.keys()].some(key => !['tuner_id', 'frequency_hz'].includes(key))) {
        throw new Error('Unknown Spectrum example frequency.');
      }
      const configured = [CONTROL_HZ, VOICE_HZ].includes(frequencyHz);
      return { eligible: !configured, matches: configured ? [{
        configuration_id: 'guide-spectrum-county-channel', name: 'County P25'
      }] : [] };
    }
    const match = url.pathname.match(/^\/api\/v1\/admin\/tuners\/([^/]+)\/browse$/);
    const tuner = match && tuners.find(candidate => candidate.id === decodeURIComponent(match[1]));
    if (tuner && method === 'POST' && !body.takeover) {
      const existing = leases.get(tuner.id);
      if (body.lease_id && body.lease_id !== existing) throw new Error('Unknown Spectrum example lease.');
      const leaseId = existing || `guide-spectrum-lease-${++nextLease}`;
      leases.set(tuner.id, leaseId);
      return { lease_id: leaseId, tuner: clone(tuner), takeover: false,
        can_tune: tuner.channel_count === 0, stopped_channels: [], expires_at_ms: Date.now() + 30000 };
    }
    if (tuner && method === 'DELETE' && body.lease_id === leases.get(tuner.id)) {
      leases.delete(tuner.id);
      return { status: 'released' };
    }
    throw new Error(`Unsupported Spectrum example request: ${method} ${url.pathname}`);
  };
  const api = (path, query = {}, options = {}) => requestJson(path, { ...options, method: 'GET' });

  const connectionFactory = (topic, initialParameters, callbacks) => {
    check();
    if (topic !== 'tuner_diagnostics') throw new Error('Unsupported Spectrum example stream.');
    let parameters = clone(initialParameters), sequence = 0, generation = 1, closed = false, timer = null;
    let resolveClosed;
    const closedPromise = new Promise(resolve => { resolveClosed = resolve; });
    const tunerFor = next => {
      const tuner = tuners.find(candidate => candidate.spectrum_target_id === next?.target_id);
      if (!tuner) throw new Error('Unknown Spectrum example target.');
      return tuner;
    };
    tunerFor(parameters);
    const viewportFor = (centerFrequencyHz) => {
      const fullStartHz = centerFrequencyHz - SAMPLE_RATE_HZ / 2;
      const requestedStartHz = Number(parameters.viewport_start_hz);
      const requestedEndHz = Number(parameters.viewport_end_hz);
      const requested = requestedStartHz > 0 && requestedEndHz > requestedStartHz;
      const binWidthHz = SAMPLE_RATE_HZ / 2048;
      const firstBin = requested ? Math.max(0, Math.min(2047,
        Math.floor((requestedStartHz - fullStartHz) / binWidthHz))) : 0;
      const endBin = requested ? Math.max(firstBin + 1, Math.min(2048,
        Math.ceil((requestedEndHz - fullStartHz) / binWidthHz))) : 2048;
      return { requested, requestedStartHz, requestedEndHz, firstBin,
        sourceBinCount: endBin - firstBin,
        startHz: fullStartHz + firstBin * binWidthHz,
        endHz: fullStartHz + endBin * binWidthHz };
    };
    const stateFrame = (centerFrequencyHz = tunerFor(parameters).frequency_hz) => {
      if (closed || disposed || signal?.aborted) return;
      const tuner = tunerFor(parameters);
      const viewport = viewportFor(centerFrequencyHz);
      callbacks.onFrame({ type: 1, generation, sequence: ++sequence,
        payload: new TextEncoder().encode(JSON.stringify({ target_id: tuner.spectrum_target_id,
          stream_state: 'live', profile: parameters.profile || 'balanced',
          center_frequency_hz: centerFrequencyHz, sample_rate_hz: tuner.sample_rate_hz,
          ...(viewport.requested ? { requested_start_hz: viewport.requestedStartHz,
            requested_end_hz: viewport.requestedEndHz,
            visible_start_hz: viewport.startHz, visible_end_hz: viewport.endHz } : {}) })) });
    };
    const replay = (observedAtEpochMs = Date.now(), centerFrequencyHz = tunerFor(parameters).frequency_hz) => {
      if (closed || disposed || signal?.aborted) return;
      const viewport = viewportFor(centerFrequencyHz), count = viewport.sourceBinCount;
      const values = new Float32Array(count);
      const burst = signalState(observedAtEpochMs);
      for (let index = 0; index < count; index++) {
        const frequencyHz = viewport.startHz + index * SAMPLE_RATE_HZ / 2048;
        const peak = (center, strength, width) => strength * Math.exp(-Math.pow((frequencyHz - center) / width, 4));
        values[index] = -117 + 2.3 * Math.sin(index * 1.73 + sequence * 0.47) +
          1.6 * Math.cos(index * 0.61 - sequence * 0.31) + peak(CONTROL_HZ, 69, 5100) +
          (burst.analog ? peak(ANALOG_HZ, 58, 6200) : 0) +
          (burst.voice ? peak(VOICE_HZ, 64, 5100) : 0);
      }
      frames += 1;
      callbacks.onFrame({ type: 4, generation, sequence: ++sequence, valueCount: count,
        centerFrequencyHz, sampleRateHz: SAMPLE_RATE_HZ, fftSize: 2048,
        firstBin: viewport.firstBin, sourceBinCount: count, observedAtEpochMs, encodedAtEpochMs: Date.now(),
        payload: new Uint8Array(values.buffer) });
    };
    const connection = { replay,
      update(next) {
        if (closed || disposed || signal?.aborted) return false;
        tunerFor(next);
        parameters = clone(next);
        generation += 1;
        stateFrame();
        replay();
        return true;
      },
      close() {
        if (closed) return;
        closed = true;
        clear(timer);
        timer = null;
        connections.delete(connection);
        resolveClosed();
      },
      whenClosed: () => closedPromise };
    connections.add(connection);
    queueMicrotask(() => {
      if (closed || disposed || signal?.aborted) return;
      callbacks.onOpen?.();
      // Feed genuine earlier observations through the normal waterfall renderer so
      // the actual history includes both completed transmissions and a tuner retune.
      const now = Date.now();
      const tuner = tunerFor(parameters);
      const seedRetune = !seededTargets.has(tuner.id) && !viewportFor(tuner.frequency_hz).requested;
      seededTargets.add(tuner.id);
      stateFrame(seedRetune ? HISTORICAL_CENTER_HZ : tuner.frequency_hz);
      for (let index = HISTORY_FRAME_COUNT; index > 0 && !closed && !disposed && !signal?.aborted; index--) {
        if (seedRetune && index === HISTORY_FRAME_COUNT / 2) {
          generation += 1;
          stateFrame();
        }
        replay(now - index * FRAME_INTERVAL_MS,
          seedRetune && index > HISTORY_FRAME_COUNT / 2 ? HISTORICAL_CENTER_HZ : tuner.frequency_hz);
      }
      replay();
      // Preserve the real captured history while this lesson is being read.
      // Explicit viewport updates still receive their normal state and FFT reply.
      if (!retainWaterfallHistory && !closed && !disposed && !signal?.aborted)
        timer = schedule(replay, FRAME_INTERVAL_MS);
    });
    return connection;
  };

  const activityFeed = (callbacks = {}) => {
    check();
    let closed = false, timer = null, revision = 0;
    const replay = () => {
      if (closed || disposed || signal?.aborted) return;
      const burst = signalState();
      snapshots += 1;
      callbacks.snapshot?.({ revision: ++revision, tables: [{ table_id: 'guide-spectrum-county',
        channel_name: 'County P25', system_name: 'County P25', site_name: 'North Site',
        identifiers: [{ label: 'WACN', value: 'BEE00' }, { label: 'System ID', value: '123' }], rows: [
          { frequency_hz: CONTROL_HZ, bandwidth_hz: 12500, status: 'ACTIVE', tags: ['CURRENT_CONTROL'],
            channel_name: 'County P25', decoder: 'P25_PHASE1', signal_dbfs: -48, decode_health_pct: 99.2 },
          ...(burst.voice ? [{ frequency_hz: VOICE_HZ, bandwidth_hz: 12500, status: 'CALL', tags: ['VOICE'],
            channel_name: 'County P25', decoder: 'P25_PHASE1', target_form: 'TALKGROUP', target_id: 1001,
            target_alias: 'Sheriff Dispatch', source_form: 'RADIO', source_id: 214,
            source_alias: 'Sheriff 214', signal_dbfs: -53, vc_quality_pct: 98.7 }] : [])
        ] }] });
    };
    const source = { replay, close() {
      if (closed) return;
      closed = true;
      clear(timer);
      timer = null;
      activitySources.delete(source);
    } };
    activitySources.add(source);
    queueMicrotask(() => {
      if (closed || disposed || signal?.aborted) return;
      callbacks.open?.();
      replay();
      if (!closed && !disposed && !signal?.aborted) timer = schedule(replay, 350);
    });
    return source;
  };
  const dispose = () => {
    if (disposed) return;
    disposed = true;
    connections.forEach(connection => connection.close());
    activitySources.forEach(source => source.close());
    leases.clear();
    signal?.removeEventListener('abort', dispose);
  };
  signal?.addEventListener('abort', dispose, { once: true });
  if (signal?.aborted) dispose();
  return { requestJson, api, connectionFactory, activityFeed, snapPresets, requests, dispose,
    selectedTunerId: tuners[0].id, selectedAnalogHz: ANALOG_HZ, selectedCarrierHz: VOICE_HZ,
    stats: () => ({ disposed, connections: connections.size, activityFeeds: activitySources.size,
      timers: timers.size, leases: leases.size, frames, snapshots }) };
}
