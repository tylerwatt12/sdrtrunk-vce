'use strict';

const P25_EVENT_SETTINGS = Object.freeze([
  Object.freeze({ id: 'emergency', action: 'EMERGENCY',
    highlight: true, autoZoom: true }),
  Object.freeze({ id: 'movement', label: 'Affiliation change',
    detail: 'A confirmed radio affiliation changed between talkgroups in comparable scope.',
    highlight: true, autoZoom: true }),
  Object.freeze({ id: 'denial', action: 'DENIAL',
    highlight: true, autoZoom: false }),
  Object.freeze({ id: 'patch', label: 'Patch change', detail: 'A stored patch observation, creation, or removal.',
    highlight: true, autoZoom: true }),
  Object.freeze({ id: 'busy', action: 'BUSY',
    highlight: true, autoZoom: true }),
  Object.freeze({ id: 'queued', action: 'QUEUED',
    highlight: true, autoZoom: true }),
  Object.freeze({ id: 'check', action: 'CHECK',
    highlight: true, autoZoom: false }),
  Object.freeze({ id: 'page', action: 'PAGE',
    highlight: true, autoZoom: false }),
  Object.freeze({ id: 'logout', action: 'LOGOUT',
    highlight: true, autoZoom: false }),
  Object.freeze({ id: 'call', label: 'Calls and grants',
    detail: 'Routine stored call and control-channel grant activity. This does not prove affiliation.',
    highlight: true, autoZoom: false })
]);

function defaultP25EventSettings() {
  return Object.fromEntries(P25_EVENT_SETTINGS.map((entry) => [entry.id, {
    highlight: entry.highlight,
    autoZoom: entry.autoZoom
  }]));
}

function normalizeP25EventSettings(value) {
  const source = value && typeof value === 'object' && !Array.isArray(value) ? value : {};
  return Object.fromEntries(P25_EVENT_SETTINGS.map((entry) => {
    const saved = source[entry.id];
    return [entry.id, {
      highlight: typeof saved?.highlight === 'boolean' ? saved.highlight : entry.highlight,
      autoZoom: typeof saved?.autoZoom === 'boolean' ? saved.autoZoom : entry.autoZoom
    }];
  }));
}

function enabledP25EventCategories(settings, property) {
  const normalized = normalizeP25EventSettings(settings);
  return new Set(P25_EVENT_SETTINGS.filter((entry) => normalized[entry.id]?.[property]).map((entry) => entry.id));
}

function routineP25ActivityEnabled(settings) {
  const call = normalizeP25EventSettings(settings).call;
  return call.highlight || call.autoZoom;
}

export {
  P25_EVENT_SETTINGS,
  defaultP25EventSettings,
  normalizeP25EventSettings,
  enabledP25EventCategories,
  routineP25ActivityEnabled
};
