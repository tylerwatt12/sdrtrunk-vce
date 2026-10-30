'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { setMaxListeners } = require('node:events');

setMaxListeners(0);
const document = { activeElement: null, hidden: false };
function eventTarget(target = {}) {
  const listeners = new Map();
  target.addEventListener = (type, callback, options = {}) => {
    if (options.signal?.aborted) return;
    if (!listeners.has(type)) listeners.set(type, new Set());
    listeners.get(type).add(callback);
    options.signal?.addEventListener('abort', () => target.removeEventListener(type, callback), { once: true });
  };
  target.removeEventListener = (type, callback) => listeners.get(type)?.delete(callback);
  target.dispatch = (type, event = {}) => [...(listeners.get(type) || [])].forEach(callback => callback(event));
  return target;
}
eventTarget(document);
function node(tag, className = '', text = '') {
  const attributes = new Map();
  const element = eventTarget({ tagName: tag.toUpperCase(), className, textContent: text,
    children: [], dataset: {}, hidden: false, disabled: false, isConnected: true,
    style: { setProperty(name, value) { this[name] = value; } },
    append(...children) { children.forEach(child => { child.parentElement = this; this.children.push(child); }); },
    setAttribute(name, value) { attributes.set(name, String(value)); },
    getAttribute(name) { return attributes.get(name) ?? null; },
    removeAttribute(name) { attributes.delete(name); },
    contains(other) { return this === other || this.children.some(child => child.contains(other)); },
    querySelector(selector) {
      return this.children.find(child => selector === 'use' ? child.tagName === 'USE' :
        selector === '[data-volume-value]' && child.dataset.volumeValue !== undefined) ||
        this.children.map(child => child.querySelector(selector)).find(Boolean) || null;
    },
    matches(selector) { return selector === ':popover-open' && Boolean(this.open); },
    showPopover() { this.open = true; this.dispatch('toggle'); },
    hidePopover() { this.dispatch('beforetoggle', { newState: 'closed' }); this.open = false; this.dispatch('toggle'); },
    getBoundingClientRect() { return { left: 48, top: 200, width: 240, height: 52 }; },
    getClientRects() { return this.isConnected ? [{}] : []; },
    focus() { document.activeElement = this; },
    remove() {
      this.isConnected = false;
      if (this.parentElement) this.parentElement.children = this.parentElement.children.filter(child => child !== this);
    }
  });
  element.classList = { add(...names) { element.className += ` ${names.join(' ')}`; },
    remove(...names) { element.className = element.className.split(/\s+/).filter(name => !names.includes(name)).join(' '); },
    contains(name) { return element.className.split(/\s+/).includes(name); } };
  return element;
}
document.documentElement = node('html');
const window = eventTarget({ innerWidth: 360, innerHeight: 640,
  visualViewport: eventTarget({ width: 360, height: 640, offsetLeft: 0, offsetTop: 0 }) });
const iconButton = (icon, label, classes) => {
  const button = node('button', classes); button.setAttribute('aria-label', label);
  button.dataset.uiHint = label;
  const svg = node('svg'), use = node('use'); use.setAttribute('href', `#${icon}`); svg.append(use); button.append(svg);
  return button;
};
const filename = path.resolve(process.argv[2] || path.join(__dirname, '../../../../stats-web/assets/core/audio-controls.js'));
const source = fs.readFileSync(filename, 'utf8').replaceAll('export function ', 'function ');
const { createAudioCommand, audioPlaybackAction, createAudioTransport, createAudioTiming, createAudioVolume } =
  vm.runInNewContext(`${source}; ({ createAudioCommand, audioPlaybackAction, createAudioTransport, createAudioTiming, createAudioVolume });`,
    { document, window, AbortController, getComputedStyle: () => ({ getPropertyValue: () => '8' }) });

const events = [];
const actions = Object.fromEntries(['replay', 'play', 'skip', 'stop', 'hold', 'avoid'].map(action =>
  [action, () => events.push(action)]));
const menu = node('button'), volume = node('button');
const transport = createAudioTransport({ node, iconButton, actions, menu, volume });
assert.deepEqual(transport.main.children.map(button => button.dataset.audioAction), ['replay', 'play', 'skip', 'stop']);
assert.deepEqual(transport.options.children, [transport.hold, transport.avoid, menu, volume]);
assert.equal(transport.hold.textContent, 'Hd'); assert.equal(transport.avoid.textContent, 'Av');
assert.equal(transport.hold.getAttribute('aria-label'), 'Hold current target');
assert.equal(transport.play.classList.contains('ui-button-primary'), true);
assert.equal(transport.play.classList.contains('ui-audio-command-primary'), false);
assert.equal(transport.stop.classList.contains('ui-button-secondary'), true);
for (const action of Object.keys(actions)) transport[action].dispatch('click');
assert.deepEqual(events, Object.keys(actions), 'Shared buttons retain the caller’s action callbacks.');
const recorded = createAudioTransport({ node, iconButton, actions: { ...actions,
  previous() {}, rewind() {}, forward() {} } });
assert.deepEqual(recorded.main.children.map(button => button.dataset.audioAction),
  ['previous', 'rewind', 'replay', 'play', 'forward', 'skip', 'stop']);
assert.equal(createAudioCommand(iconButton, 'Next', 'icon-skip').querySelector('use').getAttribute('href'), '#icon-skip');
assert.equal(audioPlaybackAction({ stopped: false, paused: false }).label, 'Pause live audio');
assert.equal(audioPlaybackAction({ stopped: false, paused: true }).label, 'Resume live audio');
assert.equal(audioPlaybackAction({ stopped: true, paused: false }).label, 'Listen live');
assert.equal(audioPlaybackAction({ playing: true }, { recording: true }).icon, 'pause');
assert.equal(audioPlaybackAction({ playing: false }, { recording: true }).label, 'Play recording');

const seek = node('input');
const timing = createAudioTiming({ node, seek });
assert.equal(timing.elapsed.textContent, '—'); assert.equal(timing.duration.textContent, '—');
assert.equal(timing.progress.hidden, true); assert.equal(seek.hidden, true);
timing.update({ current: {}, currentTime: 72.9, duration: 180 });
assert.equal(timing.elapsed.textContent, '01:12'); assert.equal(timing.duration.textContent, '03:00');
assert.equal(timing.progress.value, 72.9 / 180); assert.equal(timing.progress.hidden, false);
assert.equal(seek.hidden, true);
timing.update({ current: {}, currentTime: 250, duration: 180 }, { recording: true });
assert.equal(timing.elapsed.textContent, '03:00'); assert.equal(timing.progress.hidden, true);
assert.equal(seek.hidden, false); assert.equal(seek.value, '180'); assert.equal(seek.disabled, false);
timing.update({ current: {}, currentTime: -10, duration: 180 }, { recording: true, allowed: false });
assert.equal(seek.value, '0'); assert.equal(seek.disabled, true);
for (const unknown of [0, NaN, Infinity, -1]) {
  timing.update({ current: {}, currentTime: 10, duration: unknown }, { recording: true });
  assert.equal(timing.elapsed.textContent, '00:10');
  assert.equal(timing.duration.textContent, 'Duration unavailable');
  assert.equal(timing.progress.hidden, true); assert.equal(seek.hidden, true); assert.equal(seek.disabled, true);
  assert.equal(timing.progress.getAttribute('aria-valuetext'), null, 'Unknown duration clears stale progress descriptions.');
}
timing.update({ current: {}, currentTime: Infinity, duration: 30 });
assert.equal(timing.elapsed.textContent, '—'); assert.equal(timing.progress.hidden, true);
const compactTiming = createAudioTiming({ node });
compactTiming.update({ current: {}, currentTime: 5, duration: 10 }, { recording: true });
assert.equal(compactTiming.progress.hidden, false, 'Compact recordings retain progress without a seek control.');
timing.update({ currentTime: 0, duration: 0 });
assert.equal(timing.duration.textContent, '—', 'Idle state does not claim an unavailable recording duration.');

const host = node('div'), volumeEvents = [], abort = new AbortController();
const audioVolume = createAudioVolume({ node, iconButton, host, id: 'test', signal: abort.signal,
  onInput: value => volumeEvents.push(['input', value]), onCommit: value => volumeEvents.push(['commit', value]) });
const first = audioVolume.createTrigger('first-volume'), second = audioVolume.createTrigger('second-volume');
assert.equal(audioVolume.popover.id, 'test-volume-popover'); assert.equal(audioVolume.input.id, 'test-volume');
assert.equal(first.getAttribute('popovertarget'), 'test-volume-popover');
assert.equal(first.classList.contains('ui-icon-button'), false, 'Volume percent must not be squeezed into an icon-only width.');
assert.equal(first.dataset.audioAction, 'volume');
audioVolume.update(0.65, true);
assert.equal(first.getAttribute('aria-label'), 'Volume, 65 percent');
assert.equal(second.querySelector('[data-volume-value]').textContent, '65%');
first.dispatch('click'); audioVolume.popover.showPopover();
assert.equal(audioVolume.invoker, first); assert.equal(audioVolume.isOpen(), true);
assert.equal(first.getAttribute('aria-expanded'), 'true'); assert.equal(second.getAttribute('aria-expanded'), 'false');
assert.equal(document.activeElement, audioVolume.input);
audioVolume.input.value = '0.3'; audioVolume.input.dispatch('input'); audioVolume.input.dispatch('change');
assert.deepEqual(volumeEvents, [['input', 0.3], ['commit', 0.3]]);
audioVolume.close(true);
assert.equal(document.activeElement, first, 'Explicit popup close restores focus to its own invoker.');
second.dispatch('click'); audioVolume.popover.showPopover();
audioVolume.update(0, false);
assert.equal(audioVolume.isOpen(), false); assert.equal(first.disabled, true); assert.equal(second.disabled, true);
assert.equal(audioVolume.input.getAttribute('aria-valuetext'), 'Muted, 0 percent');
audioVolume.input.dispatch('input'); assert.equal(volumeEvents.length, 2, 'Unavailable volume cannot invoke a callback.');
audioVolume.update(0.5, true); second.dispatch('click'); audioVolume.popover.showPopover();
window.dispatch('resize'); assert.equal(audioVolume.isOpen(), false);
abort.abort();
assert.equal(host.children.length, 0, 'Aborting the owner removes the popup.');
audioVolume.input.dispatch('change'); assert.equal(volumeEvents.length, 2, 'Aborting removes volume event handlers.');
console.log('Shared audio control order, playback labels, truthful timing and volume lifecycle passed.');
