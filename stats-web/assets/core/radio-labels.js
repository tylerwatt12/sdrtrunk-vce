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
  const explicit = text(row?.radio_system_key) || text(row?.system_key) ||
    text(row?.playback_target?.radio_system_key);
  if (explicit) return explicit;
  const reference = prefix ? row?.[`${prefix}_entity_ref`] : null;
  return text(reference?.kind).toLowerCase() === 'radio' ? text(reference.radio_system_key) : '';
}

function formatP25RadioIdentifier(identity, { servingSystemKey = '', homeSystemName = '', workingId = null } = {}) {
  const wacn = integer(identity?.wacn, 0, 0xFFFFF);
  const systemId = integer(identity?.system_id, 0, 0xFFF);
  const subscriberId = integer(identity?.subscriber_id, 1, 0xFFFFFC);
  if (wacn === null || systemId === null || subscriberId === null) return '';

  const serving = /^p25:([0-9a-f]{1,5}):([0-9a-f]{1,3})$/i.exec(text(servingSystemKey));
  const local = serving && parseInt(serving[1], 16) === wacn && parseInt(serving[2], 16) === systemId;
  const name = text(homeSystemName);
  const fullIdentity = `${wacn.toString(16).toUpperCase().padStart(5, '0')}.` +
    `${systemId.toString(16).toUpperCase().padStart(3, '0')}.${subscriberId}`;
  const label = local ? String(subscriberId) : name ? `${name} · ${subscriberId}` : fullIdentity;
  const observedId = integer(workingId, 1, 0xFFFFFC);
  return observedId !== null && observedId !== subscriberId ? `${label} (Working ID ${observedId})` : label;
}

export { formatP25RadioIdentifier, p25ServingSystemKey };
