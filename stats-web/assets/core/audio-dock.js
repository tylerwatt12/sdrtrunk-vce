import { createAudioCommand, createAudioTransport, createAudioTiming, createAudioVolume, audioPlaybackAction } from './audio-controls.js?v=1';
import { systemLabel, systemName, systemIdentity, homeSystemName } from './system-labels.js?v=2';
import { formatSourceName } from './source-names.js?v=1';
import { formatHexDecimal, formatVoiceQuality } from './call-value-format.js?v=1';
import { p25IdentityFacts, p25IdentifierText, p25ServingSystemKey } from './radio-labels.js?v=7';
import { callAudioDetailGroups } from './call-audio-details.js?v=7';
import { createCallDisplayHangtimeField } from './call-display-settings.js?v=1';

/* Reuse map: global workspace composition; ui-button/ui-button-header/ui-disclosure-button/ui-icon-button, ui-segmented, ui-select,
 * ui-range, ui-pill, ui-notice, ui-feedback, ui-fact-list, ui-select-list-item, ui-toggle-field and ui-section-disclosure. Existing scanner
 * and recording-choice modal retain their lifecycle. One live engine and one
 * recording adapter; the dock owns presentation only, in light/dark and all sizes. */
export function createAudioDock({ node, iconButton, uiToggleField, recordings, getLivePlayer: getRealLivePlayer, access, openRecordings, openScanListCoverage, refreshScanLists,
  entityRefHref, canViewRadio, href, getTitlePreference, setTitlePreference,
  getSourceNameDisplay = () => 'talker_alias',
  getCallDisplayHangtimeSeconds = () => 5, setCallDisplayHangtimeSeconds = async () => {},
  radioIdentifier = (_call, value) => value }) {
  const dock = node('section', 'audio-dock ui-audio-surface');
  dock.id = 'audio-dock';
  dock.dataset.guideTarget = 'player';
  dock.setAttribute('aria-label', 'Audio player');
  const presentation = node('div', 'audio-dock-presentation');
  presentation.id = 'audio-dock-presentation';
  let size = 'minimal';
  // Desktop hiding is presentation only: retain the size, source and both engines.
  let superCollapsed = false;
  let externalManagement = false;
  let managementSource = null;
  let guidePresentation = false;
  let guideControlValues = new WeakMap();
  // Detached sample controls must remain read-only after a guide rerenders them.
  const getLivePlayer = () => guidePresentation || !access().live ? null : getRealLivePlayer();
  const userAction = action => {
    const sampleControl = guidePresentation;
    return event => { if (!sampleControl && !guidePresentation) return action(event); };
  };
  const mobileLayout = window.matchMedia('(max-width: 900px), (max-height: 500px) and (pointer: coarse)');
  let presentationMotion = null;
  let sizeGesture = null;
  let source = 'live';
  let panel = null;
  let liveState = {};
  let latestLiveState = liveState;
  let guideScanListState = null;
  let recordingState = recordings.viewState();
  let latestRecordingState = recordingState;
  let guideRecordingState = null;
  let previousRecordingActive = false;
  let previousLiveActive = false;
  let boundLive = null;
  let unsubscribeLive = () => {};
  let panelKey = '';
  let renderedPanel = '';
  const disclosureStates = new Map();
  let destroyed = false;
  let progressTimer = null;
  let spectrumFrame = null;
  let sidebarKey = '';
  let selectedQueueKey = null;
  let contextInvoker = null;
  let menuReturnSize = null;
  const menuPanels = new Set(['menu', 'listening', 'direct', 'avoids', 'settings']);
  const menuTriggers = [];
  let sharedQueueKey = '';
  const playbackStatuses = new Map([
    ['waiting', 'Waiting'], ['waiting for the next matching call', 'Waiting'],
    ['buffering', 'Buffering'], ['reconnecting', 'Reconnecting'],
    ['replaying last call', 'Replaying last call'], ['unavailable', 'Unavailable'],
    ['select a list', 'Select a List'], ['loading lists', 'Loading Lists'],
    ['loading shared recording queue…', 'Loading queue'],
    ['continuing through matching calls', 'Continuing']
  ]);

  const command = (label, icon, action, primary = false) => {
    const button = createAudioCommand(iconButton, label, icon, userAction(action), primary);
    if (primary) button.classList.add('audio-dock-play');
    return button;
  };
  const textButton = (label, action) => {
    const button = node('button', 'ui-button', label);
    button.type = 'button';
    button.addEventListener('click', userAction(action));
    return button;
  };
  const setLabel = (button, label, icon) => {
    button.setAttribute('aria-label', label);
    button.dataset.uiHint = label;
    if (icon) button.querySelector('use')?.setAttribute('href', `#icon-${icon}`);
  };
  const state = () => source === 'live' ? liveState : recordingState;
  const current = () => {
    const snapshot = state();
    return Object.hasOwn(snapshot, 'displayCall') ? snapshot.displayCall : snapshot.current;
  };
  const identityType = (form, fallback = 'ID') =>
    ({ TALKGROUP: 'TGID', PATCH_GROUP: 'Patch', RADIO: 'Radio' })[String(form || '').toUpperCase()] || fallback;
  const sourceId = (call) => {
    const value = call?.source_id ?? call?.radio_id;
    return value === undefined || value === null || value === '' ? value : radioIdentifier(call, value, 'source');
  };
  const targetId = (call) => radioIdentifier(call, call?.target_id ?? call?.destination_radio_id ??
    call?.talkgroup_id ?? call?.group_id, 'target');
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
    if (target !== undefined && target !== null && target !== '') return `${kind} ${targetId(call) ?? target}`;
    return call.playback_target?.label || call.channel_name || call.channel || 'Recorded call';
  };
  const identityLink = (label, reference, classes = '') => {
    const target = canViewRadio() ? entityRefHref(reference) : null;
    if (!target) return document.createTextNode(String(label));
    const link = node('a', classes, String(label));
    link.href = target;
    return link;
  };
  const renderTitle = (label, reference) => {
    const target = canViewRadio() ? entityRefHref(reference) : null;
    const link = nowTitle.firstElementChild;
    if (target && link?.tagName === 'A') {
      if (link.getAttribute('href') !== target) link.href = target;
      if (link.textContent !== label) link.textContent = label;
    } else if (target || link || nowTitle.textContent !== label) {
      nowTitle.replaceChildren(identityLink(label, reference, 'ui-listening-title-action'));
    }
  };
  const sourceName = (call) => {
    const name = formatSourceName(call?.source_alias || call?.radio_alias,
      call?.source_ota_alias || call?.source_ota_ta || call?.talker_alias || call?.ota_alias, getSourceNameDisplay());
    const id = sourceId(call);
    return name || (id !== undefined && id !== null && id !== '' ? `Radio ${id}` : '');
  };
  const sourceSummary = (call) => {
    if (!call) return '';
    const alias = call.source_alias || call.radio_alias;
    const id = sourceId(call);
    const ota = call.source_ota_alias || call.source_ota_ta || call.talker_alias || call.ota_alias;
    const name = formatSourceName(alias, ota, getSourceNameDisplay());
    return [name, id !== undefined && id !== null && id !== '' ? `${identityType(call.source_form, 'Radio')} ${id}` : '']
      .filter(Boolean).join(' · ');
  };
  const time = (seconds) => {
    const value = Math.max(0, Math.floor(Number(seconds) || 0));
    return `${Math.floor(value / 60).toString().padStart(2, '0')}:${(value % 60).toString().padStart(2, '0')}`;
  };
  const date = (call) => {
    const value = call?.started_at_ms ?? call?.start_ms;
    return Number.isFinite(Number(value)) && Number(value) > 0 ? new Date(Number(value)).toLocaleString() : '';
  };
  const description = (call) => {
    const text = String(call?.target_description || call?.talkgroup_description || call?.group_description ||
      call?.destination_radio_description || '').trim();
    return text && text.toLocaleLowerCase() !== String(title(call)).trim().toLocaleLowerCase() ? text : '';
  };
  const subtitle = (call) => [sourceName(call), call?.channel || call?.channel_name]
    .filter(Boolean).join(' · ');
  const queueCount = () => source === 'live' ? Number(liveState.queuedCount) || 0 : recordingState.queue?.length || 0;
  const isAllowed = () => Boolean(access()[source]);
  const setSize = (value) => {
    if (guidePresentation) return;
    if (externalManagement && value !== 'full') { closePanel(); return; }
    cancelSizeGesture();
    closeVolume(false);
    cancelPresentationMotion();
    const returnFocus = dock.contains(document.activeElement);
    size = value; panelKey = ''; render();
    if (returnFocus) (size === 'collapsed' ? slimExpand : handle).focus({ preventScroll: true });
  };
  const sizes = ['collapsed', 'minimal', 'full'];
  const shiftSize = (direction) => setSize(sizes[Math.max(0, Math.min(2, sizes.indexOf(size) + direction))]);
  const switchSource = (value) => {
    if (guidePresentation) return;
    if (source === value || !access()[value]) return;
    cancelSizeGesture();
    if (source === 'recordings') recordings.stop();
    else if (getLivePlayer() && !liveState.stopped) void getLivePlayer().togglePlayback();
    closeVolume(false);
    source = value;
    managementSource = null; externalManagement = false;
    panel = null;
    selectedQueueKey = null;
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
    if (guidePresentation) return;
    const bounded = Math.max(0, Math.min(1, Number(value)));
    if (!Number.isFinite(bounded)) return;
    if (source === 'recordings') recordings.setVolume(bounded);
    else getLivePlayer()?.setVolume(bounded, persist);
    render();
  };

  const handle = node('button', 'ui-audio-handle');
  handle.type = 'button';
  handle.id = 'audio-dock-size-handle';
  handle.dataset.guideTarget = 'player-handle';
  handle.dataset.uiHintPlacement = 'above';
  handle.setAttribute('aria-label', 'Change audio player size');
  handle.setAttribute('aria-controls', 'audio-dock-content');
  handle.setAttribute('aria-describedby', 'audio-dock-size-hint');
  const grip = node('span', 'ui-audio-grip');
  grip.setAttribute('aria-hidden', 'true');
  const sizeHint = node('span', 'visually-hidden');
  sizeHint.id = 'audio-dock-size-hint';
  const collapsedTitle = node('strong', 'audio-dock-single-line');
  const collapsedMeta = node('span', 'audio-dock-meta audio-dock-collapsed-meta');
  handle.append(grip, sizeHint);
  function measureSizeChoices() {
    // Measure inert copies without hiding the focused or captured real controls.
    const copy = dock.cloneNode(true);
    copy.classList.add('audio-dock-size-measurement');
    copy.inert = true;
    copy.setAttribute('aria-hidden', 'true');
    [copy, ...copy.querySelectorAll('[id]')].forEach(element => element.removeAttribute('id'));
    delete copy.dataset.dragging; delete copy.dataset.dragOrigin;
    copy.style.removeProperty('height'); copy.style.removeProperty('width');
    dock.parentElement.append(copy);
    const choices = sizes.map(value => {
      copy.dataset.state = value;
      copy.querySelector('.audio-dock-header').hidden = value === 'collapsed';
      copy.querySelector('.audio-dock-compact').hidden = value !== 'minimal';
      copy.querySelector('.audio-dock-slim').hidden = value !== 'collapsed';
      copy.querySelector('.audio-dock-expanded').hidden = value !== 'full';
      const bounds = copy.getBoundingClientRect();
      return { size: value, height: bounds.height, width: bounds.width };
    });
    copy.remove();
    return choices;
  }
  function finishSizeGesture(commit = false) {
    const gesture = sizeGesture;
    if (!gesture) return;
    sizeGesture = null;
    if (!commit || gesture.axis) gesture.suppressClick();
    delete dock.dataset.dragging; delete dock.dataset.dragOrigin;
    dock.style.removeProperty('height'); dock.style.removeProperty('width');
    if (gesture.control.hasPointerCapture(gesture.id)) gesture.control.releasePointerCapture(gesture.id);
    if (!gesture.active) return;
    size = commit ? gesture.choices.reduce((nearest, choice) =>
      Math.abs(choice.height - gesture.height) < Math.abs(nearest.height - gesture.height) ? choice : nearest).size : gesture.initialSize;
    panelKey = ''; render();
    if (!dock.hidden) (size === 'collapsed' ? slimExpand : handle).focus({ preventScroll: true });
  }
  function cancelSizeGesture() { finishSizeGesture(); }
  function moveSizeGesture(event) {
    const gesture = sizeGesture;
    if (!gesture || gesture.id !== event.pointerId) return;
    const vertical = gesture.y - event.clientY, horizontal = event.clientX - gesture.x;
    if (!gesture.axis && Math.hypot(vertical, horizontal) > 8) {
      gesture.axis = Math.abs(vertical) > Math.abs(horizontal) ? 'vertical' : 'horizontal';
      gesture.suppressClick();
    }
    if (gesture.axis !== 'vertical') return;
    if (!gesture.active) {
      closeVolume(false); cancelPresentationMotion();
      gesture.choices = measureSizeChoices();
      const viewport = window.visualViewport;
      const tokens = getComputedStyle(document.documentElement);
      const headerHeight = parseFloat(tokens.getPropertyValue('--app-header-offset')) || 0;
      const gap = parseFloat(tokens.getPropertyValue('--space-2')) || 8;
      gesture.maximum = Math.min(parseFloat(getComputedStyle(dock).maxHeight) || innerHeight,
        gesture.bottom - (viewport?.offsetTop || 0) - headerHeight - gap);
      gesture.active = true;
      dock.dataset.dragging = 'true'; dock.dataset.dragOrigin = gesture.initialSize;
    }
    const [collapsedChoice, minimalChoice, fullChoice] = gesture.choices;
    gesture.height = Math.max(collapsedChoice.height, Math.min(gesture.maximum, gesture.initialHeight + vertical));
    const lower = gesture.height <= minimalChoice.height ? collapsedChoice : minimalChoice;
    const upper = gesture.height <= minimalChoice.height ? minimalChoice : fullChoice;
    const fraction = Math.max(0, Math.min(1, (gesture.height - lower.height) / Math.max(1, upper.height - lower.height)));
    dock.style.height = `${gesture.height}px`;
    dock.style.width = `${lower.width + (upper.width - lower.width) * fraction}px`;
    const presentationSize = gesture.height > minimalChoice.height ? 'full' : 'minimal';
    if (gesture.presentationSize !== presentationSize) {
      gesture.presentationSize = presentationSize; render();
    } else measure();
  }
  const wireSizeControl = (control, activate) => {
    let suppressClick = false;
    control.addEventListener('pointerdown', (event) => {
      if (guidePresentation || !event.isPrimary || event.button !== 0 || sizeGesture) return;
      suppressClick = false;
      const bounds = dock.getBoundingClientRect();
      sizeGesture = { id: event.pointerId, control, x: event.clientX, y: event.clientY,
        initialSize: size, initialHeight: bounds.height, bottom: bounds.bottom,
        suppressClick: () => { suppressClick = true; } };
      control.setPointerCapture(event.pointerId);
    });
    control.addEventListener('pointermove', moveSizeGesture);
    control.addEventListener('pointerup', (event) => {
      if (sizeGesture?.control !== control || sizeGesture.id !== event.pointerId) return;
      moveSizeGesture(event); finishSizeGesture(true);
    });
    const cancel = (event) => {
      if (sizeGesture?.control === control && sizeGesture.id === event.pointerId) cancelSizeGesture();
    };
    control.addEventListener('pointercancel', cancel);
    control.addEventListener('lostpointercapture', cancel);
    control.addEventListener('click', (event) => {
      if (suppressClick && event.detail !== 0) { suppressClick = false; return; }
      suppressClick = false; activate();
    });
    control.addEventListener('keydown', (event) => {
      if (!['ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) return;
      event.preventDefault();
      if (event.key === 'Home' || event.key === 'End') setSize(event.key === 'Home' ? 'collapsed' : 'full');
      else shiftSize(event.key === 'ArrowUp' ? 1 : -1);
    });
  };
  wireSizeControl(handle, () => setSize(sizes[(sizes.indexOf(size) + 1) % sizes.length]));

  const header = node('div', 'audio-dock-header');
  const chooseSource = (value) => {
    if (guidePresentation) return;
    if (!access()[value] || (value === 'recordings' && openRecordings?.() === false)) {
      render();
      return;
    }
    if (source === value) render();
    else switchSource(value);
  };
  const sourcePicker = node('select', 'ui-select ui-audio-source-picker');
  sourcePicker.dataset.guideTarget = 'player-mode';
  sourcePicker.setAttribute('aria-label', 'Audio source');
  // A source choice is also a navigation action. The hidden current label lets
  // choosing Recordings navigate even when that source is already selected.
  const currentSourceChoice = node('option');
  currentSourceChoice.value = ''; currentSourceChoice.hidden = true;
  sourcePicker.append(currentSourceChoice);
  sourcePicker.addEventListener('change', () => chooseSource(sourcePicker.value));
  const sourceOptions = {};
  ['live', 'recordings'].forEach((value) => {
    const label = value === 'live' ? 'Live' : 'Recordings';
    const option = node('option', '', label);
    option.value = value;
    sourceOptions[value] = option;
    sourcePicker.append(option);
  });
  let focusedSourceControl = null;
  sourcePicker.addEventListener('focusin', (event) => { focusedSourceControl = event.target; });
  sourcePicker.addEventListener('focusout', (event) => {
    // Retain focus when a presentation change temporarily hides the picker.
    if (event.target.getClientRects().length) focusedSourceControl = null;
  });
  const count = textButton('0 queued', () => openPanel('queue', count));
  count.className = 'link-button audio-dock-queue-count';
  count.setAttribute('aria-label', 'Open queue');
  const headerStart = node('div', 'audio-dock-header-start audio-dock-source-choice');
  const headerEnd = node('div', 'audio-dock-header-end');
  const cancelPresentationMotion = () => {
    const motion = presentationMotion;
    presentationMotion = null;
    if (!motion) return;
    motion.animations.forEach((animation) => animation.cancel());
    motion.ghost.remove();
  };
  const animatePresentation = (from, to, template, target) => {
    if (!from.width || !from.height || !to.width || !to.height) return;
    const tokens = getComputedStyle(document.documentElement);
    const durationToken = tokens.getPropertyValue(target === restore ?
      '--audio-presentation-collapse-motion-duration' : '--audio-presentation-motion-duration').trim();
    const duration = Number.parseFloat(durationToken) * (durationToken.endsWith('ms') ? 1 : 1_000);
    const easing = tokens.getPropertyValue('--audio-presentation-motion-easing').trim();
    if (!Number.isFinite(duration) || duration <= 0 || !easing) return;
    const ghost = template.cloneNode(true);
    ghost.classList.add('audio-dock-motion');
    ghost.hidden = false;
    ghost.setAttribute('aria-hidden', 'true');
    ghost.inert = true;
    // The decorative copy must never duplicate IDs or become an audio control.
    [ghost, ...ghost.querySelectorAll('[id]')].forEach((element) => element.removeAttribute('id'));
    ghost.querySelectorAll('[aria-controls], [aria-describedby]').forEach((element) => {
      element.removeAttribute('aria-controls'); element.removeAttribute('aria-describedby');
    });
    const panel = ghost.querySelector('.audio-dock-presentation');
    if (panel) panel.hidden = false;
    const base = target === dock ? to : from;
    const transform = value => `translate(${value.x - base.x}px, ${value.y - base.y}px) scale(${value.width / base.width}, ${value.height / base.height})`;
    // Geometry comes from the two real controls, including the responsive header.
    ghost.style.left = `${base.x}px`; ghost.style.top = `${base.y}px`;
    ghost.style.width = `${base.width}px`; ghost.style.height = `${base.height}px`;
    ghost.dataset.superCollapsed = 'false';
    document.querySelector('.app-shell').append(ghost);
    const animation = ghost.animate([
      { transform: transform(from), opacity: 1 },
      { transform: transform(to), opacity: 0 }
    ], { duration, easing });
    const targetAnimation = target.animate([{ opacity: 0 }, { opacity: 1 }], { duration, easing });
    const motion = { ghost, animations: [animation, targetAnimation] };
    presentationMotion = motion;
    void animation.finished.then(() => {
      if (presentationMotion === motion) cancelPresentationMotion();
    }, () => {});
  };
  const setSuperCollapsed = (value) => {
    if (guidePresentation) return;
    if (externalManagement && value) { closePanel(); return; }
    if (mobileLayout.matches || superCollapsed === value) return;
    cancelSizeGesture();
    closeVolume(false);
    cancelPresentationMotion();
    const from = (value ? dock : restore).getBoundingClientRect();
    const template = value ? dock.cloneNode(true) : null;
    superCollapsed = value;
    render();
    const target = value ? restore : dock;
    const to = target.getBoundingClientRect();
    // Use the restored panel content in both directions without changing engines.
    animatePresentation(from, to, template || dock, target);
    (value ? restore : size === 'collapsed' ? slimHide : hide).focus({ preventScroll: true });
  };
  const hide = iconButton('icon-close', 'Minimize player to header',
    'ui-disclosure-button ui-icon-button ui-audio-dismiss');
  hide.dataset.uiHint = 'Minimize player to header';
  hide.id = 'audio-dock-hide';
  hide.dataset.guideTarget = 'player-hide';
  hide.setAttribute('aria-controls', presentation.id);
  hide.addEventListener('click', () => setSuperCollapsed(true));
  const restore = iconButton('icon-maximize-corners', 'Restore player',
    'ui-button ui-button-header ui-audio-header-restore');
  restore.prepend(node('span', '', 'Player'));
  restore.setAttribute('aria-controls', presentation.id);
  restore.addEventListener('click', () => setSuperCollapsed(false));
  let focusedPresentationControl = null;
  [hide, restore].forEach((control) => {
    control.addEventListener('focusin', () => { focusedPresentationControl = control; });
    control.addEventListener('focusout', () => {
      if (control.getClientRects().length) focusedPresentationControl = null;
    });
  });
  const updatePresentationLayout = () => {
    cancelSizeGesture();
    closeVolume(false);
    cancelPresentationMotion();
    const returnFocus = dock.contains(document.activeElement) || Boolean(focusedPresentationControl || focusedSourceControl);
    render();
    if (!returnFocus || (restore.hidden && dock.hidden)) return;
    if (superCollapsed && !mobileLayout.matches) restore.focus({ preventScroll: true });
    else if (focusedPresentationControl || !document.activeElement.getClientRects().length) {
      (size === 'collapsed' ? slimExpand : handle).focus({ preventScroll: true });
      focusedPresentationControl = null;
    }
  };

  const appendIcon = (target, name) => target.append(iconButton(`icon-${name}`, '', '').querySelector('svg'));
  const sourceFrame = node('label', 'ui-select-frame audio-dock-source-native');
  sourceFrame.append(node('span', 'visually-hidden', 'Audio source'), sourcePicker);
  appendIcon(sourceFrame, 'chevron-down');
  headerStart.append(sourceFrame);
  const makeMenuTrigger = id => {
    const button = createAudioCommand(iconButton, 'Player menu', 'menu');
    button.id = id; button.dataset.playerMenuTrigger = ''; button.dataset.guideTarget = 'player-menu';
    button.setAttribute('aria-controls', 'audio-dock-panel'); button.setAttribute('aria-expanded', 'false');
    button.addEventListener('click', () => openPlayerMenu(button));
    menuTriggers.push(button); return button;
  };
  const shrink = iconButton('icon-chevron-down', 'Make player compact', 'ui-disclosure-button ui-icon-button');
  shrink.id = 'audio-dock-shrink';
  shrink.addEventListener('click', () => setSize(size === 'full' ? 'minimal' : 'collapsed'));
  headerEnd.append(shrink, hide);
  header.append(headerStart, handle, headerEnd);

  const createSpectrum = (classes, backdrop = false) => {
    const wave = node('span', `ui-audio-spectrum ${classes}${backdrop ? ' ui-audio-spectrum-backdrop' : ''}`);
    if (backdrop) wave.setAttribute('aria-hidden', 'true');
    else { wave.setAttribute('role', 'img'); wave.setAttribute('aria-label', 'Voice spectrum idle'); }
    for (let index = 0; index < 64; index++) wave.append(node('i'));
    return wave;
  };
  const compact = node('div', 'audio-dock-compact');
  const now = node('div', 'audio-dock-now audio-dock-compact-now');
  const compactCopy = node('button', 'audio-dock-compact-copy');
  compactCopy.dataset.guideTarget = 'rec-player-button';
  compactCopy.type = 'button'; compactCopy.setAttribute('aria-label', 'Expand player');
  compactCopy.addEventListener('click', () => setSize('full'));
  const compactWave = createSpectrum('audio-dock-mini-wave', true);
  const compactTitle = node('strong', 'audio-dock-title audio-dock-single-line');
  const compactSubtitle = node('span', 'ui-muted ui-label');
  compactCopy.append(compactWave, compactTitle, compactSubtitle);
  const miniControls = node('div', 'audio-dock-mini-controls');
  const miniPlay = command('Play audio', 'play', toggle, true);
  const miniNext = command('Next call', 'skip', next);
  const volumeControl = createAudioVolume({ node, iconButton, host: document.querySelector('.app-shell'), id: 'audio-dock',
    onInput: value => changeVolume(value, false),
    onCommit: () => { if (!guidePresentation && source === 'live') getLivePlayer()?.writePreferences({ volume: true }); }
  });
  const volumePopover = volumeControl.popover;
  const closeVolume = returnFocus => volumeControl.close(returnFocus);
  const makeVolumeTrigger = id => volumeControl.createTrigger(id);
  const miniVolume = makeVolumeTrigger('audio-dock-compact-volume');
  const miniMenu = makeMenuTrigger('audio-dock-compact-menu');
  miniControls.append(miniPlay, miniNext, miniMenu, miniVolume);
  now.append(compactCopy, miniControls);
  const compactTiming = createAudioTiming({ node, className: 'audio-dock-compact-bottom' });
  [compactTiming.elapsed, compactTiming.duration].forEach(element => element.classList.add('audio-dock-time'));
  compactTiming.element.append(count);
  compact.append(now, compactTiming.element);

  const slim = node('div', 'audio-dock-slim');
  const slimCopy = node('button', 'audio-dock-slim-copy');
  slimCopy.type = 'button'; slimCopy.setAttribute('aria-label', 'Expand player');
  slimCopy.addEventListener('click', () => setSize('minimal'));
  const slimWave = createSpectrum('audio-dock-mini-wave', true);
  slimCopy.append(slimWave, collapsedTitle, collapsedMeta);
  const slimPlay = command('Play audio', 'play', toggle, true);
  const slimHide = iconButton('icon-close', 'Minimize player to header', 'ui-disclosure-button ui-icon-button');
  slimHide.id = 'audio-dock-slim-hide'; slimHide.dataset.uiHint = 'Minimize player to header';
  slimHide.setAttribute('aria-controls', presentation.id);
  slimHide.addEventListener('click', () => setSuperCollapsed(true));
  const slimExpand = iconButton('icon-maximize-corners', 'Expand player', 'ui-disclosure-button ui-icon-button ui-audio-size-action');
  slimExpand.id = 'audio-dock-slim-expand'; wireSizeControl(slimExpand, () => setSize('minimal'));
  const slimMenu = makeMenuTrigger('audio-dock-slim-menu');
  slim.append(slimCopy, slimPlay, slimMenu, slimHide, slimExpand);

  const body = node('div', 'audio-dock-body audio-dock-expanded'); body.id = 'audio-dock-content';
  const main = node('div', 'audio-dock-player-main ui-listening-stage-main');
  const mainScroll = node('div', 'audio-dock-main-scroll');
  const hero = node('div', 'audio-dock-hero');
  const listeningStatus = node('div', 'audio-dock-listening-status');
  const status = node('span', 'ui-pill'); status.setAttribute('role', 'status');
  const callContext = node('div', 'audio-dock-call-context');
  const age = node('span', 'ui-muted ui-label'); age.id = 'audio-dock-call-age';
  const transcriptButton = iconButton('icon-messages', 'Transcript', 'ui-disclosure-button ui-icon-button ui-audio-transcript-action');
  transcriptButton.id = 'audio-dock-call-transcript'; transcriptButton.dataset.uiHint = 'Transcript';
  transcriptButton.addEventListener('click', () => openPanel('transcript', transcriptButton));
  const detailsButton = iconButton('icon-info-circle', 'Call details', 'ui-disclosure-button ui-icon-button ui-audio-info-action');
  detailsButton.id = 'audio-dock-call-details'; detailsButton.dataset.uiHint = 'Call details';
  detailsButton.addEventListener('click', () => openPanel('details', detailsButton));
  callContext.append(age, transcriptButton, detailsButton); listeningStatus.append(status, callContext);
  const identity = node('div', 'audio-dock-identity');
  const kind = node('span', 'ui-muted ui-label');
  const nowTitle = node('h2', 'audio-dock-title');
  const nowDescription = node('span', 'audio-dock-description ui-muted');
  const nowSystem = node('span', 'ui-muted');
  identity.append(kind, nowTitle, nowDescription, nowSystem);
  const audioHeading = node('div', 'audio-dock-audio-heading ui-listening-audio-heading');
  const quality = node('span', 'ui-muted ui-label');
  const qualityValue = node('strong'); quality.append('Voice quality ', qualityValue);
  audioHeading.append(node('span', 'ui-muted ui-label', 'Audio spectrum'), quality);
  const fullWave = createSpectrum('audio-dock-wave');
  const transmitting = node('div', 'audio-dock-transmitting ui-listening-source');
  const transmittingCopy = node('div');
  const transmittingName = node('strong');
  transmittingCopy.append(node('span', 'ui-muted ui-label', 'Transmitting'), transmittingName);
  const targetGroup = node('span', 'ui-pill'); transmitting.append(transmittingCopy, targetGroup);
  const technical = node('div', 'audio-dock-technical ui-muted ui-label');
  // Detailed messages float beside the dock so playback transitions cannot resize it.
  const playbackFeedback = node('div', 'ui-notice audio-dock-notice audio-dock-playback-feedback');
  playbackFeedback.hidden = true;
  playbackFeedback.setAttribute('role', 'status');
  playbackFeedback.setAttribute('aria-atomic', 'true');
  hero.append(listeningStatus, identity, audioHeading, fullWave, transmitting, technical);
  const context = node('section', 'audio-dock-context'); context.hidden = true;
  const contextHeader = node('div', 'audio-dock-context-heading');
  const back = iconButton('icon-chevron-left', 'Back to player', 'ui-disclosure-button ui-icon-button');
  back.id = 'audio-dock-context-back'; back.dataset.guideTarget = 'player-menu-back';
  back.addEventListener('click', () => {
    if (menuPanels.has(panel) && panel !== 'menu') {
      const previous = panel; openPanel('menu');
      panelHost.querySelector(`[data-player-menu-panel="${previous}"]`)?.focus({ preventScroll: true });
    } else closePanel();
  });
  const contextCopy = node('div', 'ui-heading-group');
  const contextTitle = node('h3', 'ui-section-heading'); contextTitle.id = 'audio-dock-context-title';
  const contextSubtitle = node('span', 'ui-muted ui-label');
  contextCopy.append(contextTitle, contextSubtitle); contextHeader.append(back, contextCopy);
  const panelHost = node('div', 'audio-dock-panel audio-dock-context-body');
  panelHost.id = 'audio-dock-panel'; panelHost.setAttribute('role', 'region'); panelHost.setAttribute('aria-labelledby', contextTitle.id);
  context.append(contextHeader, panelHost);
  const teaser = node('button', 'audio-dock-next-teaser');
  teaser.type = 'button'; teaser.setAttribute('aria-label', 'Open queue');
  teaser.addEventListener('click', () => openPanel('queue', teaser));
  const nextLabel = node('span', 'audio-dock-next-label'); const teaserCount = node('span', 'ui-muted ui-label');
  nextLabel.append(node('strong', '', 'Up next'), teaserCount);
  const nextCopy = node('span', 'audio-dock-next-copy'); const nextTitle = node('strong'); const nextSubtitle = node('span', 'ui-muted ui-label');
  nextCopy.append(nextTitle, nextSubtitle); teaser.append(nextLabel, nextCopy); appendIcon(teaser, 'chevron-right');
  mainScroll.append(hero, context, teaser);
  const controls = node('footer', 'audio-dock-controls');
  const seek = node('input', 'ui-range ui-audio-range');
  seek.type = 'range'; seek.min = '0'; seek.max = '1'; seek.step = '0.1'; seek.value = '0';
  seek.setAttribute('aria-label', 'Recording position');
  seek.addEventListener('input', userAction(() => recordings.seek(Number(seek.value))));
  const timing = createAudioTiming({ node, seek, className: 'audio-dock-timing' });
  [timing.elapsed, timing.duration].forEach(element => element.classList.add('audio-dock-time'));
  const fullMenu = makeMenuTrigger('audio-dock-menu');
  const expandedVolume = makeVolumeTrigger('audio-dock-expanded-volume');
  const sharedTransport = createAudioTransport({ node, iconButton, menu: fullMenu, volume: expandedVolume, actions: {
    previous: userAction(() => void recordings.previous()), rewind: userAction(() => recordings.skipSeconds(-10)),
    replay: userAction(() => void getLivePlayer()?.replayLastCall()), play: userAction(toggle),
    forward: userAction(() => recordings.skipSeconds(10)), skip: userAction(next),
    stop: userAction(() => {
      if (source === 'recordings') recordings.stop();
      else if (!liveState.stopped) void getLivePlayer()?.togglePlayback();
    }),
    hold: userAction(() => getLivePlayer()?.toggleHold()), avoid: userAction(() => getLivePlayer()?.avoidCurrent())
  } });
  const { previous, rewind, replay, play, forward, skip, stop, hold, avoid } = sharedTransport;
  sharedTransport.element.classList.add('audio-dock-transport', 'audio-dock-primary-controls');
  sharedTransport.main.classList.add('audio-dock-transport-main');
  sharedTransport.options.classList.add('audio-dock-transport-options');
  play.classList.add('audio-dock-play');
  hold.classList.add('audio-dock-hold'); avoid.classList.add('audio-dock-avoid-action');
  controls.append(timing.element, sharedTransport.element); main.append(mainScroll, controls);

  const sidebar = node('aside', 'audio-dock-up-next ui-listening-queue');
  const queueHeading = node('div', 'audio-dock-queue-heading');
  const sidebarCount = node('span', 'ui-muted ui-label'); queueHeading.append(node('h3', 'ui-section-heading', 'Up next'), sidebarCount);
  const sidebarList = node('ol', 'audio-dock-queue-list');
  sidebarList.tabIndex = 0; sidebarList.setAttribute('aria-label', 'Queued calls');
  const inspector = node('div', 'audio-dock-queue-inspector'); inspector.hidden = true;
  const queueFooter = node('div', 'audio-dock-queue-footer');
  const clearQueue = textButton('Clear queue', () => source === 'recordings' ? recordings.clearQueue() : getLivePlayer()?.clearQueue());
  clearQueue.className = 'link-button ui-label'; queueFooter.append(clearQueue);
  const sidebarClickMode = clickModeField('audio-dock-click-mode');
  sidebarClickMode.dataset.guideTarget = 'rec-click-mode';
  sidebar.append(queueHeading, sidebarList, inspector, queueFooter, sidebarClickMode); body.append(main, sidebar);
  presentation.append(header, compact, slim, body); dock.append(presentation);
  document.querySelector('.app-shell').append(dock, playbackFeedback);
  const headerControls = document.querySelector('.app-shell .header-controls');
  headerControls.insertBefore(restore, headerControls.querySelector('#receiver-health-indicator'));

  const message = (text, error = false) => {
    const item = node('p', `ui-feedback${error ? ' ui-feedback-error' : ''}`, text);
    item.setAttribute('role', error ? 'alert' : 'status'); return item;
  };
  function openPanel(value, invoker) {
    if (guidePresentation) return;
    if (!isAllowed() || source === 'recordings' && ['listening', 'direct', 'avoids'].includes(value)) return;
    if (menuPanels.has(value)) {
      if (!menuPanels.has(panel)) { menuReturnSize = size; contextInvoker = invoker || contextInvoker; }
    } else { menuReturnSize = null; contextInvoker = invoker || contextInvoker; }
    closeVolume(false); panel = value; panelKey = ''; setSize('full');
    (value === 'menu' ? panelHost.querySelector('button') || back : back).focus({ preventScroll: true });
  }
  function openPlayerMenu(invoker) {
    if (guidePresentation) return;
    if (!access()[source]) return;
    if (panel === 'menu') { closePanel(); return; }
    superCollapsed = false;
    externalManagement = document.body.dataset.view === 'scanner';
    openPanel('menu', invoker);
  }
  function closePanel() {
    if (guidePresentation) return;
    closeVolume(false); panel = null; externalManagement = false;
    if (managementSource !== null) { source = managementSource; managementSource = null; }
    if (menuReturnSize !== null) size = menuReturnSize;
    menuReturnSize = null; panelKey = ''; render();
    (contextInvoker?.getClientRects().length ? contextInvoker : handle).focus({ preventScroll: true });
  }
  function renderMenu() {
    const items = source === 'live' ? [
      ['Lists', 'scan-lists', 'listening', (liveState.scanLists || []).filter(item => item.selected).length + (liveState.directListen?.selected ? 1 : 0)],
      ...(liveState.directListen?.allowed ? [['Direct Listen', 'direct-listen', 'direct', liveState.directListen.targets.length]] : []),
      ['Avoid List', 'skip', 'avoids', (liveState.avoids || []).length],
      ['Listening settings', 'playback-controls', 'settings'],
      ['Clear queue', 'clear-queue', 'clear', queueCount()]
    ] : [['Call details', 'info-circle', 'details'], ['Queue', 'scan-lists', 'queue', queueCount()],
      ['Transcript', 'messages', 'transcript'], ['Listening settings', 'playback-controls', 'settings'],
      ['Recording library', 'recording', 'library']];
    const choices = node('div', 'ui-surface audio-dock-selection-list');
    items.forEach(([label, icon, destination, count]) => {
      const button = node('button', 'ui-select-list-item'); button.type = 'button';
      button.setAttribute('aria-label', label); button.dataset.playerMenuPanel = destination;
      button.dataset.guideTarget = `player-menu-${({ listening: 'lists', settings: 'settings', avoids: 'avoids' })[destination] || destination}`;
      const leading = node('span', 'ui-record-card-icon'); appendIcon(leading, icon); button.append(leading);
      button.append(node('span', 'ui-record-card-copy', label));
      if (count !== undefined) button.append(node('span', 'ui-pill ui-pill-compact', String(count)));
      if (destination === 'clear') button.disabled = !queueCount();
      if (destination !== 'clear') {
        const trailing = node('span', 'ui-record-card-icon'); appendIcon(trailing, 'chevron-right'); button.append(trailing);
      }
      button.addEventListener('click', userAction(() => {
        if (destination === 'clear') {
          if (source === 'live') getLivePlayer()?.clearQueue(); else recordings.clearQueue();
          closePanel();
        } else if (destination === 'library') { closePanel(); openRecordings?.(); }
        else openPanel(destination);
      }));
      choices.append(button);
    });
    panelHost.append(choices);
  }
  function clickModeField(id) {
    const label = node('label', 'ui-field ui-audio-queue-setting audio-dock-click-mode');
    const frame = node('span', 'ui-select-frame'); const select = node('select', 'ui-select');
    select.id = id; select.dataset.clickMode = ''; select.setAttribute('aria-label', 'When a call is clicked');
    [['queue', 'Queue clicked calls'], ['once', 'Play once'], ['continue', 'Continue matching calls']].forEach(([value, text]) => {
      const option = node('option', '', text); option.value = value; select.append(option);
    });
    select.addEventListener('change', userAction(() => {
      recordings.setClickMode(select.value);
      dock.querySelectorAll('[data-click-mode]').forEach(control => { control.value = recordingState.clickMode; });
    }));
    frame.append(select); appendIcon(frame, 'chevron-down'); label.append(node('span', 'ui-field-label', 'When a call is clicked'), frame);
    return label;
  }
  function patchMembers(call) {
    if (!Array.isArray(call?.patch_members) || !call.patch_members.length) return null;
    const list = node('span');
    call.patch_members.forEach((member) => {
      if (!member || typeof member !== 'object') return;
      const id = member.id ?? member.talkgroup_id;
      const kind = String(member.kind || member.form || '').toLowerCase() === 'radio' ? 'radio' : 'talkgroup';
      const row = { ...member, serving_system_key: p25ServingSystemKey(call) };
      const facts = p25IdentityFacts(row, '', kind);
      const identity = facts ? p25IdentifierText(row, id, { kind,
        homeSystemName: homeSystemName(member, '', facts.homeSystemKey) }) || id : id;
      const alias = member.alias || member.name || member.ota_alias;
      if (identity === undefined || identity === null || identity === '') {
        if (!alias) return;
      }
      const value = [alias, identity !== undefined && identity !== null && identity !== '' ?
        `${kind === 'radio' ? 'Radio' : 'TGID'} ${identity}` : ''].filter(Boolean).join(' · ');
      if (list.childNodes.length) list.append(', ');
      const target = member.entity_ref && canViewRadio() ? entityRefHref(member.entity_ref) : null;
      if (target) { const link = node('a', '', value); link.href = target; list.append(link); }
      else list.append(value);
    });
    if (call.patch_members_truncated) {
      const count = Number(call.patch_member_count);
      list.append(`${list.childNodes.length ? ' · ' : ''}Showing ${call.patch_members.length}` +
        `${Number.isInteger(count) && count > call.patch_members.length ? ` of ${count}` : ''} members`);
    }
    return list.childNodes.length ? list : null;
  }
  function factList(rows, { empty = false, includeEmpty = false } = {}) {
    const list = node('dl', 'ui-fact-list audio-dock-facts');
    rows.forEach(([label, value, reference]) => {
      if (!includeEmpty && (value === null || value === undefined || value === '')) return;
      const description = node('dd');
      const hasValue = value !== null && value !== undefined && value !== '';
      const target = !empty && hasValue && reference && canViewRadio() ? entityRefHref(reference) : null;
      if (!empty && value?.nodeType) description.append(value);
      else if (target) { const link = node('a', '', String(value)); link.href = target; description.append(link); }
      else description.textContent = empty ? '' : String(value ?? '');
      list.append(node('dt', '', label), description);
    });
    return list;
  }
  function group(label, rows, options) {
    const list = factList(rows, options);
    if (!list.children.length) return;
    const disclosure = node('details', 'ui-section-disclosure');
    const body = node('div', 'ui-section-body'); body.append(list);
    disclosure.append(node('summary', 'ui-section-summary', label), body);
    panelHost.append(disclosure);
  }
  function renderLiveDetails() {
    const displayed = current();
    const call = displayed || {};
    const options = { empty: !displayed, includeEmpty: true };
    panelHost.append(factList([
      ['Target', title(call), call.target_entity_ref], ['Target ID', targetId(call), call.target_entity_ref],
      ['Source', sourceName(call), call.source_entity_ref], ['Source ID', sourceId(call), call.source_entity_ref],
      ['System', systemLabel(call), call.radio_system_entity_ref], ['Channel', call.channel, call.entity_ref],
      ['Started', date(call)], ['Duration', call.duration_ms !== undefined ? `${Number(call.duration_ms) / 1000}s` : null],
      ['Lists', (liveState.scanLists || []).filter((item) => (call._matchedScanListIds || call.scan_list_ids || [])
        .map(String).includes(String(item.id))).map((item) => item.name).join(', ')]
    ], options));
    group('Identity & aliases', [
      ['Target type', call.target_form], ['Target description', call.target_description], ['Target group', call.target_group],
      ['Source type', call.source_form], ['Source description', call.source_description], ['Source group', call.source_group],
      ['Talker Alias', call.talker_alias], ['Alias List', call.alias_list || call.alias_list_name],
      ['Playback target', call.playback_target?.label], ['Patch group', call.patch_group_id],
      ['Patch members', patchMembers(call)]
    ], options);
    group('Radio & channel', [
      ['Protocol', call.protocol], ['Decoder', call.decoder], ['Modulation', call.modulation],
      ['Frequency', Number(call.frequency_hz) > 0 ? `${(Number(call.frequency_hz) / 1e6).toFixed(5)} MHz` : null],
      ['Site', call.site], ['Network ID', call.network_id ?? call.network], ['Site identity', call.site_identity],
      ['Model', call.model ?? call.network_model], ['Location category', call.location_category],
      ['WACN', formatHexDecimal(call.wacn, 5)], ['SysID', formatHexDecimal(call.system_id ?? call.sysid, 3)],
      ['RFSS', formatHexDecimal(call.rfss_id ?? call.rfss, 2)], ['Site ID', formatHexDecimal(call.site_id, 2)], ['RAN', call.ran],
      ['NAC', formatHexDecimal(call.nac, 3)], ['LCN', call.logical_channel_number ?? call.lcn], ['Timeslot', call.timeslot],
      ['Encrypted', typeof call.encrypted === 'boolean' ? call.encrypted ? 'Yes' : 'No' : null]
    ], options);
    group('Voice quality', [
      ['Quality', formatVoiceQuality(call.vc_quality_pct)],
      ['Decoded frames', call.vc_decoded_frames], ['Repeated frames', call.vc_repeated_frames],
      ['Concealed frames', call.vc_concealed_frames], ['Missing frames', call.vc_missing_frames],
      ['FEC errors', call.vc_fec_errors], ['FEC protected bits', call.vc_fec_protected_bits]
    ], options);
    if (!options.empty) callAudioDetailGroups(call).forEach(([label, rows]) => group(label, rows));
    group('Call', [
      ['Call ID', call.call_id], ['Identifier', call.id], ['Configuration ID', call.configuration_id, call.entity_ref],
      ['Radio system key', call.radio_system_key, call.radio_system_entity_ref],
      ['List IDs', Array.isArray(call.scan_list_ids) ? call.scan_list_ids.join(', ') : null],
      ['Start timestamp', call.started_at_ms],
      ['Completed', Number.isFinite(Number(call.completed_at_ms)) && Number(call.completed_at_ms) > 0 ?
        new Date(Number(call.completed_at_ms)).toLocaleString() : null],
      ['Completion timestamp', call.completed_at_ms]
    ], options);
  }

  const queueEntryKey = (call, index) => String(call?._queueEntryKey ?? call?.id ?? call?.call_id ?? index);
  const queueRows = () => state().queue || [];
  function queueItem(call, index, { currentRow = false, detailed = false } = {}) {
    const row = node('li');
    const item = node('div', 'audio-dock-queue-item ui-listening-queue-item');
    const button = command(`View ${currentRow ? 'playing' : 'queued'} call ${title(call)}`, 'info-circle', () => {
      selectedQueueKey = selectedQueueKey === key ? null : key; sidebarKey = ''; panelKey = ''; render();
    });
    const key = queueEntryKey(call, index); button.dataset.queueKey = key;
    button.setAttribute('aria-pressed', String(selectedQueueKey === key));
    const copy = node('span', 'audio-dock-queue-copy ui-heading-group');
    const heading = node('strong'); heading.append(identityLink(title(call), call.target_entity_ref));
    copy.append(heading, node('span', 'ui-muted ui-label',
      [sourceName(call), call.duration_ms ? `${Number(call.duration_ms) / 1000}s` : ''].filter(Boolean).join(' · ') || '—'));
    if (currentRow) copy.prepend(node('span', 'ui-muted ui-label', state().stopped ? 'Last played' : state().paused ? 'Paused' : 'Now playing'));
    if (detailed) {
      const frequency = Number(call.frequency_hz);
      const names = (liveState.scanLists || []).filter(item => (call._matchedScanListIds || call.scan_list_ids || [])
        .map(String).includes(String(item.id))).map(item => item.name).join(', ');
      copy.append(node('span', 'ui-muted ui-label', [date(call), call.channel_name || call.channel,
        frequency > 0 ? `${(frequency / 1e6).toFixed(5)} MHz` : '', call.decoder || call.protocol,
        call.timeslot !== undefined && call.timeslot !== null ? `Slot ${call.timeslot}` : '',
        typeof call.encrypted === 'boolean' ? call.encrypted ? 'Encrypted' : 'Unencrypted' : '', source === 'live' ? names : '']
        .filter(Boolean).join(' · ')));
    }
    item.append(node('span', 'audio-dock-queue-order ui-listening-order', currentRow ? '•' : String(index + 1)), copy, button);
    row.append(item);
    if (source === 'recordings' && !currentRow && detailed) {
      const actions = node('div', 'audio-dock-inspector-actions');
      actions.append(command(`Play ${title(call)}`, 'play', () => recordings.playQueued(call._queueEntryKey || call.id)),
        command(`Remove ${title(call)} from queue`, 'close', () => recordings.removeQueued(call._queueEntryKey || call.id)));
      row.append(actions);
    }
    return row;
  }
  function preserveContent(host, draw, focusFallback = () => host === panelHost ? back : host) {
    const focused = host.contains(document.activeElement) ? document.activeElement : null;
    const focusId = focused?.id, name = focused?.getAttribute('aria-label') || focused?.textContent, tag = focused?.tagName;
    const scrollTop = host.scrollTop, mainTop = mainScroll.scrollTop;
    host.replaceChildren(); draw();
    if (focused) {
      const replacement = [...host.querySelectorAll('button, input, select, a, summary, [tabindex]')].find(control =>
        focusId ? control.id === focusId : control.tagName === tag && (control.getAttribute('aria-label') || control.textContent) === name);
      (replacement || focusFallback()).focus({ preventScroll: true });
    }
    host.scrollTop = scrollTop; mainScroll.scrollTop = mainTop;
  }
  function renderSidebar() {
    const calls = queueRows(); const next = calls[0];
    const summary = `${queueCount()} queued`;
    sidebarCount.textContent = teaserCount.textContent = summary;
    count.replaceChildren(document.createTextNode(summary)); appendIcon(count, 'chevron-right'); count.dataset.uiHint = `${queueCount()} calls queued`;
    teaser.dataset.uiHint = `${queueCount()} calls queued`; nextTitle.textContent = next ? title(next) : 'Nothing queued';
    nextSubtitle.textContent = next ? [sourceName(next), next.duration_ms ? `${Number(next.duration_ms) / 1000}s` : ''].filter(Boolean).join(' · ') : 'Choose a call to listen';
    clearQueue.disabled = !isAllowed() || !queueCount() && !state().continuation;
    sidebarClickMode.hidden = source !== 'recordings';
    const nextKey = JSON.stringify([source, calls, selectedQueueKey, getSourceNameDisplay()]);
    if (sidebarKey === nextKey) return; sidebarKey = nextKey;
    const previousSelectedIndex = [...sidebarList.querySelectorAll('[data-queue-key]')]
      .findIndex(button => button.dataset.queueKey === selectedQueueKey);
    preserveContent(sidebarList, () => {
      calls.forEach((call, index) => sidebarList.append(queueItem(call, index)));
      if (!calls.length) sidebarList.append(node('li', 'ui-muted ui-label', state().continuation ? 'More matching calls are loading…' : 'Nothing queued'));
    });
    const index = calls.findIndex((call, index) => queueEntryKey(call, index) === selectedQueueKey);
    const call = calls[index];
    preserveContent(inspector, () => {
      inspector.hidden = !call;
      if (!call) return;
      const heading = node('p');
      heading.append(identityLink(title(call), call.target_entity_ref), ` · ${sourceName(call)}`);
      inspector.append(heading,
        node('p', 'ui-muted ui-label', [call.channel || call.channel_name, systemLabel(call), date(call),
          Number(call.frequency_hz) > 0 ? `${(Number(call.frequency_hz) / 1e6).toFixed(5)} MHz` : ''].filter(Boolean).join(' · ')));
      if (source === 'recordings') {
        const actions = node('div', 'audio-dock-inspector-actions');
        const playNow = textButton('Play now', () => recordings.playQueued(call._queueEntryKey || call.id));
        const remove = textButton('Remove', () => recordings.removeQueued(call._queueEntryKey || call.id));
        playNow.className = remove.className = 'link-button'; actions.append(playNow, remove); inspector.append(actions);
      }
    }, () => {
      const rows = [...sidebarList.querySelectorAll('[data-queue-key]')];
      return rows.find(button => button.dataset.queueKey === selectedQueueKey) ||
        rows[Math.min(Math.max(0, previousSelectedIndex), rows.length - 1)] || sidebarList;
    });
  }
  function renderQueue() {
    const tools = node('div', 'audio-dock-queue-heading');
    const clear = textButton('Clear queue', () => source === 'recordings' ? recordings.clearQueue() : getLivePlayer()?.clearQueue());
    clear.className = 'link-button'; clear.disabled = !queueCount() && !state().continuation;
    tools.append(node('span', 'ui-muted ui-label', `${queueCount()} queued`), clear); panelHost.append(tools);
    if (recordingState.continuation && source === 'recordings') panelHost.append(message('Continuing through matching calls. More calls are loaded as you listen.'));
    const list = node('ol', 'audio-dock-context-body-list'); const playingCall = state().current;
    if (playingCall) list.append(queueItem(playingCall, -1, { currentRow: true, detailed: true }));
    queueRows().forEach((call, index) => list.append(queueItem(call, index, { detailed: true })));
    if (!list.children.length) panelHost.append(message('Queue is empty.')); else panelHost.append(list);
    if (source === 'recordings') panelHost.append(clickModeField('audio-dock-context-click-mode'));
  }
  function renderListening() {
    const lists = liveState.scanLists || [], maximum = liveState.maximumSelectedScanLists || 16;
    const selected = lists.filter(item => item.selected).length;
    panelHost.append(node('p', 'ui-muted ui-label audio-dock-selection-summary', 'Only selected lists add calls to your Live queue.'));
    if (!liveState.scanListCatalogReady) {
      const unavailable = liveState.scanListCatalogState === 'unavailable';
      panelHost.append(message(unavailable ? 'Unable to load lists. Try again.' : 'Lists are loading…'));
      if (unavailable && refreshScanLists) panelHost.append(textButton('Retry scan lists', refreshScanLists));
    }
    else if (!lists.length) panelHost.append(message('No lists are available.'));
    const choices = node('div', 'ui-surface audio-dock-selection-list');
    choices.dataset.guideTarget = 'scan-lists-player-lists';
    const direct = liveState.directListen;
    if (direct?.allowed) {
      const row = node('div', 'ui-select-list-item ui-audio-selection-row audio-dock-selection-row');
      const label = node('label', 'ui-action-row ui-action-row-start');
      const checkbox = node('input', 'ui-selection-check');
      checkbox.type = 'checkbox'; checkbox.checked = direct.selected;
      checkbox.setAttribute('aria-label', 'Direct Listen');
      checkbox.disabled = !direct.targets.length;
      checkbox.addEventListener('change', userAction(() => getLivePlayer()?.setDirectListenSelected(checkbox.checked)));
      const copy = node('span', 'ui-heading-group audio-dock-selection-copy');
      copy.append(node('strong', '', 'Direct Listen'),
        node('span', 'ui-muted ui-label', `${direct.targets.length} ${direct.targets.length === 1 ? 'target' : 'targets'} · This session`));
      label.append(checkbox, copy);
      row.append(label); choices.append(row);
    }
    lists.forEach(item => {
      const label = node('label', 'ui-select-list-item ui-audio-selection-row audio-dock-selection-row');
      const checkbox = node('input', 'ui-selection-check'); checkbox.type = 'checkbox'; checkbox.checked = item.selected;
      checkbox.id = `audio-dock-scan-list-${item.id}`;
      checkbox.disabled = !isAllowed() || !item.enabled || selected >= maximum && !item.selected;
      checkbox.addEventListener('change', userAction(() => getLivePlayer()?.setScanListSelected(item.id, checkbox.checked)));
      const copy = node('span', 'ui-heading-group audio-dock-selection-copy'); copy.append(node('strong', '', item.name));
      if (item.description) copy.append(node('span', 'ui-muted ui-label', item.description));
      label.append(checkbox, copy); choices.append(label);
    });
    if (lists.length || direct?.allowed) panelHost.append(choices);
    if (liveState.scanListCatalogReady && lists.length && !selected && !direct?.selected) panelHost.append(message('Select a List to start adding live calls.'));
    const footer = node('div', 'audio-dock-context-footer');
    footer.append(node('span', 'ui-muted ui-label', `Up to ${maximum} lists`));
    if (openScanListCoverage) footer.append(textButton('View coverage', openScanListCoverage));
    panelHost.append(footer);
  }
  function renderDirectListen() {
    const direct = liveState.directListen;
    if (!direct?.allowed) { panelHost.append(message('Direct Listen is unavailable for this session.')); return; }
    panelHost.append(node('p', 'ui-muted ui-label', 'Your talkgroups and channels for this session.'));
    if (!direct.targets.length) {
      const empty = node('div', 'ui-empty-state ui-empty-state-quiet');
      empty.append(node('h3', '', 'No Direct Listen targets'),
        node('p', 'ui-label', 'Use Add to Direct Listen beside a talkgroup or channel.'));
      panelHost.append(empty);
    } else {
      const list = node('div', 'ui-surface audio-dock-selection-list');
      direct.targets.forEach(item => {
        const section = node('section', 'ui-record-section');
        const row = node('div', 'ui-record-card-header audio-dock-avoid-row');
        const copy = node('div', 'ui-record-card-copy');
        const target = item.target || {};
        const reference = target.kind === 'channel' ? { kind: 'channel', key: target.key } : target;
        const heading = node('strong'); heading.append(identityLink(item.label || 'Unnamed target', reference));
        copy.append(heading);
        const scope = target.kind === 'channel' ? `Channel ${target.key}` :
          target.radio_system_key?.includes(':channel:') ? target.radio_system_key.split(':').at(-1) :
            systemIdentity(target.radio_system_key);
        const identity = target.identity_key ? `${target.identity_key.startsWith('v1-p-') ? 'Patch' : 'TG'} ${target.identity_key.split('-').at(-1)}` : '';
        const detail = node('span', 'ui-muted ui-label', item.context || scope);
        if (identity) {
          if (detail.textContent) detail.append(' · ');
          detail.append(identityLink(identity, reference));
        }
        detail.dataset.uiHint = [scope, identity].filter(Boolean).join(' · ');
        copy.append(detail);
        const remove = command(`Remove ${item.label || 'target'} from Direct Listen`, 'close', () => {
          const index = direct.targets.findIndex(target => target.key === item.key);
          getLivePlayer()?.removeDirectListenTarget(item.key);
          const remaining = panelHost.querySelectorAll('[data-direct-listen-remove]');
          (remaining[Math.min(index, remaining.length - 1)] || back).focus({ preventScroll: true });
        });
        remove.dataset.directListenRemove = item.key;
        row.append(copy, remove);
        section.append(row); list.append(section);
      });
      panelHost.append(list);
    }
    const footer = node('div', 'audio-dock-context-footer');
    footer.append(node('span', 'ui-muted ui-label', `Up to ${direct.maximumTargets} targets`));
    panelHost.append(footer);
  }
  function renderAvoids() {
    const items = liveState.avoids || [];
    if (!items.length) {
      const empty = node('div', 'ui-empty-state ui-empty-state-quiet');
      empty.append(node('h3', '', 'No avoided targets'), node('p', 'ui-label', 'Use Avoid during a live call to skip that target. Remove a target here to hear it again.'));
      panelHost.append(empty);
    } else {
      panelHost.append(node('p', 'ui-muted ui-label', 'These targets are skipped during Live listening.'));
      const list = node('div', 'ui-surface audio-dock-selection-list');
      items.forEach(item => {
        const section = node('section', 'ui-record-section'), row = node('div', 'ui-record-card-header audio-dock-avoid-row');
        const copy = node('div', 'ui-record-card-copy');
        const heading = node('strong'); heading.append(identityLink(item.label || item.key, item.target_entity_ref));
        copy.append(heading, node('span', 'ui-muted ui-label', systemLabel(item) || item.system_scope || item.systemName || 'All systems'));
        row.append(copy, command(`Remove ${item.label || item.key} from Avoid List`, 'close', () => getLivePlayer()?.removeAvoid(item.key)));
        section.append(row); list.append(section);
      }); panelHost.append(list);
    }
    const footer = node('div', 'audio-dock-context-footer'); footer.append(node('span', 'ui-muted ui-label', 'Live listening'));
    panelHost.append(footer);
  }
  function renderSettings() {
    const settings = node('div', 'audio-dock-settings');
    panelHost.append(settings);
    const { field: hangtimeField, input: hangtime } = createCallDisplayHangtimeField(node, {
      id: 'audio-dock-call-display-hangtime', value: getCallDisplayHangtimeSeconds()
    });
    hangtime.addEventListener('change', userAction(() => {
      if (!hangtime.checkValidity()) { hangtime.reportValidity(); return; }
      hangtime.disabled = true;
      const refresh = () => { panelKey = ''; render(); };
      void Promise.resolve().then(() => setCallDisplayHangtimeSeconds(Number(hangtime.value)))
        .then(refresh, refresh);
    }));
    settings.append(hangtimeField);
    if (source !== 'live') return;
    const groupField = uiToggleField('Group calls by target', liveState.targetGrouping !== false,
      'Group calls by target', 'Keeps calls for the same group together to preserve conversationality');
    const grouping = groupField.querySelector('input'); grouping.id = 'audio-dock-target-grouping';
    const groupHelp = groupField.querySelector('.ui-toggle-copy small');
    if (groupHelp) { groupHelp.id = 'audio-dock-grouping-help'; grouping.setAttribute('aria-describedby', groupHelp.id); }
    grouping.addEventListener('change', userAction(() => {
      const player = getLivePlayer(); if (!player) return;
      player.applyPreferences({ target_grouping: grouping.checked }); player.writePreferences();
    }));
    settings.append(groupField);
    const burstLabel = node('label', 'ui-toggle-field'); burstLabel.htmlFor = 'audio-dock-target-burst';
    const burstCopy = node('span', 'ui-toggle-copy');
    const burstHelp = node('small', '', 'When grouping is on, switch to another waiting target after this many consecutive calls.');
    burstHelp.id = 'audio-dock-burst-help';
    burstCopy.append(node('strong', '', 'Calls per target'), burstHelp);
    const burst = node('input', 'ui-input'); burst.id = 'audio-dock-target-burst'; burst.type = 'number'; burst.min = '1'; burst.max = '20'; burst.value = liveState.targetBurstLimit || 4;
    burst.setAttribute('aria-label', 'Calls per target');
    burst.setAttribute('aria-describedby', burstHelp.id);
    burst.addEventListener('change', userAction(() => {
      if (!burst.checkValidity()) { burst.reportValidity(); return; }
      const player = getLivePlayer(); if (!player) return;
      player.applyPreferences({ target_burst_limit: Number(burst.value) }); player.writePreferences();
    }));
    burstLabel.append(burstCopy, burst); settings.append(burstLabel);
    const titleField = uiToggleField('Playing call in page title', getTitlePreference(),
      'Playing call in page title', 'Show the playing Live call in browser tab titles outside Scanner.');
    const prepend = titleField.querySelector('input'); prepend.id = 'audio-dock-title-preference';
    const titleHelp = titleField.querySelector('.ui-toggle-copy small');
    if (titleHelp) { titleHelp.id = 'audio-dock-title-help'; prepend.setAttribute('aria-describedby', titleHelp.id); }
    prepend.addEventListener('change', userAction(() => {
      const refresh = () => { panelKey = ''; render(); };
      void Promise.resolve(setTitlePreference(prepend.checked)).then(refresh, refresh);
    }));
    settings.append(titleField);
  }

  function relativeAge(call) {
    const timestamp = call?.completed_at_ms ?? call?.started_at_ms ?? call?.start_ms;
    if (!Number.isFinite(Number(timestamp)) || Number(timestamp) <= 0) return call ? 'Time unavailable' : 'Waiting for a call';
    const seconds = Math.max(0, Math.floor((Date.now() - Number(timestamp)) / 1000));
    if (seconds < 5) return 'Just now'; if (seconds < 60) return `${seconds}s ago`;
    const minutes = Math.floor(seconds / 60); if (minutes < 60) return `${minutes}m ago`;
    const hours = Math.floor(minutes / 60); return hours < 24 ? `${hours}h ago` : `${Math.floor(hours / 24)}d ago`;
  }
  function updateAge() { age.textContent = relativeAge(current()); }
  function updateProgress() {
    if (dock.hidden || superCollapsed && !mobileLayout.matches || size === 'collapsed') return;
    const snapshot = guideScanListState || guideRecordingState || (source === 'live' ? getLivePlayer()?.viewState() || liveState : recordings.viewState());
    const timingOptions = { recording: source === 'recordings', allowed: isAllowed() };
    timing.update(snapshot, timingOptions); compactTiming.update(snapshot, timingOptions);
  }
  const spectrumLevels = new Float32Array(64);
  const spectrumNodes = [fullWave, compactWave, slimWave];
  function drawSpectrum() {
    spectrumFrame = null;
    if (destroyed || dock.hidden || document.hidden || source !== 'live' || !isAllowed()) return;
    const playing = !guideScanListState && Boolean(getLivePlayer()?.readAudioSpectrum?.(spectrumLevels, 8_000));
    spectrumNodes.forEach(wave => {
      if (!wave.getClientRects().length) return;
      wave.classList.toggle('paused', !playing);
      const height = Math.max(0, wave.clientHeight - 8);
      [...wave.children].forEach((bar, index) => { bar.style.height = `${playing ? Math.round(3 + spectrumLevels[index] * height) : 3}px`; });
    });
    fullWave.setAttribute('aria-label', playing ? 'Voice frequency spectrum' : 'Voice spectrum idle');
    spectrumFrame = window.requestAnimationFrame(drawSpectrum);
  }
  function synchronizeLoops() {
    const visible = !destroyed && !dock.hidden && !document.hidden;
    if (visible && progressTimer === null) progressTimer = window.setInterval(() => { updateProgress(); updateAge(); }, 250);
    else if (!visible && progressTimer !== null) { window.clearInterval(progressTimer); progressTimer = null; }
    if (visible && source === 'live' && isAllowed()) {
      if (spectrumFrame === null) spectrumFrame = window.requestAnimationFrame(drawSpectrum);
    } else if (spectrumFrame !== null) { window.cancelAnimationFrame(spectrumFrame); spectrumFrame = null; }
  }
  function renderPanel() {
    context.hidden = !panel; hero.hidden = Boolean(panel);
    if (!panel || size !== 'full' || dock.hidden) return;
    const active = state(), call = current();
    const selected = (liveState.scanLists || []).filter(item => item.selected).length + (liveState.directListen?.selected ? 1 : 0);
    const names = { menu: 'Player menu', details: 'Call details', transcript: 'Transcript', queue: 'Queue', listening: 'Lists', direct: 'Direct Listen', settings: 'Listening settings', avoids: 'Avoid List' };
    contextTitle.textContent = names[panel];
    setLabel(back, menuPanels.has(panel) && panel !== 'menu' ? 'Back to menu' : externalManagement ? 'Back to Scanner' : 'Back to player');
    contextSubtitle.textContent = panel === 'menu' ? (source === 'live' ? 'Live listening' : 'Recordings') : panel === 'listening' ? `${selected} of ${(liveState.scanLists || []).length + (liveState.directListen?.allowed ? 1 : 0)} selected` :
      panel === 'direct' ? `${liveState.directListen?.targets.length || 0} ${liveState.directListen?.targets.length === 1 ? 'target' : 'targets'} · This session` : panel === 'queue' ? `${queueCount()} queued` : panel === 'avoids' ? `${(liveState.avoids || []).length} avoided` : panel === 'settings' ? 'Playback preferences' :
      panel === 'details' && !call ? 'Waiting for a call' : title(call);
    const panelState = panel === 'menu' ? [liveState.scanLists, liveState.directListen, liveState.avoids, queueCount()] : panel === 'listening' ? [active.scanLists, active.directListen, active.maximumSelectedScanLists, active.scanListCatalogReady, active.scanListCatalogState] :
      panel === 'direct' ? [liveState.directListen] :
      panel === 'avoids' ? [liveState.avoids, liveState.scanLists, liveState.directListen?.allowed] : panel === 'settings' ? [getCallDisplayHangtimeSeconds(), liveState.targetGrouping, liveState.targetBurstLimit, getTitlePreference()] :
      panel === 'queue' ? [active.current, active.queue, active.scanLists, active.continuation, active.stopped, active.paused, selectedQueueKey] :
      panel === 'transcript' ? [call?.id, call?.transcription, active.detailsLoading, active.detailsError] :
      [active.current, call, active.stopped, active.scanLists, active.detailsLoading, active.detailsError];
    const nextKey = JSON.stringify([source, panel, getSourceNameDisplay(), ...panelState]);
    if (panelKey === nextKey) return; panelKey = nextKey;
    panelHost.querySelectorAll('details').forEach(item => disclosureStates.set(`${renderedPanel}:${item.querySelector('summary')?.textContent}`, item.open));
    preserveContent(panelHost, () => {
      renderedPanel = `${source}:${panel}`;
      if (panel === 'menu') renderMenu();
      else if (panel === 'details') {
        if (source === 'recordings') {
          const mount = node('div'); panelHost.append(mount);
          recordings.renderDetails(mount, call, { shell: true, empty: !call });
          if (call) callAudioDetailGroups(call).forEach(([label, rows]) => group(label, rows));
          mount.querySelectorAll('details > dl').forEach(list => {
            list.classList.remove('ui-form-section'); const wrapper = node('div', 'ui-section-body'); list.replaceWith(wrapper); wrapper.append(list);
          });
        } else renderLiveDetails();
      } else if (panel === 'transcript') { const mount = node('div'); panelHost.append(mount); recordings.renderTranscript(mount, call); }
      else if (panel === 'queue') renderQueue(); else if (panel === 'listening') renderListening();
      else if (panel === 'direct') renderDirectListen();
      else if (panel === 'avoids') renderAvoids(); else renderSettings();
      panelHost.querySelectorAll('details').forEach(item => {
        const key = `${renderedPanel}:${item.querySelector('summary')?.textContent}`; item.open = disclosureStates.get(key) === true;
        item.addEventListener('toggle', () => { if (item.isConnected && panelHost.contains(item)) disclosureStates.set(key, item.open); });
      });
    });
  }
  function render() {
    if (destroyed) return;
    const permissions = access();
    if (!permissions[source] && (permissions.live || permissions.recordings)) {
      closeVolume(false);
      panelHost.querySelectorAll('button, input, select').forEach(control => { control.disabled = true; });
      source = permissions.live ? 'live' : 'recordings'; panel = null; externalManagement = false; managementSource = null;
      if (menuReturnSize !== null) size = menuReturnSize;
      menuReturnSize = null; panelKey = '';
    }
    const unavailable = (!guidePresentation && document.body.dataset.view === 'scanner' && !externalManagement) ||
      document.body.dataset.view === 'access-landing' || !permissions.live && !permissions.recordings;
    if (unavailable && sizeGesture) cancelSizeGesture();
    const hiddenToHeader = superCollapsed && !mobileLayout.matches;
    const presentationSize = sizeGesture?.presentationSize || size;
    const dragging = Boolean(sizeGesture?.active);
    dock.hidden = unavailable || hiddenToHeader;
    if (dock.hidden) closeVolume(false);
    if (unavailable) cancelPresentationMotion();
    dock.dataset.superCollapsed = String(hiddenToHeader); dock.dataset.state = presentationSize; dock.dataset.source = source;
    document.body.classList.toggle('has-audio-dock', !unavailable);
    presentation.hidden = hiddenToHeader; restore.hidden = unavailable || !hiddenToHeader;
    hide.hidden = slimHide.hidden = mobileLayout.matches;
    [hide, restore, slimHide].forEach(button => button.setAttribute('aria-expanded', String(!hiddenToHeader)));
    if (unavailable) focusedPresentationControl = null;
    // Keep the original top control under the pointer while revealing the body.
    header.hidden = dragging ? sizeGesture.initialSize === 'collapsed' : presentationSize === 'collapsed';
    slim.hidden = dragging ? sizeGesture.initialSize !== 'collapsed' : presentationSize !== 'collapsed';
    compact.hidden = presentationSize !== 'minimal'; body.hidden = presentationSize !== 'full';
    if (dock.hidden || size === 'collapsed') focusedSourceControl = null;
    handle.setAttribute('aria-expanded', String(presentationSize !== 'collapsed'));
    sizeHint.textContent = `${{ collapsed: 'Collapsed player', minimal: 'Compact player', full: 'Expanded player' }[size]}. Drag up or down, or click to change player size.`;
    handle.dataset.uiHint = sizeHint.textContent;
    const call = current(), active = state(), name = String(title(call));
    compactTitle.textContent = collapsedTitle.textContent = name;
    renderTitle(name, call?.target_entity_ref);
    compactTitle.dataset.uiHint = collapsedTitle.dataset.uiHint = nowTitle.dataset.uiHint = name;
    collapsedMeta.textContent = `${source === 'live' ? 'Live' : 'Recording'} · ${queueCount()} queued`;
    compactSubtitle.textContent = subtitle(call) || (source === 'live' ? 'Select a List to listen' : 'Choose a recording to play');
    compactSubtitle.dataset.uiHint = [sourceSummary(call), call?.channel || call?.channel_name].filter(Boolean).join(' · ') || compactSubtitle.textContent;
    Object.entries(sourceOptions).forEach(([value, option]) => { option.disabled = !permissions[value]; });
    const sourceLabel = source === 'live' ? 'Live' : 'Recordings'; currentSourceChoice.textContent = sourceLabel;
    if (sourcePicker.value !== '') sourcePicker.value = ''; sourcePicker.disabled = !permissions.live && !permissions.recordings;
    const live = source === 'live';

    const selected = (liveState.scanLists || []).filter(item => item.selected && item.enabled).length + (liveState.directListen?.selected ? 1 : 0);
    menuTriggers.forEach(button => { button.disabled = !isAllowed(); button.setAttribute('aria-expanded', String(menuPanels.has(panel))); });
    shrink.querySelector('use').setAttribute('href', mobileLayout.matches ? '#icon-close' : '#icon-chevron-down');
    setLabel(shrink, presentationSize === 'full' ? 'Make player compact' : 'Collapse player');
    transcriptButton.hidden = live; transcriptButton.disabled = !permissions.recordings;
    detailsButton.disabled = !isAllowed();
    kind.textContent = [call?.decoder || call?.protocol, live ? 'Voice' : 'Recorded call'].filter(Boolean).join(' · ');
    nowDescription.textContent = description(call); nowDescription.hidden = !nowDescription.textContent;
    nowSystem.textContent = call ? [systemLabel(call), call.channel_name || call.channel].filter(Boolean).join(' · ') : (live ? 'Select a List to start listening' : 'Choose a recording to play');
    transmittingName.textContent = sourceName(call) || '—'; targetGroup.textContent = call?.target_group || call?.group_name || ''; targetGroup.hidden = !targetGroup.textContent;
    transmittingName.dataset.uiHint = sourceSummary(call) || transmittingName.textContent;
    transmitting.hidden = false;
    technical.replaceChildren();
    [Number(call?.frequency_hz) > 0 ? `${(Number(call.frequency_hz) / 1e6).toFixed(5)} MHz` : '', call?.site,
      call?.alias_list || call?.alias_list_name].filter(Boolean).forEach(value => technical.append(node('span', '', String(value))));
    if (!technical.children.length) technical.append(node('span', '', '—'));
    audioHeading.hidden = fullWave.hidden = !live; compactWave.hidden = slimWave.hidden = !live;
    const qualityNumber = Number(call?.vc_quality_pct); quality.hidden = !call || call.vc_quality_pct === null ||
      call.vc_quality_pct === undefined || call.vc_quality_pct === '' || !Number.isFinite(qualityNumber);
    qualityValue.textContent = quality.hidden ? '' : formatVoiceQuality(qualityNumber);
    const playing = live ? !active.stopped && !active.paused : active.playing;
    const engineStatus = String(active.status || '').trim().replace(/\bscan lists\b/gi, 'Lists').replace(/\bscan list\b/gi, 'List');
    const ordinaryStatus = engineStatus.toLocaleLowerCase().replace(/[.!]$/, '');
    const shortStatus = playbackStatuses.get(ordinaryStatus);
    const statusLabel = active.paused ? 'Paused' : live && selected > 0 && active.holdTarget ? 'Holding' : shortStatus ||
      (active.stopped ? 'Stopped' : active.loading ? 'Loading' : playing ? 'Listening' : 'Ready');
    if (status.textContent !== statusLabel) status.textContent = statusLabel;
    status.classList.toggle('ui-pill-success', playing && ['Listening', 'Holding', 'Replaying last call'].includes(statusLabel));
    status.dataset.uiHint = engineStatus || statusLabel;
    const ordinary = !engineStatus || Boolean(shortStatus) || engineStatus === statusLabel ||
      ['listening', 'paused', 'stopped', 'playing recording', 'ready to play', 'ready', 'playback finished',
        'choose a recording to play'].includes(ordinaryStatus) || /^paused\s*[—·-]/i.test(engineStatus);
    const playbackError = /could not|unavailable/i.test(engineStatus);
    playbackFeedback.setAttribute('role', playbackError ? 'alert' : 'status');
    playbackFeedback.classList.toggle('ui-notice-danger', playbackError);
    if (playbackFeedback.textContent !== engineStatus) playbackFeedback.textContent = engineStatus;
    playbackFeedback.hidden = ordinary || dock.hidden;
    updateAge();
    const playback = audioPlaybackAction(active, { recording: !live });
    [play, miniPlay, slimPlay].forEach(button => { setLabel(button, playback.label, playback.icon); button.disabled = !isAllowed() || !live && !active.current && !queueCount(); });
    [miniNext, skip].forEach(button => { button.disabled = !isAllowed() || (live ? !active.current && !queueCount() : !active.canNext); });
    previous.disabled = !isAllowed() || !active.canPrevious; rewind.disabled = forward.disabled = !isAllowed() || !active.current || !active.duration;
    replay.disabled = !isAllowed() || !liveState.lastCallReady || liveState.paused;
    [previous, rewind, forward].forEach(button => { button.hidden = live; }); replay.hidden = !live;
    stop.disabled = !isAllowed() || (live ? Boolean(active.stopped) : !active.current && !queueCount()); setLabel(stop, live ? 'Stop listening' : 'Stop recording');
    hold.hidden = avoid.hidden = !live;
    hold.disabled = !isAllowed() || liveState.replayingLast || !liveState.currentReady && !liveState.holdTarget;
    setLabel(hold, liveState.holdTarget ? 'Release hold' : 'Hold current target'); hold.setAttribute('aria-pressed', String(Boolean(liveState.holdTarget)));
    avoid.disabled = !isAllowed() || !liveState.currentReady || liveState.replayingLast;
    volumeControl.update(active.volume, isAllowed());
    if (source === 'live' && panel === 'transcript' || source === 'recordings' && ['listening', 'direct', 'avoids'].includes(panel)) { panel = null; panelKey = ''; }
    renderSidebar(); renderPanel(); dock.querySelectorAll('[data-click-mode]').forEach(select => { if (select.value !== recordingState.clickMode) select.value = recordingState.clickMode; });
    updateProgress(); measure(); synchronizeLoops();
    if (guidePresentation) [dock, volumePopover].forEach(root => root.querySelectorAll('input, select, textarea').forEach(control => {
      if (!guideControlValues.has(control)) guideControlValues.set(control, { value: control.value, checked: control.checked });
    }));
  }
  function positionPlaybackFeedback() {
    if (playbackFeedback.hidden) return;
    const viewport = window.visualViewport;
    const bounds = { left: viewport?.offsetLeft || 0, top: viewport?.offsetTop || 0,
      width: viewport?.width || innerWidth, height: viewport?.height || innerHeight };
    const tokens = getComputedStyle(document.documentElement);
    const gap = parseFloat(tokens.getPropertyValue('--space-2')) || 8;
    const safeTop = bounds.top + Math.max(gap, (parseFloat(tokens.getPropertyValue('--app-header-offset')) || 0) + gap);
    const anchor = dock.getBoundingClientRect();
    playbackFeedback.style.width = `${Math.min(anchor.width, bounds.width - gap * 2)}px`;
    playbackFeedback.style.maxHeight = `${Math.max(gap, bounds.top + bounds.height - safeTop - gap)}px`;
    let rect = playbackFeedback.getBoundingClientRect();
    let left = anchor.left;
    let top = Math.max(safeTop, anchor.top - rect.height - gap);
    if (anchor.top - rect.height - gap < safeTop && mainScroll.getClientRects().length) {
      // A short viewport leaves no room above the expanded dock. Keep its
      // header and transport clear by containing the notice over the scroll area.
      const scroll = mainScroll.getBoundingClientRect();
      const scrollTop = Math.max(safeTop, scroll.top + gap);
      const scrollBottom = Math.min(bounds.top + bounds.height - gap, scroll.bottom - gap);
      playbackFeedback.style.width = `${Math.max(0, Math.min(scroll.width - gap * 2, bounds.width - gap * 2))}px`;
      playbackFeedback.style.maxHeight = `${Math.max(0, scrollBottom - scrollTop)}px`;
      rect = playbackFeedback.getBoundingClientRect();
      left = scroll.left + gap;
      top = scrollTop;
    }
    playbackFeedback.style.left = `${Math.max(bounds.left + gap, Math.min(left, bounds.left + bounds.width - rect.width - gap))}px`;
    playbackFeedback.style.top = `${top}px`;
  }
  function measure() {
    const height = dock.hidden ? 0 : Math.ceil(dock.getBoundingClientRect().height);
    document.documentElement.style.setProperty('--audio-dock-height', `${height}px`);
    positionPlaybackFeedback();
  }
  const resizeObserver = new ResizeObserver(measure); resizeObserver.observe(dock);
  const viewportChanged = () => { cancelSizeGesture(); closeVolume(false); cancelPresentationMotion(); measure(); };
  const visibilityChanged = () => { if (document.hidden) { cancelSizeGesture(); closeVolume(false); } synchronizeLoops(); };
  const escapeContext = event => { if (!guidePresentation && event.key === 'Escape' && !volumePopover.matches(':popover-open') && panel) { event.preventDefault(); closePanel(); } };
  mobileLayout.addEventListener('change', updatePresentationLayout);
  window.addEventListener('resize', viewportChanged); window.visualViewport?.addEventListener('resize', viewportChanged); window.visualViewport?.addEventListener('scroll', viewportChanged);
  window.addEventListener('blur', cancelSizeGesture);
  document.addEventListener('visibilitychange', visibilityChanged); dock.addEventListener('keydown', escapeContext);
  const routeObserver = new MutationObserver(() => { cancelSizeGesture(); closeVolume(false); cancelPresentationMotion(); panelKey = ''; render(); });
  routeObserver.observe(document.body, { attributes: true, attributeFilter: ['data-view'] });
  const unsubscribeRecordings = recordings.subscribeState(value => {
    latestRecordingState = value;
    recordingState = guideRecordingState || value;
    const queueOnly = !value.current && value.queue?.length && liveState.stopped !== false;
    const active = Boolean(value.playing || value.loading || queueOnly);
    if (!guidePresentation && active && !previousRecordingActive && access().recordings) { closeVolume(false); source = 'recordings'; panelKey = ''; }
    previousRecordingActive = active; render();
  });
  function synchronize() {
    if (!access().recordings) sharedQueueKey = '';
    const player = getRealLivePlayer();
    if (player !== boundLive) {
      unsubscribeLive(); boundLive = player;
      unsubscribeLive = player?.subscribeState(value => {
        latestLiveState = value;
        if (!guideScanListState) liveState = value;
        if (!guidePresentation && !value.stopped && !previousLiveActive && access().live) { closeVolume(false); managementSource = null; source = 'live'; panelKey = ''; }
        previousLiveActive = !value.stopped; render();
      }) || (() => {});
    }
    render();
    const params = new URLSearchParams(window.location.search), queue = params.get('recording_queue');
    if (!guidePresentation && access().recordings && queue && queue !== sharedQueueKey) { sharedQueueKey = queue; closeVolume(false); source = 'recordings'; size = 'full'; panel = 'queue'; void recordings.loadSharedQueue(params).then(render); }
  }
  synchronize();
  // Tours use the normal player renderers with a separate, read-only view of
  // sample lists. Real engine snapshots keep arriving while the guide is open.
  // No sample control may issue an engine command, including synthetic events.
  const guideCommandEvents = ['click', 'auxclick', 'dblclick', 'contextmenu', 'pointerdown', 'mousedown', 'keydown', 'input', 'change', 'submit'];
  const protectGuideCommands = (event) => {
    if ((!guideScanListState && !guideRecordingState) || event.type === 'keydown' && ['Tab', 'Escape'].includes(event.key)) return;
    event.preventDefault(); event.stopImmediatePropagation();
    if (['input', 'change'].includes(event.type)) {
      const original = guideControlValues.get(event.target);
      if (original) Object.assign(event.target, original);
    }
  };
  [dock, volumePopover, restore].forEach(root => guideCommandEvents.forEach(type =>
    root.addEventListener(type, protectGuideCommands, true)));
  function prepareGuide({ minimized = false, sampleScanLists = null, openSampleLists = true,
    sampleRecordings = null, recordingsSize = 'minimal', sampleLiveState = null, guidePanel = undefined } = {}) {
    const previous = { size, superCollapsed, source, panel, externalManagement, managementSource, menuReturnSize, guidePresentation, guideScanListState, guideRecordingState,
      contextInvoker, selectedQueueKey, focus: document.activeElement,
      scroll: [mainScroll.scrollTop, panelHost.scrollTop, sidebarList.scrollTop] };
    cancelSizeGesture(); closeVolume(false); cancelPresentationMotion();
    guidePresentation = true;
    guideControlValues = new WeakMap();
    superCollapsed = minimized;
    if (!minimized) {
      size = 'minimal';
      if (access().live) source = 'live';
    }
    panel = null;
    if (Array.isArray(sampleScanLists)) {
      guideScanListState = { scanLists: sampleScanLists.filter(item => item.enabled !== false &&
        item.available_to_listeners !== false).map(item => ({ ...item, enabled: true, selected: Boolean(item.selected) })),
      maximumSelectedScanLists: 16, scanListCatalogReady: true, scanListCatalogState: 'ready',
      stopped: true, paused: false, current: null, displayCall: null, queue: [], queuedCount: 0,
      currentReady: false, lastCallReady: false, currentTime: 0, duration: 0, volume: 1,
      status: 'Stopped', avoids: [], holdTarget: null };
      liveState = guideScanListState;
      superCollapsed = false; size = openSampleLists ? 'full' : 'minimal'; source = 'live';
      panel = openSampleLists ? 'listening' : null;
      contextInvoker = miniMenu;
    }
    if (sampleLiveState) {
      guideScanListState = { ...sampleLiveState }; liveState = guideScanListState;
      source = 'live'; superCollapsed = false; size = 'minimal'; panel = null;
      externalManagement = document.body.dataset.view === 'scanner';
    }
    if (guidePanel !== undefined && source === 'live') {
      panel = guidePanel; if (panel) size = 'full';
    }
    let unsubscribeSampleRecordings = () => {};
    if (sampleRecordings && access().recordings) {
      guideRecordingState = typeof sampleRecordings.viewState === 'function' ? sampleRecordings.viewState() : structuredClone(sampleRecordings);
      recordingState = guideRecordingState;
      source = 'recordings'; superCollapsed = false;
      size = recordingsSize === 'full' ? 'full' : 'minimal';
      panel = null; selectedQueueKey = null;
      if (typeof sampleRecordings.subscribeState === 'function') {
        unsubscribeSampleRecordings = sampleRecordings.subscribeState(value => {
          guideRecordingState = value; recordingState = value;
          panelKey = ''; sidebarKey = ''; render();
        });
      }
    }
    panelKey = ''; sidebarKey = ''; render();
    let restored = false;
    return () => {
      if (restored) return;
      restored = true;
      unsubscribeSampleRecordings();
      if (guideScanListState || guideRecordingState) { panelHost.replaceChildren(); renderedPanel = ''; }
      ({ size, superCollapsed, source, panel, externalManagement, managementSource, menuReturnSize, guidePresentation, guideScanListState, guideRecordingState,
        contextInvoker, selectedQueueKey } = previous);
      liveState = guideScanListState || latestLiveState;
      recordingState = guideRecordingState || latestRecordingState;
      panelKey = ''; sidebarKey = ''; render();
      [mainScroll.scrollTop, panelHost.scrollTop, sidebarList.scrollTop] = previous.scroll;
      if (previous.focus?.isConnected && (dock.contains(previous.focus) || previous.focus === restore) &&
        previous.focus.getClientRects().length && !document.querySelector('.ui-setup-guide')) {
        previous.focus.focus({ preventScroll: true });
      }
    };
  }
  return { synchronize, prepareGuide, openPlayerMenu(invoker) {
    if (guidePresentation || document.body.dataset.view === 'scanner' && !access().live) return;
    if (document.body.dataset.view === 'scanner' && source !== 'live') { managementSource = source; source = 'live'; }
    openPlayerMenu(invoker);
  }, openDirectListen(invoker) {
    if (guidePresentation) return;
    if (!access().live || !liveState.directListen?.allowed) return;
    if (source !== 'live') switchSource('live');
    superCollapsed = false;
    externalManagement = document.body.dataset.view === 'scanner';
    openPanel('direct', invoker);
  }, destroy() {
    [dock, volumePopover, restore].forEach(root => guideCommandEvents.forEach(type =>
      root.removeEventListener(type, protectGuideCommands, true)));
    cancelSizeGesture();
    closeVolume(false); destroyed = true; synchronizeLoops();
    mobileLayout.removeEventListener('change', updatePresentationLayout);
    window.removeEventListener('resize', viewportChanged);
    window.removeEventListener('blur', cancelSizeGesture);
    window.visualViewport?.removeEventListener('resize', viewportChanged); window.visualViewport?.removeEventListener('scroll', viewportChanged);
    document.removeEventListener('visibilitychange', visibilityChanged); dock.removeEventListener('keydown', escapeContext);
    cancelPresentationMotion(); restore.remove(); volumeControl.destroy(); playbackFeedback.remove(); unsubscribeLive(); unsubscribeRecordings();
    resizeObserver.disconnect(); routeObserver.disconnect(); dock.remove(); document.body.classList.remove('has-audio-dock'); document.documentElement.style.removeProperty('--audio-dock-height');
  } };
}
