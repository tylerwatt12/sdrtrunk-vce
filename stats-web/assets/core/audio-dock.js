import { systemLabel, systemName } from './system-labels.js?v=1';
import { formatSourceName } from './source-names.js?v=1';
import { formatHexDecimal, formatVoiceQuality } from './call-value-format.js?v=1';
import { p25IdentityFacts, p25IdentifierText, p25ServingSystemKey } from './radio-labels.js?v=7';
import { callAudioDetailGroups } from './call-audio-details.js?v=3';

/* Reuse map: global workspace composition; ui-button/ui-button-header/ui-disclosure-button/ui-icon-button, ui-segmented, ui-select,
 * ui-range, ui-pill, ui-notice, ui-feedback, ui-fact-list, ui-select-list-item, ui-toggle-field and ui-section-disclosure. Existing scanner
 * and recording-choice modal retain their lifecycle. One live engine and one
 * recording adapter; the dock owns presentation only, in light/dark and all sizes. */
export function createAudioDock({ node, iconButton, uiToggleField, recordings, getLivePlayer, access, openRecordings,
  entityRefHref, canViewRadio, href, getTitlePreference, setTitlePreference,
  getSourceNameDisplay = () => 'talker_alias',
  radioIdentifier = (_call, value) => value }) {
  const dock = node('section', 'audio-dock ui-audio-surface');
  dock.id = 'audio-dock';
  dock.setAttribute('aria-label', 'Audio player');
  const presentation = node('div', 'audio-dock-presentation');
  presentation.id = 'audio-dock-presentation';
  let size = 'minimal';
  // Desktop hiding is presentation only: retain the size, source and both engines.
  let superCollapsed = false;
  const mobileLayout = window.matchMedia('(max-width: 900px), (max-height: 500px) and (pointer: coarse)');
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  let presentationMotion = null;
  let sizeGesture = null;
  let source = 'live';
  let panel = null;
  let liveState = {};
  let recordingState = recordings.viewState();
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
  let volumeInvoker = null;
  let volumeReturnFocus = true;
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
    const button = iconButton(`icon-${icon}`, label,
      `ui-button ui-icon-button${primary ? ' ui-button-primary ui-audio-command-primary audio-dock-play' : ' ui-button-quiet'}`);
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
    if (source === value || !access()[value]) return;
    cancelSizeGesture();
    if (source === 'recordings') recordings.stop();
    else if (getLivePlayer() && !liveState.stopped) void getLivePlayer().togglePlayback();
    closeVolume(false);
    source = value;
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
    const bounded = Math.max(0, Math.min(1, Number(value)));
    if (!Number.isFinite(bounded)) return;
    if (source === 'recordings') recordings.setVolume(bounded);
    else getLivePlayer()?.setVolume(bounded, persist);
    render();
  };

  const handle = node('button', 'ui-audio-handle');
  handle.type = 'button';
  handle.id = 'audio-dock-size-handle';
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
      if (!event.isPrimary || event.button !== 0 || sizeGesture) return;
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
    if (!access()[value] || (value === 'recordings' && openRecordings?.() === false)) {
      render();
      return;
    }
    if (source === value) render();
    else switchSource(value);
  };
  const sourcePicker = node('select', 'ui-select ui-audio-source-picker');
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
    if (reducedMotion.matches || !from.width || !from.height || !to.width || !to.height) return;
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
  hide.title = 'Minimize player to header';
  hide.id = 'audio-dock-hide';
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
  const contextButton = (label, icon, name, id, classes = '') => {
    const button = iconButton(`icon-${icon}`, label, `ui-button ui-button-secondary ui-audio-context-trigger ${classes}`);
    button.id = id;
    const text = node('span', 'audio-dock-scan-label', label);
    const badge = node('span', 'ui-pill ui-pill-compact ui-audio-context-count');
    button.append(text, badge);
    button.addEventListener('click', () => openPanel(name, button));
    return { button, badge };
  };
  const desktopLists = contextButton('Lists', 'scan-lists', 'listening', 'audio-dock-source-context', 'audio-dock-desktop-scanlists audio-dock-lists-trigger');
  const mobileLists = contextButton('Lists', 'scan-lists', 'listening', 'audio-dock-mobile-lists', 'audio-dock-mobile-scanlists audio-dock-lists-trigger');
  const library = iconButton('icon-recording', 'Browse recording library', 'ui-button ui-button-secondary ui-audio-context-trigger audio-dock-library-control');
  library.append(node('span', 'audio-dock-scan-label', 'Library'));
  library.addEventListener('click', () => { closeVolume(false); openRecordings?.(); });
  const settingsButton = iconButton('icon-playback-controls', 'Listening settings', 'ui-disclosure-button ui-icon-button');
  settingsButton.id = 'audio-dock-settings';
  settingsButton.addEventListener('click', () => openPanel('settings', settingsButton));
  const shrink = iconButton('icon-chevron-down', 'Make player compact', 'ui-disclosure-button ui-icon-button');
  shrink.id = 'audio-dock-shrink';
  shrink.addEventListener('click', () => setSize(size === 'full' ? 'minimal' : 'collapsed'));
  headerEnd.append(desktopLists.button, mobileLists.button, library, settingsButton, shrink, hide);
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
  compactCopy.type = 'button'; compactCopy.setAttribute('aria-label', 'Expand player');
  compactCopy.addEventListener('click', () => setSize('full'));
  const compactWave = createSpectrum('audio-dock-mini-wave', true);
  const compactTitle = node('strong', 'audio-dock-title audio-dock-single-line');
  const compactSubtitle = node('span', 'ui-muted ui-label');
  compactCopy.append(compactWave, compactTitle, compactSubtitle);
  const miniControls = node('div', 'audio-dock-mini-controls');
  const miniPlay = command('Play audio', 'play', toggle, true);
  const miniNext = command('Next call', 'skip', next);
  const makeVolumeTrigger = (id) => {
    const button = iconButton('icon-speaker', 'Volume', 'ui-button ui-button-quiet ui-audio-volume-trigger');
    button.id = id; button.dataset.volumeTrigger = '';
    button.setAttribute('popovertarget', 'audio-dock-volume-popover');
    button.setAttribute('aria-controls', 'audio-dock-volume-popover'); button.setAttribute('aria-expanded', 'false');
    const value = node('span'); value.dataset.volumeValue = ''; button.append(value);
    button.addEventListener('click', () => { volumeInvoker = button; volumeReturnFocus = true; });
    return button;
  };
  const miniVolume = makeVolumeTrigger('audio-dock-compact-volume');
  miniControls.append(miniPlay, miniNext, miniVolume);
  now.append(compactCopy, miniControls);
  const compactTiming = node('div', 'audio-dock-compact-bottom');
  const compactElapsed = node('span', 'audio-dock-time');
  const compactProgress = node('progress', 'ui-audio-progress');
  compactProgress.max = 1; compactProgress.value = 0; compactProgress.setAttribute('aria-label', 'Call progress');
  const compactDuration = node('span', 'audio-dock-time');
  compactTiming.append(compactElapsed, compactProgress, compactDuration, count);
  compact.append(now, compactTiming);

  const slim = node('div', 'audio-dock-slim');
  const slimCopy = node('button', 'audio-dock-slim-copy');
  slimCopy.type = 'button'; slimCopy.setAttribute('aria-label', 'Expand player');
  slimCopy.addEventListener('click', () => setSize('minimal'));
  const slimWave = createSpectrum('audio-dock-mini-wave', true);
  slimCopy.append(slimWave, collapsedTitle, collapsedMeta);
  const slimPlay = command('Play audio', 'play', toggle, true);
  const slimHide = iconButton('icon-close', 'Minimize player to header', 'ui-disclosure-button ui-icon-button');
  slimHide.id = 'audio-dock-slim-hide'; slimHide.title = 'Minimize player to header';
  slimHide.setAttribute('aria-controls', presentation.id);
  slimHide.addEventListener('click', () => setSuperCollapsed(true));
  const slimExpand = iconButton('icon-maximize-corners', 'Expand player', 'ui-disclosure-button ui-icon-button ui-audio-size-action');
  slimExpand.id = 'audio-dock-slim-expand'; wireSizeControl(slimExpand, () => setSize('minimal'));
  slim.append(slimCopy, slimPlay, slimHide, slimExpand);

  const body = node('div', 'audio-dock-body audio-dock-expanded'); body.id = 'audio-dock-content';
  const main = node('div', 'audio-dock-player-main ui-listening-stage-main');
  const mainScroll = node('div', 'audio-dock-main-scroll');
  const hero = node('div', 'audio-dock-hero');
  const listeningStatus = node('div', 'audio-dock-listening-status');
  const status = node('span', 'ui-pill'); status.setAttribute('role', 'status');
  const callContext = node('div', 'audio-dock-call-context');
  const age = node('span', 'ui-muted ui-label'); age.id = 'audio-dock-call-age';
  const transcriptButton = iconButton('icon-messages', 'Transcript', 'ui-disclosure-button ui-icon-button ui-audio-transcript-action');
  transcriptButton.id = 'audio-dock-call-transcript'; transcriptButton.title = 'Transcript';
  transcriptButton.addEventListener('click', () => openPanel('transcript', transcriptButton));
  const detailsButton = iconButton('icon-info-circle', 'Call details', 'ui-disclosure-button ui-icon-button ui-audio-info-action');
  detailsButton.id = 'audio-dock-call-details'; detailsButton.title = 'Call details';
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
  back.id = 'audio-dock-context-back'; back.addEventListener('click', closePanel);
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
  const timing = node('div', 'audio-dock-timing');
  const elapsed = node('span', 'audio-dock-time');
  const seek = node('input', 'ui-range ui-audio-range');
  seek.type = 'range'; seek.min = '0'; seek.max = '1'; seek.step = '0.1'; seek.value = '0';
  seek.setAttribute('aria-label', 'Recording position');
  seek.addEventListener('input', () => recordings.seek(Number(seek.value)));
  const progress = node('progress', 'ui-audio-progress');
  progress.max = 1; progress.value = 0; progress.setAttribute('aria-label', 'Live call progress');
  const duration = node('span', 'audio-dock-time'); timing.append(elapsed, seek, progress, duration);
  const transport = node('div', 'audio-dock-transport audio-dock-primary-controls');
  const transportMain = node('div', 'audio-dock-transport-main');
  transportMain.setAttribute('role', 'group'); transportMain.setAttribute('aria-label', 'Playback controls');
  const previous = command('Previous recording', 'previous', () => void recordings.previous());
  const rewind = command('Back 10s', 'rewind', () => recordings.skipSeconds(-10));
  const replay = command('Replay last call', 'replay', () => void getLivePlayer()?.replayLastCall());
  const play = command('Play audio', 'play', toggle, true);
  const forward = command('Forward 10s', 'forward', () => recordings.skipSeconds(10));
  const skip = command('Next call', 'skip', next);
  const stop = command('Stop listening', 'stop', () => {
    if (source === 'recordings') recordings.stop();
    else if (!liveState.stopped) void getLivePlayer()?.togglePlayback();
  });
  transportMain.append(previous, rewind, replay, play, forward, skip, stop);
  const options = node('div', 'audio-dock-transport-options');
  options.setAttribute('role', 'group'); options.setAttribute('aria-label', 'Additional playback controls');
  const hold = textButton('H', () => getLivePlayer()?.toggleHold());
  hold.className = 'ui-button ui-button-quiet ui-icon-button audio-dock-hold';
  const avoids = node('div', 'audio-dock-avoid-actions'); avoids.setAttribute('role', 'group'); avoids.setAttribute('aria-label', 'Avoid controls');
  const avoid = textButton('Avoid', () => getLivePlayer()?.avoidCurrent()); avoid.className = 'ui-button ui-button-quiet audio-dock-avoid-action';
  const avoidList = iconButton('icon-chevron-down', 'Open Avoid List', 'ui-button ui-button-quiet ui-icon-button');
  avoidList.addEventListener('click', () => openPanel('avoids', avoidList)); avoids.append(avoid, avoidList);
  const expandedVolume = makeVolumeTrigger('audio-dock-expanded-volume'); options.append(hold, avoids, expandedVolume);
  transport.append(transportMain, options); controls.append(timing, transport); main.append(mainScroll, controls);

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
  sidebar.append(queueHeading, sidebarList, inspector, queueFooter, sidebarClickMode); body.append(main, sidebar);
  presentation.append(header, compact, slim, body); dock.append(presentation);
  document.querySelector('.app-shell').append(dock, playbackFeedback);
  const headerControls = document.querySelector('.app-shell .header-controls');
  headerControls.insertBefore(restore, headerControls.querySelector('#receiver-health-indicator'));

  const volumePopover = node('div', 'ui-popover ui-audio-volume-popover audio-dock-volume-popover');
  volumePopover.id = 'audio-dock-volume-popover'; volumePopover.setAttribute('popover', 'auto');
  volumePopover.setAttribute('role', 'dialog'); volumePopover.setAttribute('aria-label', 'Volume controls');
  const volumeFill = node('label', 'ui-audio-volume-fill audio-dock-volume');
  const volume = node('input', 'ui-range ui-audio-range ui-audio-volume-fill-range');
  volume.id = 'audio-dock-volume'; volume.type = 'range'; volume.min = '0'; volume.max = '1'; volume.step = '0.05';
  volume.autofocus = true; volume.setAttribute('aria-label', 'Volume');
  volume.addEventListener('input', () => changeVolume(volume.value, false));
  volume.addEventListener('change', () => { if (source === 'live') getLivePlayer()?.writePreferences({ volume: true }); });
  const volumeOutput = node('output', 'ui-audio-volume-fill-output'); volumeOutput.htmlFor = volume.id;
  volumeFill.append(node('span', 'ui-audio-volume-fill-label', 'Volume'), volume, volumeOutput); volumePopover.append(volumeFill);
  document.querySelector('.app-shell').append(volumePopover);
  const syncVolumeExpanded = (open) => [miniVolume, expandedVolume].forEach(button => button.setAttribute('aria-expanded', String(open && button === volumeInvoker)));
  function closeVolume(returnFocus = false) {
    if (!volumePopover.matches(':popover-open')) { syncVolumeExpanded(false); return; }
    volumeReturnFocus = returnFocus; volumePopover.hidePopover(); volumeReturnFocus = true;
  }
  function positionVolume() {
    if (!volumeInvoker || !volumePopover.matches(':popover-open')) return;
    const viewport = window.visualViewport;
    const bounds = { left: viewport?.offsetLeft || 0, top: viewport?.offsetTop || 0,
      width: viewport?.width || innerWidth, height: viewport?.height || innerHeight };
    const gap = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--space-2')) || 8;
    volumePopover.style.width = `${Math.min(300, bounds.width - gap * 2)}px`;
    volumePopover.style.maxHeight = `${Math.max(0, bounds.height - gap * 2)}px`;
    const anchor = volumeInvoker.getBoundingClientRect(), rect = volumePopover.getBoundingClientRect();
    volumePopover.style.left = `${Math.max(bounds.left + gap, Math.min(anchor.left, bounds.left + bounds.width - rect.width - gap))}px`;
    volumePopover.style.top = `${Math.max(bounds.top + gap, Math.min(anchor.top - rect.height - gap, bounds.top + bounds.height - rect.height - gap))}px`;
  }
  volumePopover.addEventListener('beforetoggle', event => {
    if (event.newState === 'closed') {
      syncVolumeExpanded(false);
      if (volumeReturnFocus && volumePopover.contains(document.activeElement) && volumeInvoker?.isConnected && volumeInvoker.getClientRects().length) volumeInvoker.focus({ preventScroll: true });
    }
  });
  volumePopover.addEventListener('toggle', () => {
    const open = volumePopover.matches(':popover-open'); syncVolumeExpanded(open);
    if (open) { positionVolume(); volume.focus({ preventScroll: true }); }
  });
  const message = (text, error = false) => {
    const item = node('p', `ui-feedback${error ? ' ui-feedback-error' : ''}`, text);
    item.setAttribute('role', error ? 'alert' : 'status'); return item;
  };
  function openPanel(value, invoker) {
    if (!isAllowed() || source === 'recordings' && ['listening', 'settings', 'avoids'].includes(value)) return;
    closeVolume(false); contextInvoker = invoker || contextInvoker; panel = value; panelKey = ''; setSize('full');
    back.focus({ preventScroll: true });
  }
  function closePanel() {
    closeVolume(false); panel = null; panelKey = ''; render();
    (contextInvoker?.getClientRects().length ? contextInvoker : handle).focus({ preventScroll: true });
  }
  function clickModeField(id) {
    const label = node('label', 'ui-field ui-audio-queue-setting audio-dock-click-mode');
    const frame = node('span', 'ui-select-frame'); const select = node('select', 'ui-select');
    select.id = id; select.dataset.clickMode = ''; select.setAttribute('aria-label', 'When a call is clicked');
    [['queue', 'Queue clicked calls'], ['once', 'Play once'], ['continue', 'Continue matching calls']].forEach(([value, text]) => {
      const option = node('option', '', text); option.value = value; select.append(option);
    });
    select.addEventListener('change', () => {
      recordings.setClickMode(select.value);
      dock.querySelectorAll('[data-click-mode]').forEach(control => { control.value = recordingState.clickMode; });
    });
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
        homeSystemName: member.home_system_name || systemName(member.home_system) ||
          systemName(facts.homeSystemKey) }) || id : id;
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
    const call = liveState.current || {};
    const options = { empty: !liveState.current || liveState.stopped, includeEmpty: true };
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
    const button = node('button', 'audio-dock-queue-item ui-listening-queue-item'); button.type = 'button';
    const key = queueEntryKey(call, index); button.dataset.queueKey = key;
    button.setAttribute('aria-label', `View ${currentRow ? 'playing' : 'queued'} call ${title(call)}`);
    button.setAttribute('aria-pressed', String(selectedQueueKey === key));
    const copy = node('span', 'audio-dock-queue-copy ui-heading-group');
    copy.append(node('strong', '', String(title(call))), node('span', 'ui-muted ui-label',
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
    button.append(node('span', 'audio-dock-queue-order ui-listening-order', currentRow ? '•' : String(index + 1)), copy);
    button.addEventListener('click', () => { selectedQueueKey = selectedQueueKey === key ? null : key; sidebarKey = ''; panelKey = ''; render(); });
    row.append(button);
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
    count.replaceChildren(document.createTextNode(summary)); appendIcon(count, 'chevron-right'); count.title = `${queueCount()} calls queued`;
    teaser.title = `${queueCount()} calls queued`; nextTitle.textContent = next ? title(next) : 'Nothing queued';
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
      inspector.append(node('p', '', `${title(call)} · ${sourceName(call)}`),
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
    if (!liveState.scanListCatalogReady) panelHost.append(message(liveState.scanListCatalogState === 'unavailable' ?
      'Lists are unavailable. Try listening again when the receiver is available.' : 'Lists are loading…'));
    else if (!lists.length) panelHost.append(message('No lists are available.'));
    const choices = node('div', 'ui-surface audio-dock-selection-list');
    lists.forEach(item => {
      const label = node('label', 'ui-select-list-item ui-audio-selection-row audio-dock-selection-row');
      const checkbox = node('input', 'ui-selection-check'); checkbox.type = 'checkbox'; checkbox.checked = item.selected;
      checkbox.id = `audio-dock-scan-list-${item.id}`;
      checkbox.disabled = !isAllowed() || !item.enabled || selected >= maximum && !item.selected;
      checkbox.addEventListener('change', () => getLivePlayer()?.setScanListSelected(item.id, checkbox.checked));
      const copy = node('span', 'ui-heading-group audio-dock-selection-copy'); copy.append(node('strong', '', item.name));
      if (item.description) copy.append(node('span', 'ui-muted ui-label', item.description));
      label.append(checkbox, copy); choices.append(label);
    });
    if (lists.length) panelHost.append(choices);
    if (liveState.scanListCatalogReady && lists.length && !selected) panelHost.append(message('Select a List to start adding live calls.'));
    const footer = node('div', 'audio-dock-context-footer');
    footer.append(node('span', 'ui-muted ui-label', `Up to ${maximum} lists`));
    const button = textButton('Avoid List', () => openPanel('avoids', button));
    button.className = 'ui-button ui-button-quiet ui-audio-context-trigger'; button.append(node('span', 'ui-pill ui-pill-compact', String((liveState.avoids || []).length)));
    footer.append(button); panelHost.append(footer);
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
        copy.append(node('strong', '', item.label || item.key), node('span', 'ui-muted ui-label', systemLabel(item) || item.system_scope || item.systemName || 'All systems'));
        row.append(copy, command(`Remove ${item.label || item.key} from Avoid List`, 'close', () => getLivePlayer()?.removeAvoid(item.key)));
        section.append(row); list.append(section);
      }); panelHost.append(list);
    }
    const footer = node('div', 'audio-dock-context-footer'); footer.append(node('span', 'ui-muted ui-label', 'Live listening'));
    const button = textButton('Lists', () => openPanel('listening', button)); button.className = 'ui-button ui-button-quiet ui-audio-context-trigger';
    button.append(node('span', 'ui-pill ui-pill-compact', String((liveState.scanLists || []).filter(item => item.selected).length)));
    footer.append(button); panelHost.append(footer);
  }
  function renderSettings() {
    const settings = node('div', 'audio-dock-settings');
    panelHost.append(settings);
    const groupField = uiToggleField('Group calls by target', liveState.targetGrouping !== false,
      'Group calls by target', 'Keeps calls for the same group together to preserve conversationality');
    const grouping = groupField.querySelector('input'); grouping.id = 'audio-dock-target-grouping';
    const groupHelp = groupField.querySelector('.ui-toggle-copy small');
    if (groupHelp) { groupHelp.id = 'audio-dock-grouping-help'; grouping.setAttribute('aria-describedby', groupHelp.id); }
    grouping.addEventListener('change', () => {
      const player = getLivePlayer(); if (!player) return;
      player.applyPreferences({ target_grouping: grouping.checked }); player.writePreferences();
    });
    settings.append(groupField);
    const burstLabel = node('label', 'ui-toggle-field'); burstLabel.htmlFor = 'audio-dock-target-burst';
    const burstCopy = node('span', 'ui-toggle-copy');
    const burstHelp = node('small', '', 'When grouping is on, switch to another waiting target after this many consecutive calls.');
    burstHelp.id = 'audio-dock-burst-help';
    burstCopy.append(node('strong', '', 'Calls per target'), burstHelp);
    const burst = node('input', 'ui-input'); burst.id = 'audio-dock-target-burst'; burst.type = 'number'; burst.min = '1'; burst.max = '20'; burst.value = liveState.targetBurstLimit || 4;
    burst.setAttribute('aria-label', 'Calls per target');
    burst.setAttribute('aria-describedby', burstHelp.id);
    burst.addEventListener('change', () => {
      if (!burst.checkValidity()) { burst.reportValidity(); return; }
      const player = getLivePlayer(); if (!player) return;
      player.applyPreferences({ target_burst_limit: Number(burst.value) }); player.writePreferences();
    });
    burstLabel.append(burstCopy, burst); settings.append(burstLabel);
    const titleField = uiToggleField('Playing call in page title', getTitlePreference(),
      'Playing call in page title', 'Show the playing Live call in browser tab titles outside Scanner.');
    const prepend = titleField.querySelector('input'); prepend.id = 'audio-dock-title-preference';
    const titleHelp = titleField.querySelector('.ui-toggle-copy small');
    if (titleHelp) { titleHelp.id = 'audio-dock-title-help'; prepend.setAttribute('aria-describedby', titleHelp.id); }
    prepend.addEventListener('change', () => {
      const refresh = () => { panelKey = ''; render(); };
      void Promise.resolve(setTitlePreference(prepend.checked)).then(refresh, refresh);
    });
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
    const snapshot = source === 'live' ? getLivePlayer()?.viewState() || liveState : recordings.viewState();
    const total = Math.max(0, Number(snapshot.duration) || 0), position = Math.max(0, Number(snapshot.currentTime) || 0);
    elapsed.textContent = compactElapsed.textContent = time(position); duration.textContent = compactDuration.textContent = time(total);
    progress.value = compactProgress.value = total ? Math.min(1, position / total) : 0;
    seek.max = String(total || 1); if (document.activeElement !== seek) seek.value = String(Math.min(position, total));
    seek.disabled = !isAllowed() || !total; seek.setAttribute('aria-valuetext', `${time(position)} of ${time(total)}`);
  }
  const spectrumLevels = new Float32Array(64);
  const spectrumNodes = [fullWave, compactWave, slimWave];
  function drawSpectrum() {
    spectrumFrame = null;
    if (destroyed || dock.hidden || document.hidden || source !== 'live' || !isAllowed()) return;
    const playing = Boolean(getLivePlayer()?.readAudioSpectrum?.(spectrumLevels, 8_000));
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
    const selected = (liveState.scanLists || []).filter(item => item.selected).length;
    const names = { details: 'Call details', transcript: 'Transcript', queue: 'Queue', listening: 'Lists', settings: 'Listening settings', avoids: 'Avoid List' };
    contextTitle.textContent = names[panel];
    contextSubtitle.textContent = panel === 'listening' ? `${selected} of ${(liveState.scanLists || []).length} selected` :
      panel === 'queue' ? `${queueCount()} queued` : panel === 'avoids' ? `${(liveState.avoids || []).length} avoided` : panel === 'settings' ? 'Live listening preferences' :
      source === 'live' && panel === 'details' && !active.current ? 'Waiting for a call' : title(call);
    const panelState = panel === 'listening' ? [active.scanLists, active.maximumSelectedScanLists, active.scanListCatalogReady, active.scanListCatalogState] :
      panel === 'avoids' ? [liveState.avoids, liveState.scanLists] : panel === 'settings' ? [liveState.targetGrouping, liveState.targetBurstLimit, getTitlePreference()] :
      panel === 'queue' ? [active.current, active.queue, active.scanLists, active.continuation, active.stopped, active.paused, selectedQueueKey] :
      panel === 'transcript' ? [call?.id, call?.transcription, active.detailsLoading, active.detailsError] :
      [active.current, call, active.stopped, active.scanLists, active.detailsLoading, active.detailsError];
    const nextKey = JSON.stringify([source, panel, getSourceNameDisplay(), ...panelState]);
    if (panelKey === nextKey) return; panelKey = nextKey;
    panelHost.querySelectorAll('details').forEach(item => disclosureStates.set(`${renderedPanel}:${item.querySelector('summary')?.textContent}`, item.open));
    preserveContent(panelHost, () => {
      renderedPanel = `${source}:${panel}`;
      if (panel === 'details') {
        if (source === 'recordings') {
          const mount = node('div'); panelHost.append(mount);
          recordings.renderDetails(mount, active.current, { shell: true, empty: !active.current || active.stopped });
          if (active.current && !active.stopped) callAudioDetailGroups(active.current).forEach(([label, rows]) => group(label, rows));
          mount.querySelectorAll('details > dl').forEach(list => {
            list.classList.remove('ui-form-section'); const wrapper = node('div', 'ui-section-body'); list.replaceWith(wrapper); wrapper.append(list);
          });
        } else renderLiveDetails();
      } else if (panel === 'transcript') { const mount = node('div'); panelHost.append(mount); recordings.renderTranscript(mount); }
      else if (panel === 'queue') renderQueue(); else if (panel === 'listening') renderListening();
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
    if (!permissions[source] && (permissions.live || permissions.recordings)) { closeVolume(false); source = permissions.live ? 'live' : 'recordings'; panelKey = ''; }
    const unavailable = document.body.dataset.view === 'scanner' || document.body.dataset.view === 'access-landing' || !permissions.live && !permissions.recordings;
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
    handle.title = sizeHint.textContent;
    const call = current(), active = state(), name = String(title(call));
    compactTitle.textContent = collapsedTitle.textContent = nowTitle.textContent = name;
    compactTitle.title = collapsedTitle.title = nowTitle.title = name;
    collapsedMeta.textContent = `${source === 'live' ? 'Live' : 'Recording'} · ${queueCount()} queued`;
    compactSubtitle.textContent = subtitle(call) || (source === 'live' ? 'Select a List to listen' : 'Choose a recording to play');
    compactSubtitle.title = [sourceSummary(call), call?.channel || call?.channel_name].filter(Boolean).join(' · ') || compactSubtitle.textContent;
    Object.entries(sourceOptions).forEach(([value, option]) => { option.disabled = !permissions[value]; });
    const sourceLabel = source === 'live' ? 'Live' : 'Recordings'; currentSourceChoice.textContent = sourceLabel;
    if (sourcePicker.value !== '') sourcePicker.value = ''; sourcePicker.disabled = !permissions.live && !permissions.recordings;
    const live = source === 'live';
    desktopLists.button.hidden = mobileLists.button.hidden = settingsButton.hidden = !live; library.hidden = live;
    const selected = (liveState.scanLists || []).filter(item => item.selected && item.enabled).length;
    [desktopLists, mobileLists].forEach(({button, badge}) => { badge.textContent = String(selected); button.setAttribute('aria-label', `Lists, ${selected} selected`); button.disabled = !permissions.live; });
    shrink.querySelector('use').setAttribute('href', mobileLayout.matches ? '#icon-close' : '#icon-chevron-down');
    setLabel(shrink, presentationSize === 'full' ? 'Make player compact' : 'Collapse player');
    transcriptButton.hidden = live; transcriptButton.disabled = !permissions.recordings;
    detailsButton.disabled = !isAllowed();
    kind.textContent = [call?.decoder || call?.protocol, live ? 'Voice' : 'Recorded call'].filter(Boolean).join(' · ');
    nowDescription.textContent = description(call); nowDescription.hidden = !nowDescription.textContent;
    nowSystem.textContent = call ? [systemLabel(call), call.channel_name || call.channel].filter(Boolean).join(' · ') : (live ? 'Select a List to start listening' : 'Choose a recording to play');
    transmittingName.textContent = sourceName(call) || '—'; targetGroup.textContent = call?.target_group || call?.group_name || ''; targetGroup.hidden = !targetGroup.textContent;
    transmittingName.title = sourceSummary(call) || transmittingName.textContent;
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
    const statusLabel = active.paused ? 'Paused' : live && active.holdTarget ? 'Holding' : shortStatus ||
      (active.stopped ? 'Stopped' : active.loading ? 'Loading' : playing ? 'Listening' : 'Ready');
    if (status.textContent !== statusLabel) status.textContent = statusLabel;
    status.classList.toggle('ui-pill-success', playing && ['Listening', 'Holding', 'Replaying last call'].includes(statusLabel));
    status.title = engineStatus || statusLabel;
    const ordinary = !engineStatus || Boolean(shortStatus) || engineStatus === statusLabel ||
      ['listening', 'paused', 'stopped', 'playing recording', 'ready to play', 'ready', 'playback finished',
        'choose a recording to play'].includes(ordinaryStatus) || /^paused\s*[—·-]/i.test(engineStatus);
    const playbackError = /could not|unavailable/i.test(engineStatus);
    playbackFeedback.setAttribute('role', playbackError ? 'alert' : 'status');
    playbackFeedback.classList.toggle('ui-notice-danger', playbackError);
    if (playbackFeedback.textContent !== engineStatus) playbackFeedback.textContent = engineStatus;
    playbackFeedback.hidden = ordinary || dock.hidden;
    updateAge();
    const playLabel = playing ? live ? 'Pause live audio' : 'Pause recording' : live ? active.paused ? 'Resume live audio' : 'Listen live' : 'Play recording';
    [play, miniPlay, slimPlay].forEach(button => { setLabel(button, playLabel, playing ? 'pause' : 'play'); button.disabled = !isAllowed() || !live && !active.current && !queueCount(); });
    [miniNext, skip].forEach(button => { button.disabled = !isAllowed() || (live ? !active.current && !queueCount() : !active.canNext); });
    previous.disabled = !isAllowed() || !active.canPrevious; rewind.disabled = forward.disabled = !isAllowed() || !active.current || !active.duration;
    replay.disabled = !isAllowed() || !liveState.lastCallReady || liveState.paused;
    [previous, rewind, forward].forEach(button => { button.hidden = live; }); replay.hidden = !live;
    stop.disabled = !isAllowed() || (live ? Boolean(active.stopped) : !active.current && !queueCount()); setLabel(stop, live ? 'Stop listening' : 'Stop recording');
    hold.hidden = avoids.hidden = !live;
    hold.disabled = !isAllowed() || !liveState.currentReady && !liveState.holdTarget;
    setLabel(hold, liveState.holdTarget ? 'Release hold' : 'Hold this talkgroup'); hold.setAttribute('aria-pressed', String(Boolean(liveState.holdTarget)));
    avoid.disabled = !isAllowed() || !liveState.currentReady || liveState.replayingLast;
    seek.hidden = live; progress.hidden = !live;
    const level = Math.max(0, Math.min(1, Number(active.volume ?? 1))), percent = Math.round(level * 100);
    [miniVolume, expandedVolume].forEach(button => { setLabel(button, `Volume, ${percent} percent`, level ? 'speaker' : 'speaker-muted'); button.querySelector('[data-volume-value]').textContent = `${percent}%`; button.disabled = !isAllowed(); });
    if (document.activeElement !== volume) volume.value = String(level); volume.disabled = !isAllowed();
    volume.setAttribute('aria-valuetext', level ? `${percent} percent` : 'Muted, 0 percent'); volumeOutput.textContent = `${percent}%`; volumeFill.style.setProperty('--audio-volume-percent', `${percent}%`);
    if (source === 'live' && panel === 'transcript' || source === 'recordings' && ['listening', 'settings', 'avoids'].includes(panel)) { panel = null; panelKey = ''; }
    renderSidebar(); renderPanel(); dock.querySelectorAll('[data-click-mode]').forEach(select => { if (select.value !== recordingState.clickMode) select.value = recordingState.clickMode; });
    updateProgress(); measure(); synchronizeLoops();
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
  const escapeContext = event => { if (event.key === 'Escape' && !volumePopover.matches(':popover-open') && panel) { event.preventDefault(); closePanel(); } };
  mobileLayout.addEventListener('change', updatePresentationLayout);
  reducedMotion.addEventListener('change', cancelPresentationMotion);
  window.addEventListener('resize', viewportChanged); window.visualViewport?.addEventListener('resize', viewportChanged); window.visualViewport?.addEventListener('scroll', viewportChanged);
  window.addEventListener('blur', cancelSizeGesture);
  document.addEventListener('visibilitychange', visibilityChanged); dock.addEventListener('keydown', escapeContext);
  const routeObserver = new MutationObserver(() => { cancelSizeGesture(); closeVolume(false); cancelPresentationMotion(); panelKey = ''; render(); });
  routeObserver.observe(document.body, { attributes: true, attributeFilter: ['data-view'] });
  const unsubscribeRecordings = recordings.subscribeState(value => {
    recordingState = value;
    const queueOnly = !value.current && value.queue?.length && liveState.stopped !== false;
    const active = Boolean(value.playing || value.loading || queueOnly);
    if (active && !previousRecordingActive && access().recordings) { closeVolume(false); source = 'recordings'; panelKey = ''; }
    previousRecordingActive = active; render();
  });
  function synchronize() {
    if (!access().recordings) sharedQueueKey = '';
    const player = getLivePlayer();
    if (player !== boundLive) {
      unsubscribeLive(); boundLive = player;
      unsubscribeLive = player?.subscribeState(value => {
        liveState = value;
        if (!value.stopped && !previousLiveActive && access().live) { closeVolume(false); source = 'live'; panelKey = ''; }
        previousLiveActive = !value.stopped; render();
      }) || (() => {});
    }
    render();
    const params = new URLSearchParams(window.location.search), queue = params.get('recording_queue');
    if (access().recordings && queue && queue !== sharedQueueKey) { sharedQueueKey = queue; closeVolume(false); source = 'recordings'; size = 'full'; panel = 'queue'; void recordings.loadSharedQueue(params).then(render); }
  }
  synchronize();
  return { synchronize, destroy() {
    cancelSizeGesture();
    closeVolume(false); destroyed = true; synchronizeLoops();
    mobileLayout.removeEventListener('change', updatePresentationLayout);
    reducedMotion.removeEventListener('change', cancelPresentationMotion); window.removeEventListener('resize', viewportChanged);
    window.removeEventListener('blur', cancelSizeGesture);
    window.visualViewport?.removeEventListener('resize', viewportChanged); window.visualViewport?.removeEventListener('scroll', viewportChanged);
    document.removeEventListener('visibilitychange', visibilityChanged); dock.removeEventListener('keydown', escapeContext);
    cancelPresentationMotion(); restore.remove(); volumePopover.remove(); playbackFeedback.remove(); unsubscribeLive(); unsubscribeRecordings();
    resizeObserver.disconnect(); routeObserver.disconnect(); dock.remove(); document.body.classList.remove('has-audio-dock'); document.documentElement.style.removeProperty('--audio-dock-height');
  } };
}
