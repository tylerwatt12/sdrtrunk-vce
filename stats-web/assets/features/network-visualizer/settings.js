'use strict';

const P25_EVENT_SETTINGS = Object.freeze([
  Object.freeze({ id: 'emergency', action: 'EMERGENCY', label: 'Emergency',
    highlight: true, autoZoom: true }),
  Object.freeze({ id: 'movement', label: 'Affiliation change',
    detail: 'A confirmed radio affiliation changed between talkgroups in comparable scope.',
    highlight: true, autoZoom: true }),
  Object.freeze({ id: 'denial', action: 'DENIAL', label: 'Denial',
    highlight: true, autoZoom: false }),
  Object.freeze({ id: 'patch', label: 'Patch change', detail: 'A patch observation, creation, or removal.',
    highlight: true, autoZoom: true }),
  Object.freeze({ id: 'busy', action: 'BUSY', label: 'Busy',
    highlight: true, autoZoom: true }),
  Object.freeze({ id: 'queued', action: 'QUEUED', label: 'Queued',
    highlight: true, autoZoom: true }),
  Object.freeze({ id: 'check', action: 'CHECK', label: 'Radio check',
    highlight: true, autoZoom: false }),
  Object.freeze({ id: 'page', action: 'PAGE', label: 'Page',
    highlight: true, autoZoom: false }),
  Object.freeze({ id: 'logout', action: 'LOGOUT', label: 'Logout',
    highlight: true, autoZoom: false }),
  Object.freeze({ id: 'call', label: 'Calls and grants',
    detail: 'Call and control-channel grant activity.',
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

export {
  P25_EVENT_SETTINGS,
  defaultP25EventSettings,
  normalizeP25EventSettings,
  enabledP25EventCategories
};
