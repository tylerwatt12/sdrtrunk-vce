const STORAGE_KEY = 'sdrtrunk-vce-radioreference-localities-v1';
const LIFETIME_MS = 30 * 24 * 60 * 60 * 1000;
const MAX_ENTRIES = 64;
const MAX_ITEMS = 500;
const MAX_STORAGE_LENGTH = 256 * 1024;

function browserStorage() {
  try { return globalThis.localStorage; } catch (_error) { return null; }
}

function validKey(key) {
  if (typeof key !== 'string') return false;
  if (key === 'countries') return true;
  const match = /^(?:states|counties):([1-9]\d*)$/.exec(key);
  return Boolean(match && Number.isSafeInteger(Number(match[1])) && Number(match[1]) <= 2147483647);
}

function localityItems(items) {
  if (!Array.isArray(items) || !items.length || items.length > MAX_ITEMS) return null;
  const result = [];
  for (const item of items) {
    if (!Number.isSafeInteger(item?.id) || item.id <= 0 || item.id > 2147483647 || typeof item.name !== 'string' ||
        !item.name.trim() || item.name.length > 256) return null;
    const value = { id: item.id, name: item.name };
    for (const key of ['abbreviation', 'code']) {
      if (item[key] == null) continue;
      if (typeof item[key] !== 'string' || item[key].length > 64) return null;
      value[key] = item[key];
    }
    result.push(value);
  }
  return result;
}

const copy = items => Array.isArray(items) ? items.map(item => ({ ...item })) : items;
const storageLength = value => new TextEncoder().encode(value).byteLength;

/** Only public locality names and IDs are persisted; account and radio catalog data remain live. */
export function createRadioReferenceLocalityCache({ storage = browserStorage(), now = Date.now } = {}) {
  const entries = new Map();
  const pending = new Map();
  const fresh = entry => Number.isSafeInteger(entry?.storedAt) && entry.storedAt <= now() &&
    now() - entry.storedAt < LIFETIME_MS;
  try {
    const raw = storage?.getItem(STORAGE_KEY);
    const saved = typeof raw === 'string' && raw.length <= MAX_STORAGE_LENGTH &&
      storageLength(raw) <= MAX_STORAGE_LENGTH ? JSON.parse(raw) : null;
    if (saved?.version === 1 && Array.isArray(saved.entries) && saved.entries.length <= MAX_ENTRIES) {
      for (const entry of saved.entries) {
        const items = localityItems(entry?.items);
        if (validKey(entry?.key) && fresh(entry) && items) {
          entries.set(entry.key, { key: entry.key, storedAt: entry.storedAt, items });
        }
      }
    }
  } catch (_error) { /* Missing, blocked or damaged storage is an ordinary cache miss. */ }

  const retain = (key, items) => {
    for (const [existingKey, entry] of entries) if (!fresh(entry)) entries.delete(existingKey);
    entries.delete(key);
    entries.set(key, { key, storedAt: now(), items });
    let serialized;
    do {
      if (entries.size > MAX_ENTRIES) entries.delete(entries.keys().next().value);
      serialized = JSON.stringify({ version: 1, entries: [...entries.values()] });
      if (storageLength(serialized) > MAX_STORAGE_LENGTH) entries.delete(entries.keys().next().value);
    } while (storageLength(serialized) > MAX_STORAGE_LENGTH);
    try { storage?.setItem(STORAGE_KEY, serialized); }
    catch (_error) { /* Continue with bounded in-memory caching when storage is unavailable or full. */ }
  };

  return {
    async load(key, loadItems) {
      if (!validKey(key)) throw new TypeError('Unknown RadioReference locality.');
      const cached = entries.get(key);
      if (cached && fresh(cached)) {
        entries.delete(key);
        entries.set(key, cached);
        return copy(cached.items);
      }
      entries.delete(key);
      if (pending.has(key)) return copy(await pending.get(key));
      const request = Promise.resolve().then(loadItems).then(items => {
        const safeItems = localityItems(items);
        if (safeItems) retain(key, safeItems);
        return items;
      });
      if (pending.size < MAX_ENTRIES) pending.set(key, request);
      try { return await request; }
      finally { if (pending.get(key) === request) pending.delete(key); }
    }
  };
}

export const radioReferenceLocalityCache = createRadioReferenceLocalityCache();
