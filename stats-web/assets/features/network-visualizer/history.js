'use strict';

import { systemName, homeSystemName, systemIdentity } from '../../core/system-labels.js?v=2';
import { formatP25RadioIdentifier, p25IdentityFacts, p25IdentifierText, p25ServingSystemKey } from '../../core/radio-labels.js?v=7';

const P25_HISTORY_ACTIONS = Object.freeze([
  'JOIN', 'LOGOUT', 'DENIAL', 'EMERGENCY', 'CHECK', 'PAGE', 'BUSY', 'QUEUED',
  'PATCH', 'PATCH_CREATE', 'PATCH_CANCEL'
]);
const P25_ROUTINE_ACTIONS = Object.freeze(['CALL', 'GRANT']);

const ACTIONS = new Set([...P25_HISTORY_ACTIONS, ...P25_ROUTINE_ACTIONS]);
const MAX_PRIOR_GROUPS = 3;
const SYSTEM_RADIUS = 300;
const GROUP_CLOUD_MARGIN = 76;
const GROUP_GRID_SPACING = 124;
const GROUP_GRID_JITTER = 6;


function finite(value, fallback = 0) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

function text(value) {
  return value === null || value === undefined ? '' : String(value).trim();
}

function actionOf(row) {
  return text(row?.action).toUpperCase();
}

function keyKind(key) {
  const value = text(key).toLowerCase();
  if (value.startsWith('v1-r-')) return 'radio';
  if (value.startsWith('v1-g-')) return 'talkgroup';
  if (value.startsWith('v1-p-')) return 'patch_group';
  return '';
}

function isP25Trunked(row) {
  return text(row?.protocol).toLowerCase() === 'p25' &&
    text(row?.channel_kind).toLowerCase() === 'trunked_site' &&
    Boolean(text(row?.radio_system_key));
}

function nativeId(key, explicit) {
  const value = Number(explicit);
  if (Number.isSafeInteger(value) && value >= 0) return value;
  const tail = text(key).split('-').at(-1);
  return /^\d+$/.test(tail) ? Number(tail) : null;
}

function hex(value, width) {
  const number = Number(value);
  return Number.isSafeInteger(number) && number >= 0 ? number.toString(16).toUpperCase().padStart(width, '0') : '';
}

function sourceRadioKey(row) {
  const key = text(row?.source_identity_key);
  return Number(row?.source_identity_kind_code) === 2 || keyKind(key) === 'radio' ? key : '';
}

function targetRadioKey(row) {
  const key = text(row?.target_identity_key);
  const kind = text(row?.target_kind).toLowerCase();
  return kind === 'radio' || Number(row?.target_identity_kind_code) === 2 || keyKind(key) === 'radio' ? key : '';
}

function targetGroupKey(row, allowPatch = true) {
  const key = text(row?.target_identity_key);
  const kind = keyKind(key);
  if (kind === 'talkgroup' || allowPatch && kind === 'patch_group') return key;
  const targetKind = text(row?.target_kind).toLowerCase();
  return targetKind === 'talkgroup' || allowPatch && targetKind === 'patch_group' ? key : '';
}

function systemLabel(row) {
  const supplied = systemName(row);
  const wacn = hex(row?.wacn, 5);
  const system = hex(row?.system_id, 3);
  const identity = wacn && system ? `${wacn}:${system}` : wacn || system;
  return supplied ? `${supplied}${identity ? ` · ${identity}` : ''}` : identity ? `P25 ${identity}` : 'P25 radio system';
}

function groupLabel(row, key) {
  const id = nativeId(key, row?.target_native_id ?? row?.target_id);
  const facts = p25IdentityFacts({ ...row, target_identity_key: key }, 'target', 'talkgroup');
  const label = facts ? p25IdentifierText({ ...row, target_identity_key: key }, id, {
    prefix: 'target', kind: 'talkgroup', homeSystemName: homeSystemName(row, 'target', facts.homeSystemKey) }) : '';
  return text(row?.target_alias_name) || text(row?.target_name) || label || String(id ?? 'Talkgroup');
}

function radioAlias(row, role = 'source') {
  return text(row?.[`${role}_alias_name`]) || text(row?.[`${role}_talker_alias`]);
}

function radioLabel(row, key, role = 'source') {
  const source = role === 'source';
  const alias = radioAlias(row, role);
  if (alias) return alias;
  const identity = row?.[`${role}_canonical_identity`] || {
    wacn: row?.[`${role}_canonical_wacn`], system_id: row?.[`${role}_canonical_system_id`],
    subscriber_id: row?.[`${role}_canonical_subscriber_id`]
  };
  if (formatP25RadioIdentifier(identity)) {
    const homeKey = `p25:${Number(identity.wacn).toString(16).padStart(5, '0')}:` +
      Number(identity.system_id).toString(16).padStart(3, '0');
    return formatP25RadioIdentifier(identity, {
      servingSystemKey: p25ServingSystemKey(row, role),
      homeSystemName: homeSystemName(row, role, homeKey),
      workingId: row?.[`${role}_observed_working_id`] ?? row?.[`${role}_working_subscriber_id`]
    });
  }
  return String(nativeId(key, source ? row?.source_native_id ?? row?.source_radio_id :
      row?.target_native_id ?? row?.target_id) ?? 'Radio');
}

function entityKey(systemKey, identityKey) {
  return `${systemKey}:${identityKey}`;
}

function stableHash(value) {
  let hash = 2166136261;
  for (const character of String(value || '')) {
    hash ^= character.charCodeAt(0);
    hash = Math.imul(hash, 16777619);
  }
  return hash >>> 0;
}

function unit(value) {
  let hash = stableHash(value);
  hash ^= hash >>> 16;
  hash = Math.imul(hash, 0x7feb352d);
  hash ^= hash >>> 15;
  hash = Math.imul(hash, 0x846ca68b);
  hash ^= hash >>> 16;
  return (hash >>> 0) / 0xffffffff;
}

function systemPosition(ordinal) {
  if (!ordinal) return { x: 0, y: 0, z: 0 };
  const angle = ordinal * Math.PI * (3 - Math.sqrt(5));
  const radius = 720 * Math.sqrt(ordinal);
  return { x: Math.cos(angle) * radius, y: ((ordinal % 5) - 2) * 105, z: Math.sin(angle) * radius };
}

function localPosition(key, minimum, maximum) {
  const vertical = unit(`${key}:y`) * 2 - 1;
  const angle = unit(`${key}:a`) * Math.PI * 2;
  const radius = minimum + Math.cbrt(unit(`${key}:r`)) * (maximum - minimum);
  const horizontal = Math.sqrt(Math.max(0, 1 - vertical * vertical));
  return { x: Math.cos(angle) * horizontal * radius, y: vertical * radius,
    z: Math.sin(angle) * horizontal * radius };
}

function groupSlot(system, ordinal) {
  while (system.groupSlots.length <= ordinal) {
    const shell = system.groupShell + 1;
    const slots = [];
    for (let x = -shell; x <= shell; x += 1) {
      for (let y = -shell; y <= shell; y += 1) {
        for (let z = -shell; z <= shell; z += 1) {
          const distance = Math.hypot(x, y, z);
          if (distance > shell || distance <= shell - 1) continue;
          slots.push({ x, y, z });
        }
      }
    }
    slots.sort((left, right) => stableHash(`${system.key}:${left.x}:${left.y}:${left.z}`) -
      stableHash(`${system.key}:${right.x}:${right.y}:${right.z}`));
    system.groupSlots.push(...slots);
    system.groupShell = shell;
  }
  return system.groupSlots[ordinal];
}

function groupPosition(system, key, ordinal) {
  const slot = groupSlot(system, ordinal);
  const x = slot.x * GROUP_GRID_SPACING + (unit(`${key}:jx`) * 2 - 1) * GROUP_GRID_JITTER;
  const y = slot.y * GROUP_GRID_SPACING + (unit(`${key}:jy`) * 2 - 1) * GROUP_GRID_JITTER;
  const z = slot.z * GROUP_GRID_SPACING + (unit(`${key}:jz`) * 2 - 1) * GROUP_GRID_JITTER;
  const yaw = unit(`${system.key}:yaw`) * Math.PI * 2;
  const pitch = (unit(`${system.key}:pitch`) - 0.5) * Math.PI;
  const yawX = x * Math.cos(yaw) - z * Math.sin(yaw);
  const yawZ = x * Math.sin(yaw) + z * Math.cos(yaw);
  return { x: system.x + yawX, y: system.y + y * Math.cos(pitch) - yawZ * Math.sin(pitch),
    z: system.z + y * Math.sin(pitch) + yawZ * Math.cos(pitch) };
}

function ordinalPool() { return { next: 0, free: [], changed: false }; }
function takeOrdinal(pool) { return pool.free.length ? pool.free.pop() : pool.next++; }
function releaseOrdinal(pool, ordinal) { pool.free.push(ordinal); pool.changed = true; }
function compactOrdinals(pool) {
  if (!pool.changed) return;
  pool.free.sort((left, right) => left - right);
  while (pool.free.at(-1) === pool.next - 1) { pool.free.pop(); pool.next--; }
  pool.free.reverse(); pool.changed = false;
}

function createP25HistoryState(options = {}) {
  return {
    actionLabel: typeof options.actionLabel === 'function' ? options.actionLabel :
      (action) => text(action).toLowerCase().replaceAll('_', ' ').replace(/\b\w/g, (value) => value.toUpperCase()),
    systems: new Map(),
    groups: new Map(),
    radios: new Map(),
    currentAffiliations: new Map(),
    actionCounts: new Map(),
    activeKeys: new Set(),
    activeRadioGroups: new Map(),
    liveTablesAvailable: false,
    systemOrdinals: ordinalPool(),
    available: true,
    afterId: 0
  };
}

function ensureSystem(state, row, atMs = finite(row.observed_at_ms)) {
  const key = text(row.radio_system_key);
  let system = state.systems.get(key);
  if (!system) {
    const ordinal = takeOrdinal(state.systemOrdinals), position = systemPosition(ordinal);
    system = { key, name: systemName(row), identity: systemIdentity(row), label: systemLabel(row),
      entityRef: row.radio_system_entity_ref || null, wacn: row.wacn ?? null, systemId: row.system_id ?? null,
      ordinal, x: position.x, y: position.y, z: position.z, radius: SYSTEM_RADIUS,
      lastAtMs: 0, groupKeys: new Set(), radioKeys: new Set(), groupSlots: [], groupShell: -1,
      groupOrdinals: ordinalPool() };
    state.systems.set(key, system);
  } else {
    const name = systemName(row);
    if (name && !system.current) {
      system.name = name;
      system.label = systemLabel(row);
    }
    if (row.radio_system_entity_ref) system.entityRef = row.radio_system_entity_ref;
  }
  system.lastAtMs = Math.max(system.lastAtMs, atMs);
  return system;
}

function ensureGroup(state, system, row, identityKey, atMs = finite(row.observed_at_ms)) {
  if (!identityKey) return null;
  const key = entityKey(system.key, identityKey);
  let group = state.groups.get(key);
  if (!group) {
    const ordinal = takeOrdinal(system.groupOrdinals), position = groupPosition(system, key, ordinal);
    group = { key, identityKey, systemKey: system.key, ordinal, label: groupLabel(row, identityKey),
      x: position.x, y: position.y, z: position.z,
      nativeId: nativeId(identityKey, row.target_native_id ?? row.target_id), lastAtMs: 0, radioKeys: new Set(),
      signalAction: '', highlightUntilMs: 0 };
    state.groups.set(key, group);
    system.groupKeys.add(key);
  } else {
    const label = groupLabel(row, identityKey);
    if (label && !group.current && /^\d+$/.test(group.label)) group.label = label;
  }
  group.lastAtMs = Math.max(group.lastAtMs, atMs);
  return group;
}

function ensureRadio(state, system, row, identityKey, role, atMs = finite(row.observed_at_ms)) {
  if (!identityKey) return null;
  const key = entityKey(system.key, identityKey);
  let radio = state.radios.get(key);
  if (!radio) {
    const local = localPosition(key, 52, 145);
    radio = { key, identityKey, systemKey: system.key, label: radioLabel(row, identityKey, role),
      labelIsAlias: Boolean(radioAlias(row, role)),
      nativeId: nativeId(identityKey, role === 'source' ? row.source_native_id ?? row.source_radio_id :
        row.target_native_id ?? row.target_id), x: system.x + local.x, y: system.y + local.y,
      z: system.z + local.z, lastAtMs: 0, visualGroupKey: '', activityGroupKey: '',
      priorGroups: new Map(), priorGroupKeys: [], signalAction: '', highlightUntilMs: 0,
      motionUntilMs: 0, motionGroupKeys: [] };
    state.radios.set(key, radio);
    system.radioKeys.add(key);
  } else {
    const label = radioLabel(row, identityKey, role);
    if (label && !radio.current && !radio.labelIsAlias) {
      radio.label = label;
      radio.labelIsAlias = Boolean(radioAlias(row, role));
    }
  }
  radio.lastAtMs = Math.max(radio.lastAtMs, atMs);
  return radio;
}

function highlight(entity, action, untilMs) {
  if (!entity) return;
  const sameSignal = entity.signalAction === action.toLowerCase();
  entity.highlightUntilMs = sameSignal ? Math.max(entity.highlightUntilMs, untilMs) : untilMs;
  entity.signalAction = action.toLowerCase();
}

function eventDetail(action, radio, group, previousGroup) {
  if (action === 'movement') return `${radio.label} · ${previousGroup.label} → ${group.label}`;
  if (action === 'logout') return `${radio.label} deregistered`;
  if (radio && group) return `${radio.label} · ${group.label}`;
  return radio?.label || group?.label || 'P25 system activity';
}

function radioPosition(radio, parent, parentKey, systemRadius) {
  if (!parentKey && radio.unaffiliatedPosition) return { ...radio.unaffiliatedPosition };
  const offset = localPosition(`${radio.key}:${parentKey || 'unaffiliated'}`,
    parentKey ? 16 : systemRadius * 0.72, parentKey ? 40 : systemRadius * 0.82);
  const position = { x: parent.x + offset.x, y: parent.y + offset.y, z: parent.z + offset.z };
  if (!parentKey) radio.unaffiliatedPosition = position;
  return { ...position };
}

// Saved JOIN observations describe an event; canonical current state owns the solid link.
function rememberGroup(radio, groupKey, atMs, id = 0) {
  const previous = radio.priorGroups.get(groupKey);
  if (previous && (previous.atMs > atMs || previous.atMs === atMs && previous.id >= id)) return;
  radio.priorGroups.set(groupKey, { atMs, id });
  const latest = [...radio.priorGroups].sort((a, b) => b[1].atMs - a[1].atMs || b[1].id - a[1].id || a[0].localeCompare(b[0]))
    .slice(0, MAX_PRIOR_GROUPS);
  radio.priorGroups = new Map(latest);
  radio.priorGroupKeys = latest.map(([key]) => key);
}

function applyJoin(state, system, row) {
  const radioIdentity = sourceRadioKey(row), groupIdentity = targetGroupKey(row, false);
  if (!radioIdentity || !groupIdentity) return;
  const radio = ensureRadio(state, system, row, radioIdentity, 'source');
  const group = ensureGroup(state, system, row, groupIdentity);
  if (!radio || !group) return;
  if (radio.visualGroupKey !== group.key) rememberGroup(radio, group.key, finite(row.observed_at_ms), Number(row.id));
}

function applySignal(state, system, row, result, initial, action, ingestedAtMs, shouldHighlight) {
  const atMs = finite(row.observed_at_ms);
  let radioIdentity = '';
  let role = 'source';
  if (action === 'DENIAL') {
    radioIdentity = targetRadioKey(row);
    role = radioIdentity ? 'target' : 'source';
    if (!radioIdentity) radioIdentity = sourceRadioKey(row);
    if (!radioIdentity) return;
  } else if (action === 'CHECK' || action === 'PAGE') {
    radioIdentity = targetRadioKey(row);
    role = 'target';
    if (!radioIdentity) radioIdentity = sourceRadioKey(row);
  } else {
    radioIdentity = sourceRadioKey(row) || targetRadioKey(row);
    role = sourceRadioKey(row) ? 'source' : 'target';
  }
  const groupIdentity = targetGroupKey(row);
  const group = groupIdentity ? ensureGroup(state, system, row, groupIdentity) : null;
  const radio = radioIdentity ? ensureRadio(state, system, row, radioIdentity, role) : null;
  if (!radio && !group) return;

  const category = action.toLowerCase();
  const entity = radio || group;
  const eventKey = `${category}:${entity.key}`;
  const title = state.actionLabel(action);
  const eventCategory = action.startsWith('PATCH') ? 'patch' : category;
  const event = { key: eventKey, category: eventCategory, title,
    detail: eventDetail(category, radio, group), observedAtMs: atMs, id: row.id, systemKey: system.key,
    focusKeys: [radio?.key, group?.key].filter(Boolean) };
  if (!initial && shouldHighlight(eventCategory)) {
    const untilMs = ingestedAtMs + (action === 'EMERGENCY' ? 12_000 : 7_000);
    highlight(radio, eventCategory, untilMs);
    highlight(group, eventCategory, untilMs);
  }
  if (!initial) result.focusCandidates.push(event);
}

function applyLogout(state, system, row, result, initial, ingestedAtMs, shouldHighlight) {
  const identity = sourceRadioKey(row) || targetRadioKey(row);
  if (!identity) return;
  const role = sourceRadioKey(row) ? 'source' : 'target';
  const radio = ensureRadio(state, system, row, identity, role);
  if (!radio) return;
  if (!initial && shouldHighlight('logout')) highlight(radio, 'logout', ingestedAtMs + 7_000);
  const event = { key: `logout:${radio.key}`, category: 'logout', title: state.actionLabel('LOGOUT'),
    detail: `${radio.label} · Logout observed`, observedAtMs: finite(row.observed_at_ms), id: row.id,
    systemKey: system.key, focusKeys: [radio.key] };
  if (!initial) result.focusCandidates.push(event);
}

function applyRoutineActivity(state, system, row, result, initial, action, ingestedAtMs, shouldHighlight,
    grantActivityTimeoutMs) {
  const radioIdentity = sourceRadioKey(row) || targetRadioKey(row);
  const role = sourceRadioKey(row) ? 'source' : 'target';
  const groupIdentity = targetGroupKey(row);
  const group = groupIdentity ? ensureGroup(state, system, row, groupIdentity) : null;
  const radio = radioIdentity ? ensureRadio(state, system, row, radioIdentity, role) : null;
  if (!radio && !group) return;
  const atMs = finite(row.observed_at_ms);
  if (radio && group && (atMs > (radio.activityAtMs || 0) ||
      atMs === radio.activityAtMs && row.id > (radio.activityId || 0))) {
    if (!radio.activityGroupKey && !radio.visualGroupKey) radio.activityGroupKey = group.key;
    radio.callGroupKey = group.key;
    radio.activityAtMs = atMs; radio.activityId = row.id;
  }
  const entity = group || radio;
  const event = { key: `call:${entity.key}`, category: 'call',
    title: state.actionLabel(action),
    detail: eventDetail('call', radio, group), observedAtMs: atMs, id: row.id, systemKey: system.key,
    focusKeys: [radio?.key, group?.key].filter(Boolean) };
  if (!initial && shouldHighlight('call')) {
    highlight(radio, 'call', ingestedAtMs + grantActivityTimeoutMs);
    highlight(group, 'call', ingestedAtMs + grantActivityTimeoutMs);
  }
  if (!initial) result.focusCandidates.push(event);
}

function applyP25ActivityRows(state, rows, options = {}) {
  if (!state?.systems || !state?.groups || !state?.radios) throw new TypeError('P25 history state is required.');
  const initial = options.initial === true;
  const ingestedAtMs = finite(options.atMs, Date.now());
  const grantActivityTimeoutMs = Math.max(100, Math.min(15_000, finite(options.grantActivityTimeoutMs, 1_000)));
  const highlighted = options.highlightCategories instanceof Set ? options.highlightCategories : null;
  const shouldHighlight = (category) => !highlighted || highlighted.has(category);
  const highlightDuration = (category) => category === 'call' ? grantActivityTimeoutMs :
    category === 'emergency' ? 12_000 : category === 'movement' ? 8_000 : 7_000;
  const result = { changed: false, accepted: 0, ignored: 0, focusCandidates: [], affiliationChanges: [] };
  const cursor = state.afterId;
  const incoming = [], ids = new Set();
  for (const row of rows) {
    if (!Number.isSafeInteger(row?.id) || row.id < 1) throw new Error('Invalid P25 activity record.');
    if (row.id <= cursor || ids.has(row.id)) { result.ignored++; continue; }
    ids.add(row.id); incoming.push(row);
  }
  const after = incoming.reduce((maximum, row) => Math.max(maximum, row.id), cursor);
  state.afterId = after;
  const ordered = incoming.sort((left, right) =>
    finite(left?.observed_at_ms) - finite(right?.observed_at_ms) || finite(left?.id) - finite(right?.id));
  for (const row of ordered) {
    const action = actionOf(row);
    if (!isP25Trunked(row) || !ACTIONS.has(action)) { result.ignored++; continue; }
    result.accepted++; result.changed = true;
    if (!initial && action !== 'JOIN') countAction(state, action.startsWith('PATCH') ? 'patch' :
      P25_ROUTINE_ACTIONS.includes(action) ? 'call' : action.toLowerCase());
    if (action === 'DENIAL' && !targetRadioKey(row) && !sourceRadioKey(row)) {
      continue;
    }
    const system = ensureSystem(state, row);
    const highlightAtMs = options.highlightAtObservationTime === true ?
      Math.min(ingestedAtMs, finite(row.observed_at_ms, ingestedAtMs)) : ingestedAtMs;
    const rowShouldHighlight = (category) => shouldHighlight(category) &&
      (options.highlightAtObservationTime !== true || highlightAtMs + highlightDuration(category) > ingestedAtMs);
    if (action === 'JOIN') applyJoin(state, system, row);
    else if (action === 'LOGOUT') applyLogout(state, system, row, result, initial, highlightAtMs, rowShouldHighlight);
    else if (P25_ROUTINE_ACTIONS.includes(action)) {
      applyRoutineActivity(state, system, row, result, initial, action, highlightAtMs, rowShouldHighlight,
        grantActivityTimeoutMs);
    } else applySignal(state, system, row, result, initial, action, highlightAtMs, rowShouldHighlight);
  }
  if (options.highlightAtObservationTime === true) {
    result.focusCandidates = result.focusCandidates.filter((event) => {
      return event.observedAtMs + highlightDuration(event.category) > ingestedAtMs;
    });
  }
  result.focusCandidates.sort((left, right) => right.observedAtMs - left.observedAtMs);
  return result;
}


// Native current-state fields use the existing identity label helpers without inventing Activity rows.
function affiliationLabelContext(fact) {
  return { ...fact, source_identity_key: fact.radio_identity_key, source_native_id: fact.radio_id,
    source_radio_id: fact.radio_observed_local_id ?? fact.radio_id,
    source_alias_name: fact.radio_alias_name, source_talker_alias: fact.radio_talker_alias,
    source_canonical_identity: fact.radio_canonical_identity || { wacn: fact.radio_home_wacn,
      system_id: fact.radio_home_system_id, subscriber_id: fact.radio_id },
    source_home_system_name: fact.radio_home_system_name, target_home_system_name: fact.talkgroup_home_system_name, source_observed_working_id: fact.radio_observed_working_id,
    target_identity_key: fact.talkgroup_identity_key, target_native_id: fact.talkgroup_id,
    target_id: fact.talkgroup_observed_local_id ?? fact.talkgroup_id,
    target_alias_name: fact.talkgroup_alias_name, target_observed_local_id: fact.talkgroup_observed_local_id,
    target_canonical_group_identity: { wacn: fact.talkgroup_home_wacn,
      system_id: fact.talkgroup_home_system_id, talkgroup_id: fact.talkgroup_id } };
}

// Only confirmed receiver affiliations can move an existing radio between groups.
function applyP25Affiliations(state, update = {}, options = {}) {
  const result = { changed: false, focusCandidates: [], affiliationChanges: [] };
  state.available = update.available !== false;
  if (!state.available && !options.replace) return result;
  const facts = update.affiliations || [];
  const now = finite(options.atMs, Date.now());
  const retained = options.replace ? new Set(facts.map(fact => entityKey(fact.radio_system_key, fact.radio_identity_key))) : null;
  const removals = retained ? [...state.currentAffiliations.keys()].filter(key => !retained.has(key)) :
    (update.removed_radio_keys || []).map(key => entityKey(key.radio_system_key, key.radio_identity_key));
  for (const key of removals) {
    const radio = state.radios.get(key);
    if (radio) {
      result.changed = result.changed || Boolean(radio.current || radio.motionUntilMs);
      radio.current = false; radio.motionUntilMs = 0; radio.motionGroupKeys = [];
    }
    result.changed = state.currentAffiliations.delete(key) || result.changed;
  }
  for (const incoming of facts) {
    if (!text(incoming?.radio_system_key) || !text(incoming?.radio_identity_key) ||
        !text(incoming?.talkgroup_identity_key) || !Number.isFinite(Number(incoming.confirmed_at_ms)))
      throw new Error('Invalid current P25 affiliation.');
    const { previous_affiliation: suppliedPrior, ...fact } = incoming;
    const key = entityKey(fact.radio_system_key, fact.radio_identity_key), atMs = finite(fact.confirmed_at_ms);
    const storedPrior = state.currentAffiliations.get(key);
    if (storedPrior && atMs < finite(storedPrior.confirmed_at_ms)) continue;
    const explicitPrior = suppliedPrior && suppliedPrior.radio_system_key === fact.radio_system_key &&
      suppliedPrior.radio_identity_key === fact.radio_identity_key && text(suppliedPrior.talkgroup_identity_key) ? suppliedPrior : null;
    const prior = storedPrior || explicitPrior;
    const same = storedPrior && JSON.stringify({ ...storedPrior, confirmed_at_ms: undefined }) ===
      JSON.stringify({ ...fact, confirmed_at_ms: undefined });
    state.currentAffiliations.set(key, fact);
    const context = affiliationLabelContext(fact), system = ensureSystem(state, fact, atMs);
    const group = ensureGroup(state, system, context, fact.talkgroup_identity_key, atMs);
    const radio = ensureRadio(state, system, context, fact.radio_identity_key, 'source', atMs);
    system.current = group.current = radio.current = true;
    radio.visualGroupKey = group.key;
    if (same) continue;
    system.name = systemName(fact); system.label = systemLabel(fact);
    radio.label = radioLabel(context, fact.radio_identity_key); radio.labelIsAlias = Boolean(radioAlias(context));
    group.label = groupLabel(context, fact.talkgroup_identity_key);
    result.changed = true;
    const comparable = prior && text(fact.configuration_id) && prior.configuration_id === fact.configuration_id &&
      prior.talkgroup_identity_key !== fact.talkgroup_identity_key && atMs > finite(prior.confirmed_at_ms);
    if (!comparable || options.initial || atMs + 8_000 <= now) continue;
    const previousGroup = ensureGroup(state, system, affiliationLabelContext(prior), prior.talkgroup_identity_key, atMs);
    rememberGroup(radio, previousGroup.key, atMs);
    radio.priorGroups.delete(group.key); radio.priorGroupKeys = [...radio.priorGroups.keys()];
    radio.motionUntilMs = Math.max(radio.motionUntilMs || 0, now + 10_000);
    radio.motionGroupKeys = [...new Set([...(radio.motionGroupKeys || []).filter(key =>
      key !== previousGroup.key && key !== group.key), previousGroup.key, group.key])].slice(-3);
    const event = { key: `movement:${key}`, category: 'movement', title: 'Observed affiliation change',
      detail: eventDetail('movement', radio, group, previousGroup), observedAtMs: atMs,
      systemKey: system.key, focusKeys: [key, previousGroup.key, group.key],
      affiliationRadioKey: key };
    countAction(state, 'movement'); result.focusCandidates.push(event);
    result.affiliationChanges.push({ id: `affiliation:${options.sequence}:${key}:${atMs}`, radioKey: key,
      systemKey: system.key, fromGroupKey: previousGroup.key, toGroupKey: group.key,
      from: radioPosition(radio, previousGroup, previousGroup.key, system.radius),
      target: radioPosition(radio, group, group.key, system.radius), observedAtMs: atMs,
      remainingMs: Math.max(0, atMs + 8_000 - now) });
    if (!options.highlightCategories || options.highlightCategories.has('movement')) {
      for (const entity of [radio, previousGroup, group]) highlight(entity, 'movement', atMs + 8_000);
    }
  }
  return result;
}

function countAction(state, category) {
  state.actionCounts.set(category, (state.actionCounts.get(category) || 0) + 1);
}

function p25ActionCount(state, category) { return state.actionCounts.get(category) || 0; }

// Live supplies complete current tables. Activity observations count actions; these
// tables alone determine which entities remain lit for an ongoing call.
function applyP25LiveTables(state, tables = [], options = {}) {
  const result = { changed: false, focusCandidates: [], affiliationChanges: [] };
  const active = new Set(), activeRadioGroups = new Map(), now = finite(options.atMs, Date.now());
  const wasAvailable = state.liveTablesAvailable;
  state.liveTablesAvailable = true;
  for (const table of tables) {
    if (table?.table_id === 'conventional' || table?.channel_running === false) continue;
    for (const raw of table?.rows || []) {
      const order = Number(raw?.activation_order), status = text(raw?.status).toUpperCase();
      if (!Number.isSafeInteger(order) || order <= 0 || status === 'IDLE' ||
          text(raw?.protocol).toLowerCase() !== 'p25') continue;
      const systemKey = Object.prototype.hasOwnProperty.call(raw, 'serving_system_key') ?
        text(raw.serving_system_key) : text(raw.radio_system_key);
      if (!systemKey) continue;
      const row = { ...raw, radio_system_key: systemKey, observed_at_ms: now,
        configuration_id: raw.configuration_id || table.configuration_id,
        source_identity_key: raw.source_entity_ref?.identity_key || raw.source_identity_key,
        target_identity_key: raw.target_entity_ref?.identity_key || raw.target_identity_key,
        source_identity_kind_code: raw.source_entity_ref?.kind === 'radio' || text(raw.source_type).toLowerCase() === 'radio' ?
          2 : raw.source_identity_kind_code,
        target_kind: raw.target_entity_ref?.kind || raw.target_kind || text(raw.target_type).toLowerCase(),
        source_native_id: raw.source_id, target_native_id: raw.target_id,
        source_alias_name: raw.source_alias, target_alias_name: raw.target_alias };
      const groupIdentity = targetGroupKey(row), radioIdentity = sourceRadioKey(row);
      if (!groupIdentity && !radioIdentity) continue;
      const system = ensureSystem(state, row, now);
      const group = groupIdentity ? ensureGroup(state, system, row, groupIdentity, now) : null;
      const radio = radioIdentity ? ensureRadio(state, system, row, radioIdentity, 'source', now) : null;
      if (group) { group.label = groupLabel(row, groupIdentity); active.add(group.key); }
      if (radio) {
        radio.label = radioLabel(row, radioIdentity); radio.labelIsAlias = Boolean(radioAlias(row));
        active.add(radio.key);
        if (group) {
          if (!radio.visualGroupKey && !radio.activityGroupKey) radio.activityGroupKey = group.key;
          if (!activeRadioGroups.has(radio.key)) activeRadioGroups.set(radio.key, new Set());
          activeRadioGroups.get(radio.key).add(group.key);
        }
      }
    }
  }
  const signature = JSON.stringify([...active].sort().map(key => {
    const entity = state.radios.get(key) || state.groups.get(key);
    return [key, entity.label, state.systems.get(entity.systemKey)?.label,
      [...(activeRadioGroups.get(key) || [])].sort()];
  }));
  result.changed = !wasAvailable || signature !== state.liveActivitySignature;
  state.liveActivitySignature = signature;
  state.activeKeys = active; state.activeRadioGroups = activeRadioGroups;
  return result;
}

function visibleSignal(entity, atMs, highlighted, state) {
  const signal = entity?.highlightUntilMs > atMs ? entity.signalAction : '';
  if (signal === 'call' && state?.liveTablesAvailable && !state.activeKeys.has(entity.key)) return '';
  return signal && (!highlighted || highlighted.has(signal)) ? signal : '';
}

function nextP25HighlightExpiry(state, atMs = Date.now(), options = {}) {
  const highlighted = options.highlightCategories instanceof Set ? options.highlightCategories : null;
  let next = 0;
  for (const entities of [state.groups, state.radios]) {
    for (const entity of entities.values()) {
      if (visibleSignal(entity, atMs, highlighted, state) && (!next || entity.highlightUntilMs < next)) {
        next = entity.highlightUntilMs;
      }
    }
  }
  return next;
}

// Retire quiet scene entities without dropping active calls or either endpoint of a fresh movement.
function expireP25History(state, cutoffMs, options = {}) {
  const currentSystems = new Set(), currentGroups = new Set();
  const now = finite(options.atMs, Date.now());
  for (const radio of state.radios.values()) {
    if (radio.motionUntilMs <= now) { radio.motionGroupKeys = []; radio.motionUntilMs = 0; }
    for (const key of radio.motionGroupKeys || []) currentGroups.add(key);
    if (radio.lastAtMs >= cutoffMs || state.activeKeys.has(radio.key) || radio.motionUntilMs > now) {
      currentSystems.add(radio.systemKey);
      if (radio.visualGroupKey) currentGroups.add(radio.visualGroupKey);
      if (radio.activityGroupKey) currentGroups.add(radio.activityGroupKey);
    }
  }
  let changed = false;
  for (const [key, radio] of state.radios) {
    radio.current = state.currentAffiliations.has(key);
    if (radio.activityGroupKey && radio.activityAtMs < cutoffMs) {
      radio.activityGroupKey = ''; radio.activityAtMs = radio.activityId = 0; changed = true;
    }
    for (const [groupKey, observation] of radio.priorGroups) if (observation.atMs < cutoffMs) {
      radio.priorGroups.delete(groupKey); changed = true;
    }
    radio.priorGroupKeys = [...radio.priorGroups.keys()];
    if (radio.lastAtMs < cutoffMs && !state.activeKeys.has(key) && !(radio.motionUntilMs > now)) {
      state.currentAffiliations.delete(key);
      state.radios.delete(key); state.systems.get(radio.systemKey).radioKeys.delete(key); changed = true;
    }
  }
  const layouts = new Set();
  for (const [key, group] of state.groups) {
    group.current = currentGroups.has(key);
    if (!group.current && group.lastAtMs < cutoffMs && !state.activeKeys.has(key)) {
      const system = state.systems.get(group.systemKey);
      state.groups.delete(key); system.groupKeys.delete(key); releaseOrdinal(system.groupOrdinals, group.ordinal);
      layouts.add(system); changed = true;
    }
  }
  for (const system of layouts) {
    compactOrdinals(system.groupOrdinals);
    // Existing anchors keep their coordinates; only the scratch generator and obsolete camera envelope reset.
    system.groupSlots = []; system.groupShell = -1; system.radius = SYSTEM_RADIUS;
  }
  for (const [key, system] of state.systems) {
    system.current = currentSystems.has(key);
    if (!system.current && !system.groupKeys.size && !system.radioKeys.size && system.lastAtMs < cutoffMs) {
      state.systems.delete(key); releaseOrdinal(state.systemOrdinals, system.ordinal); changed = true;
    }
  }
  compactOrdinals(state.systemOrdinals);
  return { changed, focusCandidates: [], affiliationChanges: [] };
}

function currentActivityScores(state) {
  const scores = new Map();
  for (const key of state.activeKeys) {
    const entity = state.radios.get(key) || state.groups.get(key);
    if (entity) scores.set(entity.systemKey, (scores.get(entity.systemKey) || 0) + 1);
  }
  return scores;
}

function buildP25Graph(state, systemKey = '', atMs = Date.now(), focusGroupKey = '', options = {}) {
  const currentGroup = (radio) => {
    const fact = state.currentAffiliations.get(radio.key);
    return fact ? entityKey(fact.radio_system_key, fact.talkgroup_identity_key) : '';
  };
  const highlighted = options.highlightCategories instanceof Set ? options.highlightCategories : null;
  if (!systemKey) {
    const scores = currentActivityScores(state);
    const nodes = [...state.systems.values()].map((system) => ({ id: system.key, type: 'system',
      label: system.label, x: system.x, y: system.y, z: system.z, radius: 58, systemKey: system.key,
      activityScore: scores.get(system.key) || 0 }));
    return { nodes, links: [], labels: nodes.map((node) => node.id), truncated: false };
  }
  const system = state.systems.get(systemKey);
  if (!system) return { nodes: [], links: [], labels: [], truncated: false };
  const focusGroup = state.groups.get(focusGroupKey);
  const focused = focusGroup?.systemKey === systemKey;
  const allGroups = [...system.groupKeys].map((key) => state.groups.get(key)).filter(Boolean)
    .sort((left, right) => right.lastAtMs - left.lastAtMs);
  const layoutGroups = allGroups;
  if (focused && !layoutGroups.includes(focusGroup)) layoutGroups.push(focusGroup);
  const groupPositions = new Map(layoutGroups.map((group) => [group.key, group]));
  system.radius = layoutGroups.reduce((radius, group) => Math.max(radius, Math.hypot(group.x - system.x,
    group.y - system.y, group.z - system.z) + GROUP_CLOUD_MARGIN), system.radius);
  const motionGroups = new Set();
  for (const radio of state.radios.values()) {
    if (radio.systemKey === systemKey && radio.motionUntilMs > atMs &&
        (!focused || radio.motionGroupKeys.includes(focusGroup.key)))
      radio.motionGroupKeys.forEach(key => motionGroups.add(key));
  }
  const touchesFocus = (radio) => currentGroup(radio) === focusGroup?.key ||
    radio.activityGroupKey === focusGroup?.key || radio.callGroupKey === focusGroup?.key ||
    state.activeRadioGroups.get(radio.key)?.has(focusGroup?.key) ||
    radio.priorGroupKeys.includes(focusGroup?.key) ||
    radio.motionUntilMs > atMs && radio.motionGroupKeys.includes(focusGroup?.key);
  const eligibleRadios = [...system.radioKeys].map((key) => state.radios.get(key)).filter(Boolean)
    .filter((radio) => !focused || touchesFocus(radio)).sort((left, right) => right.lastAtMs - left.lastAtMs);
  const radios = eligibleRadios;
  const physicalGroup = radio => groupPositions.has(currentGroup(radio)) ? currentGroup(radio) :
    groupPositions.has(radio.visualGroupKey) ? radio.visualGroupKey :
    groupPositions.has(radio.activityGroupKey) ? radio.activityGroupKey : '';
  const relatedGroups = new Set([focusGroup?.key, ...motionGroups]);
  if (focused) radios.forEach(radio => { const key = physicalGroup(radio); if (key) relatedGroups.add(key); });
  const groups = focused ? layoutGroups.filter(group => relatedGroups.has(group.key)) : layoutGroups;
  const groupKeys = new Set(groups.map(group => group.key));
  const radioKeys = new Set(radios.map((radio) => radio.key));
  const nodes = [{ id: system.key, type: 'system', label: system.label, x: system.x, y: system.y, z: system.z,
    radius: system.radius, systemKey: system.key }];
  groups.forEach((group) => {
    const position = groupPositions.get(group.key);
    nodes.push({ id: group.key, type: 'talkgroup', label: group.label,
      x: position.x, y: position.y, z: position.z, radius: 13, systemKey,
      active: state.activeKeys.has(group.key), signalAction: visibleSignal(group, atMs, highlighted, state) });
  });

  const groupedRadios = new Map();
  radios.forEach((radio) => {
    const parentKey = physicalGroup(radio);
    if (!groupedRadios.has(parentKey)) groupedRadios.set(parentKey, []);
    groupedRadios.get(parentKey).push(radio);
  });
  groupedRadios.forEach((members, parentKey) => {
    const parent = groupPositions.get(parentKey) || system;
    members.forEach((radio) => {
      Object.assign(radio, radioPosition(radio, parent, parentKey, system.radius));
      nodes.push({ id: radio.key, type: 'radio', label: radio.label, x: radio.x, y: radio.y, z: radio.z,
        radius: 6.5, systemKey, groupKey: parentKey, affiliationGroupKey: currentGroup(radio),
        active: state.activeKeys.has(radio.key), signalAction: visibleSignal(radio, atMs, highlighted, state) });
    });
  });

  const links = [];
  radios.forEach((radio) => {
    if (!radioKeys.has(radio.key)) return;
    if (groupKeys.has(currentGroup(radio))) links.push({ id: `current:${radio.key}:${currentGroup(radio)}`,
      source: radio.key, target: currentGroup(radio), kind: 'current' });
    const activityGroups = state.liveTablesAvailable ? state.activeRadioGroups.get(radio.key) || [] :
      [radio.callGroupKey || radio.activityGroupKey];
    for (const groupKey of activityGroups) if (groupKeys.has(groupKey) && groupKey !== currentGroup(radio)) {
      links.push({ id: `activity:${radio.key}:${groupKey}`, source: radio.key, target: groupKey, kind: 'activity' });
    }
    const earlierGroups = new Set(radio.priorGroupKeys);
    [...earlierGroups].filter((key) => groupKeys.has(key) && key !== currentGroup(radio)).forEach((groupKey) =>
      links.push({ id: `history:${radio.key}:${groupKey}`, source: radio.key, target: groupKey, kind: 'history' }));
  });
  return { nodes, links, labels: nodes.map((node) => node.id), truncated: (!focused && groups.length < allGroups.length) || radios.length < eligibleRadios.length };
}

function mostActiveP25System(state) {
  const scores = currentActivityScores(state);
  return [...state.systems.values()].sort((left, right) => (scores.get(right.key) || 0) - (scores.get(left.key) || 0) ||
    right.lastAtMs - left.lastAtMs || left.ordinal - right.ordinal)[0] || null;
}

export {
  P25_HISTORY_ACTIONS,
  P25_ROUTINE_ACTIONS,
  createP25HistoryState,
  applyP25ActivityRows,
  applyP25Affiliations,
  applyP25LiveTables,
  p25ActionCount,
  expireP25History,
  buildP25Graph,
  nextP25HighlightExpiry,
  mostActiveP25System
};
