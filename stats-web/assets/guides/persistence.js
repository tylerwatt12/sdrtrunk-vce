const endpoint = '/api/v1/me/tour-dismissals';
const identity = context => context.authenticated === true && context.account && context.dismissalScope ?
  JSON.stringify([context.account, context.dismissalScope]) : null;

// Retained only for importing old browser records and retrying an interrupted save.
export function guideStorageKey(account, dismissalScope, id, version = 1) {
  if (typeof dismissalScope !== 'string' || !dismissalScope.trim()) return null;
  return `sdrtrunk-vce-guide:v1:${encodeURIComponent(account || 'anonymous')}:` +
    `${encodeURIComponent(dismissalScope)}:${encodeURIComponent(id)}:${version}`;
}

function documentHistory(value) {
  if (value?.version !== 1 || !value.dismissed || typeof value.dismissed !== 'object' ||
      Array.isArray(value.dismissed) || Object.keys(value.dismissed).length > 64 ||
      Object.entries(value.dismissed).some(([id, version]) => !/^[a-z][a-z0-9_-]{0,63}$/.test(id) ||
        !Number.isInteger(version) || version < 1 || version > 1_000_000)) {
    throw new Error('The receiver returned invalid tour history.');
  }
  return { ...value.dismissed };
}

const merge = (target, source) => {
  for (const [id, version] of Object.entries(source))
    target[id] = Math.max(Object.hasOwn(target, id) ? target[id] : 0, version);
};

// Account history is authoritative. Local keys are never consulted by has(); they
// are imported only after a successful read for the exact account/installation.
export function createGuideMemory(storage, { fetchDocument, definitions = [], onError, onSaved,
  setTimer = globalThis.setTimeout, clearTimer = globalThis.clearTimeout } = {}) {
  let active = null;
  const known = new Map(definitions.map(definition => [definition.id, definition.version]));
  const browserStorage = () => storage ?? globalThis.localStorage;
  const current = state => active === state && !state.abort.signal.aborted;
  const report = (state, error, kind) => {
    if (current(state)) onError?.(error, { kind, retry: () => retry(state.context) });
  };
  const cancel = () => {
    if (!active) return;
    active.abort.abort();
    if (active.timer !== null) clearTimer(active.timer);
    active = null;
  };
  async function request(state, method, dismissed) {
    if (typeof fetchDocument !== 'function') throw new Error('Tour history is unavailable.');
    const response = await fetchDocument(endpoint, { method, signal: state.abort.signal,
      ...(method === 'PUT' ? { headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ version: 1, dismissed }), keepalive: true } : {}) });
    const body = await response.json();
    if (!response.ok) {
      const error = new Error(body?.error?.message || 'Tour history is unavailable.');
      error.status = response.status;
      throw error;
    }
    return documentHistory(body?.data);
  }
  const importBrowser = state => {
    for (const [id, version] of known) {
      for (let previous = version; previous >= 1; previous--) {
        try {
          if (browserStorage()?.getItem(guideStorageKey(state.context.account, state.context.dismissalScope, id, previous)) === '1') {
            if ((state.history[id] || 0) < previous) merge(state.pending, { [id]: previous });
            break;
          }
        } catch (_) { break; }
      }
    }
  };
  const scheduleRetry = state => {
    const delay = [2000, 10000, 30000][state.retryCount++];
    if (delay === undefined || !current(state) || state.timer !== null) return;
    state.timer = setTimer(() => {
      state.timer = null;
      if (current(state)) void retry(state.context);
    }, delay);
  };
  async function flush(state) {
    if (!current(state) || !state.loaded) return false;
    if (state.saving) return state.saving;
    if (!Object.keys(state.pending).length) { onSaved?.(); return true; }
    state.saving = (async () => {
      try {
        while (current(state) && Object.keys(state.pending).length) {
          const sent = { ...state.pending };
          const saved = await request(state, 'PUT', sent);
          if (!current(state)) return false;
          if (Object.entries(sent).some(([id, version]) => (saved[id] || 0) < version))
            throw new Error('The receiver did not confirm the saved tour history.');
          merge(state.history, saved);
          for (const [id, version] of Object.entries(state.pending))
            if ((state.history[id] || 0) >= version) delete state.pending[id];
        }
        if (!current(state)) return false;
        state.retryCount = 0;
        if (state.timer !== null) { clearTimer(state.timer); state.timer = null; }
        onSaved?.();
        return true;
      } catch (error) {
        if (current(state)) { report(state, error, 'save'); scheduleRetry(state); }
        return false;
      } finally { state.saving = null; }
    })();
    return state.saving;
  }
  async function load(state) {
    state.loading = (async () => {
      try {
        const saved = await request(state, 'GET');
        if (!current(state)) return false;
        merge(state.history, saved);
        state.loaded = true;
        importBrowser(state);
        if (Object.keys(state.pending).length) void flush(state);
        else onSaved?.();
        return true;
      } catch (error) {
        if (current(state)) {
          const unsaved = Object.keys(state.pending).length > 0;
          report(state, error, unsaved ? 'save' : 'load');
          if (unsaved) scheduleRetry(state);
        }
        return false;
      } finally { state.loading = null; }
    })();
    return state.loading;
  }
  function synchronize(context) {
    const key = identity(context);
    if (active?.key === key && key) return active.loading || Promise.resolve(active.loaded);
    cancel();
    onSaved?.();
    if (!key) return Promise.resolve(false);
    const state = { key, context: { ...context }, abort: new AbortController(), loaded: false,
      loading: null, saving: null, history: Object.create(null), pending: Object.create(null), timer: null, retryCount: 0 };
    active = state;
    return load(state);
  }
  async function retry(context) {
    if (active?.key !== identity(context)) return false;
    const state = active;
    if (!state) return false;
    if (state.timer !== null) { clearTimer(state.timer); state.timer = null; }
    if (!state.loaded && !await (state.loading || load(state))) return false;
    return flush(state);
  }
  return {
    synchronize, retry,
    has(context, definition) {
      return active?.key === identity(context) &&
        Math.max(active.history[definition.id] || 0, active.pending[definition.id] || 0) >= definition.version;
    },
    remember(context, definition) {
      if (!identity(context) || known.get(definition.id) !== definition.version) return;
      if (active?.key !== identity(context)) return;
      const state = active;
      if (!state || (state.history[definition.id] || 0) >= definition.version) return;
      merge(state.pending, { [definition.id]: definition.version });
      try { browserStorage()?.setItem(guideStorageKey(context.account, context.dismissalScope,
        definition.id, definition.version), '1'); } catch (_) { /* Retry still works for this signed-in session. */ }
      void retry(context);
    },
    destroy() { cancel(); }
  };
}
