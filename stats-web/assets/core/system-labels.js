const names = new Map();
const MAX_NAMES = 1000;

function text(value) {
  return typeof value === 'string' ? value.trim() : '';
}

function systemKey(row) {
  return text(row?.radio_system_key) || text(row?.system_key) || text(row?.radioSystemKey) ||
    (/^(?:p25|dmr|nxdn-[cd]):/i.test(text(row?.key)) ? text(row.key) : '');
}

function knownSystemNameForKey(key) {
  return names.get(text(key).toLowerCase())?.name || '';
}

function configuredSystemName(row) {
  const captured = text(row?.system);
  const capturedCallName = row?.call_id || row?.playback_target;
  const direct = text(row?.system_name) || text(row?.radio_system_name) || text(row?.systemName) ||
    (systemKey(row) && text(row?.key) ? text(row?.name) : '') ||
    (captured && (capturedCallName || !/^(?:[0-9a-f]+|(?:p25|dmr|nxdn-[cd]):.*)$/i.test(captured)) ? captured : '');
  if (direct) return direct;
  const configured = Array.isArray(row?.system_names) ? row.system_names : [];
  const distinct = new Map();
  configured.forEach((value) => {
    const name = text(value);
    if (name && !distinct.has(name.toLowerCase())) distinct.set(name.toLowerCase(), name);
  });
  if (distinct.size) return [...distinct.values()].sort((a, b) => a.localeCompare(b)).join(' / ');
  return '';
}

function systemName(row) {
  if (typeof row === 'string') return knownSystemNameForKey(row);
  const cached = names.get(systemKey(row).toLowerCase());
  if (row?.system_name_source === 'catalog' && cached && !cached.catalog) return cached.name;
  return configuredSystemName(row) || cached?.name || '';
}

// A catalog hint cannot outrank an explicit label or a configured home name already returned by the receiver.
function homeSystemName(row, prefix = '', key = '') {
  const base = prefix ? `${prefix}_` : '';
  const direct = text(row?.[`${base}home_system_name`]);
  const home = row?.[`${base}home_system`];
  const homeKey = text(key) || systemKey(home) || text(row?.[`${base}home_radio_system_key`]) ||
    text(row?.[`${base}home_system_key`]);
  const source = text(row?.[`${base}home_system_name_source`]);
  const catalog = source === 'catalog' || !source && home?.system_name_source === 'catalog' &&
    (!direct || direct === configuredSystemName(home));
  if (direct && !catalog) return direct;
  const nested = configuredSystemName(home);
  if (nested && home?.system_name_source !== 'catalog') return nested;
  const cached = names.get(homeKey.toLowerCase());
  if (cached && !cached.catalog) return cached.name;
  return direct || systemName(home) || cached?.name || text(row?.[`${base}home_catalog_name`]) ||
    (!prefix ? text(row?.catalog_name) : '');
}

function hex(value, width) {
  if (value === null || value === undefined || value === '') return '';
  const number = Number(value);
  return Number.isSafeInteger(number) && number >= 0 ? number.toString(16).toUpperCase().padStart(width, '0') : '';
}

function title(value) {
  return text(value).toLowerCase().replace(/(^|[ _-])\w/g, (part) => part.toUpperCase()).replace(/_/g, ' ');
}

function systemIdentity(row) {
  const key = typeof row === 'string' ? text(row) : systemKey(row);
  let match = /^p25:([0-9a-f]{1,5}):([0-9a-f]{1,3})$/i.exec(key);
  if (match) return `${match[1].toUpperCase().padStart(5, '0')}-${match[2].toUpperCase().padStart(3, '0')}`;
  match = /^dmr:tier3:([^:]+):(\d+)$/i.exec(key);
  if (match) return `DMR Tier III · ${title(match[1])} model · Network ${match[2]}`;
  match = /^nxdn-c:([^:]+):(\d+)$/i.exec(key);
  if (match && match[1].toLowerCase() !== 'channel') return `NXDN Type-C · ${title(match[1])} · System ${match[2]}`;
  if (/^(dmr|nxdn-[cd]):channel:/i.test(key)) return `${key.toLowerCase().startsWith('dmr:') ? 'DMR' : 'NXDN'} saved channel scope`;
  if (typeof row !== 'object' || !row) return key;
  const protocol = text(row.protocol || row.decoder).toUpperCase();
  if (row.wacn != null || protocol.startsWith('P25')) {
    return [hex(row.wacn, 5), hex(row.system_id ?? row.sysid, 3)].filter(Boolean).join('-');
  }
  if (protocol === 'DMR' && row.network_id != null) {
    return ['DMR Tier III', row.model ? `${title(row.model)} model` : '', `Network ${row.network_id}`].filter(Boolean).join(' · ');
  }
  if (protocol === 'NXDN' && row.system_id != null) {
    return ['NXDN Type-C', title(row.location_category), `System ${row.system_id}`].filter(Boolean).join(' · ');
  }
  return key;
}

function systemLabel(row) {
  return systemName(row) || systemIdentity(row);
}

// Cache only labels already returned by an authorized read. No additional inventory request is needed.
function rememberSystemNames(value) {
  const pending = [value];
  let visited = 0;
  while (pending.length && visited++ < 10000) {
    const row = pending.pop();
    if (!row || typeof row !== 'object') continue;
    if (Array.isArray(row)) {
      pending.push(...row.slice(0, 1000));
      continue;
    }
    const key = systemKey(row).toLowerCase();
    if (key) {
      const name = configuredSystemName(row);
      if (name) {
        const catalog = row.system_name_source === 'catalog';
        if (!catalog || !names.has(key) || names.get(key).catalog) {
          names.delete(key);
          names.set(key, { name, catalog });
          if (names.size > MAX_NAMES) names.delete(names.keys().next().value);
        }
      } else if (Object.hasOwn(row, 'system_name')) names.delete(key);
    }
    Object.values(row).forEach((child) => {
      if (child && typeof child === 'object') pending.push(child);
    });
  }
  return value;
}

export { systemName, homeSystemName, systemLabel, systemIdentity, knownSystemNameForKey, rememberSystemNames };
