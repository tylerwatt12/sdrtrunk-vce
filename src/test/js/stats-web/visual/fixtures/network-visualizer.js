'use strict';

import { createNetworkVisualizer } from '/assets/features/network-visualizer/index.js?browser-fixture=1';

const host = document.querySelector('#network-visualizer-fixture');
const bootStartedAt = performance.now();
const FIXED_LIVE_EDGE_MS = 1_700_000_000_000;
Date.now = () => FIXED_LIVE_EDGE_MS + Math.floor(globalThis.performance.now() - bootStartedAt);

function node(tag, className = '', text = null) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (text !== null) element.textContent = String(text);
  return element;
}

function iconGlyph(name) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.classList.add('network-visualizer-fixture-icon');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  svg.dataset.icon = String(name || '');
  const circle = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
  circle.setAttribute('cx', '12');
  circle.setAttribute('cy', '12');
  circle.setAttribute('r', '7');
  const line = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  line.setAttribute('d', 'M12 2v3m0 14v3M2 12h3m14 0h3');
  svg.append(circle, line);
  return svg;
}

function iconButton(iconName, label) {
  const button = node('button', 'ui-button ui-button-secondary');
  button.type = 'button';
  button.setAttribute('aria-label', label);
  button.title = label;
  button.append(iconGlyph(iconName));
  return button;
}

const metrics = {
  bootStartedAt,
  mountedAt: 0,
  mounts: 0,
  closes: 0,
  networkCreated: 0,
  networkActive: 0,
  channelCreated: 0,
  channelActive: 0,
  topics: [],
  lastClosedDiagnostics: null
};
let latestNetworkConnection = null;
let forcedHidden = false;

Object.defineProperty(document, 'hidden', {
  configurable: true,
  get: () => forcedHidden
});

function fakeNetworkConnection(topic, options = {}) {
  metrics.networkCreated += 1;
  metrics.networkActive += 1;
  metrics.topics.push({ topic, options: { ...options } });
  const listeners = new Map();
  let closed = false;
  const connection = {
    onopen: null,
    onerror: null,
    addEventListener(type, listener) {
      if (!listeners.has(type)) listeners.set(type, new Set());
      listeners.get(type).add(listener);
    },
    dispatch(type, data) {
      const event = { data: typeof data === 'string' ? data : JSON.stringify(data) };
      listeners.get(type)?.forEach((listener) => listener(event));
    },
    close() {
      if (closed) return Promise.resolve();
      closed = true;
      metrics.networkActive -= 1;
      listeners.clear();
      if (latestNetworkConnection === connection) latestNetworkConnection = null;
      return Promise.resolve();
    }
  };
  latestNetworkConnection = connection;
  queueMicrotask(() => {
    if (!closed) connection.onopen?.();
  });
  return connection;
}

function fakeChannelActivity(callbacks = {}) {
  metrics.channelCreated += 1;
  metrics.channelActive += 1;
  let closed = false;
  queueMicrotask(() => {
    if (!closed) callbacks.open?.();
  });
  return {
    close() {
      if (closed) return;
      closed = true;
      metrics.channelActive -= 1;
    }
  };
}

let controller = null;
let abortController = null;

function closeCurrent({ remove = true } = {}) {
  if (!controller) return null;
  const closing = controller;
  closing.close();
  metrics.lastClosedDiagnostics = closing.diagnostics();
  metrics.closes += 1;
  abortController?.abort();
  controller = null;
  abortController = null;
  if (remove) host.replaceChildren();
  return metrics.lastClosedDiagnostics;
}

function mount() {
  closeCurrent();
  abortController = new AbortController();
  controller = createNetworkVisualizer({
    node,
    iconGlyph,
    iconButton,
    entityRefHref: () => null,
    navigateTo: () => {},
    liveConnection: fakeNetworkConnection,
    subscribeLiveChannelActivity: fakeChannelActivity,
    profileKey: 'network-visualizer-browser-contract',
    signal: abortController.signal
  });
  host.replaceChildren(controller.element);
  metrics.mounts += 1;
  metrics.mountedAt = performance.now();
  return controller.diagnostics();
}

function diagnostics() {
  return controller?.diagnostics() || null;
}

function setHidden(value) {
  forcedHidden = Boolean(value);
  document.dispatchEvent(new Event('visibilitychange'));
}

function dispatchNetwork(type, data) {
  latestNetworkConnection?.dispatch(type, data);
}

window.networkVisualizerTest = {
  metrics,
  mount,
  remount: mount,
  close: closeCurrent,
  diagnostics,
  dispatchNetwork,
  setHidden,
  get controller() { return controller; }
};

mount();
window.dispatchEvent(new CustomEvent('network-visualizer-fixture-ready'));
