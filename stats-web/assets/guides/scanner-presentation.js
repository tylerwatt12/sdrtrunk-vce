// Temporary observations for the production Scanner. The real player supplies
// target selection, queue order, Hold, Avoid, Pause, Stop, and Replay behavior.
// Only its receiver feed and audio transport are replaced with inert memory data.
import { WebCallPlayer } from '../web-call-player.js?v=20';
import { defaults, validate } from '../core/preference-schema.js?v=8';

const COUNTY = 'p25:bee00:123';
const CITY = 'p25:bee00:456';
const COUNTY_CHANNEL = 'b00ba000-0000-4000-8000-000000000001';
const CITY_CHANNEL = 'b00ba000-0000-4000-8000-000000000002';
const LAW = 9501;
const FIRE = 9502;
const clone = value => structuredClone(value);
const systemRef = key => ({ kind: 'radio_system', key });
const channelRef = key => ({ kind: 'channel', key });
const identityRef = (system, kind, id) => ({ kind, radio_system_key: system,
  identity_key: `v1-${kind === 'radio' ? 'r' : 'g'}-bee00-${system === CITY ? '456' : '123'}-${id}` });

export function createScannerGuideData({ signal, section } = {}) {
  let disposed = false, preferenceRevision = 1, preferenceUpdates = 0, deliveries = 0;
  const createdAt = Date.now();
  const listeners = new Set();
  const available = () => !disposed && !signal?.aborted;
  const check = () => {
    if (!available()) throw new DOMException('The Scanner example was closed.', 'AbortError');
  };
  const scanLists = [
    { id: String(LAW), name: 'Law Enforcement', description: 'County sheriff and city police', enabled: true, default: true },
    { id: String(FIRE), name: 'Fire/EMS', description: 'Regional and city fire and EMS', enabled: true, default: false }
  ];
  let preferences = clone(defaults);
  preferences.playback.selected_scan_list_ids = [LAW, FIRE];
  preferences.playback.volume = 0.75;
  preferences.page_titles.prepend_playing_call = false;
  const source = (system, target, targetName, radio, radioName, group, elapsed, duration, suffix) => {
    const city = system === CITY, systemId = city ? 0x456 : 0x123;
    const channel = city ? CITY_CHANNEL : COUNTY_CHANNEL;
    const completed = createdAt - elapsed;
    return { call_id: `guide-scanner-${suffix}`, started_at_ms: completed - duration,
      completed_at_ms: completed, duration_ms: duration,
      audio_url: `/api/v1/calls/guide-scanner-${suffix}/audio`, scan_list_ids: [group],
      configuration_id: channel, channel: city ? 'City Site' : 'North Site',
      system: city ? 'City P25' : 'County P25', system_name: city ? 'City P25' : 'County P25',
      radio_system_key: system, serving_system_key: system, protocol: 'P25', decoder: 'P25 Phase 1',
      channel_kind: 'TRUNKED', site: city ? 'City' : 'North', alias_list: city ? 'City P25' : 'County P25',
      source_form: 'RADIO', source_id: radio, source_alias: radioName,
      source_description: group === LAW ? 'Patrol unit' : 'Fire engine crew',
      source_group: group === LAW ? 'Field units' : 'Fire units',
      source_canonical_identity: { wacn: 0xBEE00, system_id: systemId, subscriber_id: radio },
      target_form: 'TALKGROUP', target_id: target, target_alias: targetName,
      target_description: group === LAW ? 'Law enforcement dispatch' : 'Fire and EMS dispatch',
      target_group: group === LAW ? 'Law Enforcement' : 'Fire/EMS',
      target_canonical_group_identity: { wacn: 0xBEE00, system_id: systemId, talkgroup_id: target },
      radio_system_entity_ref: systemRef(system), entity_ref: channelRef(channel), channel_entity_ref: channelRef(channel),
      source_entity_ref: identityRef(system, 'radio', radio), target_entity_ref: identityRef(system, 'talkgroup', target),
      playback_target: { kind: 'talkgroup', key: `${system}:talkgroup:${target}`,
        label: targetName, radio_system_key: system },
      frequency_hz: city ? target === 2001 ? 858412500 : 858662500 : target === 1001 ? 856912500 : 857162500,
      wacn: 0xBEE00, system_id: systemId, rfss_id: 1, site_id: city ? 2 : 1, nac: city ? 0x456 : 0x123,
      lcn: city ? '00-0272' : target === 1001 ? '00-0152' : '00-0172', modulation: 'C4FM', encrypted: false,
      vc_quality_pct: 98, vc_decoded_frames: 392, vc_repeated_frames: 4, vc_concealed_frames: 2,
      vc_missing_frames: 2, vc_fec_errors: 18, vc_fec_protected_bits: 59200 };
  };
  const examples = [
    source(COUNTY, 1001, 'Sheriff Dispatch', 214, 'Sheriff 214', LAW, 8_000, 8_000, 'sheriff'),
    source(COUNTY, 1002, 'Regional Fire', 7007, 'Engine 7', FIRE, 7_000, 8_000, 'regional-fire'),
    source(CITY, 2001, 'Police Department', 12012, 'Patrol 12', LAW, 5_000, 8_000, 'city-police'),
    source(COUNTY, 1002, 'Regional Fire', 7008, 'Engine 8', FIRE, 3_000, 8_000, 'regional-fire-followup'),
    source(CITY, 2002, 'City Fire', 7002, 'Engine 2', FIRE, 2_000, 8_000, 'city-fire'),
    source(CITY, 2001, 'Police Department', 12012, 'Patrol 12', LAW, 25_000, 7_000, 'previous-city-police')
  ];
  const knownCalls = new Set(examples.map(call => call.call_id));
  const engine = Object.create(WebCallPlayer.prototype);
  const bufferFor = call => ({ duration: Number(call.duration_ms) / 1000, sampleRate: 8000 });
  const clock = {
    state: 'running',
    get currentTime() { return (Date.now() - createdAt) / 1000; },
    async resume() { if (available()) this.state = 'running'; },
    async suspend() { this.state = 'suspended'; },
    createBufferSource() { return { buffer: null, onended: null, connect() {}, disconnect() {}, start() {}, stop() {} }; }
  };
  Object.assign(engine, {
    ui: { status: { textContent: 'Listening' }, volume: { value: '0.75' } },
    stateObservers: new Set(), queuedCalls: [], queuedCount: 0, avoids: new Map(),
    lastHeard: null, lastHeardBuffer: null, lastHeardSourceSampleRate: 8000,
    replayingLast: false, stopAfterReplay: false, holdTarget: null, heldDisplayCall: null,
    current: null, currentBuffer: null, currentSourceSampleRate: 8000, source: null,
    idleDisplayCall: null, idleDisplayTimer: null, idleDisplayDeadline: 0,
    audioContext: clock, analyserNode: {}, gainNode: null, playbackStartedAt: 0,
    progressFrame: null, transportToken: 0, loadToken: 0, loadController: null,
    feedActive: true, feedCursor: null, feedGeneration: 0, feedController: null, feedTimer: null,
    stopped: false, paused: false, volume: 0.75, statusValue: 'Listening',
    maximumQueued: WebCallPlayer.MAXIMUM_QUEUED_CALLS,
    maximumSelectedScanLists: WebCallPlayer.MAXIMUM_SELECTED_SCAN_LISTS,
    targetGrouping: true, targetBurstLimit: 4, arrivalSequence: 0,
    lastPlaybackTargetKey: null, consecutiveTargetCalls: 1,
    seenCallIds: new Set(), seenCallOrder: [], scanLists: clone(scanLists),
    scanListById: new Map(scanLists.map(item => [item.id, clone(item)])),
    selectedScanListIds: new Set(scanLists.map(item => item.id)), scanListCatalogReady: true,
    scanListCatalogState: 'ready', volumeEditRevision: 0, volumeEditPending: false,
    volumeCommit: null, preferenceWriteSequence: 0, pendingPreferenceWrites: new Map(),
    getSourceNameDisplay: () => 'source_alias',
    // These inert adapters leave the normal scheduling and transport decisions in
    // WebCallPlayer, while its DOM/network/audio work remains outside the example.
    render() { if (available()) { this.renderStatus(); this.notifyStateObservers(); } },
    renderVolume() {}, renderScanLists() {}, updateScanListStatus() {},
    ensureAudioContext() {}, startProgress() {}, stopProgress() {},
    clearIdleDisplay() { this.idleDisplayCall = null; this.idleDisplayDeadline = 0; },
    showIdleDisplay(call) {
      this.idleDisplayCall = call;
      this.idleDisplayDeadline = Date.now() + WebCallPlayer.IDLE_CALL_DISPLAY_MS;
    },
    ensureConnected() {
      if (!available() || this.stopped || !this.activeSelectedScanListIds().length) return false;
      this.feedActive = true;
      return true;
    },
    async loadCurrent() {
      check();
      if (!this.current) return;
      this.currentBuffer = bufferFor(this.current);
      this.currentSourceSampleRate = 8000;
      if (!this.stopped && !this.paused) this.startCurrent();
      else this.render();
    }
  });
  const normalized = examples.map(call => engine.normalizeCall(clone(call)));
  engine.current = normalized.find(call => call.call_id === 'guide-scanner-sheriff');
  engine.currentBuffer = bufferFor(engine.current);
  engine.lastPlaybackTargetKey = engine.current._playbackTargetKey;
  engine.lastHeard = normalized.find(call => call.call_id === 'guide-scanner-previous-city-police');
  engine.lastHeardBuffer = bufferFor(engine.lastHeard);
  normalized.filter(call => call !== engine.current && call !== engine.lastHeard)
    .forEach(call => engine.insertQueuedCall(call));
  const selectedCall = engine.current, previousCall = engine.lastHeard;
  engine.startCurrent();

  const readSnapshot = () => {
    check();
    return { identity: 'guide-scanner', loaded: true, revision: preferenceRevision, preferences: clone(preferences) };
  };
  const updatePreferences = async mutate => {
    check();
    if (typeof mutate !== 'function') throw new Error('Unknown Scanner example settings.');
    await Promise.resolve();
    check();
    const draft = clone(preferences), returned = mutate(draft);
    if (returned && typeof returned.then === 'function') throw new Error('Unknown Scanner example settings.');
    const requested = validate(returned && typeof returned === 'object' ? returned : draft);
    for (const key of Object.keys(preferences)) {
      if (!['playback', 'scanner', 'page_titles'].includes(key) && JSON.stringify(requested[key]) !== JSON.stringify(preferences[key]))
        throw new Error('Unknown Scanner example settings.');
    }
    if (requested.playback.selected_scan_list_ids.some(id => !scanLists.some(item => Number(item.id) === id)))
      throw new Error('Unknown Scanner example Scan List.');
    check();
    preferences = clone(requested);
    preferenceRevision += 1;
    preferenceUpdates += 1;
    engine.targetGrouping = preferences.playback.target_grouping;
    engine.targetBurstLimit = preferences.playback.target_burst_limit;
    engine.volume = preferences.playback.volume;
    engine.ui.volume.value = String(engine.volume);
    engine.selectedScanListIds = new Set(preferences.playback.selected_scan_list_ids.map(String));
    engine.filterQueueForSelectedLists();
    engine.render();
    return clone(preferences);
  };
  engine.preferenceWriter = playback => updatePreferences(draft => { draft.playback = clone(playback); });
  const method = name => (...args) => {
    check();
    const result = WebCallPlayer.prototype[name].apply(engine, args);
    if (result && typeof result.then === 'function') return result.then(value => { check(); return value; });
    return result;
  };
  const requireCall = call => {
    check();
    if (!knownCalls.has(call?.call_id)) throw new Error('Unknown Scanner example call.');
  };
  const player = Object.freeze({
    viewState() { check(); return clone(engine.viewState()); },
    subscribeState(observer) {
      check();
      if (typeof observer !== 'function') return () => {};
      const deliver = () => { if (available()) { deliveries += 1; observer(clone(engine.viewState())); } };
      listeners.add(deliver);
      let unsubscribe;
      try { unsubscribe = engine.subscribeState(deliver); }
      catch (error) { listeners.delete(deliver); engine.stateObservers.delete(deliver); throw error; }
      return () => { listeners.delete(deliver); unsubscribe(); };
    },
    targetLabel(call) { requireCall(call); return engine.targetLabel(call); },
    callDetails(call) { requireCall(call); return engine.callDetails(call); },
    readAudioSpectrum(levels, maximum = 8000) {
      if (!(levels instanceof Float32Array) || levels.length < 2 || levels.length > 512 ||
          !Number.isFinite(maximum) || maximum <= 0 || maximum > 48_000)
        throw new Error('Unknown Scanner example spectrum.');
      levels.fill(0);
      if (!available() || engine.stopped || engine.paused || !engine.current || !engine.source) return false;
      const phase = (Date.now() - createdAt) / 140;
      for (let index = 0; index < levels.length; index++) {
        const envelope = Math.exp(-index / (levels.length * 0.43));
        levels[index] = Math.min(1, envelope * (0.18 + 0.35 * Math.abs(Math.sin(index * 0.51 + phase)) +
          0.21 * Math.abs(Math.sin(index * 0.19 - phase * 0.67))));
      }
      return true;
    },
    togglePlayback: method('togglePlayback'), togglePause: method('togglePause'),
    skip: method('skip'), replayLastCall: method('replayLastCall'), toggleHold: method('toggleHold'),
    avoidCurrent: method('avoidCurrent'), clearQueue: method('clearQueue'),
    removeAvoid(key) {
      check();
      if (typeof key !== 'string' || !normalized.some(call => call._playbackTargetKey === key))
        throw new Error('Unknown Scanner example target.');
      return engine.removeAvoid(key);
    },
    setVolume: method('setVolume'),
    setScanListSelected(id, selected) {
      check();
      if (!scanLists.some(item => item.id === String(id)) || typeof selected !== 'boolean')
        throw new Error('Unknown Scanner example Scan List.');
      engine.setScanListSelected(id, selected);
    }
  });
  const channels = [
    { configuration_id: COUNTY_CHANNEL, name: 'North Site', site_name: 'North', system_name: 'County P25',
      radio_system_key: COUNTY, protocol: 'P25', decoder: 'P25_PHASE1', channel_kind: 'TRUNKED',
      p25_decoder_mode: 'C4FM', entity_ref: channelRef(COUNTY_CHANNEL) },
    { configuration_id: CITY_CHANNEL, name: 'City Site', site_name: 'City', system_name: 'City P25',
      radio_system_key: CITY, protocol: 'P25', decoder: 'P25_PHASE1', channel_kind: 'TRUNKED',
      p25_decoder_mode: 'C4FM', entity_ref: channelRef(CITY_CHANNEL) }
  ];
  const channelMetadata = async id => {
    check();
    const channel = channels.find(item => item.configuration_id === id);
    if (!channel) throw new Error('Unknown Scanner example channel.');
    await Promise.resolve();
    check();
    return clone(channel);
  };
  const dispose = () => {
    if (disposed) return;
    disposed = true;
    signal?.removeEventListener('abort', dispose);
    engine.transportToken += 1;
    engine.loadToken += 1;
    engine.stopSource();
    engine.current = null;
    engine.currentBuffer = null;
    engine.lastHeard = null;
    engine.lastHeardBuffer = null;
    engine.clearQueuedCalls();
    engine.avoids.clear();
    engine.holdTarget = null;
    engine.heldDisplayCall = null;
    engine.stateObservers.clear();
    listeners.clear();
  };
  if (signal?.aborted) dispose();
  else signal?.addEventListener('abort', dispose, { once: true });
  return { player, channelMetadata, readSnapshot, updatePreferences, dispose, section,
    selectedConfigurationId: COUNTY_CHANNEL, avoidedTargetLabel: 'Sheriff Dispatch',
    selectedCallId: selectedCall.call_id, previousCallId: previousCall.call_id,
    selectedTargetKey: selectedCall._playbackTargetKey,
    stats: () => ({ disposed, subscribers: listeners.size, deliveries, preferenceUpdates,
      timers: 0, connections: 0, audioContexts: 0 }) };
}
