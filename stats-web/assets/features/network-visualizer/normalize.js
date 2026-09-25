'use strict';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const ANALOG_PROTOCOLS = new Set(['am', 'nbfm']);
const AFFILIATION_SUCCESS = new Set(['success', 'successful', 'accepted', 'confirmed']);
const AFFILIATION_EVIDENCE = new Set([
  'affiliation',
  'successful_affiliation',
  'group_affiliation_response',
  'group_affiliation_query_response'
]);
const REJECTED_AFFILIATION_EVIDENCE = /deni|reject|register|grant|alias|last.?seen/i;
const INACTIVE_SNAPSHOT_STATUSES = new Set(['', 'IDLE', 'STOPPED', 'PENDING', 'GRANT', 'GRANTED', 'QUEUED']);

function text(value, maximum = 256) {
  const normalized = typeof value === 'string' ? value.trim() :
    ((typeof value === 'number' && Number.isFinite(value)) || typeof value === 'bigint' ? String(value) : '');
  return normalized ? normalized.slice(0, maximum) : '';
}

function timestamp(value, fallback) {
  const numeric = Number(value);
  return Number.isSafeInteger(numeric) && numeric > 0 ? numeric : fallback;
}

function optionalInteger(value) {
  if (value === null || value === undefined || value === '') return null;
  const numeric = Number(value);
  return Number.isSafeInteger(numeric) && numeric >= 0 ? numeric : null;
}

function configurationId(value) {
  const normalized = text(value, 64).toLowerCase();
  return UUID.test(normalized) ? normalized : '';
}

function opaqueKey(value) {
  return text(value, 256);
}

function observedLocalId(value) {
  if (Number.isSafeInteger(value) && value >= 0) return String(value);
  return text(value, 128);
}

function p25DisplayId(value, width) {
  if (Number.isSafeInteger(value) && value >= 0) return value.toString(16).toUpperCase().padStart(width, '0');
  const supplied = text(value, width);
  return /^[0-9a-f]+$/i.test(supplied) ? supplied.toUpperCase().padStart(width, '0') : supplied;
}

function protocolName(value) {
  const protocol = text(value, 32).toLowerCase().replace(/[^a-z0-9_-]/g, '');
  if (protocol === 'apco25' || protocol === 'apco25_phase2') return 'p25';
  return protocol;
}

function isAnalogProtocol(value) {
  return ANALOG_PROTOCOLS.has(protocolName(value));
}

function normalizedSite(raw = {}) {
  const candidate = raw.site && typeof raw.site === 'object' && !Array.isArray(raw.site) ? raw.site : raw;
  const result = {
    configurationId: configurationId(candidate.configuration_id ?? raw.configuration_id),
    rfssId: optionalInteger(candidate.rfss_id ?? candidate.rfss ?? raw.rfss_id ?? raw.rfss),
    siteId: optionalInteger(candidate.site_id ?? candidate.site ?? raw.site_id),
    nac: optionalInteger(candidate.nac ?? raw.nac),
    timeslot: optionalInteger(candidate.timeslot ?? raw.timeslot),
    channelName: text(candidate.channel_name ?? raw.channel_name, 128),
    siteName: text(candidate.site_name ?? raw.site_name, 128)
  };
  return Object.freeze(result);
}

function scopeKeyFor(raw = {}) {
  const supplied = opaqueKey(raw.comparison_scope_key ?? raw.scope_key);
  if (supplied) return supplied;
  const site = normalizedSite(raw);
  if (!site.configurationId) return '';
  return [site.configurationId, site.rfssId ?? 'x', site.siteId ?? 'x', site.timeslot ?? 'x'].join(':');
}

function universeKeyFor(value = {}) {
  const systemKey = opaqueKey(value.radioSystemKey ?? value.radio_system_key);
  if (systemKey) return `system:${systemKey}`;
  const channelKey = configurationId(value.configurationId ?? value.configuration_id);
  return channelKey ? `channel:${channelKey}` : '';
}

function groupKeyFor(universeKey, identityKey, kind = 'talkgroup', channelKey = '') {
  const owner = opaqueKey(universeKey);
  if (!owner) return '';
  if (kind === 'channel') {
    const configuration = configurationId(channelKey);
    return configuration ? `${owner}|channel:${configuration}` : '';
  }
  const identity = opaqueKey(identityKey);
  return identity ? `${owner}|${kind === 'patch_group' ? 'patch' : 'group'}:${identity}` : '';
}

function radioKeyFor(universeKey, identityKey) {
  const owner = opaqueKey(universeKey);
  const identity = opaqueKey(identityKey);
  return owner && identity ? `${owner}|radio:${identity}` : '';
}

function normalizeEntity(raw, kind) {
  const candidate = raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : {};
  const suppliedReference = candidate.entity_ref ?? candidate.entityRef;
  const reference = suppliedReference && typeof suppliedReference === 'object' ? suppliedReference : candidate;
  const suppliedKind = text(reference.kind ?? candidate.kind, 32);
  if (suppliedKind && suppliedKind !== kind) return null;
  const identityKey = opaqueKey(candidate.identity_key ?? candidate.identityKey ??
    reference.identity_key ?? reference.identityKey);
  if (!identityKey) return null;
  const radioSystemKey = opaqueKey(candidate.radio_system_key ?? candidate.radioSystemKey ??
    reference.radio_system_key ?? reference.radioSystemKey);
  return Object.freeze({
    identityKey,
    observedLocalId: observedLocalId(candidate.observed_local_id ?? candidate.observedLocalId ??
      reference.observed_local_id ?? reference.observedLocalId ?? candidate.native_id ?? reference.native_id),
    radioSystemKey,
    label: text(candidate.label ?? candidate.alias_name ?? candidate.alias ?? candidate.name ??
      reference.label ?? reference.alias_name ?? reference.alias ?? reference.name, 128),
    displayId: text(candidate.display_id ?? candidate.displayId ?? candidate.native_id ?? candidate.id ??
      reference.display_id ?? reference.displayId ?? reference.native_id ?? reference.id, 64),
    entityRef: suppliedReference ? Object.freeze({ kind, radio_system_key: radioSystemKey,
      identity_key: identityKey }) : null,
    kind
  });
}

function inlineEntity(raw, prefix, kind) {
  const identityKey = opaqueKey(raw[`${prefix}_identity_key`]);
  if (!identityKey) return null;
  return Object.freeze({
    identityKey,
    observedLocalId: observedLocalId(raw[`${prefix}_observed_local_id`] ?? raw[`${prefix}_id`]),
    radioSystemKey: opaqueKey(raw.radio_system_key),
    label: text(raw[`${prefix}_label`] ?? raw[`${prefix}_alias`], 128),
    displayId: text(raw[`${prefix}_id`], 64),
    entityRef: null,
    kind
  });
}

function groupEntity(raw) {
  const suppliedKind = text(raw.group_kind ?? raw.target_kind ?? raw.group?.kind ?? raw.target?.kind, 32).toLowerCase();
  const kind = suppliedKind === 'patch_group' ? 'patch_group' : 'talkgroup';
  return normalizeEntity(raw.group ?? raw.target ?? raw.target_entity_ref, kind) || inlineEntity(raw, 'group', kind) ||
    inlineEntity(raw, 'target', kind);
}

function radioEntity(raw) {
  return normalizeEntity(raw.radio ?? raw.source ?? raw.source_entity_ref, 'radio') || inlineEntity(raw, 'radio', 'radio') ||
    inlineEntity(raw, 'source', 'radio');
}

function baseContext(raw, observedAtMs) {
  const protocol = protocolName(raw.protocol);
  const site = normalizedSite(raw);
  const radioSystemKey = opaqueKey(raw.radio_system_key);
  const configuration = site.configurationId || configurationId(raw.configuration_id);
  const universeKey = universeKeyFor({ radioSystemKey, configurationId: configuration });
  if (!universeKey) return null;
  return {
    observedAtMs,
    protocol,
    analog: isAnalogProtocol(protocol),
    radioSystemKey,
    configurationId: configuration,
    universeKey,
    systemName: text(raw.system_name, 128),
    channelName: site.channelName,
    siteName: site.siteName,
    wacn: p25DisplayId(raw.wacn ?? raw.site?.wacn, 5),
    systemId: p25DisplayId(raw.system_id ?? raw.sysid ?? raw.site?.system_id, 3),
    site
  };
}

function successfulAffiliation(raw, kind) {
  const evidence = text(raw.evidence_type ?? raw.evidence, 64).toLowerCase();
  const outcomeAccepted = raw.successful === true ||
    AFFILIATION_SUCCESS.has(text(raw.outcome ?? raw.result ?? raw.status, 32).toLowerCase());
  if (evidence === 'registration') {
    return kind === 'affiliation_observed' && outcomeAccepted && Boolean(groupEntity(raw));
  }
  if (!evidence || REJECTED_AFFILIATION_EVIDENCE.test(evidence) || !AFFILIATION_EVIDENCE.has(evidence)) return false;
  return outcomeAccepted;
}

function normalizeAffiliation(raw, observedAtMs, kind = '') {
  if (!successfulAffiliation(raw, kind)) return null;
  const context = baseContext(raw, observedAtMs);
  const radio = radioEntity(raw);
  const group = groupEntity(raw);
  const scopeKey = scopeKeyFor(raw);
  if (!context || !radio || !group || group.kind === 'patch_group' || !scopeKey) {
    return null;
  }
  if ((radio.radioSystemKey && radio.radioSystemKey !== context.radioSystemKey) ||
      (group.radioSystemKey && group.radioSystemKey !== context.radioSystemKey)) return null;
  return Object.freeze({
    kind: 'affiliation',
    eventId: opaqueKey(raw.event_id),
    evidenceType: text(raw.evidence_type ?? raw.evidence, 64).toLowerCase(),
    sequence: optionalInteger(raw.sequence ?? raw.order),
    scopeKey,
    radio,
    group,
    ...context
  });
}

function normalizePresenceRemoval(raw, observedAtMs, authoritative = false) {
  if (!authoritative && (raw.supported !== true || raw.explicit !== true)) return null;
  const context = baseContext(raw, observedAtMs);
  const radio = radioEntity(raw);
  const scopeKey = scopeKeyFor(raw);
  if (!context || !radio || !scopeKey || (radio.radioSystemKey && radio.radioSystemKey !== context.radioSystemKey)) {
    return null;
  }
  return Object.freeze({
    kind: 'presence_remove',
    eventId: opaqueKey(raw.event_id),
    sequence: optionalInteger(raw.sequence ?? raw.order),
    scopeKey,
    radio,
    reason: text(raw.reason, 64),
    ...context
  });
}

function normalizeIdentityReconciliation(raw, observedAtMs) {
  const suppliedFromUniverseKey = opaqueKey(raw.from_universe_key);
  const fromUniverseKey = suppliedFromUniverseKey.startsWith('system:') ||
    suppliedFromUniverseKey.startsWith('channel:') ? suppliedFromUniverseKey :
    universeKeyFor({ radioSystemKey: suppliedFromUniverseKey });
  const fromRadioIdentityKey = opaqueKey(raw.from_radio_identity_key);
  const context = baseContext(raw, observedAtMs);
  const radio = radioEntity(raw.to && typeof raw.to === 'object' ? { ...raw, ...raw.to } : raw);
  const group = groupEntity(raw.to && typeof raw.to === 'object' ? { ...raw, ...raw.to } : raw);
  if (!fromUniverseKey || !fromRadioIdentityKey || !context || !context.radioSystemKey || !radio) return null;
  if (radio.radioSystemKey && radio.radioSystemKey !== context.radioSystemKey) return null;
  return Object.freeze({
    kind: 'identity_reconciled',
    eventId: opaqueKey(raw.event_id),
    fromUniverseKey,
    fromRadioIdentityKey,
    radio,
    group,
    ...context
  });
}

function callPhase(raw, kind) {
  const state = text(raw.transmission_state ?? raw.phase ?? raw.state, 32).toLowerCase();
  if (kind.endsWith('_end') || ['ended', 'complete', 'completed', 'stopped'].includes(state)) return 'end';
  if (['grant', 'granted', 'pending', 'queued'].includes(state) || kind === 'grant') return 'granted';
  if (kind.endsWith('_start') || ['active', 'transmitting', 'started'].includes(state)) return 'active';
  if (kind.endsWith('_update') || state === 'update') return 'update';
  return raw.transmitting === true ? 'active' : null;
}

function normalizeCall(raw, kind, observedAtMs) {
  const context = baseContext(raw, observedAtMs);
  if (!context) return null;
  const phase = callPhase(raw, kind);
  if (!phase) return null;
  const legId = opaqueKey(raw.call_leg_id ?? raw.call_id);
  if (!legId) return null;
  const resourceContext = opaqueKey(raw.resource_context_key ?? raw.resource_id ?? raw.channel_resource, 128);
  // The saved channel and real leg/resource context remain stable when a provisional channel-scoped identity is later
  // proven to belong to a canonical radio system. Universe identity deliberately is not part of call ownership.
  const callScope = context.configurationId ? `channel:${context.configurationId}` : context.universeKey;
  const callKey = [callScope, legId, resourceContext || 'resource', context.site.timeslot ?? 'x'].join('|call:');
  let group = groupEntity(raw);
  let radio = radioEntity(raw);
  if (context.analog) {
    group = Object.freeze({
      identityKey: context.configurationId,
      radioSystemKey: '',
      label: context.channelName || text(raw.target_label, 128),
      displayId: '',
      entityRef: context.configurationId ? Object.freeze({ kind: 'channel', key: context.configurationId }) : null,
      kind: 'channel'
    });
    // Analog activity never promotes decoder display text into a subscriber identity.
    radio = raw.source_identity_supported === true ? radio : null;
  }
  if (context.radioSystemKey) {
    if ((radio?.radioSystemKey && radio.radioSystemKey !== context.radioSystemKey) ||
        (group?.radioSystemKey && group.radioSystemKey !== context.radioSystemKey)) return null;
  }
  const groupKind = group?.kind || group?.entityRef?.kind || (context.analog ? 'channel' : 'talkgroup');
  const groupKey = group ? groupKeyFor(context.universeKey, group.identityKey, groupKind,
    context.configurationId) : '';
  const radioKey = radio ? radioKeyFor(context.universeKey, radio.identityKey) : '';
  return Object.freeze({
    kind: 'call',
    eventId: opaqueKey(raw.event_id),
    phase,
    callKey,
    legId,
    resourceContext,
    group,
    groupKey,
    radio,
    radioKey,
    encrypted: raw.encrypted === true || Boolean(text(raw.encryption, 64)),
    endProven: raw.end_proven === true || raw.tx_end_certain === true,
    ...context
  });
}

function normalizeObservation(raw, receivedAtMs = Date.now()) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const kind = text(raw.kind ?? raw.type, 64).toLowerCase();
  const observedAtMs = timestamp(raw.observed_at_ms ?? raw.timestamp_ms, receivedAtMs);
  if (kind === 'affiliation' || kind === 'affiliation_success' || kind === 'affiliation_observed' ||
      kind === 'presence_observed') return normalizeAffiliation(raw, observedAtMs, kind);
  if (kind === 'presence_cleared') return normalizePresenceRemoval(raw, observedAtMs, true);
  if (kind === 'presence_remove' || kind === 'deaffiliation') return normalizePresenceRemoval(raw, observedAtMs);
  if (kind === 'identity_reconciled') return normalizeIdentityReconciliation(raw, observedAtMs);
  if (['call', 'call_start', 'call_update', 'call_end', 'tx_start', 'tx_update', 'tx_end', 'grant']
    .includes(kind)) return normalizeCall(raw, kind, observedAtMs);
  return null;
}

function snapshotRowActive(row) {
  const txState = text(row?.tx_state, 32).toLowerCase();
  return txState === 'active';
}

function snapshotRowPending(row) {
  const txState = text(row?.tx_state, 32).toLowerCase();
  if (txState) return txState === 'pending';
  const activation = Number(row?.activation_order);
  const status = text(row?.status, 48).toUpperCase();
  const role = text(row?.role, 32).toUpperCase();
  const tags = new Set(Array.isArray(row?.tags) ? row.tags.map((tag) => text(tag, 48).toUpperCase()) : []);
  if (!Number.isSafeInteger(activation) || activation <= 0 || INACTIVE_SNAPSHOT_STATUSES.has(status)) return false;
  if (role === 'CONTROL' || tags.has('CURRENT_CONTROL') || tags.has('ALTERNATE_CONTROL')) return false;
  // Traffic rows can be created by a grant before AudioCall establishes actual RF activity.
  // Preserve that state as granted/pending without lighting it as a transmission.
  return role === 'TRAFFIC' || tags.has('VOICE') || tags.has('DATA') ||
    /CALL|ACTIVE|VOICE|ENCRYPT/.test(status);
}

function normalizeSnapshotRow(table, row, receivedAtMs = Date.now()) {
  const tableId = opaqueKey(table?.table_id);
  const rowKey = opaqueKey(row?.key);
  const activationOrder = optionalInteger(row?.activation_order) ?? 0;
  const configuration = configurationId(row?.configuration_id ?? table?.configuration_id);
  if (!tableId || !rowKey || !configuration) return null;
  const protocol = protocolName(row?.protocol ?? table?.protocol ?? row?.decoder);
  const sourceReference = row?.source_entity_ref;
  const targetReference = row?.target_entity_ref;
  const sourceSystem = opaqueKey(sourceReference?.radio_system_key);
  const targetSystem = opaqueKey(targetReference?.radio_system_key);
  if (sourceSystem && targetSystem && sourceSystem !== targetSystem) return null;
  const radioSystemKey = sourceSystem || targetSystem || opaqueKey(row?.radio_system_key ?? table?.radio_system_key);
  const targetForm = text(row?.target_form, 32).toLowerCase();
  const targetKind = text(targetReference?.kind, 32) ||
    (targetForm === 'patch_group' ? 'patch_group' : (targetForm === 'talkgroup' ? 'talkgroup' : ''));
  const source = normalizeEntity({ entity_ref: sourceReference, identity_key: row?.source_identity_key,
    observed_local_id: row?.source_id, radio_system_key: radioSystemKey,
    label: row?.source_alias || row?.talker_alias, native_id: row?.source_id }, 'radio');
  const target = ['talkgroup', 'patch_group'].includes(targetKind) ? normalizeEntity({ entity_ref: targetReference,
    identity_key: row?.target_identity_key, observed_local_id: row?.target_id,
    radio_system_key: radioSystemKey, label: row?.target_alias, native_id: row?.target_id }, targetKind) : null;
  const analog = isAnalogProtocol(protocol);
  const txState = text(row?.tx_state, 32).toLowerCase();
  const active = snapshotRowActive(row);
  const pending = !active && snapshotRowPending(row);
  const phase = pending ? 'granted' : (active ? 'active' : 'ended');
  const observedAtMs = timestamp(row?.tx_last_observed_at_ms ?? row?.tx_observed_at_ms ??
    row?.tx_start_ms ?? row?.tx_started_at_ms, receivedAtMs);
  const callLegId = opaqueKey(row?.call_leg_id) || `snapshot:${tableId}:${rowKey}:${activationOrder}`;
  const burstStartedAtMs = timestamp(row?.tx_burst_started_at_ms, 0);
  const burstGeneration = optionalInteger(row?.tx_burst_generation) ?? 0;
  const burstIdentity = burstGeneration > 0 ? `generation:${burstGeneration}` :
    (burstStartedAtMs > 0 ? `started:${burstStartedAtMs}` : 'unknown');
  const raw = {
    kind: phase === 'granted' ? 'grant' : (phase === 'active' ? 'call_update' : 'call_end'),
    transmission_state: phase,
    call_leg_id: callLegId,
    resource_context_key: `${tableId}:${rowKey}`,
    configuration_id: configuration,
    radio_system_key: radioSystemKey,
    protocol,
    system_name: table?.system_name,
    site: row?.site ?? table?.site,
    wacn: row?.wacn ?? table?.wacn,
    system_id: row?.system_id ?? table?.system_id,
    channel_name: row?.channel_name ?? table?.channel_name,
    site_name: table?.site_name,
    timeslot: row?.timeslot,
    source: source,
    target,
    group_kind: targetKind,
    target_label: row?.target_alias || row?.target_id,
    encrypted: Boolean(text(row?.encryption_details, 64)),
    observed_at_ms: observedAtMs,
    end_proven: row?.tx_end_proven === true || row?.tx_end_certain === true,
    source_identity_supported: !analog && Boolean(source)
  };
  const normalized = normalizeCall(raw, raw.kind, observedAtMs);
  return normalized ? Object.freeze({
    ...normalized,
    origin: 'channel_activity_snapshot',
    snapshotKey: `${tableId}|${rowKey}`,
    activationOrder,
    activationKey: `${callLegId}|burst:${burstIdentity}`,
    burstGeneration,
    burstStartedAtMs,
    txState: txState || (active ? 'active' : (pending ? 'pending' : 'ended')),
    txEndReason: text(row?.tx_end_reason, 64),
    snapshotActive: snapshotRowActive(row)
  }) : null;
}

function semanticFingerprint(event) {
  if (!event) return '';
  if (event.eventId) return `event:${event.eventId}`;
  if (event.kind === 'affiliation') {
    return ['affiliation', event.universeKey, event.radio.identityKey, event.scopeKey, event.group.identityKey,
      event.sequence ?? event.observedAtMs].join('|');
  }
  if (event.kind === 'presence_remove') {
    return ['presence-remove', event.universeKey, event.radio.identityKey, event.scopeKey,
      event.sequence ?? event.observedAtMs].join('|');
  }
  if (event.kind === 'call') {
    return ['call', event.callKey, event.phase, event.groupKey, event.radioKey, event.observedAtMs].join('|');
  }
  if (event.kind === 'identity_reconciled') {
    return ['identity-reconciled', event.fromUniverseKey, event.fromRadioIdentityKey, event.universeKey,
      event.radio.identityKey, event.observedAtMs].join('|');
  }
  return '';
}

export {
  configurationId,
  groupKeyFor,
  isAnalogProtocol,
  normalizeObservation,
  normalizeSnapshotRow,
  radioKeyFor,
  scopeKeyFor,
  semanticFingerprint,
  snapshotRowActive,
  snapshotRowPending,
  universeKeyFor
};
