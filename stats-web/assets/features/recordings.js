const CALLS = '/api/v1/recordings/calls';
const SUGGESTIONS = '/api/v1/recordings/suggestions';
const STATUS = '/api/v1/recordings/status';
const ADMIN = '/api/v1/admin/recordings';
const PAGE_SIZE = 50;
const SUGGESTION_DELAY_MS = 240;

const SEARCH_FIELDS = Object.freeze([
  ['system_key', 'Radio system', 'system', 'System name or key'],
  ['site', 'Site', 'site', 'Site name or ID'],
  ['talkgroup_id', 'Talkgroup', 'talkgroup', 'Talkgroup alias or ID'],
  ['radio_id', 'Radio ID', 'radio', 'Radio alias, ID, or OTA name'],
  ['channel_id', 'Analog channel', 'channel', 'Channel name']
]);

function queryPath(root, values = {}) {
  const query = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => {
    if (value !== '' && value !== null && value !== undefined) query.set(key, String(value));
  });
  return query.size ? `${root}?${query}` : root;
}

function value(row, ...names) {
  for (const name of names) {
    const candidate = row?.[name];
    if (candidate !== null && candidate !== undefined && candidate !== '') return candidate;
  }
  return null;
}

function label(row) {
  const group = value(row, 'talkgroup_alias', 'group_alias', 'talkgroup_name');
  const groupId = value(row, 'talkgroup_id', 'group_id');
  const destination = value(row, 'destination_radio_id');
  const destinationAlias = value(row, 'destination_radio_alias');
  const channel = value(row, 'channel_name', 'analog_channel_name');
  return group || (groupId !== null ? `Talkgroup ${groupId}` :
    (destination !== null ? `Direct to ${destinationAlias || `Radio ${destination}`}` : channel)) ||
    value(row, 'system_name', 'radio_system_name') || 'Recorded call';
}

function sourceLabel(row) {
  const source = value(row, 'source_alias', 'radio_alias');
  const id = value(row, 'source_id', 'radio_id');
  const ota = value(row, 'source_ota_alias', 'source_ota_ta', 'ota_alias', 'talker_alias');
  return [source || (id !== null ? `Radio ${id}` : null), source && id !== null ? `Radio ${id}` : null,
    ota && ota !== source ? `OTA ${ota}` : null].filter(Boolean).join(' · ');
}

function millis(row) {
  const numeric = Number(value(row, 'start_ms', 'started_at_ms', 'call_start_ms'));
  return Number.isFinite(numeric) ? numeric : 0;
}

function duration(row) {
  const number = Number(value(row, 'duration_ms', 'call_duration_ms'));
  if (!Number.isFinite(number) || number < 0) return '';
  const seconds = Math.round(number / 1000);
  return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
}

function dateTime(timestamp, dateOnly = false) {
  if (!timestamp) return '';
  return new Intl.DateTimeFormat(undefined, dateOnly ?
    { year: 'numeric', month: 'short', day: 'numeric' } :
    { year: 'numeric', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', second: '2-digit' })
    .format(new Date(timestamp));
}

function timeOnly(timestamp) {
  if (!timestamp) return '';
  return new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit', second: '2-digit' })
    .format(new Date(timestamp));
}

function localDateTime(milliseconds) {
  if (!milliseconds) return '';
  const date = new Date(milliseconds);
  const pad = (number) => String(number).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function numberOrNull(input) {
  const text = String(input || '').trim();
  if (!text) return null;
  const number = Number(text);
  return Number.isFinite(number) ? number : null;
}

function frequency(row) {
  const hertz = Number(value(row, 'frequency_hz', 'winner_frequency_hz'));
  return Number.isFinite(hertz) && hertz > 0 ? `${(hertz / 1_000_000).toFixed(4)} MHz` : '';
}

function siteText(site) {
  if (!site) return '';
  if (typeof site === 'string') return site;
  const name = value(site, 'site_name', 'name', 'site_key');
  if (name) return String(name);
  const rfss = value(site, 'rfss_id', 'rfss');
  const id = value(site, 'site_id');
  return [rfss !== null ? `RFSS ${rfss}` : '', id !== null ? `Site ${id}` : '']
    .filter(Boolean).join(' · ');
}

function winningSite(row) {
  const source = value(row, 'audio_from');
  return value(row, 'site_name', 'winner_site_name') || siteText(source);
}

function prettify(valueToFormat) {
  return String(valueToFormat || '').replaceAll('_', ' ').toLowerCase().replace(/\b\w/g, (match) => match.toUpperCase());
}

function protocolLabel(protocol) {
  return ({ APCO25: 'P25 Phase 1', APCO25_PHASE2: 'P25 Phase 2',
    NBFM: 'Analog FM', AM: 'Analog AM' })[protocol] || prettify(protocol);
}

function callKey(row) {
  return `${millis(row)}:${String(row?.id ?? '')}`;
}

function afterCall(row, selected) {
  if (millis(row) !== millis(selected)) return millis(row) > millis(selected);
  const id = Number(row?.id);
  const selectedId = Number(selected?.id);
  return Number.isFinite(id) && Number.isFinite(selectedId) ? id > selectedId :
    String(row?.id ?? '') > String(selected?.id ?? '');
}

function appendFact(node, host, name, detail) {
  if (detail === null || detail === undefined || detail === '') return;
  const term = node('dt', '', name);
  const description = node('dd', '', detail);
  host.append(term, description);
}

function button(node, text, action, className = 'ui-button ui-button-secondary') {
  const control = node('button', className, text);
  control.type = 'button';
  if (action) control.addEventListener('click', action);
  return control;
}

function select(node, options, labelText) {
  const control = node('select', 'ui-select');
  control.setAttribute('aria-label', labelText);
  options.forEach(([key, name]) => {
    const choice = node('option', '', name);
    choice.value = key;
    control.append(choice);
  });
  return control;
}

// Reuse map: the shared page header and section shells frame a compact data
// workspace; ui-input, ui-select, ui-button, ui-choice-card, ui-feedback, and
// the application's modal foundation own controls and feedback. This module
// owns only recordings geometry and the historical audio lifecycle.
export function createRecordingsFeature(deps) {
  const { node, requestJson, openReadOnlyModal, section, pageHeader, beginPage,
    captureRenderContext, renderIsCurrent, content, isPrimaryAdmin, stopLiveAudio, href, anchor } = deps;
  const search = {
    q: '', from_ms: '', to_ms: '', system_key: '', site: '', talkgroup_id: '', radio_id: '',
    channel_id: '', min_duration_ms: '', max_duration_ms: '', frequency_hz: '',
    talkgroup_min: '', talkgroup_max: '', radio_min: '', radio_max: '',
    call_type: '', voice_type: '', protocol: '', wacn: '', sysid: '', rfss: '', site_id: ''
  };
  const selectedSuggestions = new Map();
  const selection = new Set();
  let currentResults = [];
  let currentFilters = {};
  let currentPage = 0;
  let cursors = [null];
  let nextCursor = null;
  let resultTotal = null;
  let pageHost = null;
  let resultHost = null;
  let resultStatus = null;
  let countHost = null;
  let pagerHost = null;
  let sharedHost = null;
  let selectedFiltersHost = null;
  let searchHint = null;
  let searchInputs = new Map();
  let filterCountHost = null;
  let selectedHost = null;
  let sortControl = null;
  let searchRequest = 0;
  let searchSignal = null;
  let player = null;

  const makeNotice = (text, type = '') => {
    const notice = node('div', `ui-feedback${type ? ` ui-feedback-${type}` : ''}`, text);
    notice.setAttribute('role', type === 'error' ? 'alert' : 'status');
    return notice;
  };

  function query(filters, extra = {}) {
    return queryPath(CALLS, { ...filters, ...extra, limit: PAGE_SIZE });
  }

  function effectiveFilters() {
    return Object.fromEntries(Object.entries(search).filter(([key, item]) =>
      key !== 'range' && item !== '' && !(key === 'site' && selectedSuggestions.get('site')?.appliedKeys?.length)));
  }

  function applySuggestion(key, suggestion) {
    selectedSuggestions.get(key)?.appliedKeys?.forEach((applied) => { search[applied] = ''; });
    if (key === 'site') {
      const hasNumericSite = value(suggestion, 'rfss', 'rfss_id') !== null &&
        value(suggestion, 'site_id') !== null;
      const appliedKeys = [];
      for (const [target, names] of [
        ['wacn', ['wacn']], ['sysid', ['sysid', 'system_id']],
        ['rfss', ['rfss', 'rfss_id']], ['site_id', ['site_id']],
        ['channel_id', ['channel_id']]
      ]) {
        if (target === 'channel_id' && hasNumericSite) continue;
        const found = value(suggestion, ...names);
        if (found === null) continue;
        search[target] = String(found);
        appliedKeys.push(target);
      }
      search.site = String(suggestion.label || suggestion.id || '');
      if (!appliedKeys.length) search.site = String(suggestion.id || suggestion.label || '');
      selectedSuggestions.set(key, { ...suggestion, appliedKeys });
      return;
    }
    const range = key === 'talkgroup_id' && suggestion.kind === 'talkgroup_range' ?
      ['talkgroup_min', 'talkgroup_max'] :
      (key === 'radio_id' && suggestion.kind === 'radio_range' ? ['radio_min', 'radio_max'] : null);
    if (range) {
      search[key] = '';
      search[range[0]] = String(suggestion.min_id);
      search[range[1]] = String(suggestion.max_id);
      selectedSuggestions.set(key, { ...suggestion, appliedKeys: range });
    } else {
      search[key] = String(key === 'system_key' ?
        (suggestion.system_key ?? suggestion.id ?? suggestion.label ?? '') :
        (suggestion.id ?? suggestion.label ?? ''));
      selectedSuggestions.set(key, suggestion);
    }
    if (['radio_id', 'talkgroup_id'].includes(key) && suggestion.system_key) {
      search.system_key = String(suggestion.system_key);
      selectedSuggestions.set('system_key', {
        kind: 'system', id: suggestion.system_key, label: suggestion.system_name || suggestion.system_key
      });
    }
  }

  function audioUrl(row) {
    return `${CALLS}/${encodeURIComponent(String(row.id))}/audio`;
  }

  function recordedPlayer() {
    if (player) return player;
    const dock = node('section', 'recordings-player');
    dock.setAttribute('aria-label', 'Recorded call playback');
    dock.hidden = true;
    const title = node('strong', 'recordings-player-title', 'Recordings');
    const detail = node('span', 'recordings-player-detail', 'Queue ready');
    const status = node('span', 'recordings-player-status');
    status.setAttribute('role', 'status');
    const main = node('div', 'recordings-player-main');
    const heading = node('div', 'recordings-player-heading');
    heading.append(node('span', 'recordings-player-eyebrow', 'RECORDINGS'), title, detail);
    const play = button(node, 'Play', () => void toggleAudio(), 'ui-button ui-button-primary');
    const next = button(node, 'Next', () => void advance(), 'ui-button ui-button-secondary');
    const stop = button(node, 'Stop', () => stopAudio(), 'ui-button ui-button-secondary');
    const queueButton = button(node, 'Queue', () => {
      queuePanel.hidden = !queuePanel.hidden;
      queueButton.setAttribute('aria-expanded', String(!queuePanel.hidden));
    });
    queueButton.setAttribute('aria-expanded', 'false');
    const controls = node('div', 'recordings-player-controls');
    controls.append(play, next, stop, queueButton);
    const seek = node('input', 'recordings-player-seek');
    seek.type = 'range';
    seek.min = '0';
    seek.max = '1';
    seek.step = '0.1';
    seek.value = '0';
    seek.setAttribute('aria-label', 'Recording position');
    const elapsed = node('span', 'recordings-player-elapsed', '00:00 / 00:00');
    const progress = node('div', 'recordings-player-progress');
    progress.append(seek, elapsed);
    main.append(heading, progress, controls, status);
    const queuePanel = node('div', 'recordings-player-queue');
    queuePanel.hidden = true;
    dock.append(main, queuePanel);
    document.querySelector('.app-shell')?.append(dock);
    const audio = new Audio();
    audio.preload = 'metadata';
    player = { dock, title, detail, status, play, next, queuePanel, seek, elapsed, audio,
      current: null, queue: [], mode: 'idle', continuation: null, loading: false, token: 0 };
    audio.addEventListener('timeupdate', () => {
      if (!player || !audio.duration || !Number.isFinite(audio.duration)) return;
      seek.max = String(audio.duration);
      seek.value = String(audio.currentTime);
      elapsed.textContent = `${secondsLabel(audio.currentTime)} / ${secondsLabel(audio.duration)}`;
    });
    audio.addEventListener('ended', () => void advance());
    audio.addEventListener('error', () => {
      if (!player?.current) return;
      status.textContent = 'This recording could not be played. Moving to the next call.';
      void advance();
    });
    audio.addEventListener('play', () => { play.textContent = 'Pause'; });
    audio.addEventListener('pause', () => { play.textContent = 'Play'; });
    seek.addEventListener('input', () => {
      if (Number.isFinite(audio.duration)) audio.currentTime = Number(seek.value);
    });
    return player;
  }

  function secondsLabel(seconds) {
    const total = Math.max(0, Math.floor(Number(seconds) || 0));
    return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`;
  }

  function drawQueue() {
    const state = recordedPlayer();
    state.dock.hidden = !state.current && !state.queue.length && !state.continuation;
    document.body.classList.toggle('recordings-audio-active', Boolean(state.current));
    state.next.disabled = !state.queue.length && !state.continuation;
    const panel = state.queuePanel;
    panel.replaceChildren();
    const heading = node('div', 'recordings-queue-heading');
    heading.append(node('strong', '', 'Playback queue'),
      node('span', '', `${state.queue.length + (state.current ? 1 : 0)} loaded calls`));
    panel.append(heading);
    if (state.current) panel.append(queueItem(state.current, 'Now playing'));
    state.queue.slice(0, 20).forEach((row, index) => panel.append(queueItem(row, index === 0 ? 'Up next' : '')));
    if (state.continuation) panel.append(node('p', 'recordings-queue-note',
      'More matching calls load as playback reaches them.'));
    if (!state.current && !state.queue.length) panel.append(node('p', 'recordings-queue-note', 'Queue is empty.'));
  }

  function queueItem(row, overline) {
    const item = node('div', 'recordings-queue-item');
    if (overline) item.append(node('span', 'recordings-player-eyebrow', overline));
    item.append(node('strong', '', `${timeOnly(millis(row))} · ${label(row)}`));
    const source = sourceLabel(row);
    if (source) item.append(node('span', '', source));
    return item;
  }

  async function toggleAudio() {
    const state = recordedPlayer();
    if (state.current) {
      if (state.audio.paused) {
        try { await state.audio.play(); }
        catch (_error) { state.status.textContent = 'Playback could not start. Try again.'; }
      } else state.audio.pause();
      return;
    }
    await advance();
  }

  function stopAudio() {
    if (!player) return;
    const state = player;
    state.token++;
    state.audio.pause();
    state.audio.removeAttribute('src');
    state.audio.load();
    state.current = null;
    state.queue = [];
    state.continuation = null;
    state.mode = 'idle';
    state.status.textContent = '';
    drawQueue();
  }

  async function loadContinuation(state) {
    const continuation = state.continuation;
    if (!continuation || continuation.loading || continuation.done) return;
    continuation.loading = true;
    try {
      const result = await requestJson(query(continuation.filters, {
        from_ms: continuation.fromMs, sort: 'asc', cursor: continuation.cursor
      }), { page: false });
      if (state.continuation !== continuation) return;
      for (const row of Array.isArray(result?.calls) ? result.calls : []) {
        if (!afterCall(row, continuation.anchor)) continue;
        if (continuation.seen.has(callKey(row))) continue;
        continuation.seen.add(callKey(row));
        state.queue.push(row);
      }
      continuation.cursor = result?.next_cursor || null;
      continuation.done = !continuation.cursor;
      if (continuation.done && !state.queue.length) state.continuation = null;
      drawQueue();
    } catch (_error) {
      if (state.continuation === continuation) {
        continuation.done = true;
        state.status.textContent = 'Could not load the next matching calls.';
      }
    } finally {
      continuation.loading = false;
    }
  }

  async function advance() {
    const state = recordedPlayer();
    if (state.loading) return;
    const token = state.token;
    state.loading = true;
    try {
      state.audio.pause();
      while (!state.queue.length && state.continuation) {
        await loadContinuation(state);
        if (token !== state.token) return;
        if (state.continuation?.loading || state.continuation?.done) break;
      }
      const next = state.queue.shift();
      if (!next) {
        state.current = null;
        state.continuation = null;
        state.audio.removeAttribute('src');
        state.audio.load();
        state.status.textContent = 'Playback finished.';
        drawQueue();
        return;
      }
      await stopLiveAudio();
      if (token !== state.token) return;
      state.current = next;
      state.title.textContent = label(next);
      state.detail.textContent = `${dateTime(millis(next))}${sourceLabel(next) ? ` · ${sourceLabel(next)}` : ''}`;
      state.status.textContent = state.mode === 'continue' ? 'Continuing through matching calls' : '';
      state.audio.src = audioUrl(next);
      state.audio.currentTime = 0;
      state.seek.value = '0';
      state.elapsed.textContent = `00:00 / ${duration(next) || '00:00'}`;
      drawQueue();
      try { await state.audio.play(); }
      catch (_error) { state.status.textContent = 'Playback could not start. Press Play to retry.'; }
      if (state.continuation && state.queue.length < 8) void loadContinuation(state);
    } finally {
      state.loading = false;
      if (token !== state.token && state.queue.length) void advance();
    }
  }

  function playbackAction(row, action) {
    const state = recordedPlayer();
    if (action === 'queue') {
      state.queue.push(row);
      state.mode = state.mode === 'idle' ? 'queue' : state.mode;
      state.status.textContent = 'Call added to the recording queue.';
      drawQueue();
      return;
    }
    state.token++;
    state.audio.pause();
    state.current = null;
    state.queue = [row];
    state.continuation = action === 'continue' ? {
      filters: { ...currentFilters }, anchor: row, fromMs: millis(row), cursor: null,
      seen: new Set([callKey(row)]), loading: false, done: false
    } : null;
    state.mode = action;
    drawQueue();
    void advance();
  }

  function actionBar(row) {
    const actions = node('div', 'recordings-call-actions');
    actions.append(button(node, 'Play', () => playbackAction(row, 'once'), 'ui-button ui-button-primary'),
      button(node, 'Continue', () => playbackAction(row, 'continue')),
      button(node, 'Add to queue', () => playbackAction(row, 'queue')));
    return actions;
  }

  function callFacts(row) {
    const receivedSites = value(row, 'also_received_on', 'also_received_sites');
    const values = [
      ['Duration', duration(row)], ['Call type', prettify(value(row, 'call_type'))],
      ['Voice', prettify(value(row, 'voice_type'))], ['Frequency', frequency(row)],
      ['Audio from', winningSite(row)],
      ['Also received on', Array.isArray(receivedSites) ? receivedSites.map(siteText)
        .filter(Boolean).join(', ') : ''],
      ['Talkgroup ID', value(row, 'talkgroup_id', 'group_id')],
      ['Talkgroup alias', value(row, 'talkgroup_alias', 'group_alias')],
      ['Talkgroup description', value(row, 'talkgroup_description', 'group_description')],
      ['Talkgroup group', value(row, 'talkgroup_group', 'group_group')],
      ['Destination radio', value(row, 'destination_radio_id')],
      ['Destination alias', value(row, 'destination_radio_alias')],
      ['Destination description', value(row, 'destination_radio_description')],
      ['Destination group', value(row, 'destination_radio_group')],
      ['Source ID', value(row, 'source_id', 'radio_id')],
      ['Source alias', value(row, 'source_alias', 'radio_alias')],
      ['Source description', value(row, 'source_description', 'radio_description')],
      ['Latest OTA name', value(row, 'source_ota_alias', 'source_ota_ta', 'ota_alias', 'talker_alias')],
      ['Source group', value(row, 'source_group', 'radio_group')],
      ['Radio system', value(row, 'system_name', 'radio_system_name')],
      ['Analog channel', value(row, 'channel_name')],
      ['Protocol', protocolLabel(value(row, 'protocol'))],
      ['WACN', value(row, 'wacn')], ['SysID', value(row, 'system_id', 'sysid')],
      ['RFSS', value(row, 'rfss_id', 'rfss')], ['Site ID', value(row, 'site_id')],
      ['NAC', value(row, 'nac')], ['Tone', value(row, 'tone')],
      ['PL', value(row, 'pl')], ['DPL', value(row, 'dpl')]
    ];
    return values.filter(([, detail]) => detail !== null && detail !== undefined && detail !== '');
  }

  async function openDetails(row) {
    const body = node('div', 'recordings-detail');
    body.append(makeNotice('Loading call details…', 'loading'));
    const modal = openReadOnlyModal('Call details', body, { id: `recording-${row.id}`, className: 'recordings-detail-modal' });
    if (!modal) return;
    try {
      const detail = await requestJson(`${CALLS}/${encodeURIComponent(String(row.id))}`, { page: false });
      if (!modal.dialog.isConnected) return;
      const call = detail?.call || detail || row;
      const heading = node('div', 'recordings-detail-heading');
      heading.append(node('h3', '', label(call)), node('span', '', dateTime(millis(call))));
      const facts = node('dl', 'ui-fact-list recordings-detail-facts');
      callFacts(call).forEach(([name, fact]) => appendFact(node, facts, name, fact));
      const patchMembers = value(call, 'patch_members', 'patch_talkgroups');
      if (Array.isArray(patchMembers) && patchMembers.length) appendFact(node, facts, 'Patch members',
        patchMembers.map((member) => typeof member === 'object' ?
          value(member, 'alias', 'name', 'id', 'talkgroup_id') : member).filter(Boolean).join(', '));
      const actions = actionBar(call);
      body.replaceChildren(heading, facts, actions);
    } catch (error) {
      if (modal.dialog.isConnected) body.replaceChildren(makeNotice(error.message ||
        'Call details are unavailable. Refresh the results and try again.', 'error'));
    }
  }

  function callCard(row) {
    const card = node('article', 'recordings-call');
    const time = node('div', 'recordings-call-time');
    time.append(node('strong', '', timeOnly(millis(row))), node('span', '', dateTime(millis(row), true)));
    const main = node('div', 'recordings-call-main');
    const sharedGroup = Boolean(currentFilters.talkgroup_id);
    const sharedSource = Boolean(currentFilters.radio_id);
    const source = sourceLabel(row);
    const heading = button(node, sharedGroup && source ? source : label(row),
      () => void openDetails(row), 'link-button recordings-call-title');
    main.append(heading);
    const id = value(row, 'talkgroup_id', 'group_id');
    if (id !== null && !sharedGroup) main.append(node('span', 'recordings-call-id', `TG ${id}`));
    if (source && !sharedSource && !sharedGroup) main.append(node('div', 'recordings-call-source', source));
    const meta = node('div', 'recordings-call-meta');
    [winningSite(row), duration(row), prettify(value(row, 'call_type')),
      protocolLabel(value(row, 'protocol')), prettify(value(row, 'voice_type'))]
      .filter(Boolean).forEach((text) => meta.append(node('span', '', text)));
    main.append(meta);
    const actions = actionBar(row);
    if (isPrimaryAdmin()) {
      const checkbox = node('input', 'recordings-call-select');
      checkbox.type = 'checkbox';
      checkbox.checked = selection.has(String(row.id));
      checkbox.setAttribute('aria-label', `Select ${label(row)} at ${dateTime(millis(row))}`);
      checkbox.addEventListener('change', () => {
        if (checkbox.checked) selection.add(String(row.id));
        else selection.delete(String(row.id));
        drawSelection();
      });
      card.append(checkbox);
    }
    card.append(time, main, actions);
    return card;
  }

  function drawSharedContext() {
    if (!sharedHost) return;
    sharedHost.replaceChildren();
    const exact = [];
    const ranges = [];
    for (const [key, name] of [['system_key', 'Radio system'], ['talkgroup_id', 'Talkgroup'],
      ['radio_id', 'Radio ID'], ['channel_id', 'Channel']]) {
      if (currentFilters[key]) exact.push([name, selectedSuggestions.get(key)?.label || currentFilters[key]]);
    }
    if (currentFilters.talkgroup_min && currentFilters.talkgroup_max) {
      ranges.push(['Talkgroup range', selectedSuggestions.get('talkgroup_id')?.label ||
        `${currentFilters.talkgroup_min}–${currentFilters.talkgroup_max}`]);
    }
    if (currentFilters.radio_min && currentFilters.radio_max) {
      ranges.push(['Radio range', selectedSuggestions.get('radio_id')?.label ||
        `${currentFilters.radio_min}–${currentFilters.radio_max}`]);
    }
    if (selectedSuggestions.get('site')) exact.push(['Site', selectedSuggestions.get('site').label]);
    if (!exact.length && !ranges.length) return;
    const heading = node('div', 'recordings-shared-heading');
    heading.append(node('span', 'recordings-shared-overline',
      exact.length ? 'ALL MATCHING CALLS' : 'SEARCH FILTERS'));
    if (exact.length) heading.append(node('h3', '', exact.slice(0, 2)
      .map(([, detail]) => detail).join(' / ')), node('p', '', exact.slice(0, 2)
      .map(([name]) => name).join(' · ')));
    sharedHost.append(heading);
    [...exact.slice(2), ...ranges].forEach(([name, detail]) => sharedHost.append(node('span', 'ui-pill',
      `${prettify(name)}: ${detail}`)));
  }

  function drawSelectedFilters() {
    if (!selectedFiltersHost) return;
    selectedFiltersHost.replaceChildren();
    if (searchHint) searchHint.hidden = Boolean(search.system_key);
    if (filterCountHost) {
      filterCountHost.textContent = String(selectedSuggestions.size);
      filterCountHost.hidden = selectedSuggestions.size === 0;
    }
    for (const [key, suggestion] of selectedSuggestions) {
      if (!search[key] && !suggestion.appliedKeys?.some((applied) => search[applied])) continue;
      const remove = button(node, `${suggestion.label || suggestion.id} ×`, () => {
        suggestion.appliedKeys?.forEach((applied) => { search[applied] = ''; });
        search[key] = '';
        selectedSuggestions.delete(key);
        const input = searchInputs.get(key);
        if (input) input.value = '';
        drawSelectedFilters();
      }, 'ui-button ui-button-secondary recordings-filter-chip');
      remove.setAttribute('aria-label', `Remove ${suggestion.label || suggestion.id} filter`);
      selectedFiltersHost.append(remove);
    }
  }

  function drawSelection() {
    if (!selectedHost) return;
    selectedHost.replaceChildren();
    if (!selection.size || !isPrimaryAdmin()) return;
    selectedHost.append(node('strong', '', `${selection.size} selected`),
      button(node, 'Review delete', () => confirmDelete(), 'ui-button ui-button-danger-quiet'));
  }

  function confirmDelete() {
    if (!isPrimaryAdmin() || !selection.size) return;
    const ids = [...selection];
    const body = node('div', 'recordings-delete-confirm');
    body.append(node('p', '', `Delete ${ids.length} selected managed ${ids.length === 1 ? 'recording' : 'recordings'}? ` +
      'Their audio files and call entries will be removed.'));
    const status = node('div', 'recordings-form-status');
    status.setAttribute('role', 'status');
    const actions = node('div', 'ui-action-row');
    const cancel = button(node, 'Cancel', () => modal.close());
    const remove = button(node, 'Delete recordings', async () => {
      remove.disabled = true;
      modal.setBusy(true);
      status.replaceChildren(makeNotice('Deleting selected calls…', 'loading'));
      try {
        await requestJson(`${ADMIN}/calls`, { method: 'DELETE',
          body: { ids: ids.map((id) => Number(id)) }, page: false });
        if (player && ids.includes(String(player.current?.id))) stopAudio();
        else if (player) {
          player.queue = player.queue.filter((row) => !ids.includes(String(row.id)));
          drawQueue();
        }
        ids.forEach((id) => selection.delete(id));
        modal.setBusy(false);
        modal.close();
        void renderSearchPage();
      } catch (error) {
        modal.setBusy(false);
        remove.disabled = false;
        status.replaceChildren(makeNotice(error.message || 'Calls could not be deleted.', 'error'));
      }
    }, 'ui-button ui-button-danger');
    actions.append(cancel, remove);
    body.append(status, actions);
    const modal = openReadOnlyModal('Delete recordings', body, { id: 'delete-recordings',
      className: 'recordings-detail-modal' });
  }

  function drawResults() {
    if (!resultHost) return;
    resultHost.replaceChildren();
    if (!currentResults.length) {
      resultHost.append(makeNotice(nextCursor ?
        'No matches in this batch. More calls may match; select Next to continue.' :
        'No matching calls. Try a wider time range or fewer filters.', 'empty'));
    } else currentResults.forEach((row) => resultHost.append(callCard(row)));
    const showCount = Boolean(currentResults.length || nextCursor);
    const showPager = currentPage > 0 || Boolean(nextCursor);
    if (countHost) {
      countHost.hidden = !showCount;
      const count = resultTotal === null ? currentResults.length : Number(resultTotal);
      countHost.textContent = `${count.toLocaleString()} ${count === 1 ? 'call' : 'calls'}` +
        (resultTotal === null ? ' shown' : '') +
        (showPager ? ` · page ${currentPage + 1}${nextCursor ? ' · more available' : ''}` : '');
    }
    if (pagerHost) {
      pagerHost.hidden = !showPager;
      pagerHost.replaceChildren();
      const previous = button(node, 'Previous', () => void loadPage(currentPage - 1));
      previous.disabled = currentPage === 0;
      const next = button(node, 'Next', () => void loadPage(currentPage + 1));
      next.disabled = !nextCursor;
      pagerHost.append(previous, node('span', '', `Page ${currentPage + 1}`), next);
    }
    drawSharedContext();
    drawSelection();
  }

  async function loadPage(page = 0) {
    if (!pageHost) return;
    if (page > currentPage && !cursors[page]) cursors[page] = nextCursor;
    if (page < 0 || (page > 0 && !cursors[page])) return;
    const request = ++searchRequest;
    searchSignal?.abort();
    searchSignal = new AbortController();
    resultStatus.replaceChildren(makeNotice('Finding calls…', 'loading'));
    try {
      const result = await requestJson(query(currentFilters, {
        cursor: cursors[page], sort: sortControl.value
      }), { page: false, signal: searchSignal.signal });
      if (request !== searchRequest || !pageHost.isConnected) return;
      currentResults = Array.isArray(result?.calls) ? result.calls : [];
      currentPage = page;
      nextCursor = result?.next_cursor || null;
      resultTotal = result?.total !== null && result?.total !== undefined &&
        Number.isFinite(Number(result.total)) ? Number(result.total) : null;
      resultStatus.replaceChildren();
      drawResults();
    } catch (error) {
      if (request !== searchRequest || error?.name === 'AbortError') return;
      resultStatus.replaceChildren(makeNotice(error.message || 'Calls could not be loaded. Try again.', 'error'));
    }
  }

  function field(title, control) {
    const wrapper = node('label', 'ui-field recordings-field');
    wrapper.append(node('span', 'ui-field-label', title), control);
    return wrapper;
  }

  function textInput(placeholder, type = 'search') {
    const input = node('input', 'ui-input');
    input.type = type;
    input.placeholder = placeholder;
    input.autocomplete = 'off';
    return input;
  }

  function autocompleteFilter(key, title, kind, placeholder) {
    const input = textInput(placeholder);
    searchInputs.set(key, input);
    input.value = selectedSuggestions.get(key)?.label || search[key] || '';
    const control = node('div', 'recordings-autocomplete');
    const options = node('div', 'ui-popover recordings-suggestions');
    options.id = `recordings-options-${key}`;
    options.setAttribute('role', 'listbox');
    options.hidden = true;
    input.setAttribute('role', 'combobox');
    input.setAttribute('aria-autocomplete', 'list');
    input.setAttribute('aria-controls', options.id);
    input.setAttribute('aria-expanded', 'false');
    let timer = null;
    let blurTimer = null;
    let generation = 0;
    let active = -1;
    const open = () => {
      options.hidden = false;
      input.setAttribute('aria-expanded', 'true');
    };
    const close = () => {
      window.clearTimeout(timer);
      generation++;
      options.hidden = true;
      input.setAttribute('aria-expanded', 'false');
      input.removeAttribute('aria-activedescendant');
      active = -1;
    };
    const showMessage = (message, type = '') => {
      const notice = makeNotice(message, type);
      notice.classList.add('recordings-suggestion-feedback');
      options.replaceChildren(notice);
      open();
      active = -1;
      input.removeAttribute('aria-activedescendant');
    };
    const choose = (suggestion) => {
      if (key === 'q') {
        const matching = { system: 'system_key', site: 'site', talkgroup: 'talkgroup_id',
          talkgroup_range: 'talkgroup_id', radio: 'radio_id', radio_range: 'radio_id',
          channel: 'channel_id' }[suggestion.kind];
        if (matching) {
          applySuggestion(matching, suggestion);
          input.value = '';
          search.q = '';
          const matchingInput = searchInputs.get(matching);
          if (matchingInput) matchingInput.value = String(suggestion.label || suggestion.id || '');
          drawSelectedFilters();
        } else {
          input.value = String(suggestion.label || suggestion.id || '');
          search.q = String(suggestion.id ?? suggestion.label ?? '');
        }
      } else {
        input.value = String(suggestion.label || suggestion.id || '');
        applySuggestion(key, suggestion);
        drawSelectedFilters();
      }
      close();
      input.focus();
    };
    input.addEventListener('input', () => {
      search[key] = input.value.trim();
      selectedSuggestions.get(key)?.appliedKeys?.forEach((applied) => { search[applied] = ''; });
      selectedSuggestions.delete(key);
      window.clearTimeout(timer);
      const request = ++generation;
      const q = input.value.trim();
      if (q.length < 2) { close(); return; }
      showMessage('Finding suggestions…', 'loading');
      timer = window.setTimeout(async () => {
        try {
          const result = await requestJson(queryPath(SUGGESTIONS, {
            q, kind, system_key: key === 'system_key' ? '' : search.system_key, limit: 20
          }), { page: false });
          if (request !== generation || input.value.trim() !== q || !input.isConnected) return;
          options.replaceChildren();
          const rows = Array.isArray(result) ? result : (result?.rows || result?.suggestions || []);
          if (!rows.length) {
            showMessage('No matching names or IDs. Try another term.');
            return;
          }
          const heading = node('div', 'recordings-suggestion-heading');
          heading.append(node('strong', '', 'Suggestions'),
            node('span', '', `${rows.length} shown`));
          options.append(heading);
          rows.forEach((suggestion, index) => {
            const option = button(node, '', () => choose(suggestion),
              'ui-suggestion-option recordings-suggestion');
            option.id = `${options.id}-${index}`;
            option.setAttribute('role', 'option');
            const description = node('span', 'recordings-suggestion-copy');
            description.append(node('strong', '', String(suggestion.label || suggestion.id || 'Unnamed')));
            if (suggestion.detail) description.append(node('small', '', String(suggestion.detail)));
            option.append(node('span', 'recordings-suggestion-kind', prettify(suggestion.kind || kind || 'Match')),
              description);
            option.addEventListener('mousedown', (event) => event.preventDefault());
            options.append(option);
          });
          open();
        } catch (_error) {
          if (request === generation && input.isConnected) {
            showMessage('Suggestions could not load. Keep typing to retry.', 'error');
          }
        }
      }, SUGGESTION_DELAY_MS);
    });
    input.addEventListener('keydown', (event) => {
      const rows = [...options.querySelectorAll('[role="option"]')];
      if (event.key === 'Escape') { close(); return; }
      if (options.hidden || !rows.length) return;
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault();
        active = event.key === 'ArrowDown' ? (active + 1) % rows.length :
          (active < 0 ? rows.length - 1 : (active + rows.length - 1) % rows.length);
      } else if (event.key === 'Home' || event.key === 'End') {
        event.preventDefault();
        active = event.key === 'Home' ? 0 : rows.length - 1;
      }
      if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
        rows.forEach((row, index) => row.setAttribute('aria-selected', String(index === active)));
        input.setAttribute('aria-activedescendant', rows[active].id);
      } else if (event.key === 'Enter' && active >= 0) {
        event.preventDefault();
        rows[active].click();
      }
    });
    input.addEventListener('focus', () => window.clearTimeout(blurTimer));
    input.addEventListener('blur', () => {
      generation++;
      window.clearTimeout(timer);
      blurTimer = window.setTimeout(close, 120);
    });
    control.append(input, options);
    const wrapper = field(title, control);
    if (key === 'system_key') {
      searchHint = node('small', 'ui-field-detail',
        'Choose a radio system to search over-the-air radio names.');
      searchHint.hidden = Boolean(search.system_key);
      wrapper.append(searchHint);
    }
    if (key === 'site') wrapper.append(node('small', 'ui-field-detail', 'Choose a site from the suggestions.'));
    return wrapper;
  }

  function makeSearchForm() {
    const form = node('form', 'recordings-search');
    const primary = node('div', 'recordings-search-primary');
    searchInputs = new Map();
    const queryField = autocompleteFilter('q', 'Find a call', '',
      'System, site, talkgroup, radio, or channel');
    queryField.classList.add('recordings-query-field');
    const range = select(node, [['24h', 'Past 24 hours'], ['7d', 'Past 7 days'], ['30d', 'Past 30 days'],
      ['10y', 'Past 10 years'], ['custom', 'Custom dates']], 'Date & time');
    range.value = search.range || '24h';
    const from = textInput('', 'datetime-local');
    const to = textInput('', 'datetime-local');
    from.value = localDateTime(search.from_ms);
    to.value = localDateTime(search.to_ms);
    const customDates = node('div', 'recordings-custom-dates');
    customDates.hidden = range.value !== 'custom';
    customDates.append(field('From', from), field('To', to));
    range.addEventListener('change', () => { customDates.hidden = range.value !== 'custom'; });
    const rangeField = field('Date & time', range);
    primary.append(rangeField, queryField);
    const advanced = node('div', 'recordings-search-advanced');
    advanced.hidden = true;
    const advancedFields = node('div', 'recordings-search-fields');
    SEARCH_FIELDS.forEach(([key, title, kind, placeholder]) =>
      advancedFields.append(autocompleteFilter(key, title, kind, placeholder)));
    const minDuration = textInput('Seconds', 'number');
    minDuration.min = '0';
    minDuration.value = search.min_duration_ms ? String(Number(search.min_duration_ms) / 1000) : '';
    const maxDuration = textInput('Seconds', 'number');
    maxDuration.min = '0';
    maxDuration.value = search.max_duration_ms ? String(Number(search.max_duration_ms) / 1000) : '';
    const frequencyInput = textInput('MHz', 'number');
    frequencyInput.step = '0.0001';
    frequencyInput.min = '0';
    frequencyInput.value = search.frequency_hz ? String(Number(search.frequency_hz) / 1_000_000) : '';
    const callType = select(node, [['', 'Any call type'], ['CONVENTIONAL', 'Conventional'],
      ['GROUP', 'Group'], ['DIRECT', 'Direct'], ['PATCH', 'Patch']], 'Call type');
    callType.value = search.call_type;
    const voiceType = select(node, [['', 'Any voice type'], ['CLEAR', 'Clear'],
      ['ENCRYPTED', 'Encrypted'], ['UNKNOWN', 'Unknown']], 'Voice type');
    voiceType.value = search.voice_type;
    const protocol = select(node, [['', 'Any protocol'], ['APCO25', 'P25 Phase 1'],
      ['APCO25_PHASE2', 'P25 Phase 2'], ['DMR', 'DMR'], ['NXDN', 'NXDN'],
      ['NBFM', 'Analog FM'], ['AM', 'Analog AM'], ['DCS', 'DCS']], 'Protocol');
    protocol.value = search.protocol;
    advancedFields.append(field('Minimum length', minDuration),
      field('Maximum length', maxDuration), field('Frequency', frequencyInput),
      field('Call type', callType), field('Voice type', voiceType), field('Protocol', protocol));
    const protocolFields = {};
    for (const [key, title] of [['wacn', 'WACN'], ['sysid', 'SysID'], ['rfss', 'RFSS'], ['site_id', 'Site ID']]) {
      const input = textInput(title);
      input.value = search[key];
      protocolFields[key] = input;
      advancedFields.append(field(title, input));
    }
    advanced.append(advancedFields);
    const actions = node('div', 'recordings-search-actions');
    const submit = button(node, 'Search', null, 'ui-button ui-button-primary');
    submit.type = 'submit';
    const filters = button(node, '', null);
    filters.append(node('span', 'recordings-filter-label-desktop', 'More filters'),
      node('span', 'recordings-filter-label-mobile', 'Filters'));
    filterCountHost = node('span', 'ui-pill ui-pill-compact recordings-filter-count');
    filterCountHost.hidden = true;
    filters.append(filterCountHost);
    filters.setAttribute('aria-expanded', 'false');
    filters.setAttribute('aria-controls', 'recordings-advanced-filters');
    advanced.id = 'recordings-advanced-filters';
    const clear = button(node, 'Clear filters', () => {
      Object.keys(search).forEach((key) => { search[key] = ''; });
      selectedSuggestions.clear();
      selection.clear();
      pageHost.replaceChildren();
      renderSearchPage();
    }, 'ui-button ui-button-secondary recordings-clear-filters');
    filters.addEventListener('click', () => {
      if (!window.matchMedia('(max-width: 700px)').matches) {
        advanced.hidden = !advanced.hidden;
        filters.setAttribute('aria-expanded', String(!advanced.hidden));
        return;
      }
      const sheet = node('div', 'recordings-filter-sheet');
      const sheetFields = node('div', 'recordings-filter-sheet-fields');
      sheetFields.append(rangeField, customDates, advancedFields);
      const sheetActions = node('div', 'ui-action-row recordings-filter-sheet-actions');
      const clearSheet = button(node, 'Clear filters', () => {
        modal.close();
        clear.click();
      }, 'ui-button ui-button-secondary recordings-sheet-action');
      const showCalls = button(node, 'Show calls', () => {
        modal.close();
        form.requestSubmit();
      }, 'ui-button ui-button-primary recordings-sheet-action');
      sheetActions.append(clearSheet, showCalls);
      sheet.append(sheetFields, sheetActions);
      const modal = openReadOnlyModal('Filters', sheet, {
        id: 'recordings-filters', className: 'recordings-filter-modal',
        onClose: () => {
          primary.insertBefore(rangeField, queryField);
          form.insertBefore(customDates, advanced);
          advanced.append(advancedFields);
          filters.setAttribute('aria-expanded', 'false');
          filters.setAttribute('aria-controls', advanced.id);
        }
      });
      if (modal) {
        modal.dialog.id = 'recordings-filter-dialog';
        filters.setAttribute('aria-controls', modal.dialog.id);
        filters.setAttribute('aria-expanded', 'true');
      }
      else {
        primary.insertBefore(rangeField, queryField);
        form.insertBefore(customDates, advanced);
        advanced.append(advancedFields);
      }
    });
    actions.append(submit, filters);
    primary.append(actions);
    advanced.append(clear);
    selectedFiltersHost = node('div', 'recordings-selected-filters');
    form.append(primary, selectedFiltersHost, customDates, advanced);
    drawSelectedFilters();
    form.addEventListener('submit', (event) => {
      event.preventDefault();
      const now = Date.now();
      if (search.site && !selectedSuggestions.has('site')) {
        resultStatus.replaceChildren(makeNotice('Choose a site from the suggestions before searching.', 'error'));
        return;
      }
      for (const [key, name] of [['talkgroup_id', 'talkgroup'], ['radio_id', 'radio ID']]) {
        if (search[key] && !/^[0-9]+$/.test(String(search[key])) && !selectedSuggestions.has(key)) {
          resultStatus.replaceChildren(makeNotice(`Choose a ${name} suggestion or enter its numeric ID.`, 'error'));
          return;
        }
      }
      if (range.value === 'custom') {
        const fromMs = new Date(from.value).getTime();
        const toMs = new Date(to.value).getTime() + 59_999;
        if (!Number.isFinite(fromMs) || !Number.isFinite(toMs) || toMs <= fromMs ||
            toMs - fromMs > 3650 * 86_400_000) {
          resultStatus.replaceChildren(makeNotice('Choose a date range of up to 10 years, with the end after the start.',
            'error'));
          return;
        }
      }
      search.range = range.value;
      const rangeMs = { '24h': 86_400_000, '7d': 7 * 86_400_000, '30d': 30 * 86_400_000,
        '10y': 3650 * 86_400_000 }[range.value];
      search.from_ms = rangeMs ? String(now - rangeMs) : range.value === 'custom' && from.value ?
        String(new Date(from.value).getTime()) : '';
      search.to_ms = range.value === 'custom' && to.value ?
        String(new Date(to.value).getTime() + 59_999) : (rangeMs ? String(now) : '');
      search.min_duration_ms = minDuration.value ? String(Math.round(Number(minDuration.value) * 1000)) : '';
      search.max_duration_ms = maxDuration.value ? String(Math.round(Number(maxDuration.value) * 1000)) : '';
      search.frequency_hz = frequencyInput.value ? String(Math.round(Number(frequencyInput.value) * 1_000_000)) : '';
      search.call_type = callType.value;
      search.voice_type = voiceType.value;
      search.protocol = protocol.value;
      Object.entries(protocolFields).forEach(([key, input]) => {
        if (!input.value.trim() && selectedSuggestions.get('site')?.appliedKeys?.includes(key)) return;
        search[key] = input.value.trim();
      });
      currentFilters = effectiveFilters();
      cursors = [null];
      currentPage = 0;
      selection.clear();
      void loadPage(0);
    });
    return form;
  }

  async function renderSearchPage() {
    const context = captureRenderContext();
    searchRequest++;
    searchSignal?.abort();
    if (!beginPage(context, pageHeader('Recordings',
      'Find and play saved calls across radio systems and conventional channels.'))) return;
    pageHost = node('div', 'recordings-library data-workspace');
    const host = pageHost;
    host.append(makeNotice('Loading recordings…', 'loading'));
    content.append(host);
    let status;
    try {
      status = await requestJson(STATUS, { signal: context.signal });
    } catch (error) {
      if (renderIsCurrent(context) && host.isConnected) host.replaceChildren(makeNotice(
        error.message || 'Recording availability could not be checked. Try again.', 'error'));
      return;
    }
    if (!renderIsCurrent(context) || !host.isConnected) return;
    host.replaceChildren();
    if (status?.available !== true) {
      host.append(makeNotice('The managed recordings library is unavailable. Check receiver status and try again.',
        'error'));
      return;
    }
    if (!status?.has_calls) {
      const empty = node('section', 'ui-empty-state recordings-library-empty');
      if (status?.mode === 'MANAGED') {
        empty.append(node('h2', '', 'Ready for the first call'), node('p', '',
          'Managed Recordings is enabled. New saved calls will appear here after they finish.'));
        empty.append(button(node, 'Refresh calls', () => void renderSearchPage(),
          'ui-button ui-button-primary'));
      } else if (isPrimaryAdmin()) {
        empty.append(node('h2', '', 'Make every call easy to find'), node('p', '',
          'Enable Managed Recordings to organize calls into folders, search by radio or talkgroup, ' +
          'play them in your browser, and set an optional age limit.'));
        empty.append(anchor('Enable Managed Recordings', href('admin', { tab: 'recordings' }),
          'ui-button ui-button-primary'));
        empty.append(node('p', 'recordings-empty-footnote',
          'Using Trunking Recorder or another tool that watches the flat recordings folder? Keep Classic mode.'));
      } else {
        empty.append(node('h2', '', 'No managed recordings yet'), node('p', '',
          'This receiver saves new calls to the Classic recordings folder. Managed calls will appear here if ' +
          'the primary administrator enables Managed Recordings.'));
      }
      host.append(empty);
      return;
    }
    if (status?.mode === 'CLASSIC') host.append(node('p', 'ui-notice recordings-mode-notice',
      'Classic mode is saving new calls in the flat recordings folder. Earlier Managed calls remain searchable here.'));
    const form = makeSearchForm();
    const searchSection = node('section', 'section ui-section recordings-search-panel');
    searchSection.setAttribute('aria-label', 'Search calls');
    searchSection.append(form);
    host.append(searchSection);
    const resultSection = node('section', 'section ui-section');
    const titleBar = node('div', 'section-title ui-section-title');
    titleBar.append(node('span', '', 'Calls'));
    const titleActions = node('div', 'ui-section-actions');
    sortControl = select(node, [['desc', 'Newest first'], ['asc', 'Oldest first']], 'Sort calls');
    sortControl.addEventListener('change', () => {
      cursors = [null];
      currentPage = 0;
      void loadPage(0);
    });
    titleActions.append(sortControl);
    titleBar.append(titleActions);
    const body = node('div', 'recordings-results-body');
    sharedHost = node('div', 'recordings-shared');
    selectedHost = node('div', 'recordings-selected');
    countHost = node('div', 'recordings-result-count');
    resultStatus = node('div', 'recordings-result-status');
    resultStatus.setAttribute('role', 'status');
    resultHost = node('div', 'recordings-results');
    pagerHost = node('nav', 'recordings-pager ui-pager');
    pagerHost.setAttribute('aria-label', 'Call result pages');
    body.append(sharedHost, selectedHost, countHost, resultStatus, resultHost, pagerHost);
    resultSection.append(titleBar, body);
    host.append(resultSection);
    if (Object.values(search).every((item) => !item)) search.from_ms = String(Date.now() - 86_400_000);
    currentFilters = effectiveFilters();
    cursors = [null];
    currentPage = 0;
    void loadPage(0);
  }

  async function renderAdminRecordings() {
    const wrapper = node('div', 'recordings-admin editor-workspace');
    const status = node('div', 'recordings-form-status');
    status.setAttribute('role', 'status');
    wrapper.append(status);
    content.append(wrapper);
    try {
      const [settings, catalog] = await Promise.all([
        requestJson(`${ADMIN}/settings`), requestJson(`${ADMIN}/status`)
      ]);
      if (!wrapper.isConnected) return;
      const modeBody = node('div', 'recordings-admin-body');
      const modes = node('div', 'recordings-mode-choices');
      const radios = {};
      for (const [mode, title, detail] of [
        ['CLASSIC', 'Classic', 'Keep a flat recordings folder for tools such as Trunking Recorder.'],
        ['MANAGED', 'Managed · Recommended', 'Organize calls automatically, search by radio or talkgroup, ' +
          'listen in your browser, and clear old calls by age.']
      ]) {
        const choice = node('label', 'ui-choice-card recordings-mode-choice');
        const radio = node('input', 'ui-choice-radio');
        radio.type = 'radio';
        radio.name = 'recording-mode';
        radio.value = mode;
        radio.checked = settings?.mode === mode;
        radios[mode] = radio;
        choice.append(radio, node('strong', '', title), node('span', '', detail));
        modes.append(choice);
      }
      const folder = node('dl', 'ui-fact-list recordings-managed-folder');
      appendFact(node, folder, 'Managed folder', settings?.managed_directory);
      const modeStatus = node('div', 'recordings-form-status');
      modeStatus.setAttribute('role', 'status');
      const saveMode = button(node, 'Save mode', async () => {
        const mode = radios.MANAGED.checked ? 'MANAGED' : 'CLASSIC';
        saveMode.disabled = true;
        try {
          await requestJson(`${ADMIN}/settings`, { method: 'PUT', body: { mode }, page: false });
          settings.mode = mode;
          modeStatus.replaceChildren(makeNotice('Recording mode saved.'));
        } catch (error) {
          modeStatus.replaceChildren(makeNotice(error.message || 'Mode could not be saved.', 'error'));
        } finally { saveMode.disabled = false; }
      }, 'ui-button ui-button-primary');
      modeBody.append(modes, folder, node('p', 'recordings-admin-note',
        'Managed calls are saved as MP3 for browser playback. The recording format setting applies to Classic mode. ' +
        'Existing managed calls stay searchable when you switch to Classic, and their age limit still applies.'),
      saveMode, modeStatus);
      const ageBody = node('div', 'recordings-admin-body');
      const age = textInput('No age limit', 'number');
      age.min = '1';
      age.step = '1';
      age.value = settings?.retention_days == null ? '' : String(settings.retention_days);
      const ageStatus = node('div', 'recordings-form-status');
      ageStatus.setAttribute('role', 'status');
      const saveAge = button(node, 'Save age limit', async () => {
        const days = numberOrNull(age.value);
        if (age.value && (!Number.isInteger(days) || days < 1)) {
          ageStatus.replaceChildren(makeNotice('Enter a whole number of days, or leave the field blank.', 'error'));
          return;
        }
        saveAge.disabled = true;
        try {
          await requestJson(`${ADMIN}/settings`, { method: 'PUT', body: { retention_days: days }, page: false });
          ageStatus.replaceChildren(makeNotice('Age limit saved.'));
        } catch (error) {
          ageStatus.replaceChildren(makeNotice(error.message || 'Age limit could not be saved.', 'error'));
        } finally { saveAge.disabled = false; }
      }, 'ui-button ui-button-primary');
      ageBody.append(field('Remove managed calls older than (days)', age), saveAge, ageStatus);
      const catalogBody = node('div', 'recordings-admin-body');
      const catalogFacts = node('dl', 'ui-facts recordings-admin-facts');
      const stat = (name, detail) => {
        if (detail === null || detail === undefined || detail === '') return;
        const fact = node('div', 'ui-fact');
        fact.append(node('dt', '', name), node('dd', '', detail));
        catalogFacts.append(fact);
      };
      const operationStatus = node('div', 'recordings-form-status');
      operationStatus.setAttribute('role', 'status');
      const operation = (snapshot) => snapshot?.maintenance || snapshot?.operation || snapshot || {};
      const operationState = (snapshot) => String(value(operation(snapshot), 'state', 'status') || 'IDLE')
        .toUpperCase();
      const activeOperation = (snapshot) => ['RUNNING', 'QUEUED', 'IN_PROGRESS'].includes(operationState(snapshot));
      const drawCatalog = (snapshot) => {
        catalogFacts.replaceChildren();
        const counts = snapshot?.catalog || snapshot;
        stat('Listed calls', Number(value(counts, 'call_count', 'total_calls') || 0).toLocaleString());
        stat('Oldest call', dateTime(value(counts, 'oldest_call_ms', 'oldest_start_ms'), true));
        stat('Last recount', dateTime(value(snapshot, 'last_recount_ms')));
        stat('Maintenance', prettify(operationState(snapshot)));
        const current = operation(snapshot);
        if (activeOperation(snapshot)) operationStatus.replaceChildren(makeNotice(
          `${prettify(value(current, 'kind') || 'Maintenance')} running` +
            `${value(current, 'inspected') !== null ? ` · ${Number(current.inspected).toLocaleString()} checked` : ''}`,
          'loading'));
      };
      const refreshCatalog = async () => {
        const latest = await requestJson(`${ADMIN}/status`, { page: false });
        if (wrapper.isConnected) drawCatalog(latest);
        return latest;
      };
      const watchMaintenance = async (initial) => {
        let latest = initial;
        while (wrapper.isConnected && activeOperation(latest)) {
          await new Promise((resolve) => window.setTimeout(resolve, 1_500));
          if (!wrapper.isConnected) return;
          try { latest = await refreshCatalog(); }
          catch (error) {
            operationStatus.replaceChildren(makeNotice(error.message || 'Maintenance status could not be read.',
              'error'));
            return;
          }
        }
        if (wrapper.isConnected && initial !== latest) {
          const failed = operationState(latest) === 'FAILED';
          operationStatus.replaceChildren(makeNotice(failed ?
            'Maintenance could not finish. Review the receiver log before trying again.' :
            'Maintenance finished. Call totals are current.', failed ? 'error' : ''));
        }
      };
      drawCatalog(catalog);
      const maintenance = node('div', 'recordings-maintenance');
      const run = (name, description, action) => {
        const row = node('div', 'recordings-maintenance-row');
        const copy = node('div');
        copy.append(node('strong', '', name), node('p', '', description));
        const control = button(node, `Run ${name.toLowerCase()}`, async () => {
          control.disabled = true;
          operationStatus.replaceChildren(makeNotice(`${name} started…`, 'loading'));
          try {
            await requestJson(`${ADMIN}/${action}`, { method: 'POST', page: false,
              timeoutMs: 60_000 });
            const latest = await refreshCatalog();
            if (activeOperation(latest)) await watchMaintenance(latest);
            else operationStatus.replaceChildren(makeNotice(`${name} complete.`));
          } catch (error) {
            operationStatus.replaceChildren(makeNotice(error.message || `${name} could not be started.`, 'error'));
          } finally { control.disabled = false; }
        });
        row.append(copy, control);
        maintenance.append(row);
      };
      run('Recount calls', 'Refresh the displayed call total.', 'recount');
      run('Reindex calls', 'Check listed calls and remove broken entries. On a very large library, this can delay ' +
        'new managed calls and may cause some to be dropped. Run during quiet reception.', 'reindex');
      catalogBody.append(catalogFacts, maintenance, operationStatus,
        node('p', 'recordings-admin-note', 'Reindex checks listed calls. It does not recover unlisted audio files.'));
      wrapper.append(section('Recording mode', modeBody), section('Managed call retention', ageBody),
        section('Call catalog', catalogBody));
      if (activeOperation(catalog)) void watchMaintenance(catalog);
    } catch (error) {
      if (wrapper.isConnected) status.replaceChildren(makeNotice(error.message ||
        'Recording settings could not be loaded. Try again.', 'error'));
    }
  }

  return { renderSearchPage, renderAdminRecordings, stopAudio };
}
