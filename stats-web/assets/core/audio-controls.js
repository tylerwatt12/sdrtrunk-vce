export function createAudioCommand(iconButton, label, icon, onClick, primary = false) {
  const button = iconButton(icon.startsWith('icon-') ? icon : `icon-${icon}`, label,
    `ui-button ${primary ? 'ui-button-primary' : 'ui-button-secondary'} ui-icon-button ui-audio-control`);
  button.type = 'button';
  if (onClick) button.addEventListener('click', onClick);
  return button;
}

export function audioPlaybackAction(state = {}, { recording = false } = {}) {
  const playing = recording ? Boolean(state.playing) : !state.stopped && !state.paused;
  const label = recording ? playing ? 'Pause recording' : 'Play recording' :
    playing ? 'Pause live audio' : state.paused ? 'Resume live audio' : 'Listen live';
  return { playing, label, icon: playing ? 'pause' : 'play' };
}

export function createAudioTransport({ node, iconButton, actions = {}, menu, volume }) {
  const element = node('div', 'ui-audio-transport');
  const main = node('div', 'ui-audio-transport-main');
  main.setAttribute('role', 'group'); main.setAttribute('aria-label', 'Playback controls');
  const options = node('div', 'ui-audio-transport-options');
  options.setAttribute('role', 'group'); options.setAttribute('aria-label', 'Additional playback controls');
  const command = (label, icon, action, primary = false) =>
    createAudioCommand(iconButton, label, icon, action, primary);
  const previous = actions.previous ? command('Previous recording', 'previous', actions.previous) : null;
  const rewind = actions.previous ? command('Back 10s', 'rewind', actions.rewind) : null;
  const replay = command('Replay last call', 'replay', actions.replay);
  const play = command('Play audio', 'play', actions.play, true);
  const forward = actions.forward ? command('Forward 10s', 'forward', actions.forward) : null;
  const skip = command('Next call', 'skip', actions.skip);
  const stop = command('Stop listening', 'stop', actions.stop);
  main.append(...[previous, rewind, replay, play, forward, skip, stop].filter(Boolean));
  const targetAction = (text, label, action) => {
    const button = node('button', 'ui-button ui-button-secondary ui-audio-control', text);
    button.type = 'button'; button.setAttribute('aria-label', label); button.dataset.uiHint = label;
    if (action) button.addEventListener('click', action);
    return button;
  };
  const hold = targetAction('Hd', 'Hold current target', actions.hold);
  const avoid = targetAction('Av', 'Avoid current target', actions.avoid);
  Object.entries({ previous, rewind, replay, play, forward, skip, stop, hold, avoid })
    .forEach(([action, button]) => { if (button) button.dataset.audioAction = action; });
  options.append(...[hold, avoid, menu, volume].filter(Boolean));
  element.append(main, options);
  return { element, main, options, play, skip, replay, stop, hold, avoid, previous, rewind, forward };
}

const playbackTime = value => {
  const seconds = Math.floor(Math.max(0, value));
  return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
};

export function createAudioTiming({ node, seek = null, className = '' }) {
  const element = node('div', `ui-audio-timing${className ? ` ${className}` : ''}`);
  const elapsed = node('span', 'ui-audio-time');
  const progress = node('progress', 'ui-audio-progress');
  progress.max = 1; progress.value = 0; progress.setAttribute('aria-label', 'Call progress');
  const duration = node('span', 'ui-audio-time');
  element.append(...[elapsed, progress, seek, duration].filter(Boolean));
  const update = (snapshot = {}, { recording = false, allowed = true } = {}) => {
    const total = Number(snapshot.duration), position = Number(snapshot.currentTime);
    const hasDuration = Number.isFinite(total) && total > 0;
    const hasPosition = Number.isFinite(position);
    const bounded = hasPosition ? Math.max(0, hasDuration ? Math.min(position, total) : position) : 0;
    elapsed.textContent = hasPosition && (snapshot.current || hasDuration) ? playbackTime(bounded) : '—';
    duration.textContent = hasDuration ? playbackTime(total) : snapshot.current ? 'Duration unavailable' : '—';
    element.dataset.durationKnown = String(hasDuration);
    progress.hidden = !hasDuration || !hasPosition || Boolean(recording && seek);
    progress.value = hasDuration && hasPosition ? bounded / total : 0;
    if (hasDuration && hasPosition) progress.setAttribute('aria-valuetext', `${playbackTime(bounded)} of ${playbackTime(total)}`);
    else progress.removeAttribute('aria-valuetext');
    if (seek) {
      seek.hidden = !recording || !hasDuration || !hasPosition;
      seek.max = String(hasDuration ? total : 1);
      if (document.activeElement !== seek) seek.value = String(bounded);
      seek.disabled = !allowed || !hasDuration || !hasPosition;
      seek.setAttribute('aria-valuetext', hasDuration && hasPosition ?
        `${playbackTime(bounded)} of ${playbackTime(total)}` : 'Duration unavailable');
    }
  };
  update();
  return { element, elapsed, progress, duration, update };
}

export function createAudioVolume({ node, iconButton, host, id, onInput, onCommit, signal }) {
  const controller = new AbortController();
  const listen = (target, type, handler) => target?.addEventListener(type, handler, { signal: controller.signal });
  const triggers = [];
  let invoker = null, returnFocus = true, destroyed = false, level = 1, allowed = true;
  const popover = node('div', `ui-popover ui-audio-volume-popover ${id}-volume-popover`);
  popover.id = `${id}-volume-popover`; popover.setAttribute('popover', 'auto');
  popover.setAttribute('role', 'dialog'); popover.setAttribute('aria-label', 'Volume controls');
  const fill = node('label', `ui-audio-volume-fill ${id}-volume`);
  const input = node('input', 'ui-range ui-audio-range ui-audio-volume-fill-range');
  input.id = `${id}-volume`; input.type = 'range'; input.min = '0'; input.max = '1'; input.step = '0.05';
  input.autofocus = true; input.setAttribute('aria-label', 'Volume');
  const output = node('output', 'ui-audio-volume-fill-output'); output.htmlFor = input.id;
  fill.append(node('span', 'ui-audio-volume-fill-label', 'Volume'), input, output); popover.append(fill);
  host.append(popover);
  const isOpen = () => !destroyed && popover.matches(':popover-open');
  const syncExpanded = open => triggers.forEach(button =>
    button.setAttribute('aria-expanded', String(open && button === invoker)));
  const close = (restoreFocus = false) => {
    if (!isOpen()) { syncExpanded(false); return; }
    returnFocus = restoreFocus; popover.hidePopover(); returnFocus = true;
  };
  const position = () => {
    if (!invoker || !isOpen()) return;
    const viewport = window.visualViewport;
    const bounds = { left: viewport?.offsetLeft || 0, top: viewport?.offsetTop || 0,
      width: viewport?.width || window.innerWidth, height: viewport?.height || window.innerHeight };
    const gap = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--space-2')) || 8;
    popover.style.width = `${Math.max(0, Math.min(300, bounds.width - gap * 2))}px`;
    popover.style.maxHeight = `${Math.max(0, bounds.height - gap * 2)}px`;
    const anchor = invoker.getBoundingClientRect(), rect = popover.getBoundingClientRect();
    popover.style.left = `${Math.max(bounds.left + gap, Math.min(anchor.left, bounds.left + bounds.width - rect.width - gap))}px`;
    popover.style.top = `${Math.max(bounds.top + gap, Math.min(anchor.top - rect.height - gap, bounds.top + bounds.height - rect.height - gap))}px`;
  };
  const paintTrigger = button => {
    const percent = Math.round(level * 100), label = `Volume, ${percent} percent`;
    button.setAttribute('aria-label', label); button.dataset.uiHint = label;
    button.querySelector('use')?.setAttribute('href', `#icon-${level ? 'speaker' : 'speaker-muted'}`);
    button.querySelector('[data-volume-value]').textContent = `${percent}%`;
    button.disabled = !allowed;
  };
  const update = (nextLevel, nextAllowed = true) => {
    level = Number.isFinite(Number(nextLevel)) ? Math.max(0, Math.min(1, Number(nextLevel))) : 1;
    allowed = Boolean(nextAllowed);
    triggers.forEach(paintTrigger);
    const percent = Math.round(level * 100);
    if (document.activeElement !== input) input.value = String(level);
    input.disabled = !allowed; input.setAttribute('aria-valuetext', level ? `${percent} percent` : 'Muted, 0 percent');
    output.textContent = `${percent}%`; fill.style.setProperty('--audio-volume-percent', `${percent}%`);
    if (!allowed) close(false);
  };
  const createTrigger = triggerId => {
    const button = createAudioCommand(iconButton, 'Volume', 'speaker');
    button.classList.remove('ui-icon-button'); button.classList.add('ui-audio-volume-trigger');
    button.id = triggerId; button.dataset.volumeTrigger = ''; button.dataset.audioAction = 'volume';
    button.setAttribute('popovertarget', popover.id); button.setAttribute('aria-controls', popover.id);
    button.setAttribute('aria-expanded', 'false');
    const value = node('span'); value.dataset.volumeValue = ''; button.append(value);
    listen(button, 'click', () => { invoker = button; returnFocus = true; });
    triggers.push(button); paintTrigger(button);
    return button;
  };
  listen(input, 'input', () => { if (allowed) onInput?.(Number(input.value)); });
  listen(input, 'change', () => { if (allowed) onCommit?.(Number(input.value)); });
  listen(popover, 'beforetoggle', event => {
    if (event.newState !== 'closed') return;
    syncExpanded(false);
    if (returnFocus && popover.contains(document.activeElement) && invoker?.isConnected && invoker.getClientRects().length) {
      invoker.focus({ preventScroll: true });
    }
  });
  listen(popover, 'toggle', () => {
    const open = isOpen(); syncExpanded(open);
    if (open) { position(); input.focus({ preventScroll: true }); }
  });
  listen(popover, 'keydown', event => {
    if (event.key !== 'Escape' || !isOpen()) return;
    event.preventDefault(); event.stopPropagation(); close(true);
  });
  const hide = () => close(false);
  listen(window, 'resize', hide); listen(window, 'blur', hide);
  listen(window.visualViewport, 'resize', hide); listen(window.visualViewport, 'scroll', hide);
  listen(document, 'visibilitychange', () => { if (document.hidden) hide(); });
  const destroy = () => {
    if (destroyed) return;
    close(false); destroyed = true; controller.abort();
    signal?.removeEventListener('abort', destroy); popover.remove();
  };
  signal?.addEventListener('abort', destroy, { once: true });
  update(level, allowed);
  if (signal?.aborted) destroy();
  return { createTrigger, update, close, position, isOpen, contains: element =>
    popover.contains(element) || triggers.some(button => button.contains(element)), destroy, popover, input,
  get invoker() { return invoker; } };
}
