'use strict';

  const definitions = Object.freeze([
    { id: 'dashboard', label: 'Dashboard', title: 'Dashboard', parent: 'dashboard', capability: 'dashboard', databaseNotice: true },
    { id: 'live', label: 'Live', title: 'Live', parent: 'live', capability: 'live', databaseNotice: false },
    { id: 'scanner', label: 'Scanner', title: 'Scanner', parent: 'scanner', capability: 'call-audio', databaseNotice: false },
    { id: 'tuner-spectrum', label: 'Tuner Spectrum', title: 'Tuner Spectrum', parent: 'tuner-spectrum', access: 'admin-tuner', databaseNotice: false },
    { id: 'systems', label: 'Trunked Systems', title: 'Trunked Systems', parent: 'systems', capability: 'systems', databaseNotice: true },
    { id: 'system', label: 'System Details', title: 'System Details', parent: 'systems', capability: 'systems', databaseNotice: true },
    { id: 'talkgroup', label: 'Talkgroup Details', title: 'Talkgroup Details', parent: 'systems', capability: 'systems', databaseNotice: true },
    { id: 'radio', label: 'Radio Details', title: 'Radio Details', parent: 'systems', capability: 'systems', databaseNotice: true },
    { id: 'site', label: 'Site Details', title: 'Site Details', parent: 'systems', capability: 'systems', databaseNotice: true },
    { id: 'conventional', label: 'Conventional Channels', title: 'Conventional Channels', parent: 'conventional', capability: 'conventional', databaseNotice: true },
    { id: 'conventional-detail', label: 'Conventional Details', title: 'Conventional Details', parent: 'conventional', capability: 'conventional', databaseNotice: true },
    { id: 'aliases', label: 'Aliases', title: 'Aliases', parent: 'aliases', access: 'admin-aliases', databaseNotice: true },
    { id: 'configuration', label: 'Configuration', title: 'Configuration', parent: 'configuration', access: 'admin-configuration', databaseNotice: false },
    { id: 'hardware', label: 'Hardware', title: 'Hardware', parent: 'hardware', access: 'admin-tuner', databaseNotice: false },
    { id: 'admin', label: 'Administration', title: 'Administration', parent: 'admin', access: 'admin', databaseNotice: false },
    { id: 'settings', label: 'My Settings', title: 'My Settings', parent: null, access: 'authenticated', databaseNotice: false },
    { id: 'credits', label: 'About', title: 'About', parent: 'credits', capability: 'credits', databaseNotice: false }
  ].map((definition) => Object.freeze({ ...definition })));

  function createRegistry(handlers, isAllowed) {
    if (!handlers || typeof handlers !== 'object' || Array.isArray(handlers)) {
      throw new TypeError('Route handlers are required.');
    }
    if (typeof isAllowed !== 'function') throw new TypeError('A route access function is required.');
    const expected = new Set(definitions.map(({ id }) => id));
    Object.keys(handlers).forEach((id) => {
      if (!expected.has(id)) throw new Error(`Unknown route handler: ${id}`);
    });
    const entries = Object.fromEntries(definitions.map((definition) => {
      const handler = handlers[definition.id];
      if (typeof handler !== 'function') throw new Error(`Missing route handler: ${definition.id}`);
      return [definition.id, Object.freeze({
        ...definition,
        handler,
        allowed: () => isAllowed(definition)
      })];
    }));
    return Object.freeze(entries);
  }

  function requestedView(search) {
    const parameters = search instanceof URLSearchParams ? search : new URLSearchParams(search || '');
    return parameters.get('view') || 'dashboard';
  }

  function resolve(registry, search) {
    return registry?.[requestedView(search)] || null;
  }

  function localTarget(location, value) {
    const target = value instanceof URL ? value : new URL(String(value || ''), location.href);
    if (target.origin !== location.origin || target.pathname !== '/') return null;
    return target;
  }

  function navigate(windowValue, value, onNavigate, options = {}) {
    const target = localTarget(windowValue.location, value);
    if (!target) return false;
    const relative = `${target.pathname}${target.search}${target.hash}`;
    if (options.replace === true) windowValue.history.replaceState({}, '', relative);
    else windowValue.history.pushState({}, '', relative);
    onNavigate?.(new URLSearchParams(target.search));
    return true;
  }

export { definitions, createRegistry, requestedView, resolve, localTarget, navigate };
