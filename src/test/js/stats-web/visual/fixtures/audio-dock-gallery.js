import { createAudioDock } from '/assets/core/audio-dock.js';
import { createRecordingsFeature } from '/assets/features/recordings.js';

// The gallery mounts the production component and recording detail renderer.
// Only playback engines and call data are synthetic; no receiver is contacted.
export async function mountAudioDockGallery(parameters) {
  const node = (tag, className = '', text) => {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text !== undefined) element.append(text);
    return element;
  };
  const anchor = (text, path, className = '') => {
    const link = node('a', className, text);
    link.href = path;
    return link;
  };
  const href = (view, values = {}) => `/?${new URLSearchParams({ view, ...values })}`;
  const entityRefHref = (reference) => reference ? href({
    radio_system: 'radio-system', channel: 'channel', talkgroup: 'group-identity', radio: 'radio'
  }[reference.kind] || 'radio-system', { key: reference.key || reference.identity_key || 'sample' }) : null;
  const iconButton = (iconId, label, className = 'ui-button ui-button-secondary ui-icon-button') => {
    const button = node('button', className);
    button.type = 'button';
    button.setAttribute('aria-label', label);
    button.title = label;
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('aria-hidden', 'true');
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', `#${iconId}`);
    svg.append(use);
    button.append(svg);
    return button;
  };
  const uiToggleField = (labelText, checked) => {
    const field = node('div', 'ui-toggle-field');
    const copy = node('span', 'ui-toggle-copy'); copy.append(node('strong', '', labelText));
    const toggle = node('label', 'ui-toggle');
    const input = node('input'); input.type = 'checkbox'; input.checked = checked;
    input.setAttribute('aria-label', labelText);
    const track = node('span', 'ui-toggle-track'); track.append(node('span', 'ui-toggle-thumb'));
    const state = node('span', 'ui-toggle-state', checked ? 'On' : 'Off');
    input.addEventListener('change', () => { state.textContent = input.checked ? 'On' : 'Off'; });
    toggle.append(input, track, state); field.append(copy, toggle);
    return field;
  };

  const documentSource = await fetch('/app.html').then((response) => response.text());
  const sprite = new DOMParser().parseFromString(documentSource, 'text/html').querySelector('.icon-sprite');
  if (sprite) document.body.prepend(document.importNode(sprite, true));

  const fixture = parameters.get('audioFixture') || 'normal';
  const long = fixture === 'long';
  const empty = fixture === 'empty';
  const started = Date.parse('2026-09-30T14:24:00Z');
  const systemRef = { kind: 'radio_system', key: 'p25:00001:001' };
  const channelRef = { kind: 'channel', key: 'gallery-north-ridge' };
  const sourceRef = { kind: 'radio', radio_system_key: systemRef.key, identity_key: 'v1-r-00001-001-30914' };
  const targetRef = { kind: 'talkgroup', radio_system_key: systemRef.key, identity_key: 'v1-g-00001-001-1201' };
  const currentRecording = {
    id: 17, start_ms: started, end_ms: started + 42_000, duration_ms: 42_000,
    size_bytes: 184320, alias_list_id: 1, system_key: systemRef.key,
    system_name: 'Metro Public Safety', channel_id: channelRef.key, channel_name: 'North Ridge Channel',
    talkgroup_id: 1201, talkgroup_alias: long ?
      'Metropolitan Emergency Communications County Public Safety Dispatch' : 'Fire Dispatch',
    talkgroup_description: 'County fire dispatch and incident response operations', talkgroup_group: 'Fire',
    source_id: 30914, source_alias: 'Engine 4', source_description: 'Fire engine response crew',
    source_group: 'Field units', source_ota_alias: 'ENG 4',
    source_home_wacn: 1, source_home_system_id: 1, source_home_id: 30914,
    target_home_wacn: 1, target_home_system_id: 1, target_home_id: 1201,
    site_name: 'North Ridge', call_type: 'GROUP', protocol: 'APCO25_PHASE2', voice_type: 'CLEAR',
    frequency_hz: 851_012_500, timeslot: 1, nac: 293, wacn: 1, system_id: 1, rfss_id: 1, site_id: 2,
    audio_from: { name: 'North Ridge', wacn: 1, system_id: 1, rfss_id: 1, site_id: 2 },
    also_received_on: [{ name: 'Central Simulcast', wacn: 1, system_id: 1, rfss_id: 1, site_id: 3 }],
    radio_system_entity_ref: systemRef, channel_entity_ref: channelRef,
    target_entity_ref: targetRef, source_entity_ref: sourceRef,
    transcription: { status: 'COMPLETE', stored_at_ms: started + 60_000,
      text: 'Dispatch, Engine 4 has arrived at staging. All crews are accounted for. ' +
        (long ? 'The access road is clear. We are ready for the next assignment and will remain on this talkgroup. '.repeat(3) : '') }
  };
  const liveCall = {
    ...currentRecording, call_id: 'gallery-live-1', started_at_ms: started,
    target_id: 1201, target_alias: currentRecording.talkgroup_alias, target_form: 'TALKGROUP',
    source_form: 'RADIO', target_description: currentRecording.talkgroup_description,
    target_group: 'Fire', system: currentRecording.system_name, channel: currentRecording.channel_name,
    site: 'North Ridge', decoder: 'P25 Phase 2', protocol: 'APCO25_PHASE2',
    playback_target: { label: currentRecording.talkgroup_alias, kind: 'talkgroup' },
    alias_list_name: 'County Public Safety', _matchedScanListIds: ['1'],
    encrypted: false, vc_quality_pct: 97.4, vc_decoded_frames: 420,
    vc_repeated_frames: 0, vc_concealed_frames: 1, vc_missing_frames: 0,
    vc_fec_errors: 3, vc_fec_protected_bits: 11760
  };
  const recordingState = {
    current: empty ? null : currentRecording,
    queue: empty ? [] : [2, 3, 4].map((index) => ({ ...currentRecording, id: 16 + index,
      source_id: 30910 + index, source_alias: `Engine ${index + 3}`, start_ms: started + index * 60_000 })),
    history: empty ? [] : [{ ...currentRecording, id: 16, source_alias: 'Engine 3', start_ms: started - 60_000 }],
    playing: false, paused: !empty, stopped: empty, loading: false,
    status: empty ? 'Choose a recording to play' : 'Ready to play', currentTime: 8, duration: empty ? 0 : 42,
    volume: 0.7, canNext: !empty, canPrevious: !empty, clickMode: 'queue', continuation: false
  };
  const liveState = {
    current: empty ? null : liveCall, displayCall: empty ? null : liveCall,
    queue: empty ? [] : [2, 3].map((index) => ({ ...liveCall, call_id: `gallery-live-${index}`,
      source_alias: `Engine ${index + 3}`, started_at_ms: started + index * 60_000 })),
    queuedCount: empty ? 0 : 2, stopped: false, paused: false, currentReady: !empty,
    lastCallReady: !empty, currentTime: 8, duration: empty ? 0 : 42, volume: 0.7,
    status: empty ? 'Waiting for the next matching call' : 'Listening',
    scanListCatalogReady: true, maximumSelectedScanLists: 16,
    scanLists: [{ id: '1', name: long ? 'Metropolitan Emergency Communications County Public Safety' : 'County Fire & EMS',
      description: 'Dispatch and tactical calls', selected: true, enabled: true },
    { id: '2', name: 'Law Dispatch', description: 'County law enforcement operations', selected: false, enabled: true },
    { id: '3', name: 'Public Works', description: 'Temporarily unavailable', selected: false, enabled: false }],
    avoids: [{ key: 'tg:9100', label: 'Training Channel', system: 'Metro Public Safety' }],
    targetGrouping: true, targetBurstLimit: 4, holdTarget: null
  };
  const liveObservers = new Set();
  const recordingObservers = new Set();
  const emit = (state, observers) => observers.forEach((listener) => listener({ ...state }));
  const notifyLive = () => emit(liveState, liveObservers);
  const notifyRecording = () => emit(recordingState, recordingObservers);
  const subscribe = (state, observers, listener) => {
    observers.add(listener); listener({ ...state });
    return () => observers.delete(listener);
  };
  const live = {
    viewState: () => ({ ...liveState }), subscribeState: (listener) => subscribe(liveState, liveObservers, listener),
    togglePlayback: async () => { liveState.stopped = !liveState.stopped; liveState.paused = false;
      liveState.status = liveState.stopped ? 'Stopped' : 'Listening'; notifyLive(); },
    togglePause: async () => { liveState.paused = !liveState.paused;
      liveState.status = liveState.paused ? 'Paused · collecting calls' : 'Listening'; notifyLive(); },
    setVolume: (value) => { liveState.volume = value; notifyLive(); },
    skip: () => { liveState.current = liveState.displayCall = liveState.queue.shift() || null;
      liveState.queuedCount = liveState.queue.length; liveState.currentReady = Boolean(liveState.current); notifyLive(); },
    replayLastCall: async () => { liveState.currentTime = 0; notifyLive(); },
    clearQueue: () => { liveState.queue = []; liveState.queuedCount = 0; notifyLive(); },
    toggleHold: () => { liveState.holdTarget = liveState.holdTarget ? null : { label: 'Fire Dispatch' }; notifyLive(); },
    avoidCurrent: () => { liveState.avoids.push({ key: 'tg:1201', label: 'Fire Dispatch', system: 'Metro Public Safety' }); notifyLive(); },
    removeAvoid: (key) => { liveState.avoids = liveState.avoids.filter((item) => item.key !== key); notifyLive(); },
    setScanListSelected: (id, selected) => { liveState.scanLists.find((item) => item.id === id).selected = selected; notifyLive(); },
    applyPreferences: (value) => { liveState.targetGrouping = value.target_grouping ?? liveState.targetGrouping;
      liveState.targetBurstLimit = value.target_burst_limit ?? liveState.targetBurstLimit; notifyLive(); },
    writePreferences: () => {}
  };
  const detailRenderer = createRecordingsFeature({ node, anchor, href, entityRefHref,
    canViewRadio: () => true, isPrimaryAdmin: () => true });
  const recordings = {
    viewState: () => ({ ...recordingState }),
    subscribeState: (listener) => subscribe(recordingState, recordingObservers, listener),
    renderDetails: (host, call, options) => detailRenderer.playback.renderDetails(host, call, options),
    renderTranscript: (host) => detailRenderer.playback.renderTranscript(host, recordingState.current),
    toggle: async () => { recordingState.playing = !recordingState.playing; recordingState.stopped = false;
      recordingState.paused = !recordingState.playing; recordingState.status = recordingState.playing ? 'Playing recording' : 'Paused'; notifyRecording(); },
    next: async () => { if (recordingState.current) recordingState.history.push(recordingState.current);
      recordingState.current = recordingState.queue.shift() || null; recordingState.canNext = Boolean(recordingState.queue.length);
      recordingState.canPrevious = Boolean(recordingState.history.length); recordingState.currentTime = 0; notifyRecording(); },
    previous: async () => { recordingState.current = recordingState.history.pop() || currentRecording;
      recordingState.canPrevious = Boolean(recordingState.history.length); recordingState.currentTime = 0; notifyRecording(); },
    stop: () => { recordingState.playing = false; recordingState.stopped = true; recordingState.status = 'Stopped'; notifyRecording(); },
    seek: (position) => { recordingState.currentTime = Math.max(0, Math.min(recordingState.duration, position)); notifyRecording(); },
    skipSeconds: (amount) => { recordingState.currentTime = Math.max(0, Math.min(42, recordingState.currentTime + amount)); notifyRecording(); },
    setVolume: (value) => { recordingState.volume = value; notifyRecording(); },
    clearQueue: () => { recordingState.queue = []; recordingState.canNext = false; notifyRecording(); },
    setClickMode: (value) => { recordingState.clickMode = value; notifyRecording(); },
    queueHref: () => recordingState.queue.length ? href('recordings', {
      recording_queue: [recordingState.current, ...recordingState.queue].filter(Boolean).map((call) => call.id).join(',') }) : '',
    removeQueued: (id) => { recordingState.queue = recordingState.queue.filter((call) => call.id !== id);
      recordingState.canNext = Boolean(recordingState.queue.length); notifyRecording(); },
    playQueued: (id) => { recordingState.current = recordingState.queue.find((call) => call.id === id);
      recordingState.queue = recordingState.queue.filter((call) => call.id !== id);
      recordingState.playing = true; recordingState.stopped = false; recordingState.paused = false;
      recordingState.canNext = Boolean(recordingState.queue.length); notifyRecording(); },
    loadSharedQueue: async () => {}
  };
  let titlePreference = true;
  createAudioDock({ node, iconButton, uiToggleField, recordings, getLivePlayer: () => live,
    access: () => ({ live: true, recordings: fixture !== 'restricted' }), entityRefHref,
    canViewRadio: () => true, href, getTitlePreference: () => titlePreference,
    setTitlePreference: async (value) => { titlePreference = value; } });

  const dock = document.querySelector('#audio-dock');
  const clickNamed = (label) => [...dock.querySelectorAll('button')].find((button) =>
    button.getClientRects().length && (button.getAttribute('aria-label') === label || button.textContent === label))?.click();
  if (parameters.get('audioSource') === 'recordings') {
    const sourcePicker = dock.querySelector('.ui-audio-source-picker');
    if (sourcePicker.getClientRects().length) {
      sourcePicker.value = 'recordings';
      sourcePicker.dispatchEvent(new Event('change', { bubbles: true }));
    } else clickNamed('Recordings');
  }
  const size = parameters.get('audioState') || 'full';
  const handle = dock.querySelector('.ui-audio-handle');
  if (size !== 'minimal') handle.dispatchEvent(new KeyboardEvent('keydown', {
    key: size === 'collapsed' ? 'Home' : 'End', bubbles: true }));
  const panel = parameters.get('audioPanel');
  if (size === 'full' && panel) clickNamed(panel[0].toUpperCase() + panel.slice(1));
  if (parameters.get('audioHidden') === 'true') clickNamed('Hide audio player');
  document.querySelector('.visual-audio-dock-example').dataset.ready = 'true';
}
