/** Labels and protocol coverage supplied by the receiver's message catalog. */
export function createMessageTypeCatalog() {
  let definitions = [];
  let byKey = new Map();
  const key = (kind, name) => `${String(kind || '').toUpperCase()}|${String(name || '').trim().toUpperCase()}`;
  return {
    replace(rows) {
      definitions = (Array.isArray(rows) ? rows : []).filter((row) =>
        ['ACTION', 'EVENT'].includes(row?.definition_kind) && typeof row.name === 'string' && row.name.trim())
        .map((row) => ({ ...row, name: row.name.trim().toUpperCase() }))
        .sort((left, right) => Number(left.sort_order || 0) - Number(right.sort_order || 0) ||
          left.name.localeCompare(right.name));
      byKey = new Map(definitions.map((row) => [key(row.definition_kind, row.name), row]));
    },
    definitions(kind) { return definitions.filter((row) => row.definition_kind === kind); },
    get(kind, name) { return byKey.get(key(kind, name)); },
    has(kind, name) { return byKey.has(key(kind, name)); },
    label(kind, name) {
      const value = byKey.get(key(kind, name));
      return value?.label || String(name || '').trim().toLowerCase().replaceAll('_', ' ')
        .replace(/\b\w/g, (character) => character.toUpperCase());
    }
  };
}
