'use strict';

  function text(value) {
    return typeof value === 'string' ? value.trim() : '';
  }

  function numericId(value) {
    return typeof value === 'number' && Number.isSafeInteger(value) && value > 0 ? value : null;
  }

  function exactKeys(value, keys) {
    const actual = Object.keys(value).sort();
    const expected = keys.slice().sort();
    return actual.length === expected.length && actual.every((key, index) => key === expected[index]);
  }

  function uuid(value) {
    const key = text(value);
    return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(key) ? key : '';
  }

  function href(reference) {
    if (!reference || typeof reference !== 'object' || Array.isArray(reference)) return null;
    const kind = text(reference.kind);
    const key = text(reference.key);
    if (kind === 'system' && exactKeys(reference, ['kind', 'key']) && key) {
      return `/?${new URLSearchParams({ view: 'system', scope: key })}`;
    }
    if (kind === 'site' && exactKeys(reference, ['kind', 'key']) && uuid(key)) {
      return `/?${new URLSearchParams({ view: 'site', guid: key })}`;
    }
    if (kind === 'conventional' && exactKeys(reference, ['kind', 'key']) && uuid(key)) {
      return `/?${new URLSearchParams({ view: 'conventional-detail', id: key })}`;
    }
    if (['talkgroup', 'patch_group', 'radio'].includes(kind) &&
        exactKeys(reference, ['kind', 'scope', 'id'])) {
      const scope = text(reference.scope);
      const id = numericId(reference.id);
      if (!scope || id === null) return null;
      const view = kind === 'radio' ? 'radio' : 'talkgroup';
      const values = { view, scope, id: String(id) };
      if (kind === 'patch_group') values.kind = 'patch_group';
      return `/?${new URLSearchParams(values)}`;
    }
    return null;
  }

export { href };
