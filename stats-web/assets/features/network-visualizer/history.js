'use strict';

import { systemName, homeSystemName, systemIdentity } from '../../core/system-labels.js?v=2';
import { formatP25RadioIdentifier, p25IdentityFacts, p25IdentifierText, p25ServingSystemKey } from '../../core/radio-labels.js?v=7';

const P25_HISTORY_ACTIONS = Object.freeze([
  'JOIN', 'LOGOUT', 'DENIAL', 'EMERGENCY', 'CHECK', 'PAGE', 'BUSY', 'QUEUED',
  'PATCH', 'PATCH_CREATE', 'PATCH_CANCEL'
]);
const P25_ROUTINE_ACTIONS = Object.freeze(['CALL', 'GRANT']);

const ACTIONS = new Set([...P25_HISTORY_ACTIONS, ...P25_ROUTINE_ACTIONS]);
const MAX_EVENTS = 120;
const MAX_SAVED_ROWS = 5_000;
const MAX_PRIOR_GROUPS = 3;
const MAX_CURRENT_AFFILIATIONS = 10_000;
const REPEAT_FOCUS_MS = 60_000;
const SYSTEM_RADIUS = 300;
const GROUP_CLOUD_MARGIN = 76;
const GROUP_GRID_SPACING = 124;
const GROUP_GRID_JITTER = 6;
const MAX_VISIBLE_GROUPS = 220;

const CAMERA_PRIORITY = Object.freeze({
  emergency: 4,
  movement: 3,
  denial: 3,
  patch: 2,
  busy: 2,
  queued: 2,
  check: 1,
  page: 1,
  logout: 1,
  call: 0.5
});

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

function createP25HistoryState(options = {}) {
  return {
    actionLabel: typeof options.actionLabel === 'function' ? options.actionLabel :
      (action) => text(action).toLowerCase().replaceAll('_', ' ').replace(/\b\w/g, (value) => value.toUpperCase()),
    systems: new Map(),
    groups: new Map(),
    radios: new Map(),
    events: new Map(),
    seenIds: new Set(),
    savedRows: new Map(),
    currentAffiliations: new Map(),
    affiliationsAvailable: true,
    latestId: 0,
    truncated: false,
    maximumRadios: Math.max(100, Number(options.maximumRadios) || 20_000),
    maximumGroups: Math.max(50, Number(options.maximumGroups) || 5_000)
  };
}

function rememberId(state, id) {
  if (!Number.isSafeInteger(id) || id < 1 || state.seenIds.has(id)) return false;
  state.seenIds.add(id);
  state.latestId = Math.max(state.latestId, id);
  return true;
}

function ensureSystem(state, row, atMs = finite(row.observed_at_ms)) {
  const key = text(row.radio_system_key);
  let system = state.systems.get(key);
  if (!system) {
    const position = systemPosition(state.systems.size);
    system = { key, name: systemName(row), identity: systemIdentity(row), label: systemLabel(row),
      entityRef: row.radio_system_entity_ref || null, wacn: row.wacn ?? null, systemId: row.system_id ?? null,
      ordinal: state.systems.size, x: position.x, y: position.y, z: position.z, radius: SYSTEM_RADIUS,
      score: 0, lastAtMs: 0, groupKeys: new Set(), radioKeys: new Set(), groupSlots: [], groupShell: -1 };
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
    if (state.groups.size >= state.maximumGroups) {
      state.truncated = true;
      return null;
    }
    const position = groupPosition(system, key, system.groupKeys.size);
    group = { key, identityKey, systemKey: system.key, label: groupLabel(row, identityKey),
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
    if (state.radios.size >= state.maximumRadios) {
      state.truncated = true;
      return null;
    }
    const local = localPosition(key, 52, 145);
    radio = { key, identityKey, systemKey: system.key, label: radioLabel(row, identityKey, role),
      labelIsAlias: Boolean(radioAlias(row, role)),
      nativeId: nativeId(identityKey, role === 'source' ? row.source_native_id ?? row.source_radio_id :
        row.target_native_id ?? row.target_id), x: system.x + local.x, y: system.y + local.y,
      z: system.z + local.z, lastAtMs: 0, visualGroupKey: '', activityGroupKey: '',
      priorGroupKeys: [], signalAction: '', highlightUntilMs: 0 };
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

function trimEvents(state) {
  if (state.events.size <= MAX_EVENTS) return;
  const oldest = [...state.events.values()].sort((left, right) => left.observedAtMs - right.observedAtMs)
    .slice(0, state.events.size - MAX_EVENTS);
  oldest.forEach((event) => state.events.delete(event.key));
}

function upsertEvent(state, value) {
  const previous = state.events.get(value.key);
  if (previous && previous.observedAtMs > value.observedAtMs) return false;
  state.events.set(value.key, { ...previous, ...value,
    firstObservedAtMs: previous?.firstObservedAtMs || value.observedAtMs });
  trimEvents(state);
  return !previous || value.observedAtMs - previous.observedAtMs >= REPEAT_FOCUS_MS;
}


function eventDetail(action, radio, group, previousGroup) {
  if (action === 'movement') return `${radio.label} · ${previousGroup.label} → ${group.label}`;
  if (action === 'logout') return `${radio.label} deregistered`;
  if (radio && group) return `${radio.label} · ${group.label}`;
  return radio?.label || group?.label || 'P25 system activity';
}

function radioPosition(radio, parent, parentKey, systemRadius) {
  const offset = localPosition(`${radio.key}:${parentKey || 'unaffiliated'}`,
    parentKey ? 16 : systemRadius * 0.72, parentKey ? 40 : systemRadius * 0.82);
  return { x: parent.x + offset.x, y: parent.y + offset.y, z: parent.z + offset.z };
}

// Saved JOIN observations describe an event; canonical current state owns the solid link.
function applyJoin(state, system, row, result, initial, ingestedAtMs, shouldHighlight) {
  const radioIdentity = sourceRadioKey(row), groupIdentity = targetGroupKey(row, false);
  if (!radioIdentity || !groupIdentity) return;
  const radio = ensureRadio(state, system, row, radioIdentity, 'source');
  const group = ensureGroup(state, system, row, groupIdentity);
  if (!radio || !group) return;
  if (radio.visualGroupKey !== group.key) radio.priorGroupKeys = [group.key,
    ...radio.priorGroupKeys.filter((key) => key !== group.key)].slice(0, MAX_PRIOR_GROUPS);
  upsertEvent(state, { key: `join:${radio.key}`, category: 'movement', title: 'Affiliation observed',
    detail: eventDetail('join', radio, group), observedAtMs: finite(row.observed_at_ms),
    systemKey: system.key, focusKeys: [radio.key, group.key], priority: CAMERA_PRIORITY.movement });
  if (!initial && shouldHighlight('movement')) {
    highlight(radio, 'movement', ingestedAtMs + 8_000);
    highlight(group, 'movement', ingestedAtMs + 8_000);
  }
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
  const priority = CAMERA_PRIORITY[category] || CAMERA_PRIORITY.patch;
  const entity = radio || group;
  const eventKey = `${category}:${entity.key}`;
  const title = state.actionLabel(action);
  const eventCategory = action.startsWith('PATCH') ? 'patch' : category;
  const event = { key: eventKey, category: eventCategory, title,
    detail: eventDetail(category, radio, group), observedAtMs: atMs, systemKey: system.key,
    focusKeys: [radio?.key, group?.key].filter(Boolean), priority };
  const firstGroupedEvent = !state.events.has(eventKey);
  const focusEligible = upsertEvent(state, event);
  if (!initial && shouldHighlight(eventCategory)) {
    const untilMs = ingestedAtMs + (action === 'EMERGENCY' ? 12_000 : 7_000);
    highlight(radio, eventCategory, untilMs);
    highlight(group, eventCategory, untilMs);
  }
  if (firstGroupedEvent) {
    system.score += action === 'EMERGENCY' ? 8 : action === 'DENIAL' ? 3 : 2;
  }
  if (!initial && focusEligible) result.focusCandidates.push(event);
}

function applyLogout(state, system, row, result, initial, ingestedAtMs, shouldHighlight) {
  const identity = sourceRadioKey(row) || targetRadioKey(row);
  if (!identity) return;
  const role = sourceRadioKey(row) ? 'source' : 'target';
  const radio = ensureRadio(state, system, row, identity, role);
  if (!radio) return;
  if (!initial && shouldHighlight('logout')) highlight(radio, 'logout', ingestedAtMs + 7_000);
  const event = { key: `logout:${radio.key}`, category: 'logout', title: state.actionLabel('LOGOUT'),
    detail: `${radio.label} · Logout observed`, observedAtMs: finite(row.observed_at_ms),
    systemKey: system.key, focusKeys: [radio.key], priority: CAMERA_PRIORITY.logout };
  const first = !state.events.has(event.key), eligible = upsertEvent(state, event);
  if (first) system.score += 1;
  if (!initial && eligible) result.focusCandidates.push(event);
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
  if (radio && group && atMs >= (radio.activityAtMs || 0)) {
    radio.activityGroupKey = group.key;
    radio.activityAtMs = atMs;
  }
  const entity = group || radio;
  const event = { key: `call:${entity.key}`, category: 'call',
    title: state.actionLabel(action),
    detail: eventDetail('call', radio, group), observedAtMs: atMs, systemKey: system.key,
    focusKeys: [radio?.key, group?.key].filter(Boolean), priority: CAMERA_PRIORITY.call };
  const firstGroupedEvent = !state.events.has(event.key);
  const focusEligible = upsertEvent(state, event);
  if (!initial && shouldHighlight('call')) {
    highlight(radio, 'call', ingestedAtMs + grantActivityTimeoutMs);
    highlight(group, 'call', ingestedAtMs + grantActivityTimeoutMs);
  }
  if (firstGroupedEvent) system.score += 0.5;
  if (!initial && focusEligible) result.focusCandidates.push(event);
}

function appendP25ActivityRows(state, rows, options = {}) {
  if (!state?.systems || !state?.groups || !state?.radios) throw new TypeError('P25 history state is required.');
  const initial = options.initial === true;
  const ingestedAtMs = finite(options.atMs, Date.now());
  const grantActivityTimeoutMs = Math.max(100, Math.min(15_000, finite(options.grantActivityTimeoutMs, 1_000)));
  const highlighted = options.highlightCategories instanceof Set ? options.highlightCategories : null;
  const shouldHighlight = (category) => !highlighted || highlighted.has(category);
  const highlightDuration = (category) => category === 'call' ? grantActivityTimeoutMs :
    category === 'emergency' ? 12_000 : category === 'movement' ? 8_000 : 7_000;
  const result = { changed: false, accepted: 0, ignored: 0, focusCandidates: [], affiliationChanges: [] };
  const ordered = (Array.isArray(rows) ? rows : []).slice().sort((left, right) =>
    finite(left?.observed_at_ms) - finite(right?.observed_at_ms) || finite(left?.id) - finite(right?.id));
  for (const row of ordered) {
    const id = Number(row?.id);
    const action = actionOf(row);
    if (!isP25Trunked(row) || !ACTIONS.has(action) || !rememberId(state, id)) {
      result.ignored += 1;
      continue;
    }
    if (action === 'DENIAL' && !targetRadioKey(row) && !sourceRadioKey(row)) {
      result.accepted += 1;
      result.changed = true;
      continue;
    }
    const system = ensureSystem(state, row);
    const rowInitial = initial && !options.attentionIds?.has(id);
    const highlightAtMs = options.highlightAtObservationTime === true ?
      Math.min(ingestedAtMs, finite(row.observed_at_ms, ingestedAtMs)) : ingestedAtMs;
    const rowShouldHighlight = (category) => shouldHighlight(category) &&
      (options.highlightAtObservationTime !== true || highlightAtMs + highlightDuration(category) > ingestedAtMs);
    if (action === 'JOIN') applyJoin(state, system, row, result, rowInitial, highlightAtMs, rowShouldHighlight);
    else if (action === 'LOGOUT') applyLogout(state, system, row, result, rowInitial, highlightAtMs, rowShouldHighlight);
    else if (P25_ROUTINE_ACTIONS.includes(action)) {
      applyRoutineActivity(state, system, row, result, rowInitial, action, highlightAtMs, rowShouldHighlight,
        grantActivityTimeoutMs);
    } else applySignal(state, system, row, result, rowInitial, action, highlightAtMs, rowShouldHighlight);
    result.accepted += 1;
    result.changed = true;
  }
  if (options.highlightAtObservationTime === true) {
    result.focusCandidates = result.focusCandidates.filter((event) => {
      return event.observedAtMs + highlightDuration(event.category) > ingestedAtMs;
    });
    result.affiliationChanges = result.affiliationChanges.filter((change) =>
      change.observedAtMs + highlightDuration('movement') > ingestedAtMs);
  }
  result.affiliationChanges = result.affiliationChanges.map((change) => ({ ...change,
    remainingMs: options.highlightAtObservationTime === true ?
      Math.max(0, change.observedAtMs + highlightDuration('movement') - ingestedAtMs) : 8_000 }));
  result.focusCandidates.sort((left, right) => right.priority - left.priority ||
    right.observedAtMs - left.observedAtMs);
  return result;
}


// Retain the bounded saved rows so a correction can remove its old graph facts.
// New rows append incrementally; corrections, deletions and oldest-record trimming rebuild once per batch.
function applyP25ActivityRows(state, rows, options = {}) {
  const savedRows = state.savedRows;
  const incoming = Array.isArray(rows) ? rows : [];
  let rebuild = false, ignored = 0, accepted = 0;
  const additions = [];
  const remove = (id) => { if (savedRows.delete(Number(id))) rebuild = true; };
  (options.removedIds || []).forEach(remove);
  for (const row of incoming) {
    const id = Number(row?.id);
    const previous = savedRows.get(id);
    if (!Number.isSafeInteger(id) || id < 1 || !isP25Trunked(row) || !ACTIONS.has(actionOf(row))) {
      if (previous) remove(id);
      ignored++;
      continue;
    }
    if (previous && JSON.stringify(previous) === JSON.stringify(row)) { ignored++; continue; }
    savedRows.set(id, row);
    accepted++;
    if (previous) rebuild = true;
    else additions.push(row);
  }
  if (savedRows.size > MAX_SAVED_ROWS) {
    const oldest = [...savedRows.values()].sort((a, b) => finite(a.observed_at_ms) - finite(b.observed_at_ms) || a.id - b.id);
    oldest.slice(0, savedRows.size - MAX_SAVED_ROWS).forEach((row) => remove(row.id));
  }
  if (!rebuild) {
    const result = appendP25ActivityRows(state, additions, options);
    result.ignored += ignored;
    return result;
  }
  const previous = { systems: state.systems, groups: state.groups, radios: state.radios };
  const next = createP25HistoryState({ actionLabel: state.actionLabel,
    maximumRadios: state.maximumRadios, maximumGroups: state.maximumGroups });
  next.savedRows = savedRows;
  next.currentAffiliations = state.currentAffiliations;
  next.affiliationsAvailable = state.affiliationsAvailable;
  hydrateCurrentAffiliations(next);
  const replay = appendP25ActivityRows(next, [...savedRows.values()], { ...options, initial: true,
    attentionIds: options.initial === true ? new Set() : new Set(additions.map((row) => Number(row.id))) });
  const sameKeys = (left, right) => left.size === right.size && [...left.keys()].every((key) => right.has(key));
  const stableSystems = sameKeys(previous.systems, next.systems);
  // Preserve anchors only when the relevant clouds retain the same layout slots.
  // A removed/introduced cloud otherwise needs its rebuilt ordinal to avoid overlap.
  for (const type of ['systems', 'groups', 'radios']) {
    for (const [key, entity] of next[type]) {
      const prior = previous[type].get(key);
      if (!prior) continue;
      const stableGroups = type === 'systems' || sameKeys(previous.systems.get(entity.systemKey).groupKeys,
        next.systems.get(entity.systemKey).groupKeys);
      if (stableSystems && stableGroups) {
        for (const field of ['x', 'y', 'z', 'radius']) if (Number.isFinite(prior[field])) entity[field] = prior[field];
      }
      if (type === 'radios') entity.priorGroupKeys = [...new Set([...entity.priorGroupKeys, ...prior.priorGroupKeys])]
        .filter((groupKey) => next.groups.has(groupKey)).slice(0, MAX_PRIOR_GROUPS);
      if (!entity.highlightUntilMs && prior.highlightUntilMs > finite(options.atMs, Date.now())) {
        entity.signalAction = prior.signalAction; entity.highlightUntilMs = prior.highlightUntilMs;
      }
    }
  }
  for (const event of state.events.values()) if (event.key.startsWith('movement:') && !next.events.has(event.key) &&
      event.focusKeys.some((key) => next.radios.has(key) || next.groups.has(key))) upsertEvent(next, event);
  Object.assign(state, next);
  // A correction is authoritative, but it is not another radio observation.
  return { changed: true, accepted, ignored, rebuilt: true, focusCandidates: replay.focusCandidates,
    affiliationChanges: replay.affiliationChanges };
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

function hydrateCurrentAffiliations(state) {
  for (const fact of state.currentAffiliations.values()) {
    const atMs = finite(fact.confirmed_at_ms), context = affiliationLabelContext(fact);
    const system = ensureSystem(state, fact, atMs);
    const group = ensureGroup(state, system, context, fact.talkgroup_identity_key, atMs);
    const radio = ensureRadio(state, system, context, fact.radio_identity_key, 'source', atMs);
    system.current = true;
    if (group) group.current = true;
    if (radio) radio.current = true;
    if (radio && group) { radio.visualGroupKey = group.key; group.radioKeys.add(radio.key); }
  }
}

function applyP25Affiliations(state, rows, options = {}) {
  const result = { changed: false, focusCandidates: [], affiliationChanges: [] };
  const available = options.available !== false && options.complete !== false;
  const availabilityChanged = state.affiliationsAvailable !== available;
  state.affiliationsAvailable = available;
  if (options.complete === false || !available && !options.replace) return { ...result, availabilityChanged };
  if (!Array.isArray(rows) || rows.length > MAX_CURRENT_AFFILIATIONS) throw new Error('Invalid current P25 affiliations.');
  const before = state.currentAffiliations;
  const current = options.replace ? new Map() : new Map(before);
  for (const key of options.removedRadioKeys || []) current.delete(entityKey(key.radio_system_key, key.radio_identity_key));
  for (const fact of rows) {
    if (!text(fact?.radio_system_key) || !text(fact?.radio_identity_key) || !text(fact?.talkgroup_identity_key) ||
        !Number.isFinite(Number(fact.confirmed_at_ms))) throw new Error('Invalid current P25 affiliation.');
    current.set(entityKey(fact.radio_system_key, fact.radio_identity_key), fact);
  }
  if (current.size > MAX_CURRENT_AFFILIATIONS) throw new Error('Too many current P25 affiliations.');
  if (current.size === before.size && [...current].every(([key, fact]) =>
    JSON.stringify(before.get(key)) === JSON.stringify(fact))) return { ...result, availabilityChanged };
  const presentation = (fact) => JSON.stringify({ ...fact, confirmed_at_ms: undefined });
  if (current.size === before.size && [...current].every(([key, fact]) =>
      before.has(key) && presentation(before.get(key)) === presentation(fact))) {
    state.currentAffiliations = current;
    for (const [key, fact] of current) {
      const atMs = finite(fact.confirmed_at_ms), radio = state.radios.get(key);
      const group = state.groups.get(entityKey(fact.radio_system_key, fact.talkgroup_identity_key));
      if (radio) radio.lastAtMs = Math.max(radio.lastAtMs, atMs);
      if (group) group.lastAtMs = Math.max(group.lastAtMs, atMs);
    }
    return { ...result, availabilityChanged };
  }
  const priorEntities = { systems: state.systems, groups: state.groups, radios: state.radios };
  state.currentAffiliations = current;
  // The same bounded replay removes nodes supplied only by an old canonical association.
  const next = createP25HistoryState({ actionLabel: state.actionLabel,
    maximumRadios: state.maximumRadios, maximumGroups: state.maximumGroups });
  next.savedRows = state.savedRows; next.currentAffiliations = current; next.affiliationsAvailable = available;
  hydrateCurrentAffiliations(next);
  appendP25ActivityRows(next, [...next.savedRows.values()], { ...options, initial: true });
  const sameKeys = (a, b) => a.size === b.size && [...a.keys()].every((key) => b.has(key));
  const stableSystems = sameKeys(next.systems, priorEntities.systems);
  for (const type of ['systems', 'groups', 'radios']) for (const [key, entity] of next[type]) {
    const prior = priorEntities[type].get(key);
    if (!prior) continue;
    if (stableSystems && (type === 'systems' || sameKeys(next.systems.get(entity.systemKey).groupKeys,
        priorEntities.systems.get(entity.systemKey).groupKeys))) {
      for (const field of ['x', 'y', 'z', 'radius']) if (Number.isFinite(prior[field])) entity[field] = prior[field];
    }
    entity.signalAction = prior.signalAction; entity.highlightUntilMs = prior.highlightUntilMs;
    if (type === 'radios') entity.priorGroupKeys = [...new Set([...entity.priorGroupKeys, ...prior.priorGroupKeys])]
      .filter((groupKey) => next.groups.has(groupKey)).slice(0, MAX_PRIOR_GROUPS);
  }
  const now = finite(options.atMs, Date.now());
  if (!options.initial) for (const [key, fact] of current) {
    const previous = before.get(key);
    if (!previous || previous.talkgroup_identity_key === fact.talkgroup_identity_key ||
        !text(fact.configuration_id) || fact.configuration_id !== previous.configuration_id ||
        finite(fact.confirmed_at_ms) <= finite(previous.confirmed_at_ms) || finite(fact.confirmed_at_ms) + 8_000 <= now) continue;
    const radio = next.radios.get(key), group = next.groups.get(entityKey(fact.radio_system_key, fact.talkgroup_identity_key));
    const previousGroup = priorEntities.groups.get(entityKey(previous.radio_system_key, previous.talkgroup_identity_key));
    const system = next.systems.get(fact.radio_system_key);
    if (!radio || !group || !previousGroup) continue;
    const event = { key: `movement:${key}`, category: 'movement', title: 'Observed affiliation change',
      detail: eventDetail('movement', radio, group, previousGroup), observedAtMs: finite(fact.confirmed_at_ms),
      systemKey: system.key, focusKeys: [key, group.key], priority: CAMERA_PRIORITY.movement,
      affiliationRadioKey: next.groups.has(previousGroup.key) ? key : '' };
    upsertEvent(next, event); result.focusCandidates.push(event);
    if (next.groups.has(previousGroup.key) && sameKeys(next.systems.get(system.key).groupKeys,
        priorEntities.systems.get(system.key).groupKeys)) result.affiliationChanges.push({ id: `affiliation:${options.revision}:${key}`, radioKey: key,
      systemKey: system.key, fromGroupKey: previousGroup.key, toGroupKey: group.key,
      from: radioPosition(radio, previousGroup, previousGroup.key, system.radius),
      target: radioPosition(radio, group, group.key, system.radius), observedAtMs: event.observedAtMs,
      remainingMs: Math.max(0, event.observedAtMs + 8_000 - now) });
    if (!options.highlightCategories || options.highlightCategories.has('movement')) {
      highlight(radio, 'movement', event.observedAtMs + 8_000); highlight(group, 'movement', event.observedAtMs + 8_000);
    }
  }
  for (const event of state.events.values()) if (event.key.startsWith('movement:') && !next.events.has(event.key) &&
      event.focusKeys.some((key) => next.radios.has(key) || next.groups.has(key))) upsertEvent(next, event);
  Object.assign(state, next);
  return { ...result, changed: true, availabilityChanged };
}

function visibleSignal(entity, atMs, highlighted) {
  const signal = entity?.highlightUntilMs > atMs ? entity.signalAction : '';
  return signal && (!highlighted || highlighted.has(signal)) ? signal : '';
}

function nextP25HighlightExpiry(state, atMs = Date.now(), options = {}) {
  const highlighted = options.highlightCategories instanceof Set ? options.highlightCategories : null;
  let next = 0;
  for (const entities of [state.groups, state.radios]) {
    for (const entity of entities.values()) {
      if (visibleSignal(entity, atMs, highlighted) && (!next || entity.highlightUntilMs < next)) {
        next = entity.highlightUntilMs;
      }
    }
  }
  return next;
}

function buildP25Graph(state, systemKey = '', atMs = Date.now(), focusGroupKey = '', options = {}) {
  const highlighted = options.highlightCategories instanceof Set ? options.highlightCategories : null;
  if (!systemKey) {
    const nodes = [...state.systems.values()].map((system) => ({ id: system.key, type: 'system',
      label: system.label, x: system.x, y: system.y, z: system.z, radius: 58, systemKey: system.key,
      activityScore: system.score }));
    return { nodes, links: [], labels: nodes.map((node) => node.id), truncated: state.truncated };
  }
  const system = state.systems.get(systemKey);
  if (!system) return { nodes: [], links: [], labels: [], truncated: state.truncated };
  const focusGroup = state.groups.get(focusGroupKey);
  const focused = focusGroup?.systemKey === systemKey;
  const allGroups = [...system.groupKeys].map((key) => state.groups.get(key)).filter(Boolean)
    .sort((left, right) => right.lastAtMs - left.lastAtMs);
  const layoutGroups = allGroups.slice(0, MAX_VISIBLE_GROUPS);
  if (focused && !layoutGroups.includes(focusGroup)) layoutGroups.push(focusGroup);
  const groupPositions = new Map(layoutGroups.map((group) => [group.key, group]));
  system.radius = Math.max(system.radius, ...layoutGroups.map((group) => Math.hypot(group.x - system.x,
    group.y - system.y, group.z - system.z) + GROUP_CLOUD_MARGIN));
  const groups = focused ? [focusGroup] : layoutGroups;
  const groupKeys = new Set(groups.map((group) => group.key));
  const touchesFocus = (radio) => radio.visualGroupKey === focusGroup?.key ||
    radio.activityGroupKey === focusGroup?.key ||
    radio.priorGroupKeys.includes(focusGroup?.key);
  const eligibleRadios = [...system.radioKeys].map((key) => state.radios.get(key)).filter(Boolean)
    .filter((radio) => !focused || touchesFocus(radio)).sort((left, right) => right.lastAtMs - left.lastAtMs);
  const radios = eligibleRadios.slice(0, 900);
  const radioKeys = new Set(radios.map((radio) => radio.key));
  const nodes = [{ id: system.key, type: 'system', label: system.label, x: system.x, y: system.y, z: system.z,
    radius: system.radius, systemKey: system.key }];
  groups.forEach((group) => {
    const position = groupPositions.get(group.key);
    nodes.push({ id: group.key, type: 'talkgroup', label: group.label,
      x: position.x, y: position.y, z: position.z, radius: 13, systemKey,
      signalAction: visibleSignal(group, atMs, highlighted) });
  });

  const groupedRadios = new Map();
  radios.forEach((radio) => {
    const parentKey = focused ? focusGroup.key : groupKeys.has(radio.visualGroupKey) ? radio.visualGroupKey :
      groupKeys.has(radio.activityGroupKey) ? radio.activityGroupKey : '';
    if (!groupedRadios.has(parentKey)) groupedRadios.set(parentKey, []);
    groupedRadios.get(parentKey).push(radio);
  });
  groupedRadios.forEach((members, parentKey) => {
    const parent = groupPositions.get(parentKey) || system;
    members.forEach((radio) => {
      Object.assign(radio, radioPosition(radio, parent, parentKey, system.radius));
      nodes.push({ id: radio.key, type: 'radio', label: radio.label, x: radio.x, y: radio.y, z: radio.z,
        radius: 6.5, systemKey, groupKey: parentKey, affiliationGroupKey: radio.visualGroupKey,
        signalAction: visibleSignal(radio, atMs, highlighted) });
    });
  });

  const links = [];
  radios.forEach((radio) => {
    if (!radioKeys.has(radio.key)) return;
    if (groupKeys.has(radio.visualGroupKey)) links.push({ id: `current:${radio.key}:${radio.visualGroupKey}`,
      source: radio.key, target: radio.visualGroupKey, kind: 'current' });
    if (groupKeys.has(radio.activityGroupKey) && radio.activityGroupKey !== radio.visualGroupKey) {
      links.push({ id: `activity:${radio.key}:${radio.activityGroupKey}`,
        source: radio.key, target: radio.activityGroupKey, kind: 'activity' });
    }
    const earlierGroups = new Set(radio.priorGroupKeys);
    [...earlierGroups].filter((key) => groupKeys.has(key) && key !== radio.visualGroupKey).forEach((groupKey) =>
      links.push({ id: `history:${radio.key}:${groupKey}`, source: radio.key, target: groupKey, kind: 'history' }));
  });
  return { nodes, links, labels: nodes.map((node) => node.id), truncated: state.truncated ||
    (!focused && groups.length < allGroups.length) || radios.length < eligibleRadios.length };
}

function groupedP25Events(state, systemKey = '') {
  return [...state.events.values()].filter((event) => !systemKey || event.systemKey === systemKey)
    .sort((left, right) => right.observedAtMs - left.observedAtMs).slice(0, MAX_EVENTS);
}

function mostActiveP25System(state) {
  return [...state.systems.values()].sort((left, right) => right.score - left.score ||
    right.lastAtMs - left.lastAtMs || left.ordinal - right.ordinal)[0] || null;
}

export {
  P25_HISTORY_ACTIONS,
  P25_ROUTINE_ACTIONS,
  CAMERA_PRIORITY,
  createP25HistoryState,
  applyP25ActivityRows,
  applyP25Affiliations,
  buildP25Graph,
  nextP25HighlightExpiry,
  groupedP25Events,
  mostActiveP25System
};
