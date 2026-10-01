/* Reuse map: global workspace composition; ui-button/ui-icon-button, ui-segmented,
 * ui-range, ui-feedback, ui-fact-list and ui-section-disclosure. Existing scanner
 * and recording-choice modal retain their lifecycle. One live engine and one
 * recording adapter; the dock owns presentation only, in light/dark and all sizes. */
export function createAudioDock({ node, iconButton, recordings, getLivePlayer, access,
  entityRefHref, canViewRadio, href, getTitlePreference, setTitlePreference }) {
  const dock = node('section', 'audio-dock ui-audio-surface');
  dock.id = 'audio-dock';
  dock.setAttribute('aria-label', 'Audio player');
  let size = 'minimal';
  let source = 'live';
  let panel = 'details';
  let liveState = {};
  let recordingState = recordings.viewState();
  let previousRecordingActive = false;
  let previousLiveActive = false;
  let boundLive = null;
  let unsubscribeLive = () => {};
  let panelKey = '';
  const lastVolume = { live: 1, recordings: 1 };
  let sharedQueueKey = '';

  const command = (label, icon, action, primary = false) => {
    const button = iconButton(`icon-${icon}`, label,
      `ui-button ui-button-secondary ui-icon-button ui-audio-command${primary ? ' ui-audio-command-primary' : ''}`);
    button.addEventListener('click', action);
    return button;
  };
  const textButton = (label, action) => {
    const button = node('button', 'ui-button', label);
    button.type = 'button';
    button.addEventListener('click', action);
    return button;
  };
  const setLabel = (button, label, icon) => {
    button.setAttribute('aria-label', label);
    button.title = label;
    if (icon) button.querySelector('use')?.setAttribute('href', `#icon-${icon}`);
  };
  const state = () => source === 'live' ? liveState : recordingState;
  const current = () => state().displayCall || state().current;
  const identityType = (form, fallback = 'ID') =>
    ({ TALKGROUP: 'TGID', PATCH_GROUP: 'Patch', RADIO: 'Radio' })[String(form || '').toUpperCase()] || fallback;
  const title = (call) => {
    if (!call) return 'Nothing queued';
    const analog = ['NBFM', 'AM'].includes(String(call.decoder || call.protocol || '').toUpperCase());
    if (analog) return call.channel_name || call.channel || call.playback_target?.label || 'Recorded channel';
    const alias = call.talkgroup_alias || call.group_alias || call.destination_radio_alias || call.target_alias || call.target_display;
    if (alias) return String(alias);
    const target = call.talkgroup_id ?? call.group_id ?? call.destination_radio_id ?? call.target_id;
    const kind = call.call_type === 'PATCH' ? 'Patch' : call.destination_radio_id !== undefined &&
      call.destination_radio_id !== null ? 'Radio' : identityType(call.target_form,
        call.talkgroup_id !== undefined && call.talkgroup_id !== null ? 'TGID' : 'ID');
    if (target !== undefined && target !== null && target !== '') return `${kind} ${target}`;
    return call.playback_target?.label || call.channel_name || call.channel || 'Recorded call';
  };
  const sourceName = (call) => call?.source_alias || call?.source_ota_alias || call?.talker_alias ||
    (call?.source_id !== undefined && call?.source_id !== null ? `Radio ${call.source_id}` : '');
  const sourceSummary = (call) => {
    if (!call) return '';
    const alias = call.source_alias || call.radio_alias;
    const id = call.source_id ?? call.radio_id;
    const ota = call.source_ota_alias || call.source_ota_ta || call.talker_alias || call.ota_alias;
    return [alias || (id !== undefined && id !== null && id !== '' ? `${identityType(call.source_form, 'Radio')} ${id}` : ''),
      alias && id !== undefined && id !== null && id !== '' ? `${identityType(call.source_form, 'Radio')} ${id}` : '',
      ota && String(ota).toLowerCase() !== String(alias || '').toLowerCase() ? `OTA ${ota}` : ''].filter(Boolean).join(' · ');
  };
  const time = (seconds) => {
    const value = Math.max(0, Math.floor(Number(seconds) || 0));
    return `${Math.floor(value / 60).toString().padStart(2, '0')}:${(value % 60).toString().padStart(2, '0')}`;
  };
  const date = (call) => {
    const value = call?.started_at_ms ?? call?.start_ms;
    return Number.isFinite(Number(value)) && Number(value) > 0 ? new Date(Number(value)).toLocaleString() : '';
  };
  const subtitle = (call) => [sourceSummary(call), call?.system || call?.system_name, call?.channel || call?.channel_name]
    .filter(Boolean).join(' · ');
  const queueCount = () => source === 'live' ? Number(liveState.queuedCount) || 0 : recordingState.queue?.length || 0;
  const isAllowed = () => Boolean(access()[source]);
  const setSize = (value) => {
    const returnFocus = dock.contains(document.activeElement);
    size = value; panelKey = ''; render();
    if (returnFocus) (size === 'collapsed' ? collapsedExpand : size === 'minimal' ? expand : minimal).focus();
  };
  const switchSource = (value) => {
    if (source === value || !access()[value]) return;
    if (source === 'recordings') recordings.stop();
    else if (getLivePlayer() && !liveState.stopped) void getLivePlayer().togglePlayback();
    source = value;
    if (panel === 'transcript' && source === 'live') panel = 'details';
    if (panel === 'listening' && source === 'recordings') panel = 'details';
    panelKey = '';
    render();
  };
  const toggle = () => {
    if (!isAllowed()) return;
    if (source === 'recordings') void recordings.toggle();
    else {
      const player = getLivePlayer();
      if (!player) return;
      if (liveState.stopped) void player.togglePlayback();
      else void player.togglePause();
    }
  };
  const next = () => source === 'recordings' ? void recordings.next() : getLivePlayer()?.skip();
  const changeVolume = (value, persist = true) => {
    const bounded = Math.max(0, Math.min(1, Number(value)));
    if (!Number.isFinite(bounded)) return;
    if (bounded > 0) lastVolume[source] = bounded;
    if (source === 'recordings') recordings.setVolume(bounded);
    else getLivePlayer()?.setVolume(bounded, persist);
    render();
  };
  const mute = () => {
    const volume = Number(state().volume);
    if (volume > 0) { lastVolume[source] = volume; changeVolume(0); }
    else changeVolume(lastVolume[source] || 1);
  };

  const collapsed = node('div', 'audio-dock-collapsed');
  const collapsedCopy = node('div', 'audio-dock-copy');
  const collapsedTitle = node('strong', 'audio-dock-single-line');
  const collapsedMeta = node('span', 'audio-dock-meta');
  collapsedCopy.append(collapsedTitle, collapsedMeta);
  const collapsedExpand = command('Expand audio player', 'arrow-up', () => setSize('minimal'));
  collapsed.append(collapsedCopy, collapsedExpand);

  const header = node('div', 'audio-dock-header');
  const sourceButtons = node('div', 'ui-segmented ui-audio-sources');
  sourceButtons.setAttribute('role', 'group');
  sourceButtons.setAttribute('aria-label', 'Audio source');
  const sourceControls = {};
  ['live', 'recordings'].forEach((value) => {
    const button = node('button', 'ui-segmented-option', value === 'live' ? 'Live' : 'Recordings');
    button.type = 'button';
    button.addEventListener('click', () => switchSource(value));
    sourceControls[value] = button;
    sourceButtons.append(button);
  });
  const count = textButton('Queue 0', () => { panel = 'queue'; setSize('full'); });
  count.classList.add('ui-audio-count');
  const minimal = command('Show minimal controls', 'minimize', () => setSize('minimal'));
  const expand = command('Expand audio player', 'arrow-up', () => setSize('full'));
  const collapse = command('Collapse audio player', 'chevron-down', () => setSize('collapsed'));
  header.append(sourceButtons, count, minimal, expand, collapse);

  const body = node('div', 'audio-dock-body');
  const now = node('div', 'audio-dock-now');
  const copy = node('div', 'audio-dock-copy');
  const nowTitle = node('strong', 'audio-dock-title');
  const nowSubtitle = node('span', 'audio-dock-meta');
  const status = node('span', 'audio-dock-status');
  status.setAttribute('role', 'status');
  copy.append(nowTitle, nowSubtitle, status);
  const miniControls = node('div', 'audio-dock-mini-controls');
  const miniPlay = command('Play audio', 'play', toggle, true);
  const miniNext = command('Next call', 'skip', next);
  const miniMute = command('Mute audio', 'speaker', mute);
  miniControls.append(miniPlay, miniNext, miniMute);
  now.append(copy, miniControls);

  const timing = node('div', 'audio-dock-timing');
  const elapsed = node('span', 'audio-dock-time');
  const seek = node('input', 'ui-range ui-audio-range');
  seek.type = 'range'; seek.min = '0'; seek.max = '1'; seek.step = '0.1'; seek.value = '0';
  seek.setAttribute('aria-label', 'Recording position');
  seek.addEventListener('input', () => recordings.seek(Number(seek.value)));
  const progress = node('progress', 'ui-audio-progress');
  progress.max = 1; progress.value = 0; progress.setAttribute('aria-label', 'Live call progress');
  const duration = node('span', 'audio-dock-time');
  timing.append(elapsed, seek, progress, duration);

  const transport = node('div', 'audio-dock-transport');
  transport.setAttribute('role', 'group');
  transport.setAttribute('aria-label', 'Audio controls');
  const previous = command('Previous recording', 'previous', () => void recordings.previous());
  const rewind = command('Back 10 seconds', 'rewind', () => recordings.skipSeconds(-10));
  const replay = command('Replay last call', 'replay', () => void getLivePlayer()?.replayLastCall());
  const play = command('Play audio', 'play', toggle, true);
  const forward = command('Forward 10 seconds', 'forward', () => recordings.skipSeconds(10));
  const skip = command('Next call', 'skip', next);
  const stop = command('Stop recording', 'stop', () => recordings.stop());
  transport.append(previous, rewind, replay, play, forward, skip, stop);

  const volumeRow = node('div', 'audio-dock-volume');
  const volumeMute = command('Mute audio', 'speaker', mute);
  const volumeLabel = node('label', 'audio-dock-meta', 'Volume');
  const volume = node('input', 'ui-range ui-audio-range');
  volume.id = 'audio-dock-volume'; volume.type = 'range'; volume.min = '0'; volume.max = '1'; volume.step = '0.05';
  volumeLabel.htmlFor = volume.id;
  volume.addEventListener('input', () => changeVolume(volume.value, false));
  volume.addEventListener('change', () => { if (source === 'live') getLivePlayer()?.writePreferences({ volume: true }); });
  const volumeOutput = node('output', 'audio-dock-meta');
  volumeOutput.htmlFor = volume.id;
  volumeRow.append(volumeMute, volumeLabel, volume, volumeOutput);

  const actions = node('div', 'audio-dock-actions');
  const stopLive = textButton('Stop listening', () => void getLivePlayer()?.togglePlayback());
  const hold = textButton('Hold', () => getLivePlayer()?.toggleHold());
  const avoid = textButton('Avoid', () => getLivePlayer()?.avoidCurrent());
  const copyLink = textButton('Copy call link', () => void share(false, true));
  const shareCall = textButton('Share call', () => void share(true, true));
  actions.append(stopLive, hold, avoid, copyLink, shareCall);

  const tabs = node('div', 'ui-audio-tabs');
  tabs.setAttribute('role', 'tablist'); tabs.setAttribute('aria-label', 'Audio information');
  const tabButtons = {};
  const panelHost = node('div', 'audio-dock-panel');
  panelHost.id = 'audio-dock-panel'; panelHost.setAttribute('role', 'tabpanel'); panelHost.tabIndex = 0;
  ['details', 'transcript', 'queue', 'listening'].forEach((value) => {
    const button = node('button', 'ui-audio-tab', value[0].toUpperCase() + value.slice(1));
    button.type = 'button'; button.id = `audio-dock-tab-${value}`;
    button.setAttribute('role', 'tab'); button.setAttribute('aria-controls', panelHost.id);
    button.addEventListener('click', () => { panel = value; panelKey = ''; render(); });
    button.addEventListener('keydown', (event) => {
      if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
      event.preventDefault();
      const available = Object.values(tabButtons).filter((tab) => !tab.hidden);
      const index = available.indexOf(button);
      const target = event.key === 'Home' ? available[0] : event.key === 'End' ? available.at(-1) :
        available[(index + (event.key === 'ArrowRight' ? 1 : -1) + available.length) % available.length];
      target.click(); target.focus();
    });
    tabButtons[value] = button; tabs.append(button);
  });
  body.append(now, timing, transport, volumeRow, actions, tabs, panelHost);
  dock.append(collapsed, header, body);
  document.querySelector('.app-shell').append(dock);

  const message = (text, error = false) => {
    const item = node('p', `ui-feedback${error ? ' ui-feedback-error' : ''}`, text);
    item.setAttribute('role', error ? 'alert' : 'status');
    return item;
  };
  async function share(useNative, oneCall = false) {
    const call = recordingState.current;
    const path = oneCall && call ? href('recordings', { recording_queue: String(call.id) }) : recordings.queueHref();
    if (!path) return;
    const url = new URL(path, window.location.origin).href;
    try {
      if (useNative && navigator.share) await navigator.share({ title: oneCall ? String(title(call)) : 'Recording queue', url });
      else { await navigator.clipboard.writeText(url); status.textContent = oneCall ? 'Call link copied' : 'Queue link copied'; }
    } catch (error) {
      if (error?.name !== 'AbortError') {
        const link = node('a', 'ui-button', 'Open shareable link'); link.href = path;
        panelHost.prepend(message('The link could not be copied. Open it to copy the address.'), link);
      }
    }
  }
  function factList(rows) {
    const list = node('dl', 'ui-fact-list audio-dock-facts');
    rows.forEach(([label, value, reference]) => {
      if (value === null || value === undefined || value === '') return;
      const description = node('dd');
      const target = reference && canViewRadio() ? entityRefHref(reference) : null;
      if (target) { const link = node('a', '', String(value)); link.href = target; description.append(link); }
      else description.textContent = String(value);
      list.append(node('dt', '', label), description);
    });
    return list;
  }
  function group(label, rows) {
    const list = factList(rows);
    if (!list.children.length) return;
    const disclosure = node('details', 'ui-section-disclosure');
    disclosure.append(node('summary', 'ui-section-summary', label), list);
    panelHost.append(disclosure);
  }
  function renderLiveDetails() {
    const call = current();
    if (!call) { panelHost.append(message(liveState.stopped ? 'Press Play to receive completed calls.' : 'Waiting for the next matching call.')); return; }
    panelHost.append(factList([
      ['Target', title(call), call.target_entity_ref], ['Target ID', call.target_id, call.target_entity_ref],
      ['Source', sourceName(call), call.source_entity_ref], ['Source ID', call.source_id, call.source_entity_ref],
      ['System', call.system, call.radio_system_entity_ref], ['Channel', call.channel, call.entity_ref],
      ['Started', date(call)], ['Duration', call.duration_ms !== undefined ? `${Number(call.duration_ms) / 1000} sec` : null],
      ['Scan lists', (liveState.scanLists || []).filter((item) => (call._matchedScanListIds || call.scan_list_ids || [])
        .map(String).includes(String(item.id))).map((item) => item.name).join(', ')]
    ]));
    group('Identity & aliases', [
      ['Target type', call.target_form], ['Target description', call.target_description], ['Target group', call.target_group],
      ['Source type', call.source_form], ['Source description', call.source_description], ['Source group', call.source_group],
      ['Talker Alias', call.talker_alias], ['Alias List', call.alias_list || call.alias_list_name],
      ['Playback target', call.playback_target?.label], ['Patch group', call.patch_group_id]
    ]);
    group('Radio & channel', [
      ['Protocol', call.protocol], ['Decoder', call.decoder], ['Modulation', call.modulation],
      ['Frequency', Number(call.frequency_hz) > 0 ? `${(Number(call.frequency_hz) / 1e6).toFixed(5)} MHz` : null],
      ['Site', call.site], ['Network ID', call.network_id ?? call.network], ['Site identity', call.site_identity],
      ['WACN', call.wacn], ['SysID', call.system_id], ['RFSS', call.rfss_id], ['Site ID', call.site_id], ['RAN', call.ran],
      ['NAC', call.nac], ['LCN', call.logical_channel_number ?? call.lcn], ['Timeslot', call.timeslot],
      ['Encrypted', typeof call.encrypted === 'boolean' ? call.encrypted ? 'Yes' : 'No' : null]
    ]);
    group('Voice quality', [
      ['Quality', call.vc_quality_pct !== undefined ? `${call.vc_quality_pct}%` : null],
      ['Decoded frames', call.vc_decoded_frames], ['Repeated frames', call.vc_repeated_frames],
      ['Concealed frames', call.vc_concealed_frames], ['Missing frames', call.vc_missing_frames],
      ['FEC errors', call.vc_fec_errors], ['FEC protected bits', call.vc_fec_protected_bits]
    ]);
    group('Call', [
      ['Call ID', call.call_id], ['Identifier', call.id], ['Configuration ID', call.configuration_id, call.entity_ref],
      ['Radio system key', call.radio_system_key, call.radio_system_entity_ref],
      ['Scan List IDs', Array.isArray(call.scan_list_ids) ? call.scan_list_ids.join(', ') : null],
      ['Start timestamp', call.started_at_ms],
      ['Completed', Number.isFinite(Number(call.completed_at_ms)) && Number(call.completed_at_ms) > 0 ?
        new Date(Number(call.completed_at_ms)).toLocaleString() : null],
      ['Completion timestamp', call.completed_at_ms]
    ]);
  }
  function renderQueue() {
    const tools = node('div', 'audio-dock-queue-tools');
    if (source === 'recordings') {
      const label = node('label', 'audio-dock-meta', 'When a call is clicked');
      const select = node('select', 'ui-select'); select.id = 'audio-dock-click-mode';
      select.setAttribute('aria-label', 'When a call is clicked');
      [['queue', 'Queue clicked calls'], ['once', 'Play once'], ['continue', 'Continue matching calls']].forEach(([value, text]) => {
        const option = node('option', '', text); option.value = value; select.append(option);
      });
      select.value = recordingState.clickMode;
      select.addEventListener('change', () => recordings.setClickMode(select.value));
      label.append(select); tools.append(label);
      const shareQueue = textButton('Share queue', () => void share(true));
      shareQueue.disabled = !recordings.queueHref(); tools.append(shareQueue);
    }
    const clear = textButton('Clear queue', () => source === 'recordings' ? recordings.clearQueue() : getLivePlayer()?.clearQueue());
    clear.disabled = !queueCount() && !state().continuation; tools.append(clear);
    panelHost.append(tools);
    if (recordingState.continuation && source === 'recordings') panelHost.append(message('Continuing through matching calls. More calls are loaded as you listen.'));
    const playingCall = state().current;
    const rows = [playingCall, ...(state().queue || [])].filter(Boolean);
    if (!rows.length) { panelHost.append(message('Queue is empty.')); return; }
    const list = node('ol', 'audio-dock-queue-list');
    rows.forEach((call, index) => {
      const row = node('li', 'audio-dock-queue-row');
      const copy = node('div', 'audio-dock-copy');
      copy.append(node('span', 'audio-dock-meta', index === 0 && playingCall ? state().stopped ? 'Last played' : state().paused ? 'Paused' : 'Now playing' : 'Up next'),
        node('strong', '', String(title(call))), node('span', 'audio-dock-meta', subtitle(call)),
        node('span', 'audio-dock-meta', [date(call), call.duration_ms ? `${Number(call.duration_ms) / 1000} sec` : ''].filter(Boolean).join(' · ')));
      if (source === 'live') {
        const frequency = Number(call.frequency_hz);
        const names = (liveState.scanLists || []).filter((item) => (call._matchedScanListIds || call.scan_list_ids || [])
          .map(String).includes(String(item.id))).map((item) => item.name).join(', ');
        copy.append(node('span', 'audio-dock-meta', [frequency > 0 ? `${(frequency / 1e6).toFixed(5)} MHz` : '',
          call.decoder || call.protocol, call.timeslot !== undefined && call.timeslot !== null ? `Slot ${call.timeslot}` : '',
          typeof call.encrypted === 'boolean' ? call.encrypted ? 'Encrypted' : 'Unencrypted' : '', names]
          .filter(Boolean).join(' · ')));
      }
      row.append(copy);
      if (source === 'recordings' && !(index === 0 && playingCall)) {
        const entryKey = call._queueEntryKey || call.id;
        row.append(command(`Play ${title(call)}`, 'play', () => recordings.playQueued(entryKey)),
          command(`Remove ${title(call)} from queue`, 'close', () => recordings.removeQueued(entryKey)));
      }
      list.append(row);
    });
    panelHost.append(list);
  }
  function renderListening() {
    panelHost.append(node('h3', '', 'Scan Lists'));
    const lists = liveState.scanLists || [];
    panelHost.append(node('p', 'audio-dock-meta', `${lists.filter((item) => item.selected).length} selected · Up to ${liveState.maximumSelectedScanLists || 16} lists`));
    if (!liveState.scanListCatalogReady) panelHost.append(message(liveState.scanListCatalogState === 'unavailable' ?
      'Scan lists are unavailable. Try listening again when the receiver is available.' : 'Scan lists are loading…'));
    else if (!lists.length) panelHost.append(message('No scan lists are available.'));
    const selectionFull = lists.filter((item) => item.selected).length >= liveState.maximumSelectedScanLists;
    lists.forEach((item) => {
      const label = node('label', 'audio-dock-choice');
      const checkbox = node('input', 'ui-selection-check'); checkbox.type = 'checkbox'; checkbox.checked = item.selected;
      checkbox.id = `audio-dock-scan-list-${item.id}`;
      checkbox.disabled = !item.enabled || (selectionFull && !item.selected);
      checkbox.addEventListener('change', () => getLivePlayer()?.setScanListSelected(item.id, checkbox.checked));
      const copy = node('span', 'audio-dock-copy'); copy.append(node('strong', '', item.name));
      if (item.description) copy.append(node('span', 'audio-dock-meta', item.description));
      label.append(checkbox, copy); panelHost.append(label);
    });
    panelHost.append(node('h3', '', 'Avoid List'));
    const avoids = liveState.avoids || [];
    if (!avoids.length) panelHost.append(message('No targets avoided.'));
    avoids.forEach((item) => {
      const row = node('div', 'audio-dock-queue-row');
      const copy = node('div', 'audio-dock-copy');
      copy.append(node('strong', '', item.label || item.key), node('span', 'audio-dock-meta', item.system_scope || item.system || item.systemName || 'All systems'));
      row.append(copy, command(`Remove ${item.label || item.key} from avoid list`, 'close', () => getLivePlayer()?.removeAvoid(item.key)));
      panelHost.append(row);
    });
    panelHost.append(node('h3', '', 'Playback preferences'));
    const groupLabel = node('label', 'audio-dock-choice');
    const grouping = node('input', 'ui-selection-check'); grouping.type = 'checkbox'; grouping.checked = liveState.targetGrouping !== false;
    grouping.id = 'audio-dock-target-grouping';
    grouping.addEventListener('change', () => {
      const player = getLivePlayer(); if (!player) return;
      player.applyPreferences({ target_grouping: grouping.checked }); player.writePreferences();
    });
    groupLabel.append(grouping, node('span', '', 'Group calls by target')); panelHost.append(groupLabel);
    const burstLabel = node('label', 'audio-dock-choice', 'Calls per target');
    const burst = node('input', 'ui-input'); burst.id = 'audio-dock-target-burst'; burst.type = 'number'; burst.min = '1'; burst.max = '20'; burst.value = liveState.targetBurstLimit || 4;
    burst.setAttribute('aria-label', 'Calls per target');
    burst.addEventListener('change', () => {
      if (!burst.checkValidity()) { burst.reportValidity(); return; }
      const player = getLivePlayer(); if (!player) return;
      player.applyPreferences({ target_burst_limit: Number(burst.value) }); player.writePreferences();
    });
    burstLabel.append(burst); panelHost.append(burstLabel);
    const titleLabel = node('label', 'audio-dock-choice');
    const prepend = node('input', 'ui-selection-check'); prepend.type = 'checkbox'; prepend.checked = getTitlePreference();
    prepend.id = 'audio-dock-title-preference';
    prepend.addEventListener('change', () => void setTitlePreference(prepend.checked));
    titleLabel.append(prepend, node('span', '', 'Playing call in page title')); panelHost.append(titleLabel);
  }
  function updateProgress() {
    if (dock.hidden || size === 'collapsed') return;
    const snapshot = source === 'live' ? getLivePlayer()?.viewState() || liveState : recordings.viewState();
    const total = Math.max(0, Number(snapshot.duration) || 0);
    const position = Math.max(0, Number(snapshot.currentTime) || 0);
    elapsed.textContent = time(position); duration.textContent = time(total);
    progress.value = total ? Math.min(1, position / total) : 0;
    seek.max = String(total || 1);
    if (document.activeElement !== seek) seek.value = String(Math.min(position, total));
    seek.disabled = !isAllowed() || !total;
    seek.setAttribute('aria-valuetext', `${time(position)} of ${time(total)}`);
  }
  function render() {
    const permissions = access();
    if (!permissions[source] && (permissions.live || permissions.recordings)) {
      source = permissions.live ? 'live' : 'recordings';
    }
    dock.hidden = document.body.dataset.view === 'scanner' || document.body.dataset.view === 'access-landing' ||
      (!permissions.live && !permissions.recordings);
    dock.dataset.state = size; dock.dataset.source = source;
    document.body.classList.toggle('has-audio-dock', !dock.hidden);
    collapsed.hidden = size !== 'collapsed'; header.hidden = size === 'collapsed'; body.hidden = size === 'collapsed';
    minimal.hidden = size !== 'full'; expand.hidden = size === 'full';
    const call = current(); const active = state();
    const name = String(title(call));
    collapsedTitle.textContent = name;
    collapsedMeta.textContent = `${source === 'live' ? 'Live audio' : 'Recording'} · ${queueCount()} queued`;
    collapsedTitle.title = name;
    count.textContent = `Queue ${queueCount()}`;
    count.title = `${queueCount()} calls queued`;
    Object.entries(sourceControls).forEach(([value, button]) => {
      button.disabled = !permissions[value]; button.setAttribute('aria-pressed', String(source === value));
    });
    nowTitle.textContent = name; nowTitle.title = name;
    nowSubtitle.textContent = subtitle(call) || (source === 'live' ? 'Select scan lists in Listening' : 'Choose a recording to play');
    nowSubtitle.title = nowSubtitle.textContent;
    status.textContent = [active.status, size === 'full' ? date(call) : ''].filter(Boolean).join(' · ');
    const playing = source === 'live' ? !active.stopped && !active.paused : active.playing;
    const playLabel = playing ? source === 'live' ? 'Pause live audio' : 'Pause recording' : source === 'live' ?
      active.paused ? 'Resume live audio' : 'Listen live' : 'Play recording';
    [play, miniPlay].forEach((button) => {
      setLabel(button, playLabel, playing ? 'pause' : 'play');
      button.disabled = !isAllowed() || (source === 'recordings' && !active.current && !queueCount());
    });
    [miniNext, skip].forEach((button) => { button.disabled = !isAllowed() || (source === 'recordings' ?
      !active.canNext : !active.current && !queueCount()); });
    previous.disabled = !active.canPrevious;
    rewind.disabled = forward.disabled = !active.current || !active.duration;
    replay.disabled = !liveState.lastCallReady || liveState.paused;
    stop.disabled = !active.current && !queueCount();
    [previous, rewind, forward, stop].forEach((button) => { button.hidden = source !== 'recordings'; });
    replay.hidden = source !== 'live';
    transport.hidden = volumeRow.hidden = actions.hidden = tabs.hidden = panelHost.hidden = size !== 'full';
    miniControls.hidden = size === 'full';
    seek.hidden = source !== 'recordings'; progress.hidden = source !== 'live';
    stopLive.hidden = hold.hidden = avoid.hidden = source !== 'live';
    copyLink.hidden = shareCall.hidden = source !== 'recordings';
    stopLive.disabled = active.stopped;
    hold.disabled = !liveState.currentReady && !liveState.holdTarget;
    hold.textContent = liveState.holdTarget ? 'Release hold' : 'Hold';
    hold.setAttribute('aria-pressed', String(Boolean(liveState.holdTarget)));
    avoid.disabled = !liveState.currentReady || liveState.replayingLast;
    copyLink.disabled = shareCall.disabled = !recordingState.current;
    const muted = !(Number(active.volume) > 0);
    [miniMute, volumeMute].forEach((button) => { setLabel(button, muted ? 'Unmute audio' : 'Mute audio', muted ? 'speaker-muted' : 'speaker'); button.setAttribute('aria-pressed', String(muted)); button.disabled = !isAllowed(); });
    if (document.activeElement !== volume) volume.value = String(active.volume ?? 1);
    volumeOutput.textContent = `${Math.round(Number(active.volume ?? 1) * 100)}%`;
    if ((source === 'live' && panel === 'transcript') || (source === 'recordings' && panel === 'listening')) panel = 'details';
    Object.entries(tabButtons).forEach(([value, button]) => {
      button.hidden = (value === 'transcript' && source !== 'recordings') || (value === 'listening' && source !== 'live');
      button.setAttribute('aria-selected', String(panel === value)); button.tabIndex = panel === value ? 0 : -1;
    });
    panelHost.setAttribute('aria-labelledby', tabButtons[panel].id);
    if (size === 'full' && !dock.hidden) {
      const panelState = panel === 'listening' ? [active.scanLists, active.avoids, active.targetGrouping,
        active.targetBurstLimit, active.maximumSelectedScanLists, active.scanListCatalogReady,
        active.scanListCatalogState, getTitlePreference()] : panel === 'queue' ? [active.current, active.queue,
        active.scanLists, active.clickMode, active.continuation, active.stopped, active.paused] : panel === 'transcript' ?
        [call?.id, call?.transcription, active.detailsLoading, active.detailsError] :
        [call, active.scanLists, active.detailsLoading, active.detailsError];
      const nextKey = JSON.stringify([source, panel, ...panelState]);
      if (panelKey !== nextKey) {
        panelKey = nextKey;
        const scrollTop = panelHost.scrollTop;
        const focused = panelHost.contains(document.activeElement) ? document.activeElement : null;
        const focusId = focused?.id;
        const focusName = focused?.getAttribute('aria-label') || focused?.textContent;
        const focusTag = focused?.tagName;
        const openGroups = [...panelHost.querySelectorAll('details[open] > summary')].map((summary) => summary.textContent);
        panelHost.replaceChildren();
        if (panel === 'details') {
          if (source === 'recordings') {
            if (active.detailsLoading) panelHost.append(message('Loading call details…'));
            if (active.detailsError) panelHost.append(message(active.detailsError, true));
            recordings.renderDetails(panelHost);
          } else renderLiveDetails();
        } else if (panel === 'transcript') recordings.renderTranscript(panelHost);
        else if (panel === 'queue') renderQueue();
        else renderListening();
        panelHost.querySelectorAll('details').forEach((item) => { item.open = openGroups.includes(item.querySelector('summary')?.textContent); });
        if (focused) {
          const restored = [...panelHost.querySelectorAll('button, input, select, a, [tabindex]')].find((control) =>
            focusId ? control.id === focusId : control.tagName === focusTag &&
              (control.getAttribute('aria-label') || control.textContent) === focusName);
          (restored || tabButtons[panel]).focus({ preventScroll: true });
        }
        panelHost.scrollTop = scrollTop;
      }
    }
    updateProgress(); measure();
  }
  function measure() {
    // Measured geometry reserves the actual dock height, including safe-area padding.
    const height = dock.hidden ? 0 : Math.ceil(dock.getBoundingClientRect().height);
    document.documentElement.style.setProperty('--audio-dock-height', `${height}px`);
  }
  const resizeObserver = new ResizeObserver(measure); resizeObserver.observe(dock);
  const routeObserver = new MutationObserver(() => { panelKey = ''; render(); });
  routeObserver.observe(document.body, { attributes: true, attributeFilter: ['data-view'] });
  const progressTimer = window.setInterval(() => { if (!document.hidden) updateProgress(); }, 250);
  const unsubscribeRecordings = recordings.subscribeState((value) => {
    recordingState = value;
    const queueOnly = !value.current && value.queue?.length && liveState.stopped !== false;
    const active = Boolean(value.playing || value.loading || queueOnly);
    if (active && !previousRecordingActive && access().recordings) { source = 'recordings'; panelKey = ''; }
    previousRecordingActive = active;
    render();
  });
  function synchronize() {
    if (!access().recordings) sharedQueueKey = '';
    const player = getLivePlayer();
    if (player !== boundLive) {
      unsubscribeLive(); boundLive = player;
      unsubscribeLive = player?.subscribeState((value) => {
        liveState = value;
        if (!value.stopped && !previousLiveActive && access().live) { source = 'live'; panelKey = ''; }
        previousLiveActive = !value.stopped;
        render();
      }) || (() => {});
    }
    render();
    const params = new URLSearchParams(window.location.search);
    const queue = params.get('recording_queue');
    if (access().recordings && queue && queue !== sharedQueueKey) {
      sharedQueueKey = queue; source = 'recordings'; size = 'full'; panel = 'queue';
      void recordings.loadSharedQueue(params).then(render);
    }
  }
  synchronize();
  return { synchronize, destroy() {
    unsubscribeLive(); unsubscribeRecordings(); resizeObserver.disconnect(); routeObserver.disconnect();
    window.clearInterval(progressTimer); dock.remove(); document.body.classList.remove('has-audio-dock');
    document.documentElement.style.removeProperty('--audio-dock-height');
  } };
}
