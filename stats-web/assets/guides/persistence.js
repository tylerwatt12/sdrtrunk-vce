// Browser-only onboarding state. Account and installation scope are both needed:
// replacing a receiver or signing in as another account starts a fresh welcome.
export function guideStorageKey(account, dismissalScope, id, version = 1) {
  if (typeof dismissalScope !== 'string' || !dismissalScope.trim()) return null;
  return `sdrtrunk-vce-guide:v1:${encodeURIComponent(account || 'anonymous')}:` +
    `${encodeURIComponent(dismissalScope)}:${encodeURIComponent(id)}:${version}`;
}

export function createGuideMemory(storage) {
  const remembered = new Set();
  const keyFor = (context, definition) => guideStorageKey(context.account, context.dismissalScope,
    definition.id, definition.version);
  const memoryKey = (context, definition) => keyFor(context, definition) ??
    `session:${encodeURIComponent(context.account || 'anonymous')}:${definition.id}:${definition.version}`;
  const browserStorage = () => storage ?? globalThis.localStorage;
  return {
    has(context, definition) {
      if (remembered.has(memoryKey(context, definition))) return true;
      const key = keyFor(context, definition);
      if (!key) return false;
      try { return browserStorage()?.getItem(key) === '1'; }
      catch (_) { return false; }
    },
    remember(context, definition) {
      remembered.add(memoryKey(context, definition));
      const key = keyFor(context, definition);
      if (!key) return;
      try { browserStorage()?.setItem(key, '1'); }
      catch (_) { /* The current session still remembers when storage is blocked. */ }
    }
  };
}
