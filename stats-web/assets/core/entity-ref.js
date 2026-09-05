'use strict';

  function text(value) {
    return typeof value === 'string' ? value.trim() : '';
  }

  function identityKey(value, kind) {
    const key = text(value);
    const match = /^v1-([grp])-(x|[0-9a-f]{5})-(x|[0-9a-f]{3})-([0-9]+)$/.exec(key);
    const expected = { talkgroup: 'g', patch_group: 'p', radio: 'r' }[kind];
    if (!match || match[1] !== expected) return '';
    const noHome = match[2] === 'x' && match[3] === 'x';
    if ((match[2] === 'x') !== (match[3] === 'x') || (kind === 'patch_group' && noHome)) return '';
    const identifier = Number(match[4]);
    if (!Number.isSafeInteger(identifier) || identifier < 1 || String(identifier) !== match[4]) return '';
    const maximum = kind === 'radio' ? (noHome ? 0xFFFFFF : 9_999_999) :
      (noHome ? 0xFFFFFF : 0xFFFE);
    return identifier <= maximum ? key : '';
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
    if (kind === 'radio_system' && exactKeys(reference, ['kind', 'key']) && key) {
      return `/?${new URLSearchParams({ view: 'radio-system', radio_system_key: key })}`;
    }
    if (kind === 'channel' && exactKeys(reference, ['kind', 'key']) && uuid(key)) {
      return `/?${new URLSearchParams({ view: 'channel', configuration_id: key })}`;
    }
    if (['talkgroup', 'patch_group', 'radio'].includes(kind) &&
        exactKeys(reference, ['kind', 'radio_system_key', 'identity_key'])) {
      const radioSystemKey = text(reference.radio_system_key);
      const key = identityKey(reference.identity_key, kind);
      if (!radioSystemKey || !key) return null;
      const view = kind === 'radio' ? 'radio' : 'group-identity';
      const values = { view, radio_system_key: radioSystemKey, identity_key: key };
      return `/?${new URLSearchParams(values)}`;
    }
    return null;
  }

export { href };
