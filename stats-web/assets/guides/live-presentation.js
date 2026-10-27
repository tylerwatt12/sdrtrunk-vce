// Fictional observations delivered to the production Live tables and diagnostic plots.
// These transports have no network, receiver, recording, or preference side effects.
const COUNTY = 'b00b8000-0000-4000-8000-000000000001';
const CITY = 'b00b8000-0000-4000-8000-000000000002';
const CONVENTIONAL = 'b00b8000-0000-4000-8000-000000000003';
const CONTROL_HZ = 856662500;
const SHERIFF_HZ = 856912500;
const FIRE_HZ = 857162500;
const clone = value => structuredClone(value);
const eventCatalog = { signature: 'guide-live-events-v1', timeslots: [], groups: [
  { key: 'voice', label: 'Calls', children: [{ key: 'CALL', label: 'Group call', children: [] }] },
  { key: 'signaling', label: 'Radio activity', children: [
    { key: 'REGISTER', label: 'Registration', children: [] },
    { key: 'AFFILIATE', label: 'Affiliation', children: [] }
  ] }
] };
const messageCatalog = { signature: 'guide-live-messages-v1', timeslots: [], groups: [
  { key: 'p25', label: 'P25 Phase 1', children: [
    { key: 'P25_LDU1', label: 'Logical link data unit 1', children: [] },
    { key: 'P25_LDU2', label: 'Logical link data unit 2', children: [] },
    { key: 'P25_TSBK', label: 'Trunking signaling block', children: [] }
  ] }
] };

function identity(systemId, subscriberId, talkgroupId) {
  return { protocol: 'P25', decoder: 'P25_PHASE1', serving_system_key: `p25:bee00:${systemId.toString(16).padStart(3, '0')}`,
    source_form: 'RADIO', source_id: subscriberId,
    source_canonical_identity: { wacn: 0xBEE00, system_id: systemId, subscriber_id: subscriberId },
    target_form: 'TALKGROUP', target_id: talkgroupId,
    target_canonical_group_identity: { wacn: 0xBEE00, system_id: systemId, talkgroup_id: talkgroupId } };
}

export function createLiveGuideData({ signal } = {}) {
  const activitySources = new Set(), liveSources = new Set(), diagnosticSources = new Set(), timers = new Set();
  const startedAt = Date.now();
  let disposed = false, activityDeliveries = 0, eventDeliveries = 0, messageDeliveries = 0, frames = 0;
  const check = () => {
    if (disposed || signal?.aborted) throw new DOMException('The Live example was closed.', 'AbortError');
  };
  const available = () => !disposed && !signal?.aborted;
  const every = (callback, delay) => {
    const timer = setInterval(callback, delay);
    timers.add(timer);
    return timer;
  };
  const clear = timer => {
    if (timer == null) return;
    clearInterval(timer);
    timers.delete(timer);
  };
  const origins = [
    { configuration_id: COUNTY, channel_name: 'County P25', frequency_hz: CONTROL_HZ },
    { configuration_id: COUNTY, channel_name: 'County P25', frequency_hz: SHERIFF_HZ },
    { configuration_id: COUNTY, channel_name: 'County P25', frequency_hz: FIRE_HZ },
    { configuration_id: CITY, channel_name: 'City P25', frequency_hz: 858162500 },
    { configuration_id: CITY, channel_name: 'City P25', frequency_hz: 858412500 },
    { configuration_id: CONVENTIONAL, channel_name: 'County Fire Dispatch', frequency_hz: 154430000 }
  ];
  const scopeFor = (topic, value) => {
    const parameters = clone(value || {});
    const allowed = new Set(['subscription_id', 'configuration_id', 'frequency_hz', 'timeslot', 'scope',
      ...(topic === 'channel_diagnostics' ? ['strength_encoding'] : [])]);
    if (Object.keys(parameters).some(key => !allowed.has(key)) ||
        typeof parameters.subscription_id !== 'string' || !parameters.subscription_id.trim()) {
      throw new Error('Unknown Live example subscription.');
    }
    if (parameters.scope != null) {
      if (topic === 'channel_diagnostics' || parameters.scope !== 'conventional' ||
          parameters.configuration_id != null || parameters.frequency_hz != null || parameters.timeslot != null) {
        throw new Error('Unknown Live example scope.');
      }
      return parameters;
    }
    const known = origins.filter(origin => origin.configuration_id === parameters.configuration_id);
    if (!known.length || parameters.frequency_hz != null &&
        !known.some(origin => origin.frequency_hz === parameters.frequency_hz) ||
        topic !== 'decode_events' && parameters.frequency_hz == null ||
        parameters.timeslot != null ||
        parameters.strength_encoding != null && parameters.strength_encoding !== 'packed6') {
      throw new Error('Unknown Live example channel.');
    }
    return parameters;
  };
  const activeOrigin = parameters => parameters.scope === 'conventional' ? origins.at(-1) :
    origins.find(origin => origin.configuration_id === parameters.configuration_id &&
      origin.frequency_hz === parameters.frequency_hz) || origins.find(origin => origin.configuration_id === parameters.configuration_id);
  const tableSnapshot = () => {
    const now = Date.now(), wave = Math.sin((now - startedAt) / 750);
    const fireActive = Math.floor((now - startedAt) / 1250) % 5 < 3;
    const common = { quality_observed_at_ms: now, bandwidth_hz: 12500 };
    const control = (configuration, key, frequency, systemId) => ({ ...common,
      receiver_id: 'guide-airspy', receiver_nickname: 'North antenna',
      key, configuration_id: configuration, frequency_hz: frequency, channel_name: configuration === COUNTY ? 'County P25' : 'City P25',
      protocol: 'P25', decoder: 'P25_PHASE1', role: 'CURRENT_CONTROL', tags: ['CURRENT_CONTROL'],
      status: 'ACTIVE', activation_order: 1, lcn: configuration === COUNTY ? '00-0132' : '00-0252',
      signal_dbfs: -48.4 + wave * 0.6, decode_health_pct: 99.2,
      cc_valid_frames: 1490, cc_invalid_frames: 12, cc_corrected_bits: 8,
      cc_sync_loss_bits: 0, cc_dropped_bits: 0,
      serving_system_key: `p25:bee00:${systemId.toString(16).padStart(3, '0')}` });
    const voice = (configuration, key, frequency, systemId, sourceId, sourceName, targetId, targetName, active, order) => ({
      ...common, ...identity(systemId, sourceId, targetId), key, configuration_id: configuration,
      ...(active ? { receiver_id: 'guide-rtl', receiver_nickname: 'Voice antenna' } : {}),
      channel_name: configuration === COUNTY ? 'County P25' : 'City P25',
      frequency_hz: frequency, lcn: configuration === COUNTY ? frequency === SHERIFF_HZ ? '00-0152' : '00-0172' : '00-0272',
      role: 'VOICE', tags: ['VOICE'], status: active ? 'CALL' : 'IDLE', activation_order: order,
      source_alias: sourceName, target_alias: targetName, signal_dbfs: -53.1 + wave * 0.8,
      vc_quality_pct: 98.7, vc_decoded_frames: 283, vc_repeated_frames: 2, vc_concealed_frames: 1,
      vc_missing_frames: 0, vc_fec_errors: 19, vc_fec_protected_bits: 41904 });
    return [
      { table_id: 'guide-live-county', configuration_id: COUNTY, title: 'County P25', system_name: 'County P25',
        site_name: 'North Site', channel_name: 'County P25', channel_running: true, control_active: true,
        identifiers: [{ label: 'WACN', value: 'BEE00' }, { label: 'System ID', value: '123' }], rows: [
          control(COUNTY, 'guide-live-county-control', CONTROL_HZ, 0x123),
          voice(COUNTY, 'guide-live-sheriff', SHERIFF_HZ, 0x123, 214, 'Sheriff 214', 1001, 'Sheriff Dispatch', true, 2),
          voice(COUNTY, 'guide-live-regional-fire', FIRE_HZ, 0x123, 7007, 'Engine 7', 1002, 'Regional Fire', fireActive, 3)
        ] },
      { table_id: 'guide-live-city', configuration_id: CITY, title: 'City P25', system_name: 'City P25',
        site_name: 'City Site', channel_name: 'City P25', channel_running: true, control_active: true,
        identifiers: [{ label: 'WACN', value: 'BEE00' }, { label: 'System ID', value: '456' }], rows: [
          control(CITY, 'guide-live-city-control', 858162500, 0x456),
          voice(CITY, 'guide-live-city-police', 858412500, 0x456, 318, 'Police 318', 2001, 'Police Department', true, 4)
        ] },
      { table_id: 'conventional', title: 'Conventional', channel_running: true, rows: [{
        ...common, ...identity(0x123, 7012, 1003), configuration_id: CONVENTIONAL,
        receiver_id: 'guide-conventional', receiver_nickname: 'VHF antenna',
        channel_name: 'County Fire Dispatch', key: 'guide-live-conventional-fire', frequency_hz: 154430000,
        role: 'CONVENTIONAL', tags: ['CONVENTIONAL', 'VOICE'], status: fireActive ? 'CALL' : 'IDLE',
        activation_order: 5, source_alias: 'Engine 12', target_alias: 'County Fire Dispatch',
        signal_dbfs: -61.5, vc_quality_pct: 97.8, vc_decoded_frames: 180, vc_repeated_frames: 3, vc_concealed_frames: 1
      }] }
    ];
  };
  const activityFeed = (callbacks = {}) => {
    check();
    let closed = false, timer = null, revision = 0;
    const replay = () => {
      if (closed || !available()) return;
      const tables = tableSnapshot();
      activityDeliveries += 1;
      if (!revision || !callbacks.activityBatch) callbacks.snapshot?.({ revision: ++revision, tables });
      else callbacks.activityBatch({ base_revision: revision, revision: ++revision,
        changes: tables.map(table => ({ table_id: table.table_id, operation: 'upsert', table })) });
    };
    const source = { replay, close() {
      if (closed) return;
      closed = true;
      clear(timer);
      activitySources.delete(source);
    } };
    activitySources.add(source);
    queueMicrotask(() => {
      if (closed || !available()) return;
      callbacks.open?.();
      replay();
      if (!closed && available()) timer = every(replay, 450);
    });
    return source;
  };
  const sampleEvent = (parameters, sequence, timestamp) => {
    const origin = activeOrigin(parameters), fire = origin.frequency_hz === FIRE_HZ;
    const city = origin.configuration_id === CITY, conventional = origin.configuration_id === CONVENTIONAL;
    const radio = fire ? 7007 : city ? 318 : conventional ? 7012 : 214;
    const group = fire ? 1002 : city ? 2001 : conventional ? 1003 : 1001;
    return { ...origin, protocol: 'P25', serving_system_key: city ? 'p25:bee00:456' : 'p25:bee00:123',
      event_id: `guide-live-event-${sequence % 32}`, time_start_ms: timestamp, duration_ms: 2400 + sequence % 4 * 650,
      event_type: 'CALL', event_label: 'Group call', category: 'VOICE',
      source_frequency_hz: origin.frequency_hz, ...(parameters.timeslot ? { timeslot: parameters.timeslot } : {}),
      from_aliases: fire ? 'Engine 7' : city ? 'Police 318' : conventional ? 'Engine 12' : 'Sheriff 214',
      from_identifiers: String(radio), to_aliases: fire ? 'Regional Fire' : city ? 'Police Department' : conventional ? 'County Fire Dispatch' : 'Sheriff Dispatch',
      to_identifiers: String(group), channel: origin.channel_name, details: 'Group voice call' };
  };
  const sampleMessage = (parameters, sequence, timestamp) => {
    const origin = activeOrigin(parameters), control = [CONTROL_HZ, 858162500].includes(origin.frequency_hz);
    const call = sampleEvent(parameters, sequence, timestamp);
    const type = control ? 'P25_TSBK' : sequence % 2 ? 'P25_LDU1' : 'P25_LDU2';
    return { ...origin, protocol: 'P25 Phase 1', message_id: `guide-live-message-${sequence % 48}`,
      timestamp_ms: timestamp, valid: true, ...(parameters.timeslot ? { timeslot: parameters.timeslot } : {}),
      filter_key: type, filter_label: control ? 'Trunking signaling block' : type === 'P25_LDU1' ? 'Logical link data unit 1' : 'Logical link data unit 2',
      text: control ? `GROUP VOICE CHANNEL GRANT · ${call.to_aliases} · Radio ${call.from_identifiers}` :
        type === 'P25_LDU1' ? `LDU1 · Group voice · Source ${call.from_identifiers} · Talkgroup ${call.to_identifiers} · Clear` :
          'LDU2 · Group voice · Clear audio · Valid frame' };
  };
  const liveConnection = (topic, initialParameters) => {
    check();
    if (!['decode_events', 'decode_messages'].includes(topic)) throw new Error('Unsupported Live example stream.');
    let parameters = scopeFor(topic, initialParameters), closed = false, timer = null, sequence = 0;
    const listeners = new Map();
    const emit = (type, value) => {
      if (closed || !available()) return;
      const event = { type, data: JSON.stringify(value), target: source };
      [...(listeners.get(type) || [])].forEach(listener => {
        if (closed || !available()) return;
        if (typeof listener === 'function') listener(event);
        else listener?.handleEvent?.(event);
      });
    };
    const replay = (timestamp = Date.now()) => {
      if (closed || !available()) return;
      const value = topic === 'decode_events' ? sampleEvent(parameters, ++sequence, timestamp) : sampleMessage(parameters, ++sequence, timestamp);
      if (topic === 'decode_events') eventDeliveries += 1;
      else messageDeliveries += 1;
      emit(topic === 'decode_events' ? 'decode_event' : 'decode_message', value);
    };
    const acknowledge = () => {
      emit('source_change', { ...parameters, scope: parameters.scope ? 'CONVENTIONAL' : 'CHANNEL', bound: true,
        filter_catalog: clone(topic === 'decode_events' ? eventCatalog : messageCatalog) });
      for (let index = 7; index >= 0 && !closed && available(); index--) replay(Date.now() - index * 3700);
    };
    const source = { replay,
      addEventListener(type, listener) {
        if (closed) return;
        if (!listeners.has(type)) listeners.set(type, new Set());
        listeners.get(type).add(listener);
      },
      removeEventListener(type, listener) { listeners.get(type)?.delete(listener); },
      update(next) {
        if (closed || !available()) return false;
        parameters = scopeFor(topic, next);
        acknowledge();
        return true;
      },
      close() {
        if (closed) return;
        closed = true;
        clear(timer);
        listeners.clear();
        liveSources.delete(source);
      }
    };
    liveSources.add(source);
    queueMicrotask(() => {
      if (closed || !available()) return;
      source.onopen?.();
      acknowledge();
      if (!closed && available()) timer = every(replay, topic === 'decode_events' ? 1800 : 650);
    });
    return source;
  };
  const connectionFactory = (topic, initialParameters, callbacks = {}) => {
    check();
    if (topic !== 'channel_diagnostics') throw new Error('Unsupported Live example diagnostic stream.');
    let parameters = scopeFor(topic, initialParameters), closed = false, timer = null, sequence = 0, generation = 1;
    let resolveClosed;
    const closedPromise = new Promise(resolve => { resolveClosed = resolve; });
    const deliver = frame => {
      if (closed || !available()) return;
      frames += 1;
      callbacks.onFrame?.(frame);
    };
    const state = () => deliver({ type: 1, generation, sequence: ++sequence,
      payload: new TextEncoder().encode(JSON.stringify({ ...parameters, signal_state: 'live', symbols_state: 'live',
        protocol: 'P25', decoder_profile: 'P25 Phase 1 (C4FM)', sample_rate_hz: 48000,
        channel_bandwidth_hz: 12500, maximum_visible_symbols: 4800 })) });
    const floatFrame = (type, values) => {
      const payload = new Uint8Array(values.length * 4), view = new DataView(payload.buffer);
      values.forEach((value, index) => view.setFloat32(index * 4, value, true));
      const now = Date.now();
      deliver({ type, generation, sequence: ++sequence, valueCount: values.length, strengthBits: 32,
        centerFrequencyHz: parameters.frequency_hz, sampleRateHz: 48000, fftSize: type === 2 ? values.length : 0,
        observedAtEpochMs: now - 12, encodedAtEpochMs: now, payload });
    };
    const replay = () => {
      if (closed || !available()) return;
      const spectrum = Array.from({ length: 512 }, (_, index) => {
        const offset = (index - 256) / 56;
        return -113 + 2.8 * Math.sin(index * 1.41 + sequence * 0.23) +
          63 * Math.exp(-Math.pow(offset, 4)) + 7 * Math.cos(index * 0.19 + sequence * 0.1) * Math.exp(-offset * offset);
      });
      const levels = [-3 * Math.PI / 4, -Math.PI / 4, Math.PI / 4, 3 * Math.PI / 4];
      const symbols = Array.from({ length: 480 }, (_, index) =>
        levels[(index * 7 + Math.floor(index / 9) + sequence) % 4] +
        Math.sin(index * 1.37 + sequence * 0.43) * 0.055);
      floatFrame(2, spectrum);
      floatFrame(3, symbols);
    };
    const source = { replay,
      update(next) {
        if (closed || !available()) return false;
        parameters = scopeFor(topic, next);
        generation += 1;
        state();
        replay();
        return true;
      },
      close() {
        if (closed) return;
        closed = true;
        clear(timer);
        diagnosticSources.delete(source);
        resolveClosed();
      },
      whenClosed: () => closedPromise
    };
    diagnosticSources.add(source);
    queueMicrotask(() => {
      if (closed || !available()) return;
      callbacks.onOpen?.();
      state();
      for (let index = 0; index < 12 && !closed && available(); index++) replay();
      if (!closed && available()) timer = every(replay, 140);
    });
    return source;
  };
  const dispose = () => {
    if (disposed) return;
    disposed = true;
    activitySources.forEach(source => source.close());
    liveSources.forEach(source => source.close());
    diagnosticSources.forEach(source => source.close());
    signal?.removeEventListener('abort', dispose);
  };
  signal?.addEventListener('abort', dispose, { once: true });
  if (signal?.aborted) dispose();
  return { activityFeed, liveConnection, connectionFactory, dispose,
    selectedTableId: 'guide-live-county', selectedRowKey: 'guide-live-sheriff',
    selectedFrequencyHz: SHERIFF_HZ, selectedConfigurationId: COUNTY,
    stats: () => ({ disposed, activityFeeds: activitySources.size, liveConnections: liveSources.size,
      diagnosticConnections: diagnosticSources.size, timers: timers.size, activityDeliveries, eventDeliveries,
      messageDeliveries, frames }) };
}
