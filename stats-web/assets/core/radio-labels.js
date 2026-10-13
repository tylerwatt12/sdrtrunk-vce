function integer(value, minimum, maximum) {
  if ((typeof value !== 'number' && typeof value !== 'string') ||
      (typeof value === 'string' && !value.trim())) return null;
  const number = Number(value);
  return Number.isInteger(number) && number >= minimum && number <= maximum ? number : null;
}

function text(value) {
  return typeof value === 'string' ? value.trim() : '';
}

function p25ServingSystemKey(row, prefix = '') {
  if (row && Object.prototype.hasOwnProperty.call(row, 'serving_system_key')) return text(row.serving_system_key);
  const explicit = text(row?.radio_system_key) || text(row?.system_key) ||
    text(row?.playback_target?.radio_system_key);
  if (explicit) return explicit;
  const reference = prefix ? row?.[`${prefix}_entity_ref`] : row?.entity_ref;
  return text(reference?.kind).toLowerCase() === 'radio' ? text(reference.radio_system_key) : '';
}

function parseP25RadioIdentifier(value) {
  const match = /^([0-9a-f]{5})\.([0-9a-f]{3})\.([0-9]+)(?: \(Working ID ([0-9]+)\))?$/i.exec(text(value));
  if (!match) return null;
  const subscriberId = integer(match[3], 1, 0xFFFFFC);
  const workingId = match[4] === undefined ? null : integer(match[4], 1, 0xFFFFFC);
  if (subscriberId === null || match[4] !== undefined && workingId === null) return null;
  return { canonical_identity: { wacn: parseInt(match[1], 16), system_id: parseInt(match[2], 16),
    subscriber_id: subscriberId }, observed_working_id: workingId };
}

function foreignP25System(servingSystemKey, wacn, systemId) {
  const serving = /^p25:([0-9a-f]{1,5}):([0-9a-f]{1,3})$/i.exec(text(servingSystemKey));
  return Boolean(serving && (parseInt(serving[1], 16) !== wacn || parseInt(serving[2], 16) !== systemId));
}

function formatP25RadioIdentifier(identity, { servingSystemKey = '', homeSystemName = '', workingId = null } = {}) {
  const wacn = integer(identity?.wacn, 0, 0xFFFFF);
  const systemId = integer(identity?.system_id, 0, 0xFFF);
  const subscriberId = integer(identity?.subscriber_id, 1, 0xFFFFFC);
  if (wacn === null || systemId === null || subscriberId === null) return '';

  const observedId = integer(workingId, 1, 0xFFFFFC);
  const differentWorkingId = observedId !== null && observedId !== subscriberId;
  const name = text(homeSystemName);
  const fullIdentity = `${wacn.toString(16).toUpperCase().padStart(5, '0')}.` +
    `${systemId.toString(16).toUpperCase().padStart(3, '0')}.${subscriberId}`;
  const label = !foreignP25System(servingSystemKey, wacn, systemId) ? String(subscriberId) :
    name ? `${name} · ${subscriberId}` : fullIdentity;
  return differentWorkingId ? `${label} (Working ID ${observedId})` : label;
}

function formatP25GroupIdentifier(identity, { servingSystemKey = '', homeSystemName = '', localId = null } = {}) {
  const wacn = integer(identity?.wacn, 0, 0xFFFFF);
  const systemId = integer(identity?.system_id, 0, 0xFFF);
  const talkgroupId = integer(identity?.talkgroup_id, 1, 0xFFFE);
  if (wacn === null || systemId === null || talkgroupId === null) return '';
  const observed = integer(localId, 1, 0xFFFE);
  const full = `${wacn.toString(16).toUpperCase().padStart(5, '0')}.` +
    `${systemId.toString(16).toUpperCase().padStart(3, '0')}.${talkgroupId}`;
  const name = text(homeSystemName);
  const label = !foreignP25System(servingSystemKey, wacn, systemId) ? String(talkgroupId) :
    name ? `${name} · ${talkgroupId}` : full;
  return observed !== null && observed !== talkgroupId ? `${label} (Local TG ID ${observed})` : label;
}

// Complete identity keys are typed facts. Unqualified keys and arbitrary dotted text are not home identities.
function p25IdentityFacts(row, prefix = '', kind = 'radio') {
  const field = name => row?.[prefix ? `${prefix}_${name}` : name];
  const supplied = name => row && Object.prototype.hasOwnProperty.call(row, prefix ? `${prefix}_${name}` : name);
  const radio = kind === 'radio';
  const form = text(field('form') || field('kind') || field('group_identity_kind')).toLowerCase();
  if (radio && ['talkgroup', 'patch_group'].includes(form) || !radio && form === 'radio') return null;
  if (radio && field('canonical_group_identity') && !field('canonical_identity')) return null;
  if (!radio && field('canonical_identity') && !field('canonical_group_identity')) return null;
  const nested = field(radio ? 'canonical_identity' : 'canonical_group_identity');
  const identity = { wacn: nested?.wacn ?? field('canonical_wacn') ?? field('home_wacn'),
    system_id: nested?.system_id ?? field('canonical_system_id') ?? field('home_system_id'),
    [radio ? 'subscriber_id' : 'talkgroup_id']: nested?.[radio ? 'subscriber_id' : 'talkgroup_id'] ??
      field(radio ? 'canonical_subscriber_id' : 'canonical_talkgroup_id') ?? field('home_id') ?? field('home_identity_id') };
  const valid = candidate => radio ? Boolean(formatP25RadioIdentifier(candidate)) :
    Boolean(formatP25GroupIdentifier(candidate));
  let canonical = valid(identity) ? identity : null;
  if (!canonical) {
    const key = text(field('identity_key')) || text(field(radio ? 'radio_identity_key' : 'group_identity_key')) ||
      text(field('entity_ref')?.identity_key);
    const match = /^v1-([rgp])-([0-9a-f]{5})-([0-9a-f]{3})-(\d+)$/i.exec(key);
    if (match && (radio ? match[1].toLowerCase() === 'r' : ['g', 'p'].includes(match[1].toLowerCase()))) {
      const candidate = { wacn: parseInt(match[2], 16), system_id: parseInt(match[3], 16),
        [radio ? 'subscriber_id' : 'talkgroup_id']: Number(match[4]) };
      if (valid(candidate)) canonical = candidate;
    }
  }
  if (!canonical) return null;
  const observed = radio ? field('observed_working_id') ?? field('working_subscriber_id') ??
    (field('home_id') != null || field('home_identity_id') != null ? field('id') : null) :
    supplied('observed_local_id') ? field('observed_local_id') :
      (field('home_id') != null || field('home_identity_id') != null ? field('id') : null);
  return { canonical, observedId: observed, homeSystemKey: `p25:${Number(canonical.wacn).toString(16).padStart(5, '0')}:` +
    Number(canonical.system_id).toString(16).padStart(3, '0') };
}

function p25IdentifierText(row, value, { prefix = '', kind = 'radio', homeSystemName = '' } = {}) {
  const facts = p25IdentityFacts(row, prefix, kind);
  if (!facts) return '';
  const field = name => row?.[prefix ? `${prefix}_${name}` : name];
  const canonicalId = facts.canonical[kind === 'radio' ? 'subscriber_id' : 'talkgroup_id'];
  const owner = field('id') ?? field('native_id') ?? field(kind === 'radio' ? 'radio_native_id' : 'group_native_id') ??
    field(kind === 'radio' ? 'radio_id' : 'group_identity_id');
  // A directory row's own identity must never replace a different peer/member passed to a cell renderer.
  if (value != null && Number(value) !== Number(owner) &&
      Number(value) !== Number(canonicalId) && Number(value) !== Number(facts.observedId)) return '';
  const name = homeSystemName || field('home_system_name');
  const options = { servingSystemKey: p25ServingSystemKey(row, prefix), homeSystemName: name,
    workingId: facts.observedId, localId: facts.observedId };
  return kind === 'radio' ? formatP25RadioIdentifier(facts.canonical, options) :
    formatP25GroupIdentifier(facts.canonical, options);
}

// Legacy decoder text is rewritten only when its explicit ISSI/ROAM grammar supplies a complete home tuple.
function formatP25QualifiedIdentifierText(value, servingSystemKey = '', homeSystemName = () => '') {
  return String(value ?? '').replace(/\b(ISSI|ROAM) (?:(\d+)\()?(\d+)\.(\d+)\.(\d+)(\))?(?=$|[^a-zA-Z0-9_.])/g,
    (matched, kind, local, homeWacn, homeSystem, homeId, closing) => {
      const wacn = integer(homeWacn, 0, 0xFFFFF);
      const systemId = integer(homeSystem, 0, 0xFFF);
      // A bare ISSI ID beyond the group-address range is radio text. An ISSI
      // local(home) tuple remains a group mapping and must keep its 16-bit bounds.
      const radio = kind === 'ROAM' || local === undefined && Number(homeId) > 0xFFFE;
      const identityId = integer(homeId, 1, radio ? 0xFFFFFF : 0xFFFE);
      const localId = local === undefined ? null : integer(local, 0, radio ? 0xFFFFFC : 0xFFFE);
      if (wacn === null || systemId === null || identityId === null ||
          local !== undefined && localId === null || Boolean(local !== undefined) !== Boolean(closing)) return matched;
      const key = `p25:${wacn.toString(16).padStart(5, '0')}:${systemId.toString(16).padStart(3, '0')}`;
      const options = { servingSystemKey, homeSystemName: typeof homeSystemName === 'function' ? homeSystemName(key) : '',
        workingId: localId, localId };
      if (!radio) return formatP25GroupIdentifier({ wacn, system_id: systemId, talkgroup_id: identityId }, options);
      const formatted = formatP25RadioIdentifier({ wacn, system_id: systemId, subscriber_id: identityId }, options);
      if (formatted) return formatted;
      // Reserved 24-bit diagnostic values still obey the display rule; they do
      // not become typed subscriber facts or navigable identities.
      const name = text(options.homeSystemName);
      const label = !foreignP25System(servingSystemKey, wacn, systemId) ? String(identityId) :
        name ? `${name} · ${identityId}` :
          `${wacn.toString(16).toUpperCase().padStart(5, '0')}.${systemId.toString(16).toUpperCase().padStart(3, '0')}.${identityId}`;
      return localId !== null && localId > 0 && localId !== identityId ? `${label} (Working ID ${localId})` : label;
    });
}

export { formatP25RadioIdentifier, formatP25GroupIdentifier, p25IdentityFacts, p25IdentifierText, formatP25QualifiedIdentifierText, p25ServingSystemKey, parseP25RadioIdentifier };
