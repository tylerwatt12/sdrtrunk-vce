import { href as entityRefHref } from './entity-ref.js?v=1';

export const MAXIMUM_DIRECT_LISTEN_TARGETS = 32;
const STORAGE_KEY = 'sdrtrunk.direct-listen.v1';

function groupIdentity(value) {
  if (typeof value !== 'string') return false;
  const match = /^v1-([gp])-(x|[0-9a-f]{5})-(x|[0-9a-f]{3})-([1-9][0-9]*)$/.exec(value);
  if (!match || (match[2] === 'x') !== (match[3] === 'x') || match[1] === 'p' && match[2] === 'x') return false;
  const id = Number(match[4]);
  return Number.isSafeInteger(id) && id <= (match[2] === 'x' ? 0xFFFFFF : 0xFFFE);
}

export function normalizeDirectListenTarget(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  if (Object.values(value).some(item => typeof item === 'string' && item !== item.trim())) return null;
  const kind = value.kind;
  if (kind === 'talkgroup' || kind === 'patch_group') {
    if (Object.keys(value).some(key => !['kind', 'radio_system_key', 'identity_key'].includes(key))) return null;
    const target = { kind, radio_system_key: value.radio_system_key, identity_key: value.identity_key };
    return entityRefHref(target) ? target : null;
  }
  if (kind !== 'channel' || Object.keys(value).some(key =>
    !['kind', 'key', 'identity_key', 'timeslot'].includes(key)) || !entityRefHref({ kind, key: value.key })) return null;
  const target = { kind, key: value.key };
  if (Object.hasOwn(value, 'identity_key')) {
    if (!groupIdentity(value.identity_key)) return null;
    target.identity_key = value.identity_key;
  }
  if (Object.hasOwn(value, 'timeslot')) {
    if (value.timeslot !== 1 && value.timeslot !== 2) return null;
    target.timeslot = value.timeslot;
  }
  return target;
}

export function directListenTargetKey(value) {
  const target = normalizeDirectListenTarget(value);
  return target ? JSON.stringify(target) : '';
}

function browserStorage() {
  try { return window.sessionStorage; } catch { return null; }
}

// This belongs to this tab's listening session, separately from account preferences.
export function createDirectListenSession({ storage = browserStorage() } = {}) {
  let identity;
  let selected = false;
  const targets = new Map();
  const snapshot = () => ({ selected, targets: [...targets.values()].map(item => ({ ...item, target: { ...item.target } })),
    maximumTargets: MAXIMUM_DIRECT_LISTEN_TARGETS });
  const save = () => {
    if (identity === undefined) return;
    try { storage?.setItem(STORAGE_KEY, JSON.stringify({ identity, selected, targets: [...targets.values()] })); } catch { }
  };
  const reset = () => {
    selected = false;
    targets.clear();
    try { storage?.removeItem(STORAGE_KEY); } catch { }
  };
  const entry = (target, label = '', context = '') => {
    const normalized = normalizeDirectListenTarget(target);
    if (!normalized) throw new Error('This item cannot be added to Direct Listen.');
    return { key: JSON.stringify(normalized), target: normalized,
      label: typeof label === 'string' ? label.trim().slice(0, 256) : '',
      context: typeof context === 'string' ? context.trim().slice(0, 256) : '' };
  };
  return {
    snapshot,
    setIdentity(value) {
      const next = typeof value === 'string' ? value : value === null ? null : String(value);
      if (identity === next) return false;
      const initial = identity === undefined;
      identity = next;
      targets.clear(); selected = false;
      if (initial) {
        try {
          const raw = storage?.getItem(STORAGE_KEY);
          const saved = raw && raw.length <= 65536 ? JSON.parse(raw) : null;
          if (saved && saved.identity === identity && typeof saved.selected === 'boolean' &&
              Array.isArray(saved.targets) && saved.targets.length <= MAXIMUM_DIRECT_LISTEN_TARGETS) {
            for (const item of saved.targets) {
              const restored = entry(item?.target, item?.label, item?.context);
              targets.set(restored.key, restored);
            }
            selected = saved.selected && targets.size > 0;
            return true;
          }
        } catch { }
      }
      reset();
      return true;
    },
    add(target, label, context) {
      const item = entry(target, label, context);
      const added = !targets.has(item.key);
      if (added && targets.size >= MAXIMUM_DIRECT_LISTEN_TARGETS) {
        throw new Error(`Direct Listen can contain up to ${MAXIMUM_DIRECT_LISTEN_TARGETS} items. Remove an item and try again.`);
      }
      targets.set(item.key, item);
      selected = true;
      save();
      return { added, key: item.key };
    },
    remove(key) {
      if (!targets.delete(String(key || ''))) return false;
      if (!targets.size) selected = false;
      save();
      return true;
    },
    select(value) {
      const next = Boolean(value) && targets.size > 0;
      if (selected === next) return false;
      selected = next;
      save();
      return true;
    },
    clear() { reset(); }
  };
}
