'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const feature = path.resolve(process.argv[2] ||
  path.resolve(__dirname, '../../../../stats-web/assets/features/network-visualizer'));

const CONFIG_A = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const CONFIG_B = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const SYSTEM_A = 'opaque-system-a';
const SYSTEM_B = 'opaque-system-b';
const BASE_TIME = 1_700_100_000_000;

function ref(kind, identity, id, label, system = SYSTEM_A) {
  return { kind, identity_key: identity, native_id: String(id), label, ...(system ? { radio_system_key: system } : {}) };
}

function affiliation(overrides = {}) {
  const sequence = overrides.sequence ?? 1;
  const configuration = overrides.configuration_id || CONFIG_A;
  const system = overrides.radio_system_key === undefined ? SYSTEM_A : overrides.radio_system_key;
  return {
    kind: 'affiliation_observed',
    event_id: overrides.event_id || `aff-${configuration}-${sequence}-${overrides.group_id || 101}`,
    observed_at_ms: overrides.observed_at_ms || BASE_TIME + sequence,
    protocol: overrides.protocol || 'p25',
    radio_system_key: system,
    system_name: overrides.system_name || 'System A',
    wacn: 'ABCDE', system_id: '123',
    configuration_id: configuration,
    rfss_id: overrides.rfss_id ?? 1,
    site_id: overrides.site_id ?? 1,
    comparison_scope_key: overrides.comparison_scope_key || `${configuration}:1:1:x`,
    evidence_type: overrides.evidence_type || 'group_affiliation_response',
    outcome: overrides.outcome || 'accepted',
    sequence,
    radio: overrides.radio || ref('radio', overrides.radio_identity || 'radio:7001',
      overrides.radio_id || 7001, overrides.radio_label || 'Unit 7001', system),
    group: overrides.group || ref('talkgroup', overrides.group_identity || `tg:${overrides.group_id || 101}`,
      overrides.group_id || 101, overrides.group_label || `Group ${overrides.group_id || 101}`, system)
  };
}

function call(overrides = {}) {
  const system = overrides.radio_system_key === undefined ? SYSTEM_A : overrides.radio_system_key;
  const configuration = overrides.configuration_id || CONFIG_A;
  const groupId = overrides.group_id || 101;
  const event = {
    kind: overrides.kind || 'call_start',
    event_id: overrides.event_id || `call-${overrides.call_leg_id || 'leg-1'}-${overrides.observed_at_ms || BASE_TIME}`,
    transmission_state: overrides.transmission_state || 'active',
    observed_at_ms: overrides.observed_at_ms || BASE_TIME + 100,
    protocol: overrides.protocol || 'p25',
    radio_system_key: system,
    system_name: overrides.system_name || 'System A',
    configuration_id: configuration,
    channel_name: overrides.channel_name || 'Site A',
    call_leg_id: overrides.call_leg_id || 'leg-1',
    resource_context_key: overrides.resource_context_key || 'resource-1',
    encrypted: overrides.encrypted === true,
    end_proven: overrides.end_proven === true
  };
  if (overrides.include_group !== false && event.protocol !== 'nbfm' && event.protocol !== 'am') {
    event.group = overrides.group || ref('talkgroup', overrides.group_identity || `tg:${groupId}`, groupId,
      overrides.group_label || `Group ${groupId}`, system);
  }
  if (overrides.include_radio !== false) {
    event.radio = overrides.radio || ref('radio', overrides.radio_identity || 'radio:7001',
      overrides.radio_id || 7001, overrides.radio_label || 'Unit 7001', system);
  }
  return event;
}

function snapshot(revision, txState = 'active', legId = 'snapshot-leg', overrides = {}) {
  const value = {
    source_key: 'test-channel-activity', revision,
    tables: [{
      table_id: 'table-a', configuration_id: CONFIG_A, protocol: 'p25', system_name: 'System A',
      site: { wacn: 0xABCDE, system_id: 0x123, rfss: 1, site: 1, nac: 0x293 },
      rows: [{
        key: 'row-a', configuration_id: CONFIG_A, protocol: 'p25', activation_order: 5,
        call_leg_id: legId, tx_state: txState, status: overrides.status ?? (txState === 'active' ? 'CALL' : 'IDLE'),
        role: overrides.role || 'TRAFFIC',
        tx_start_ms: BASE_TIME - 100, tx_last_observed_at_ms: BASE_TIME + revision,
        tx_burst_generation: overrides.burst_generation ?? 1,
        tx_burst_started_at_ms: overrides.burst_started_at_ms ?? BASE_TIME - 50,
        tx_end_proven: overrides.tx_end_proven === true, tx_end_certain: overrides.tx_end_certain === true,
        tx_end_reason: overrides.tx_end_reason,
        source_entity_ref: ref('radio', 'radio:7001', 7001, 'Unit 7001'),
        target_entity_ref: ref('talkgroup', 'tg:101', 101, 'Group 101')
      }]
    }]
  };
  if (overrides.omit_tx_state) delete value.tables[0].rows[0].tx_state;
  return value;
}

async function modules() {
  const load = (name) => import(`${pathToFileURL(path.join(feature, name)).href}?model-test=1`);
  const [config, normalize, state, visibility, layout, fixture, entrypoint] = await Promise.all([
    load('config.js'), load('normalize.js'), load('state.js'), load('visibility.js'), load('layout.js'),
    load('fixture.js'), load('index.js')
  ]);
  return { config, normalize, state, visibility, layout, fixture, entrypoint };
}

async function main() {
  const { config, normalize, state: model, visibility, layout, fixture, entrypoint } = await modules();

  // The Balanced profile is explicit, centralized, immutable, and validated.
  assert.equal(config.BALANCED_CONFIG.render.softRadiosPerGroup, 100);
  assert.equal(config.BALANCED_CONFIG.render.softRadiosTotal, 1_000);
  assert.equal(config.BALANCED_CONFIG.render.softExpandedGroups, 80);
  assert.equal(config.BALANCED_CONFIG.render.softExpandedUniverses, 8);
  assert.equal(config.BALANCED_CONFIG.render.hardNodes, 1_000);
  assert.equal(config.BALANCED_CONFIG.render.hardLinks, 900);
  assert.equal(config.BALANCED_CONFIG.render.hardLabels, 80);
  assert.equal(config.BALANCED_CONFIG.render.hardParticles, 150);
  assert.equal(config.BALANCED_CONFIG.render.hardMigrationTrails, 50);
  assert.equal(config.BALANCED_CONFIG.render.migrationTrailTtlMs, 8_000);
  assert.equal(config.BALANCED_CONFIG.state.hardRadios, 20_000);
  assert.equal(config.BALANCED_CONFIG.state.hardGroups, 5_000);
  assert.equal(config.BALANCED_CONFIG.state.hardUniverses, 64);
  assert.equal(config.BALANCED_CONFIG.state.hardSemanticEvents, 5_000);
  assert.equal(config.BALANCED_CONFIG.state.hardDedupeEntries, 40_000);
  assert.equal(config.BALANCED_CONFIG.state.hardPinnedEntities, 100);
  assert.equal(config.BALANCED_CONFIG.state.hardSavedLayoutRecords, 512);
  assert.equal(config.BALANCED_CONFIG.state.hardOverflowCallKeys, 4_096);
  assert.equal(config.BALANCED_CONFIG.layout.systemRadius, 300);
  assert.deepEqual(config.BALANCED_CONFIG.animation, {
    txReleaseMs: 2_400,
    pulseDurationMs: 700,
    particleFlightMs: 1_500,
    migrationMotionMs: 1_400,
    effectCoalesceMs: 2_200,
    cameraTransitionMs: 720,
    cameraBackTransitionMs: 560,
    autoRotateDefault: true,
    autoRotateIdleDelayMs: 2_200,
    autoRotateSpeed: 0.35
  });
  assert(Object.isFrozen(config.BALANCED_CONFIG));
  assert(Object.isFrozen(config.BALANCED_CONFIG.animation));
  assert.throws(() => config.createConfig({ render: { unknown: 1 } }), /unknown or missing/);
  assert.throws(() => config.createConfig({ animation: { autoRotateDefault: 1 } }),
    /autoRotateDefault is invalid/);
  assert.throws(() => config.createConfig({ render: { migrationTrailTtlMs: 8_001 } }),
    /migrationTrailTtlMs is invalid/);
  assert.throws(() => config.createConfig({ render: { migrationTrailTtlMs: 1_000 } }),
    /migrationMotionMs cannot exceed migrationTrailTtlMs/);
  assert.throws(() => config.createConfig({ layout: { systemRadius: 250 } }),
    /systemRadius must contain/);

  // Only confirmed response semantics normalize as affiliation evidence.
  const accepted = normalize.normalizeObservation(affiliation(), BASE_TIME);
  assert.equal(accepted.kind, 'affiliation');
  assert.equal(accepted.evidenceType, 'group_affiliation_response');
  assert.equal(normalize.normalizeObservation(affiliation({ outcome: 'denied' }), BASE_TIME), null);
  assert.equal(normalize.normalizeObservation(affiliation({ evidence_type: 'group_affiliation_request' }), BASE_TIME), null);
  assert.equal(normalize.normalizeObservation(affiliation({ evidence_type: 'unit_registration' }), BASE_TIME), null);
  assert.equal(normalize.normalizeObservation(affiliation({ evidence_type: 'registration',
    event_id: 'accepted-affiliation-bearing-registration' }), BASE_TIME).kind, 'affiliation');
  assert.equal(normalize.normalizeObservation({ ...affiliation({ evidence_type: 'registration',
    event_id: 'generic-presence-registration' }), kind: 'presence_observed' }, BASE_TIME), null);
  const groupLessRegistration = affiliation({ evidence_type: 'registration',
    event_id: 'group-less-registration' });
  delete groupLessRegistration.group;
  assert.equal(normalize.normalizeObservation(groupLessRegistration, BASE_TIME), null);
  const nestedSite = normalize.normalizeObservation({
    ...affiliation({ event_id: 'nested-site-schema' }),
    configuration_id: undefined, rfss_id: undefined, site_id: undefined, wacn: undefined, system_id: undefined,
    timeslot: 2,
    site: { configuration_id: CONFIG_A, wacn: 0xF00BA, system_id: 0x321, rfss: 7, site: 9, nac: 659 }
  }, BASE_TIME);
  assert.deepEqual({ wacn: nestedSite.wacn, systemId: nestedSite.systemId, rfssId: nestedSite.site.rfssId,
    siteId: nestedSite.site.siteId, nac: nestedSite.site.nac, timeslot: nestedSite.site.timeslot },
  { wacn: 'F00BA', systemId: '321', rfssId: 7, siteId: 9, nac: 659, timeslot: 2 });
  const certainEnd = normalize.normalizeSnapshotRow(snapshot(1, 'ended', 'certain-leg',
    { tx_end_certain: true }).tables[0], snapshot(1, 'ended', 'certain-leg',
    { tx_end_certain: true }).tables[0].rows[0], BASE_TIME);
  assert.equal(certainEnd.endProven, true);
  const canonicalStart = normalize.normalizeSnapshotRow(snapshot(1).tables[0], {
    ...snapshot(1).tables[0].rows[0], tx_last_observed_at_ms: undefined, tx_observed_at_ms: undefined,
    tx_start_ms: BASE_TIME - 321, tx_started_at_ms: BASE_TIME - 999
  }, BASE_TIME);
  assert.equal(canonicalStart.observedAtMs, BASE_TIME - 321);
  const legacyStart = normalize.normalizeSnapshotRow(snapshot(1).tables[0], {
    ...snapshot(1).tables[0].rows[0], tx_last_observed_at_ms: undefined, tx_observed_at_ms: undefined,
    tx_start_ms: undefined, tx_started_at_ms: BASE_TIME - 999
  }, BASE_TIME);
  assert.equal(legacyStart.observedAtMs, BASE_TIME - 999);
  const unresolvedSnapshot = snapshot(1);
  const unresolvedRow = {
    ...unresolvedSnapshot.tables[0].rows[0],
    source_entity_ref: undefined,
    target_entity_ref: undefined,
    source_identity_key: 'v1-local-radio-a',
    target_identity_key: 'v1-local-group-a',
    source_id: '7001',
    target_id: '101',
    target_form: 'TALKGROUP'
  };
  const unresolvedCall = normalize.normalizeSnapshotRow(unresolvedSnapshot.tables[0], unresolvedRow, BASE_TIME);
  assert.equal(unresolvedCall.radio.identityKey, 'v1-local-radio-a');
  assert.equal(unresolvedCall.radio.observedLocalId, '7001');
  assert.equal(unresolvedCall.group.identityKey, 'v1-local-group-a');
  assert.equal(unresolvedCall.group.kind, 'talkgroup');
  const referenceMetadataSnapshot = snapshot(2);
  const referenceMetadataCall = normalize.normalizeSnapshotRow(referenceMetadataSnapshot.tables[0],
    { ...referenceMetadataSnapshot.tables[0].rows[0], source_id: undefined, target_id: undefined }, BASE_TIME);
  assert.equal(referenceMetadataCall.radio.displayId, '7001');
  assert.equal(referenceMetadataCall.radio.label, 'Unit 7001');
  assert.equal(referenceMetadataCall.group.displayId, '101');
  assert.equal(referenceMetadataCall.group.label, 'Group 101');
  const numericIds = normalize.normalizeObservation(affiliation({
    event_id: 'numeric-wire-identities',
    radio: { kind: 'radio', identity_key: 'radio:numeric', native_id: 7001,
      observed_local_id: 7001, radio_system_key: SYSTEM_A },
    group: { kind: 'talkgroup', identity_key: 'tg:numeric', native_id: 101,
      observed_local_id: 101, radio_system_key: SYSTEM_A }
  }), BASE_TIME);
  assert.equal(numericIds.radio.displayId, '7001');
  assert.equal(numericIds.group.displayId, '101');
  const reconciliationWire = normalize.normalizeObservation({
    kind: 'identity_reconciled', observed_at_ms: BASE_TIME, from_universe_key: 'prior-opaque-system',
    from_radio_identity_key: 'radio:1', protocol: 'p25', radio_system_key: SYSTEM_A,
    configuration_id: CONFIG_A, radio: ref('radio', 'radio:1', 1, 'Radio 1')
  }, BASE_TIME);
  assert.equal(reconciliationWire.fromUniverseKey, 'system:prior-opaque-system');
  assert.equal(reconciliationWire.radioSystemKey, SYSTEM_A);

  // Fresh state is empty. Confirmed evidence builds incrementally, while refreshes and conflicting scopes stay honest.
  const affiliationState = model.createNetworkState(config.BALANCED_CONFIG, BASE_TIME);
  assert.deepEqual(model.networkStateCounts(affiliationState), {
    generation: 1, universes: 0, groups: 0, radios: 0, activeCalls: 0, pendingGrants: 0, semanticEvents: 0,
    dedupeEntries: 0, queuedObservations: 0, pendingEffects: 0, pinnedEntities: 0, overflowActive: 0
  });
  const firstAffiliation = model.applyObservation(affiliationState, affiliation({ sequence: 1 }), 1, BASE_TIME + 1);
  assert.equal(firstAffiliation.first, true);
  assert.equal(firstAffiliation.transitionEvent, null);
  const originalRadioKey = [...affiliationState.radios.keys()][0];
  assert.match(originalRadioKey, /^system:opaque-system-a\|radio:/);
  assert.equal([...affiliationState.universes.values()][0].radioSystemKey, SYSTEM_A);
  assert.match([...affiliationState.universes.values()][0].label, /ABCDE:123/);
  assert.equal(affiliationState.pendingEffects.filter((effect) => effect.type === 'affiliation_arrival').length, 1);
  const arrivalEffect = affiliationState.pendingEffects.find((effect) => effect.type === 'affiliation_arrival');
  assert.equal(arrivalEffect.animationEndsAtMs - arrivalEffect.createdAtMs,
    config.BALANCED_CONFIG.animation.pulseDurationMs);
  assert.equal(arrivalEffect.expiresAtMs, arrivalEffect.animationEndsAtMs);
  assert.match(arrivalEffect.coalesceKey, /^radio-arrival:/);
  const refreshedAffiliation = model.applyObservation(affiliationState,
    affiliation({ sequence: 2, observed_at_ms: BASE_TIME + 2 }), 1, BASE_TIME + 2);
  assert.equal(refreshedAffiliation.changed, false);
  assert.equal(refreshedAffiliation.transitionEvent, null);
  assert.equal(affiliationState.semanticEvents.filter((event) => event.type === 'observed_affiliation_change').length, 0);
  const changed = model.applyObservation(affiliationState, affiliation({ sequence: 3, group_id: 202,
    observed_at_ms: BASE_TIME + 3 }), 1, BASE_TIME + 3);
  assert.equal(changed.changed, true);
  assert.equal(changed.transitionEvent?.type, 'observed_affiliation_change');
  assert.equal(changed.transitionEvent?.ambiguous, false);
  assert.notEqual(changed.transitionEvent?.oldGroupKey, changed.transitionEvent?.newGroupKey);
  assert.deepEqual(entrypoint.affiliationAlertView(affiliationState, changed.transitionEvent), {
    id: changed.transitionEvent.id,
    observedAtMs: BASE_TIME + 3,
    radioLabel: 'Unit 7001',
    fromLabel: 'Group 101',
    toLabel: 'Group 202',
    systemLabel: 'System A · ABCDE:123'
  });
  assert.equal(affiliationState.radios.size, 1);
  assert(affiliationState.radios.has(originalRadioKey));
  const originalGroup = [...affiliationState.groups.values()].find((group) => group.identityKey === 'tg:101');
  assert(!originalGroup.radioKeys.has(originalRadioKey));
  assert(!originalGroup.affiliatedRadioKeys.has(originalRadioKey));
  assert.equal(affiliationState.semanticEvents.filter((event) => event.type === 'observed_affiliation_change').length, 1);
  const migrationEffectRecord = affiliationState.pendingEffects.find((effect) => effect.type === 'migration');
  assert.equal(migrationEffectRecord.animationEndsAtMs - migrationEffectRecord.createdAtMs,
    config.BALANCED_CONFIG.animation.migrationMotionMs);
  assert.equal(migrationEffectRecord.expiresAtMs - migrationEffectRecord.createdAtMs,
    config.BALANCED_CONFIG.render.migrationTrailTtlMs);
  assert(migrationEffectRecord.animationEndsAtMs < migrationEffectRecord.expiresAtMs);
  assert.equal(migrationEffectRecord.coalesceKey, `radio-motion:${originalRadioKey}`);
  const migrationGraph = visibility.selectVisibleGraph(affiliationState, BASE_TIME + 4);
  assert(migrationGraph.links.some((link) => link.type === 'affiliation' && link.source === originalRadioKey &&
    link.target.endsWith('|group:tg:101') && link.faded && !link.dashed));
  assert(migrationGraph.links.some((link) => link.type === 'migration' && link.source === originalRadioKey &&
    link.target.endsWith('|group:tg:202') && !link.dashed));
  model.tickNetworkState(affiliationState, BASE_TIME + 2_000);
  assert(affiliationState.pendingEffects.some((effect) => effect.id === migrationEffectRecord.id),
    'the quiet retained trail must outlive its short motion window');
  assert.equal(affiliationState.semanticEvents.filter((event) => event.type === 'observed_affiliation_change').length, 1,
    'presentation aging must not alter semantic history');
  assert.equal(model.applyObservation(affiliationState, affiliation({ sequence: 2, group_id: 303,
    observed_at_ms: BASE_TIME + 2, event_id: 'older' }), 1, BASE_TIME + 4).reason, 'out_of_order');
  assert(![...affiliationState.groups.values()].some((group) => group.identityKey === 'tg:303'));
  assert.equal(affiliationState.semanticEvents.filter((event) => event.type === 'observed_affiliation_change').length, 1);
  const otherScopeSame = affiliation({ sequence: 20, group_id: 202, configuration_id: CONFIG_B,
    comparison_scope_key: `${CONFIG_B}:1:2:x`, site_id: 2, observed_at_ms: BASE_TIME + 20 });
  const sameGroupOtherScope = model.applyObservation(affiliationState, otherScopeSame, 1, BASE_TIME + 20);
  assert.equal(sameGroupOtherScope.changed, false);
  assert.equal(sameGroupOtherScope.transitionEvent, null);
  assert.equal(affiliationState.radios.get(originalRadioKey).affiliationAmbiguous, false);
  const conflict = affiliation({ sequence: 21, group_id: 404, configuration_id: CONFIG_B,
    comparison_scope_key: `${CONFIG_B}:1:2:x`, site_id: 2, observed_at_ms: BASE_TIME + 21 });
  const ambiguousChange = model.applyObservation(affiliationState, conflict, 1, BASE_TIME + 21);
  assert.equal(ambiguousChange.changed, true);
  assert.equal(ambiguousChange.transitionEvent?.ambiguous, true);
  assert.equal(entrypoint.affiliationAlertView(affiliationState, ambiguousChange.transitionEvent), null);
  assert.equal(affiliationState.radios.get(originalRadioKey).affiliationAmbiguous, true);
  assert.equal(affiliationState.semanticEvents.filter((event) => event.type === 'observed_affiliation_change').length, 2);
  assert.equal(model.applyObservation(affiliationState, conflict, 1, BASE_TIME + 21).reason, 'duplicate');

  // Selection and pinning force the complete aged hierarchy open without relaxing the hard node ceiling.
  const agedConfig = config.createConfig({ render: { softExpandedUniverses: 1, softExpandedGroups: 1,
    radioQuietAfterMs: 1_000, radioHideAfterMs: 2_000, groupCollapseAfterMs: 3_000,
    universeCollapseAfterMs: 4_000 } });
  const aged = model.createNetworkState(agedConfig, BASE_TIME);
  model.applyObservation(aged, affiliation({ event_id: 'aged-a', observed_at_ms: BASE_TIME + 1 }), 1,
    BASE_TIME + 1);
  const agedRadio = [...aged.radios.values()][0];
  const agedGroupKey = agedRadio.visualParentGroupKey;
  const agedUniverseKey = agedRadio.universeKey;
  model.applyObservation(aged, affiliation({ radio_system_key: SYSTEM_B, system_name: 'System B',
    configuration_id: CONFIG_B, comparison_scope_key: `${CONFIG_B}:1:1:x`, event_id: 'fresh-b',
    observed_at_ms: BASE_TIME + 5_000,
    radio: ref('radio', 'radio:8001', 8001, 'Other 8001', SYSTEM_B),
    group: ref('talkgroup', 'tg:801', 801, 'Other 801', SYSTEM_B) }), 1, BASE_TIME + 5_000);
  model.setSelectedEntity(aged, agedRadio.key);
  const selectedAged = visibility.selectVisibleGraph(aged, BASE_TIME + 6_000);
  assert(selectedAged.nodes.some((node) => node.key === agedRadio.key));
  assert.equal(selectedAged.nodes.find((node) => node.key === agedGroupKey)?.collapsed, false);
  assert.equal(selectedAged.nodes.find((node) => node.key === agedUniverseKey)?.collapsed, false);
  model.setSelectedEntity(aged, null);
  assert.equal(model.setEntityPinned(aged, agedRadio.key, true), true);
  const pinnedAged = visibility.selectVisibleGraph(aged, BASE_TIME + 6_001);
  assert(pinnedAged.nodes.some((node) => node.key === agedRadio.key));
  assert.equal(pinnedAged.nodes.find((node) => node.key === agedGroupKey)?.collapsed, false);
  assert.equal(pinnedAged.nodes.find((node) => node.key === agedUniverseKey)?.collapsed, false);
  assert(pinnedAged.nodes.length <= agedConfig.render.hardNodes);

  // A protected retained radio remains visible at its universe after its final explicit relationship is cleared.
  const orphaned = model.createNetworkState(config.BALANCED_CONFIG, BASE_TIME);
  model.applyObservation(orphaned, affiliation({ event_id: 'orphan-affiliation', sequence: 1 }), 1,
    BASE_TIME + 1);
  const orphanedRadio = [...orphaned.radios.values()][0];
  const orphanedGroup = orphaned.groups.get(orphanedRadio.visualParentGroupKey);
  const orphanedUniverse = orphaned.universes.get(orphanedRadio.universeKey);
  model.setSelectedEntity(orphaned, orphanedRadio.key);
  assert.equal(model.setEntityPinned(orphaned, orphanedRadio.key, true), true);
  model.applyObservation(orphaned, {
    ...affiliation({ event_id: 'orphan-clear', sequence: 2, observed_at_ms: BASE_TIME + 2 }),
    kind: 'presence_cleared', reason: 'explicit_test_clear'
  }, 1, BASE_TIME + 2);
  assert.equal(orphanedRadio.relatedGroupKeys.size, 0);
  assert.equal(orphanedRadio.affiliatedGroupKeys.size, 0);
  assert(!orphanedGroup.radioKeys.has(orphanedRadio.key));
  assert(!orphanedGroup.affiliatedRadioKeys.has(orphanedRadio.key));
  assert(orphanedUniverse.radioKeys.has(orphanedRadio.key));
  const orphanedGraph = visibility.selectVisibleGraph(orphaned, BASE_TIME + 3);
  assert(orphanedGraph.nodes.some((node) => node.key === orphanedRadio.key && node.groupKey === ''));
  assert(orphanedGraph.nodes.some((node) => node.key === orphanedUniverse.key));

  // Tight hard budgets reserve a complete ancestor path for pinned and selected radios.
  const tightConfig = config.createConfig({
    render: { hardNodes: 8, hardLabels: 8, softExpandedUniverses: 8, softExpandedGroups: 8,
      softRadiosTotal: 8, softRadiosPerGroup: 8 },
    state: { hardUniverses: 10, hardGroups: 20, hardRadios: 20 }
  });
  const tight = model.createNetworkState(tightConfig, BASE_TIME);
  for (let index = 0; index < 8; index += 1) {
    const suffix = String(index + 1);
    const system = `tight-system-${suffix}`;
    const configurationId = `${suffix.padStart(8, '0')}-1111-4111-8111-${suffix.padStart(12, '0')}`;
    model.applyObservation(tight, affiliation({
      event_id: `tight-${suffix}`, observed_at_ms: BASE_TIME + index + 1,
      radio_system_key: system, system_name: `Tight ${suffix}`, configuration_id: configurationId,
      comparison_scope_key: `${configurationId}:1:1:x`,
      radio: ref('radio', `radio:tight:${suffix}`, index + 1, `Tight Radio ${suffix}`, system),
      group: ref('talkgroup', `tg:tight:${suffix}`, index + 1, `Tight Group ${suffix}`, system)
    }), 1, BASE_TIME + index + 1);
  }
  const protectedRadio = [...tight.radios.values()][0];
  const assertProtectedPath = (graph) => {
    assert(graph.nodes.length <= tightConfig.render.hardNodes);
    assert(graph.nodes.some((node) => node.key === protectedRadio.universeKey && !node.collapsed));
    assert(graph.nodes.some((node) => node.key === protectedRadio.visualParentGroupKey && !node.collapsed));
    assert(graph.nodes.some((node) => node.key === protectedRadio.key));
  };
  assert.equal(model.setEntityPinned(tight, protectedRadio.key, true), true);
  assertProtectedPath(visibility.selectVisibleGraph(tight, BASE_TIME + 20));
  assert.equal(model.setEntityPinned(tight, protectedRadio.key, false), true);
  model.setSelectedEntity(tight, protectedRadio.key);
  assertProtectedPath(visibility.selectVisibleGraph(tight, BASE_TIME + 21));
  model.setSelectedEntity(tight, null);

  // Active sources and their target hubs outrank migration and pin detail when the hard ceiling is exhausted.
  const priorityState = model.createNetworkState(tightConfig, BASE_TIME);
  const priorityConfiguration = (index) =>
    `${String(index).padStart(8, '0')}-2222-4222-8222-${String(index).padStart(12, '0')}`;
  const activePriorityKeys = [];
  for (let index = 1; index <= 2; index += 1) {
    const system = `priority-active-${index}`;
    const radio = ref('radio', `radio:priority-active:${index}`, index, `Priority Active ${index}`, system);
    const group = ref('talkgroup', `tg:priority-active:${index}`, index, `Priority Group ${index}`, system);
    model.applyObservation(priorityState, call({
      event_id: `priority-active-${index}`, observed_at_ms: BASE_TIME + 100 + index,
      radio_system_key: system, configuration_id: priorityConfiguration(index),
      call_leg_id: `priority-leg-${index}`, resource_context_key: `priority-resource-${index}`, radio, group
    }), 1, BASE_TIME + 100 + index);
    const activeRadio = [...priorityState.radios.values()].find((candidate) =>
      candidate.identityKey === radio.identity_key);
    activePriorityKeys.push({ radioKey: activeRadio.key,
      groupKey: [...activeRadio.activeCallKeys].map((key) => priorityState.activeCalls.get(key).groupKey)[0] });
  }
  const migrationSystem = 'priority-migration';
  const migrationConfiguration = priorityConfiguration(3);
  const migrationRadioRef = ref('radio', 'radio:priority-migration', 3, 'Priority Migration', migrationSystem);
  model.applyObservation(priorityState, affiliation({
    event_id: 'priority-migration-first', observed_at_ms: BASE_TIME + 103, sequence: 1,
    radio_system_key: migrationSystem, configuration_id: migrationConfiguration,
    comparison_scope_key: `${migrationConfiguration}:1:1:x`, radio: migrationRadioRef,
    group: ref('talkgroup', 'tg:priority-migration-a', 31, 'Migration A', migrationSystem)
  }), 1, BASE_TIME + 103);
  model.applyObservation(priorityState, affiliation({
    event_id: 'priority-migration-second', observed_at_ms: BASE_TIME + 104, sequence: 2,
    radio_system_key: migrationSystem, configuration_id: migrationConfiguration,
    comparison_scope_key: `${migrationConfiguration}:1:1:x`, radio: migrationRadioRef,
    group: ref('talkgroup', 'tg:priority-migration-b', 32, 'Migration B', migrationSystem)
  }), 1, BASE_TIME + 104);
  const migrationRadio = [...priorityState.radios.values()].find((candidate) =>
    candidate.identityKey === migrationRadioRef.identity_key);
  const pinnedSystem = 'priority-pinned';
  const pinnedConfiguration = priorityConfiguration(4);
  model.applyObservation(priorityState, affiliation({
    event_id: 'priority-pinned', observed_at_ms: BASE_TIME + 105,
    radio_system_key: pinnedSystem, configuration_id: pinnedConfiguration,
    comparison_scope_key: `${pinnedConfiguration}:1:1:x`,
    radio: ref('radio', 'radio:priority-pinned', 4, 'Priority Pinned', pinnedSystem),
    group: ref('talkgroup', 'tg:priority-pinned', 4, 'Pinned Group', pinnedSystem)
  }), 1, BASE_TIME + 105);
  const priorityPinnedRadio = [...priorityState.radios.values()].find((candidate) =>
    candidate.identityKey === 'radio:priority-pinned');
  assert.equal(model.setEntityPinned(priorityState, priorityPinnedRadio.key, true), true);
  const priorityGraph = visibility.selectVisibleGraph(priorityState, BASE_TIME + 106);
  activePriorityKeys.forEach(({ radioKey: key, groupKey }) => {
    assert(priorityGraph.nodes.some((node) => node.key === key && node.active));
    assert(priorityGraph.nodes.some((node) => node.key === groupKey && node.active));
  });
  assert(!priorityGraph.nodes.some((node) => node.key === migrationRadio.key));
  assert(!priorityGraph.nodes.some((node) => node.key === priorityPinnedRadio.key));
  assert(priorityGraph.nodes.length <= tightConfig.render.hardNodes);

  // Dense active traffic always leaves individual active paths plus one exact, de-duplicated aggregate.
  const denseActive = model.createNetworkState(tightConfig, BASE_TIME);
  for (let index = 0; index < 12; index += 1) {
    model.applyObservation(denseActive, affiliation({
      event_id: `dense-affiliation-${index}`, observed_at_ms: BASE_TIME + 180 + index,
      sequence: index + 1, group_id: 202, radio_identity: `radio:dense:${index}`,
      radio_id: 10_000 + index, radio_label: `Dense ${index}`
    }), 1, BASE_TIME + 180 + index);
    model.applyObservation(denseActive, call({
      event_id: `dense-active-${index}`, observed_at_ms: BASE_TIME + 200 + index,
      call_leg_id: `dense-leg-${index}`, resource_context_key: `dense-resource-${index}`,
      radio_identity: `radio:dense:${index}`, radio_id: 10_000 + index,
      radio_label: `Dense ${index}`
    }), 1, BASE_TIME + 200 + index);
  }
  const denseGraph = visibility.selectVisibleGraph(denseActive, BASE_TIME + 220);
  const denseVisibleActive = denseGraph.nodes.filter((node) => node.type === 'radio' && node.active).length;
  const denseSuppressed = 12 - denseVisibleActive;
  assert.equal(denseGraph.nodes.length, tightConfig.render.hardNodes);
  assert(denseVisibleActive > 0);
  assert(denseGraph.nodes.some((node) => node.type === 'group' && node.active));
  assert.equal(denseGraph.counts.suppressedActiveRadios, denseSuppressed);
  assert.equal(denseGraph.aggregates.reduce((sum, node) => sum + node.suppressedActiveCount, 0),
    denseSuppressed);
  assert(denseGraph.aggregates.some((node) => node.active && node.suppressedActiveCount > 0));
  assert(denseGraph.aggregates.filter((node) => node.kind !== 'active_call_legs')
    .some((node) => node.focusKey));

  // The same accounting remains exact when activity spans more universes than the eight-node ceiling.
  const wideActive = model.createNetworkState(tightConfig, BASE_TIME);
  for (let index = 1; index <= 6; index += 1) {
    const system = `wide-active-${index}`;
    model.applyObservation(wideActive, call({
      event_id: `wide-active-${index}`, observed_at_ms: BASE_TIME + 300 + index,
      radio_system_key: system, configuration_id: priorityConfiguration(index),
      call_leg_id: `wide-leg-${index}`, resource_context_key: `wide-resource-${index}`,
      radio: ref('radio', `radio:wide:${index}`, index, `Wide Radio ${index}`, system),
      group: ref('talkgroup', `tg:wide:${index}`, index, `Wide Group ${index}`, system)
    }), 1, BASE_TIME + 300 + index);
  }
  const wideGraph = visibility.selectVisibleGraph(wideActive, BASE_TIME + 320);
  const wideVisibleActive = wideGraph.nodes.filter((node) => node.type === 'radio' && node.active);
  assert.equal(wideGraph.nodes.length, tightConfig.render.hardNodes);
  assert(wideVisibleActive.length > 0);
  wideVisibleActive.forEach((node) => {
    const callRecord = [...node.entity.activeCallKeys].map((key) => wideActive.activeCalls.get(key))[0];
    assert(wideGraph.nodes.some((candidate) => candidate.key === callRecord.groupKey && candidate.active));
  });
  assert.equal(wideGraph.counts.suppressedActiveRadios, 6 - wideVisibleActive.length);
  assert.equal(wideGraph.aggregates.reduce((sum, node) => sum + node.suppressedActiveCount, 0),
    6 - wideVisibleActive.length);

  // TX activity is independent from affiliation, unknown sources never create synthetic subscribers, and legs nest.
  const txState = model.createNetworkState(config.BALANCED_CONFIG, BASE_TIME);
  model.applyObservation(txState, affiliation({ group_id: 101 }), 1, BASE_TIME + 1);
  const radioKey = [...txState.radios.keys()][0];
  const affiliationGroup = txState.radios.get(radioKey).visualParentGroupKey;
  model.applyObservation(txState, call({ group_id: 202, call_leg_id: 'leg-a', resource_context_key: 'r-a',
    observed_at_ms: BASE_TIME + 100 }), 1, BASE_TIME + 100);
  const txGroup = [...txState.groups.values()].find((group) => group.identityKey === 'tg:202');
  assert.equal(txState.radios.get(radioKey).visualParentGroupKey, affiliationGroup);
  assert.equal(txState.radios.get(radioKey).affiliations.size, 1);
  assert.equal(txGroup.activeCallKeys.size, 1);
  const radioCount = txState.radios.size;
  model.applyObservation(txState, call({ group_id: 202, include_radio: false, call_leg_id: 'leg-unknown',
    resource_context_key: 'r-u', observed_at_ms: BASE_TIME + 101 }), 1, BASE_TIME + 101);
  assert.equal(txState.radios.size, radioCount);
  assert.equal(txGroup.activeCallKeys.size, 2);
  model.applyObservation(txState, call({ group_id: 202, call_leg_id: 'leg-b', resource_context_key: 'r-b',
    observed_at_ms: BASE_TIME + 102 }), 1, BASE_TIME + 102);
  assert.equal(txGroup.activeCallKeys.size, 3);
  const targetEffects = txState.pendingEffects.filter((effect) =>
    ['tx_pulse', 'destination_highlight'].includes(effect.type) && effect.targetKey === txGroup.key);
  assert.equal(targetEffects.length, 1,
    'overlapping call legs update activity state without replaying the already-lit target animation');
  assert.equal(targetEffects[0].coalesceKey, `tx-start:${radioKey}`);
  assert(targetEffects.every((effect) => effect.animationEndsAtMs - effect.createdAtMs ===
    config.BALANCED_CONFIG.animation.pulseDurationMs));
  assert.equal(txState.semanticEvents.filter((event) => event.type === 'transmission_start').length, 3);
  model.applyObservation(txState, call({ kind: 'call_end', transmission_state: 'ended', group_id: 202,
    call_leg_id: 'leg-a', resource_context_key: 'r-a', observed_at_ms: BASE_TIME + 110, end_proven: true }),
  1, BASE_TIME + 110);
  assert.equal(txGroup.activeCallKeys.size, 2);
  const graphDuringTx = visibility.selectVisibleGraph(txState, BASE_TIME + 111);
  assert(graphDuringTx.links.some((link) => link.type === 'affiliation' && !link.dashed && link.affiliation));
  assert(graphDuringTx.links.some((link) => link.type === 'tx' && link.dashed && !link.affiliation));

  const sameTargetState = model.createNetworkState(config.BALANCED_CONFIG, BASE_TIME);
  model.applyObservation(sameTargetState, affiliation({ event_id: 'same-target-affiliation' }),
    1, BASE_TIME + 1);
  model.applyObservation(sameTargetState, call({ event_id: 'same-target-start', call_leg_id: 'same-target-leg',
    resource_context_key: 'same-target-resource', observed_at_ms: BASE_TIME + 2 }), 1, BASE_TIME + 2);
  const sameTargetRadio = [...sameTargetState.radios.values()][0];
  const sameTargetGroup = sameTargetState.groups.get(sameTargetRadio.visualParentGroupKey);
  let sameTargetGraph = visibility.selectVisibleGraph(sameTargetState, BASE_TIME + 3);
  let sameTargetLinks = sameTargetGraph.links.filter((link) => link.source === sameTargetRadio.key &&
    link.target === sameTargetGroup.key);
  assert.equal(sameTargetLinks.length, 2);
  const sameTargetAffiliation = sameTargetLinks.find((link) => link.type === 'affiliation');
  const sameTargetActivityKey = `activity:${sameTargetRadio.key}:${sameTargetGroup.key}`;
  const sameTargetTx = sameTargetLinks.find((link) => link.key === sameTargetActivityKey);
  assert.deepEqual({ active: sameTargetAffiliation.active, affiliation: sameTargetAffiliation.affiliation,
    dashed: sameTargetAffiliation.dashed }, { active: false, affiliation: true, dashed: false });
  assert.deepEqual({ type: sameTargetTx.type, active: sameTargetTx.active,
    affiliation: sameTargetTx.affiliation, dashed: sameTargetTx.dashed },
  { type: 'tx', active: true, affiliation: false, dashed: false });
  model.applyObservation(sameTargetState, call({ kind: 'call_end', transmission_state: 'ended',
    event_id: 'same-target-end', call_leg_id: 'same-target-leg', resource_context_key: 'same-target-resource',
    observed_at_ms: BASE_TIME + 4, end_proven: true }), 1, BASE_TIME + 4);
  sameTargetGraph = visibility.selectVisibleGraph(sameTargetState, BASE_TIME + 5);
  sameTargetLinks = sameTargetGraph.links.filter((link) => link.source === sameTargetRadio.key &&
    link.target === sameTargetGroup.key);
  assert.equal(sameTargetLinks.length, 2);
  assert(sameTargetLinks.some((link) => link.type === 'affiliation' && link.affiliation && !link.dashed));
  const sameTargetRecent = sameTargetLinks.find((link) => link.key === sameTargetActivityKey);
  assert.deepEqual({ type: sameTargetRecent.type, active: sameTargetRecent.active,
    affiliation: sameTargetRecent.affiliation, afterglow: sameTargetRecent.afterglow,
    dashed: sameTargetRecent.dashed, faded: sameTargetRecent.faded },
  { type: 'activity', active: false, affiliation: false, afterglow: true, dashed: false, faded: false });
  assert.equal(sameTargetGraph.nodes.find((node) => node.key === sameTargetRadio.key).afterglow, true);
  assert.equal(sameTargetGraph.nodes.find((node) => node.key === sameTargetGroup.key).afterglow, true);
  const afterglowEffect = sameTargetState.pendingEffects.find((effect) => effect.type === 'afterglow');
  assert.equal(afterglowEffect.expiresAtMs - afterglowEffect.createdAtMs,
    config.BALANCED_CONFIG.animation.txReleaseMs);
  assert.equal(afterglowEffect.animationEndsAtMs, afterglowEffect.expiresAtMs);
  const expiredAfterglowGraph = visibility.selectVisibleGraph(sameTargetState,
    BASE_TIME + 4 + config.BALANCED_CONFIG.animation.txReleaseMs + 1);
  assert.equal(expiredAfterglowGraph.nodes.find((node) => node.key === sameTargetRadio.key).afterglow, false);
  assert.equal(expiredAfterglowGraph.nodes.find((node) => node.key === sameTargetGroup.key).afterglow, false);
  const expiredRecent = expiredAfterglowGraph.links.find((link) => link.key === sameTargetActivityKey);
  assert.equal(expiredRecent.afterglow, false);
  assert.equal(expiredRecent.dashed, true);
  assert.equal(expiredRecent.faded, true);

  // Explicit hierarchy scopes filter retained state before allocating render budgets.
  const scopedConfig = config.createConfig({
    render: { hardNodes: 8, hardLabels: 8, softExpandedUniverses: 2, softExpandedGroups: 2,
      softRadiosPerGroup: 2, softRadiosTotal: 3 },
    state: { hardUniverses: 4, hardGroups: 20, hardRadios: 30, hardActiveCalls: 30 }
  });
  const scopedState = model.createNetworkState(scopedConfig, BASE_TIME);
  const scopedRadios = [];
  for (let index = 0; index < 8; index += 1) {
    const radio = ref('radio', `radio:scoped:${index}`, 10_000 + index, `Scoped ${index}`, SYSTEM_A);
    const group = ref('talkgroup', 'tg:scoped-primary', 301, 'Scoped Primary', SYSTEM_A);
    model.applyObservation(scopedState, affiliation({
      event_id: `scope-affiliation-${index}`, sequence: index + 1,
      observed_at_ms: BASE_TIME + 10 + index, radio, group,
      comparison_scope_key: `${CONFIG_A}:1:1:${index}`
    }), 1, BASE_TIME + 10 + index);
    model.applyObservation(scopedState, call({
      event_id: `scope-call-${index}`, observed_at_ms: BASE_TIME + 100 + index,
      call_leg_id: `scope-leg-${index}`, resource_context_key: `scope-resource-${index}`,
      radio, group, group_id: 301
    }), 1, BASE_TIME + 100 + index);
    scopedRadios.push([...scopedState.radios.values()].find((candidate) =>
      candidate.identityKey === radio.identity_key));
  }
  model.applyObservation(scopedState, affiliation({
    event_id: 'scope-secondary', sequence: 20, observed_at_ms: BASE_TIME + 120,
    radio: ref('radio', 'radio:scoped-secondary', 10_100, 'Scoped Secondary', SYSTEM_A),
    group: ref('talkgroup', 'tg:scoped-secondary', 302, 'Scoped Secondary Group', SYSTEM_A),
    comparison_scope_key: `${CONFIG_A}:1:1:secondary`
  }), 1, BASE_TIME + 120);
  model.applyObservation(scopedState, call({
    event_id: 'scope-other-system', observed_at_ms: BASE_TIME + 121,
    radio_system_key: SYSTEM_B, configuration_id: CONFIG_B, system_name: 'System B',
    call_leg_id: 'scope-other-leg', resource_context_key: 'scope-other-resource',
    radio: ref('radio', 'radio:scoped-other', 20_000, 'Scoped Other', SYSTEM_B),
    group: ref('talkgroup', 'tg:scoped-other', 401, 'Scoped Other Group', SYSTEM_B)
  }), 1, BASE_TIME + 121);
  const scopedUniverse = [...scopedState.universes.values()].find((universe) =>
    universe.radioSystemKey === SYSTEM_A);
  const scopedGroup = [...scopedState.groups.values()].find((group) =>
    group.identityKey === 'tg:scoped-primary');
  const scopedSecondaryGroup = [...scopedState.groups.values()].find((group) =>
    group.identityKey === 'tg:scoped-secondary');
  model.setSelectedEntity(scopedState, scopedRadios[0].key);
  model.applyObservation(scopedState, affiliation({
    event_id: 'scope-visible-migration', sequence: 21, observed_at_ms: BASE_TIME + 125,
    radio: ref('radio', 'radio:scoped:0', 10_000, 'Scoped 0', SYSTEM_A),
    group: ref('talkgroup', 'tg:scoped-secondary', 302, 'Scoped Secondary Group', SYSTEM_A),
    comparison_scope_key: `${CONFIG_A}:1:1:0`
  }), 1, BASE_TIME + 125);

  const overviewGraph = visibility.selectVisibleGraph(scopedState, BASE_TIME + 130,
    { scope: { level: 'overview' } });
  assert.deepEqual(overviewGraph.scope, { level: 'overview' });
  assert(overviewGraph.nodes.length > 0);
  assert(overviewGraph.nodes.every((node) => node.type === 'universe'));
  assert(overviewGraph.nodes.find((node) => node.key === scopedUniverse.key).active);
  assert.equal(overviewGraph.links.length, 0);

  const systemGraph = visibility.selectVisibleGraph(scopedState, BASE_TIME + 131,
    { scope: { level: 'system', universeKey: scopedUniverse.key } });
  assert.deepEqual(systemGraph.scope, { level: 'system', universeKey: scopedUniverse.key });
  assert(systemGraph.nodes.some((node) => node.key === scopedUniverse.key));
  assert(systemGraph.nodes.some((node) => node.key === scopedGroup.key && node.active));
  assert(systemGraph.nodes.some((node) => node.key === scopedSecondaryGroup.key));
  assert(systemGraph.nodes.some((node) => node.key === scopedRadios[0].key && node.selected));
  assert(systemGraph.nodes.every((node) => !node.universeKey || node.universeKey === scopedUniverse.key));
  assert(systemGraph.links.some((link) => link.type === 'migration' &&
    link.source === scopedRadios[0].key && link.target === scopedSecondaryGroup.key &&
    link.fromGroupKey === scopedGroup.key));
  assert(systemGraph.effects.some((effect) => effect.type === 'migration' &&
    effect.nodeKey === scopedRadios[0].key && effect.sourceKey === scopedGroup.key &&
    effect.targetKey === scopedSecondaryGroup.key));

  const groupGraph = visibility.selectVisibleGraph(scopedState, BASE_TIME + 132,
    { scope: { level: 'group', universeKey: scopedUniverse.key, groupKey: scopedGroup.key } });
  assert.deepEqual(groupGraph.scope,
    { level: 'group', universeKey: scopedUniverse.key, groupKey: scopedGroup.key });
  assert(groupGraph.nodes.length <= scopedConfig.render.hardNodes);
  assert(!groupGraph.nodes.some((node) => node.type === 'universe'),
    'talkgroup focus should keep system context in navigation without framing the parent system node');
  assert(groupGraph.nodes.some((node) => node.key === scopedGroup.key));
  assert(!groupGraph.nodes.some((node) => node.key === scopedSecondaryGroup.key));
  assert(!groupGraph.nodes.some((node) => node.universeKey && node.universeKey !== scopedUniverse.key));
  assert(groupGraph.nodes.some((node) => node.key === scopedRadios[0].key && node.selected));
  const scopedVisibleActive = groupGraph.nodes.filter((node) => node.type === 'radio' && node.active).length;
  assert.equal(groupGraph.counts.suppressedActiveRadios, 8 - scopedVisibleActive);
  assert.equal(groupGraph.aggregates.reduce((sum, node) => sum + node.suppressedActiveCount, 0),
    8 - scopedVisibleActive);
  assert(groupGraph.links.every((link) => groupGraph.nodes.some((node) => node.key === link.source) &&
    groupGraph.nodes.some((node) => node.key === link.target)));

  const invalidScopeGraph = visibility.selectVisibleGraph(scopedState, BASE_TIME + 133,
    { scope: { level: 'group', universeKey: scopedUniverse.key, groupKey: 'missing-group' } });
  assert.deepEqual(invalidScopeGraph.scope, { level: 'system', universeKey: scopedUniverse.key });
  assert(invalidScopeGraph.nodes.some((node) => node.type === 'radio'));
  assert(invalidScopeGraph.nodes.every((node) => !node.universeKey ||
    node.universeKey === scopedUniverse.key));
  const missingUniverseGraph = visibility.selectVisibleGraph(scopedState, BASE_TIME + 134,
    { scope: { level: 'system', universeKey: 'missing-universe' } });
  assert.deepEqual(missingUniverseGraph.scope, { level: 'overview' });
  assert(missingUniverseGraph.nodes.every((node) => node.type === 'universe'));

  model.markTransportGap(txState, { reason: 'test_gap' }, 1, BASE_TIME + 120);
  assert.equal(txState.activeCalls.size, 0);
  assert.equal(txState.radios.get(radioKey).affiliations.size, 1);
  assert.equal(txState.transport.status, 'gap');
  const postGap = model.applyObservation(txState, affiliation({ sequence: 0, group_id: 303,
    event_id: 'post-gap-lower-sequence', observed_at_ms: BASE_TIME + 121 }), 1, BASE_TIME + 121);
  assert.equal(postGap.changed, false);
  assert.equal(txState.semanticEvents.filter((event) => event.type === 'observed_affiliation_change').length, 0);
  assert(txState.semanticEvents.some((event) => event.type === 'affiliation_observed_after_gap' &&
    event.semanticMigration === false));

  // Grant/pending can introduce real identities without claiming a transmission or affiliation.
  const pendingState = model.createNetworkState(config.BALANCED_CONFIG, BASE_TIME);
  const pendingResult = model.applyObservation(pendingState, call({ kind: 'grant', transmission_state: 'pending',
    call_leg_id: 'pending-leg' }), 1, BASE_TIME + 1);
  assert.equal(pendingResult.granted, true);
  assert.equal(pendingState.radios.size, 1);
  assert.equal(pendingState.activeCalls.size, 0);
  const pendingRadio = [...pendingState.radios.values()][0];
  assert.equal(pendingRadio.affiliations.size, 0);
  assert.equal(pendingRadio.recentTxGroupKey, null);
  assert.equal(pendingRadio.visualParentGroupKey, null);
  assert(!pendingState.pendingEffects.some((effect) => effect.type === 'tx_pulse'));
  const pendingGraph = visibility.selectVisibleGraph(pendingState, BASE_TIME + 2);
  assert(pendingGraph.nodes.some((node) => node.type === 'group' && node.pending && !node.active));
  assert(!pendingGraph.nodes.some((node) => node.type === 'radio'),
    'a grant-only source remains retained without displacing meaningful subscriber nodes');
  assert.equal(pendingGraph.links.length, 0,
    'a grant without observed transmission must not create an activity-only relationship');

  // A grant-created CALL/TRAFFIC row without AudioCall evidence is pending, never active/green.
  const grantSnapshotState = model.createNetworkState(config.BALANCED_CONFIG, BASE_TIME);
  const grantSnapshot = snapshot(1, 'active', 'grant-only-leg', { omit_tx_state: true, status: 'CALL' });
  assert.equal(normalize.snapshotRowActive(grantSnapshot.tables[0].rows[0]), false);
  assert.equal(normalize.snapshotRowPending(grantSnapshot.tables[0].rows[0]), true);
  const firstGrantSnapshot = model.ingestChannelActivitySnapshot(grantSnapshotState, grantSnapshot, 1, BASE_TIME + 1);
  assert.equal(firstGrantSnapshot.pendingChanged, 1);
  assert.equal(grantSnapshotState.activeCalls.size, 0);
  assert.equal(grantSnapshotState.radios.size, 1);
  const grantSnapshotGraph = visibility.selectVisibleGraph(grantSnapshotState, BASE_TIME + 2);
  assert(grantSnapshotGraph.nodes.some((node) => node.type === 'group' && node.pending && !node.active));
  assert.equal(grantSnapshotGraph.links.length, 0);
  const repeatedGrantSnapshot = snapshot(2, 'active', 'grant-only-leg', { omit_tx_state: true, status: 'CALL' });
  const repeatedGrant = model.ingestChannelActivitySnapshot(grantSnapshotState, repeatedGrantSnapshot, 1,
    BASE_TIME + 6_000);
  assert.equal(repeatedGrant.pendingChanged, 0);
  assert.equal(repeatedGrant.visualChanged, false);
  assert.equal(grantSnapshotState.semanticEvents.filter((event) => event.type === 'grant_pending').length, 1);
  assert(visibility.selectVisibleGraph(grantSnapshotState, BASE_TIME + 6_001).nodes
    .some((node) => node.type === 'group' && node.pending),
  'pending presentation follows the authoritative row instead of an independent four-second timer');
  const clearedGrant = model.ingestChannelActivitySnapshot(grantSnapshotState,
    snapshot(3, 'ended', 'grant-only-leg', { omit_tx_state: true, status: 'IDLE' }), 1, BASE_TIME + 6_100);
  assert.equal(clearedGrant.pendingChanged, 1);
  assert.equal(grantSnapshotState.pendingGrants.size, 0);

  // Current snapshot legs are accepted once; idle/linger rows never populate a fresh state.
  const idleState = model.createNetworkState(config.BALANCED_CONFIG, BASE_TIME);
  model.ingestChannelActivitySnapshot(idleState, snapshot(1, 'ended', 'old-leg', { tx_end_proven: true }), 1,
    BASE_TIME + 1);
  assert.equal(idleState.universes.size, 0);
  const snapshotState = model.createNetworkState(config.BALANCED_CONFIG, BASE_TIME);
  assert.equal(model.ingestChannelActivitySnapshot(snapshotState, snapshot(1), 1, BASE_TIME + 1).introduced, 1);
  assert.match([...snapshotState.universes.values()][0].label, /ABCDE:123/);
  const transmissionStarts = () => snapshotState.semanticEvents
    .filter((event) => event.type === 'transmission_start').length;
  assert.equal(transmissionStarts(), 1);
  assert.equal(model.ingestChannelActivitySnapshot(snapshotState, snapshot(2), 1, BASE_TIME + 2).updated, 1);
  assert.equal(transmissionStarts(), 1);
  assert.equal(model.ingestChannelActivitySnapshot(snapshotState,
    snapshot(3, 'ended', 'snapshot-leg', { tx_end_proven: true, burst_generation: 1 }), 1,
  BASE_TIME + 3).ended, 1);
  assert.equal(snapshotState.activeCalls.size, 0);
  assert.equal(model.ingestChannelActivitySnapshot(snapshotState,
    snapshot(4, 'active', 'snapshot-leg', { burst_generation: 2, burst_started_at_ms: BASE_TIME + 4 }), 1,
  BASE_TIME + 4).introduced, 1);
  assert.equal(snapshotState.activeCalls.size, 1);
  assert.equal(transmissionStarts(), 2);
  assert.equal(model.ingestChannelActivitySnapshot(snapshotState,
    snapshot(5, 'active', 'snapshot-leg', { burst_generation: 2, burst_started_at_ms: BASE_TIME + 4 }), 1,
  BASE_TIME + 5).updated, 1);
  assert.equal(transmissionStarts(), 2);

  const coalescedBurst = model.createNetworkState(config.BALANCED_CONFIG, BASE_TIME);
  model.ingestChannelActivitySnapshot(coalescedBurst,
    snapshot(1, 'active', 'coalesced-leg', { burst_generation: 1 }), 1, BASE_TIME + 1);
  model.ingestChannelActivitySnapshot(coalescedBurst,
    snapshot(2, 'active', 'coalesced-leg', { burst_generation: 2, burst_started_at_ms: BASE_TIME + 2 }), 1,
  BASE_TIME + 2);
  assert.equal(coalescedBurst.activeCalls.size, 1);
  assert.equal(coalescedBurst.semanticEvents.filter((event) => event.type === 'transmission_start').length, 2);
  assert.equal(coalescedBurst.semanticEvents.filter((event) => event.type === 'transmission_end').length, 1);
  assert.equal(coalescedBurst.pendingEffects.filter((effect) => effect.type === 'tx_pulse').length, 1,
    'a same-source burst reacquired during release does not replay the key-up pulse');

  const oldGeneration = snapshotState.generation;
  const newGeneration = model.clearNetworkState(snapshotState, BASE_TIME + 6);
  assert.equal(newGeneration, oldGeneration + 1);
  assert.equal(model.applyObservation(snapshotState, affiliation(), oldGeneration, BASE_TIME + 7).reason,
    'stale_generation');
  model.establishChannelActivityBoundary(snapshotState,
    snapshot(6, 'active', 'snapshot-leg', { burst_generation: 2, burst_started_at_ms: BASE_TIME + 4 }),
    newGeneration, BASE_TIME + 6);
  model.ingestChannelActivitySnapshot(snapshotState,
    snapshot(7, 'active', 'snapshot-leg', { burst_generation: 2, burst_started_at_ms: BASE_TIME + 4 }),
    newGeneration, BASE_TIME + 7);
  assert.equal(snapshotState.universes.size, 0);
  model.ingestChannelActivitySnapshot(snapshotState,
    snapshot(8, 'active', 'snapshot-leg', { burst_generation: 3, burst_started_at_ms: BASE_TIME + 8 }),
    newGeneration, BASE_TIME + 8);
  assert.equal(snapshotState.activeCalls.size, 1);

  const boundaryConfig = config.createConfig({ state: { hardDedupeEntries: 20 } });
  const boundaryState = model.createNetworkState(boundaryConfig, BASE_TIME);
  const boundarySnapshot = snapshot(1);
  boundarySnapshot.tables[0].rows = Array.from({ length: 25 }, (_value, index) => ({
    ...boundarySnapshot.tables[0].rows[0],
    key: `boundary-row-${String(index).padStart(2, '0')}`,
    call_leg_id: `boundary-leg-${index}`,
    activation_order: index + 1
  }));
  assert.equal(model.establishChannelActivityBoundary(boundaryState, boundarySnapshot, 1, BASE_TIME + 9).recorded,
    25);
  assert.equal(boundaryState.snapshotRows.size, boundaryConfig.state.hardDedupeEntries);
  assert(!boundaryState.snapshotRows.has('table-a|boundary-row-00'));
  assert(boundaryState.snapshotRows.has('table-a|boundary-row-24'));

  // A reconnect snapshot restores current state without replaying a key-up or pretending continuity was observed.
  const gapSnapshotState = model.createNetworkState(config.BALANCED_CONFIG, BASE_TIME);
  model.ingestChannelActivitySnapshot(gapSnapshotState,
    snapshot(1, 'active', 'gap-leg', { burst_generation: 4 }), 1, BASE_TIME + 1);
  assert.equal(gapSnapshotState.pendingEffects.filter((effect) => effect.type === 'tx_pulse').length, 1);
  model.markTransportGap(gapSnapshotState, { reason: 'test_snapshot_gap' }, 1, BASE_TIME + 2);
  assert.equal(gapSnapshotState.activeCalls.size, 0);
  assert.equal(gapSnapshotState.pendingEffects.length, 0);
  assert.equal(model.ingestChannelActivitySnapshot(gapSnapshotState,
    snapshot(2, 'active', 'gap-leg', { burst_generation: 4 }), 1, BASE_TIME + 3).introduced, 1);
  assert.equal(gapSnapshotState.activeCalls.size, 1);
  assert.equal([...gapSnapshotState.activeCalls.values()][0].uncertain, true);
  assert.equal(gapSnapshotState.semanticEvents.filter((event) => event.type === 'transmission_start').length, 1);
  assert.equal(gapSnapshotState.semanticEvents
    .filter((event) => event.type === 'transmission_observed_after_gap').length, 1);
  assert.equal(gapSnapshotState.pendingEffects.filter((effect) => effect.type === 'tx_pulse').length, 0);
  model.ingestChannelActivitySnapshot(gapSnapshotState,
    snapshot(3, 'active', 'gap-leg', { burst_generation: 4 }), 1, BASE_TIME + 4);
  assert.equal(gapSnapshotState.semanticEvents
    .filter((event) => event.type === 'transmission_observed_after_gap').length, 1);

  // A call first seen in the authoritative snapshot after a lost interval is current but has unknown continuity.
  const newDuringGapState = model.createNetworkState(config.BALANCED_CONFIG, BASE_TIME);
  model.markTransportGap(newDuringGapState, { reason: 'channel_activity_ingress_drop' }, 1, BASE_TIME + 1);
  model.ingestChannelActivitySnapshot(newDuringGapState,
    snapshot(1, 'active', 'new-during-gap-leg', { burst_generation: 1 }), 1, BASE_TIME + 2);
  assert.equal(newDuringGapState.activeCalls.size, 1);
  assert.equal([...newDuringGapState.activeCalls.values()][0].uncertain, true);
  assert.equal(newDuringGapState.semanticEvents
    .filter((event) => event.type === 'transmission_observed_after_gap').length, 1);
  assert.equal(newDuringGapState.pendingEffects.filter((effect) => effect.type === 'tx_pulse').length, 0);

  // Missing rows and local stale-call expiry can recover the same active leg as uncertain, without a new key-up.
  const recoveryState = model.createNetworkState(config.BALANCED_CONFIG, BASE_TIME);
  model.ingestChannelActivitySnapshot(recoveryState,
    snapshot(1, 'active', 'recovery-leg', { burst_generation: 9 }), 1, BASE_TIME + 1);
  model.ingestChannelActivitySnapshot(recoveryState,
    { source_key: 'test-channel-activity', revision: 2, tables: [] }, 1, BASE_TIME + 2);
  assert.equal(recoveryState.activeCalls.size, 0);
  model.ingestChannelActivitySnapshot(recoveryState,
    snapshot(3, 'active', 'recovery-leg', { burst_generation: 9 }), 1, BASE_TIME + 3);
  assert.equal(recoveryState.activeCalls.size, 1);
  assert.equal([...recoveryState.activeCalls.values()][0].uncertain, true);
  assert.equal(recoveryState.pendingEffects.filter((effect) => effect.type === 'tx_pulse').length, 1);
  model.tickNetworkState(recoveryState, BASE_TIME + config.BALANCED_CONFIG.state.callStaleAfterMs + 10);
  assert.equal(recoveryState.activeCalls.size, 0);
  model.ingestChannelActivitySnapshot(recoveryState,
    snapshot(4, 'active', 'recovery-leg', { burst_generation: 9 }), 1,
    BASE_TIME + config.BALANCED_CONFIG.state.callStaleAfterMs + 11);
  assert.equal(recoveryState.activeCalls.size, 1);
  assert.equal(recoveryState.semanticEvents
    .filter((event) => event.type === 'transmission_observed_after_uncertainty').length, 2);

  // Provisional channel scope becoming canonical does not duplicate an already active call or fake a second key-up.
  const activeReconciliation = model.createNetworkState(config.BALANCED_CONFIG, BASE_TIME);
  const provisionalRadio = { ...ref('radio', 'provisional-radio:7001', 7001, 'Unit 7001', ''),
    observed_local_id: 7001 };
  const provisionalGroup = { ...ref('talkgroup', 'provisional-group:101', 101, 'Group 101', ''),
    observed_local_id: 101 };
  model.applyObservation(activeReconciliation, call({ radio_system_key: '', event_id: 'provisional-call',
    call_leg_id: 'reconciled-leg', resource_context_key: 'reconciled-resource', radio: provisionalRadio,
    group: provisionalGroup }), 1, BASE_TIME + 1);
  model.applyObservation(activeReconciliation, call({ radio_system_key: SYSTEM_A, event_id: 'canonical-call',
    observed_at_ms: BASE_TIME + 2, call_leg_id: 'reconciled-leg', resource_context_key: 'reconciled-resource',
    radio: { ...ref('radio', 'radio:7001', 7001, 'Unit 7001'), observed_local_id: 7001 },
    group: { ...ref('talkgroup', 'tg:101', 101, 'Group 101'), observed_local_id: 101 } }),
  1, BASE_TIME + 2);
  assert.equal(activeReconciliation.activeCalls.size, 1);
  assert.equal(activeReconciliation.universes.size, 1);
  assert.equal(activeReconciliation.radios.size, 1);
  assert.equal([...activeReconciliation.activeCalls.values()][0].universeKey, `system:${SYSTEM_A}`);
  assert([...activeReconciliation.radios.keys()][0].startsWith(`system:${SYSTEM_A}|radio:`));
  assert.equal(activeReconciliation.semanticEvents.filter((event) => event.type === 'transmission_start').length, 1);
  assert.equal(activeReconciliation.semanticEvents
    .filter((event) => event.type === 'observed_affiliation_change').length, 0);

  // The same reconciliation path works when the canonical mapping first arrives in a repeated live snapshot.
  const snapshotReconciliation = model.createNetworkState(config.BALANCED_CONFIG, BASE_TIME);
  const provisionalActive = snapshot(1, 'active', 'snapshot-reconciled-leg', { burst_generation: 8 });
  provisionalActive.tables[0].rows[0].source_entity_ref = provisionalRadio;
  provisionalActive.tables[0].rows[0].target_entity_ref = provisionalGroup;
  model.ingestChannelActivitySnapshot(snapshotReconciliation, provisionalActive, 1, BASE_TIME + 1);
  const canonicalActive = snapshot(2, 'active', 'snapshot-reconciled-leg', { burst_generation: 8 });
  canonicalActive.tables[0].rows[0].source_entity_ref = {
    ...ref('radio', 'radio:7001', 7001, 'Unit 7001'), observed_local_id: 7001
  };
  canonicalActive.tables[0].rows[0].target_entity_ref = {
    ...ref('talkgroup', 'tg:101', 101, 'Group 101'), observed_local_id: 101
  };
  model.ingestChannelActivitySnapshot(snapshotReconciliation, canonicalActive, 1, BASE_TIME + 2);
  assert.equal(snapshotReconciliation.activeCalls.size, 1);
  assert.equal(snapshotReconciliation.universes.size, 1);
  assert.equal(snapshotReconciliation.radios.size, 1);
  assert.equal([...snapshotReconciliation.activeCalls.values()][0].universeKey, `system:${SYSTEM_A}`);
  assert.equal(snapshotReconciliation.semanticEvents
    .filter((event) => event.type === 'transmission_start').length, 1);

  // Same numeric identities remain scoped; analog conventional activity creates a channel hub and no fake radio.
  const scoped = model.createNetworkState(config.BALANCED_CONFIG, BASE_TIME);
  model.applyObservation(scoped, affiliation({ radio_system_key: SYSTEM_A, sequence: 1 }), 1, BASE_TIME + 1);
  model.applyObservation(scoped, affiliation({ radio_system_key: SYSTEM_B, system_name: 'System B',
    configuration_id: CONFIG_B, comparison_scope_key: `${CONFIG_B}:1:1:x`, sequence: 1,
    event_id: 'system-b-affiliation', radio: ref('radio', 'radio:7001', 7001, 'Other 7001', SYSTEM_B),
    group: ref('talkgroup', 'tg:101', 101, 'Other 101', SYSTEM_B) }), 1, BASE_TIME + 2);
  assert.equal(scoped.universes.size, 2);
  assert.equal(scoped.radios.size, 2);

  const unsupportedAffiliation = model.createNetworkState(config.BALANCED_CONFIG, BASE_TIME);
  model.applyObservation(unsupportedAffiliation, call({ protocol: 'dmr', radio_system_key: SYSTEM_B,
    configuration_id: CONFIG_B, call_leg_id: 'dmr-leg', resource_context_key: 'dmr-slot-1',
    radio: ref('radio', 'dmr-radio:7001', 7001, 'DMR Unit', SYSTEM_B),
    group: ref('talkgroup', 'dmr-group:101', 101, 'DMR Group', SYSTEM_B) }), 1, BASE_TIME + 3);
  const dmrRadioKey = [...unsupportedAffiliation.radios.keys()][0];
  model.setSelectedEntity(unsupportedAffiliation, dmrRadioKey);
  assert.equal(entrypoint.selectedEntityView(unsupportedAffiliation).affiliationLabel,
    'Unsupported by this live feed');
  const analog = model.createNetworkState(config.BALANCED_CONFIG, BASE_TIME);
  model.applyObservation(analog, call({ protocol: 'nbfm', radio_system_key: '', configuration_id: CONFIG_A,
    channel_name: 'Fire Paging', call_leg_id: 'analog', include_group: false,
    radio: undefined, observed_at_ms: BASE_TIME + 1 }), 1, BASE_TIME + 1);
  assert.equal(analog.radios.size, 0);
  assert.equal(analog.groups.size, 1);
  assert.equal([...analog.groups.values()][0].kind, 'channel');

  // A backend reconciliation rekeys the retained radio without reporting a semantic migration.
  const reconciliationConfig = config.createConfig({ state: { hardPinnedEntities: 1 } });
  const reconciled = model.createNetworkState(reconciliationConfig, BASE_TIME);
  model.applyObservation(reconciled, affiliation({ radio_system_key: '', configuration_id: CONFIG_A,
    comparison_scope_key: `${CONFIG_A}:x:x:x`, radio: ref('radio', 'provisional:9', 9, 'Provisional', ''),
    group: ref('talkgroup', 'provisional-group:9', 9, 'Provisional Group', '') }), 1, BASE_TIME + 1);
  const pinnedProvisionalKey = [...reconciled.radios.keys()][0];
  assert.equal(model.setEntityPinned(reconciled, pinnedProvisionalKey, true), true);
  const beforeReconcileChanges = reconciled.semanticEvents
    .filter((event) => event.type === 'observed_affiliation_change').length;
  model.applyObservation(reconciled, {
    kind: 'identity_reconciled', event_id: 'reconcile-9', observed_at_ms: BASE_TIME + 2,
    from_universe_key: `channel:${CONFIG_A}`, from_radio_identity_key: 'provisional:9',
    protocol: 'p25', radio_system_key: SYSTEM_A, configuration_id: CONFIG_A,
    radio: ref('radio', 'radio:9', 9, 'Unit 9'), group: ref('talkgroup', 'tg:9', 9, 'Group 9')
  }, 1, BASE_TIME + 2);
  assert.equal(reconciled.radios.size, 1);
  assert([...reconciled.radios.keys()][0].startsWith(`system:${SYSTEM_A}|radio:`));
  const reconciledRadio = [...reconciled.radios.values()][0];
  assert.equal(reconciledRadio.pinned, true);
  assert.deepEqual([...reconciled.visual.pinnedKeys], [reconciledRadio.key]);
  assert.equal(reconciled.semanticEvents.filter((event) => event.type === 'observed_affiliation_change').length,
    beforeReconcileChanges);
  assert(reconciled.semanticEvents.some((event) => event.type === 'identity_reconciled' &&
    event.semanticMigration === false));
  assert(!reconciled.pendingEffects.some((effect) => effect.type === 'migration'));

  const implicit = model.createNetworkState(config.BALANCED_CONFIG, BASE_TIME);
  const provisionalAffiliation = affiliation({
    radio_system_key: '', configuration_id: CONFIG_A, comparison_scope_key: `${CONFIG_A}:x:x:x`,
    event_id: 'implicit-provisional', sequence: 1,
    radio: { ...ref('radio', 'provisional:77', 77, 'Provisional 77', ''), observed_local_id: 77 },
    group: { ...ref('talkgroup', 'provisional-group:88', 88, 'Provisional 88', ''), observed_local_id: 88 }
  });
  model.applyObservation(implicit, provisionalAffiliation, 1, BASE_TIME + 1);
  const implicitLayout = layout.createLayoutState(config.BALANCED_CONFIG, { profileKey: 'implicit-profile' });
  const provisionalRadioKey = [...implicit.radios.keys()][0];
  const provisionalGroupKey = [...implicit.groups.keys()][0];
  assert.equal(model.setEntityPinned(implicit, provisionalRadioKey, true), true);
  layout.restoreLayoutRecords(implicitLayout, [
    { profileKey: 'implicit-profile', key: provisionalGroupKey, type: 'group', x: 80, y: 60, z: 12,
      pinned: false, updatedAtMs: BASE_TIME },
    { profileKey: 'implicit-profile', key: provisionalRadioKey, type: 'radio', x: 123, y: -45, z: 18,
      pinned: true, updatedAtMs: BASE_TIME + 1 }
  ]);
  const provisionalGraph = visibility.selectVisibleGraph(implicit, BASE_TIME + 1);
  layout.synchronizeLayout(implicitLayout, provisionalGraph, BASE_TIME + 1);
  const canonicalAffiliation = affiliation({
    radio_system_key: SYSTEM_A, configuration_id: CONFIG_A, comparison_scope_key: `${CONFIG_A}:x:x:x`,
    event_id: 'implicit-canonical', sequence: 2, observed_at_ms: BASE_TIME + 2,
    radio: { ...ref('radio', 'radio:77', 77, 'Canonical 77'), observed_local_id: 77 },
    group: { ...ref('talkgroup', 'tg:88', 88, 'Canonical 88'), observed_local_id: 88 }
  });
  model.applyObservation(implicit, canonicalAffiliation, 1, BASE_TIME + 2);
  assert.equal(implicit.radios.size, 1);
  assert.equal(implicit.groups.size, 1);
  assert.equal(implicit.universes.size, 1);
  assert([...implicit.radios.keys()][0].startsWith(`system:${SYSTEM_A}|radio:`));
  assert.equal(implicit.semanticEvents.filter((event) => event.type === 'observed_affiliation_change').length, 0);
  assert(implicit.semanticEvents.some((event) => event.type === 'identity_reconciled' &&
    event.semanticMigration === false));
  assert(!implicit.pendingEffects.some((effect) => effect.type === 'migration'));
  const canonicalGraph = visibility.selectVisibleGraph(implicit, BASE_TIME + 2);
  layout.synchronizeLayout(implicitLayout, canonicalGraph, BASE_TIME + 2);
  const canonicalRadioNode = canonicalGraph.nodes.find((node) => node.type === 'radio');
  const canonicalGroupNode = canonicalGraph.nodes.find((node) => node.type === 'group');
  assert.deepEqual({ x: canonicalRadioNode.x, y: canonicalRadioNode.y, z: canonicalRadioNode.z },
    { x: 123, y: -45, z: 18 });
  assert.deepEqual({ x: canonicalGroupNode.x, y: canonicalGroupNode.y, z: canonicalGroupNode.z },
    { x: 80, y: 60, z: 12 });
  assert(!implicitLayout.positions.has(provisionalRadioKey));
  assert(!implicitLayout.positions.has(provisionalGroupKey));
  assert(!implicitLayout.saved.has(provisionalRadioKey));
  assert(!implicitLayout.saved.has(provisionalGroupKey));
  assert.deepEqual([...implicitLayout.pinned], [canonicalRadioNode.key]);

  // Render selection enforces all hard ceilings and explicitly accounts for suppressed active radios.
  const limitedConfig = config.createConfig({
    render: { softRadiosPerGroup: 2, softRadiosTotal: 3, softExpandedGroups: 2,
      softExpandedUniverses: 1, hardNodes: 8, hardLinks: 6, hardLabels: 3, hardParticles: 2,
      hardMigrationTrails: 1 },
    state: { hardRadios: 30, hardGroups: 10, hardUniverses: 4, hardSemanticEvents: 12,
      hardTransitionsPerRadio: 2, hardDedupeEntries: 20, hardPinnedEntities: 2,
      hardSavedLayoutRecords: 3, hardSiteEvidencePerEntity: 2, hardActiveCalls: 30,
      hardPendingEffects: 4, hardMetadataRequests: 2, hardMetadataCacheEntries: 3,
      hardIncomingQueue: 3, incomingBatchSize: 2 }
  });
  const busy = model.createNetworkState(limitedConfig, BASE_TIME);
  for (let index = 0; index < 20; index += 1) {
    model.applyObservation(busy, call({ call_leg_id: `busy-${index}`, resource_context_key: `busy-r-${index}`,
      radio_identity: `radio:${8000 + index}`, radio_id: 8000 + index, event_id: `busy-event-${index}`,
      observed_at_ms: BASE_TIME + index + 1 }), 1, BASE_TIME + index + 1);
  }
  const busyGraph = visibility.selectVisibleGraph(busy, BASE_TIME + 50,
    { softRadiosTotal: 999, hardLabels: 999, query: 'unit' });
  assert(busyGraph.nodes.length <= limitedConfig.render.hardNodes);
  assert(busyGraph.links.length <= limitedConfig.render.hardLinks);
  assert(busyGraph.labels.length <= limitedConfig.render.hardLabels);
  assert(busyGraph.effects.length <= limitedConfig.state.hardPendingEffects);
  assert(busyGraph.aggregates.some((node) => node.suppressedActiveCount > 0));
  assert(busyGraph.counts.suppressedActiveRadios > 0);
  assert(busyGraph.searchResults.length <= 100);
  const quietRadio = [...busy.radios.values()][0];
  quietRadio.activeCallKeys.clear();
  quietRadio.lastMeaningfulAtMs = BASE_TIME - limitedConfig.render.radioQuietAfterMs - 1;
  const quietGraph = visibility.selectVisibleGraph(busy, BASE_TIME + 50, { filters: { quiet: false } });
  assert(!quietGraph.nodes.some((node) => node.key === quietRadio.key));
  assert.equal(model.setEntityPinned(busy, [...busy.radios.keys()][0]), true);
  assert.equal(model.setEntityPinned(busy, [...busy.radios.keys()][1]), true);
  assert.equal(model.setEntityPinned(busy, [...busy.radios.keys()][2]), false);
  assert.equal(model.enqueueObservation(busy, affiliation({ event_id: 'queued-1' }), 1, BASE_TIME + 1).accepted, true);
  assert.equal(model.enqueueObservation(busy, affiliation({ event_id: 'queued-2' }), 1, BASE_TIME + 2).accepted, true);
  assert.equal(model.enqueueObservation(busy, affiliation({ event_id: 'queued-3' }), 1, BASE_TIME + 3).accepted, true);
  assert.equal(model.enqueueObservation(busy, affiliation({ event_id: 'queued-4' }), 1, BASE_TIME + 4).reason,
    'queue_capacity');
  assert.equal(model.drainObservationQueue(busy, 99).length, 0);
  assert.equal(busy.transport.status, 'gap');
  assert.equal(busy.transport.gap.dropped, 4);
  assert.equal(busy.overflow.queueDropped, 4);

  // A gap establishes a new source epoch: queued/effect/revision state is discarded, process-local IDs can restart,
  // and repeated gap notices coalesce instead of fabricating multiple comparison epochs.
  const resetState = model.createNetworkState(config.BALANCED_CONFIG, BASE_TIME);
  model.applyObservation(resetState, affiliation({ event_id: 'process-local-1', sequence: 4 }), 1, BASE_TIME + 4);
  const resetRadio = [...resetState.radios.values()][0];
  resetState.incomingQueue.push({ raw: affiliation({ event_id: 'queued-before-gap' }), generation: 1,
    receivedAtMs: BASE_TIME + 5 });
  resetState.snapshotRevisions.set('channel_activity', 42);
  assert(resetState.pendingEffects.length > 0);
  const epochBefore = resetRadio.comparisonEpoch;
  model.markTransportGap(resetState, { reason: 'server_restart', dropped: 2 }, 1, BASE_TIME + 6);
  assert.equal(resetState.incomingQueue.length, 0);
  assert.equal(resetState.pendingEffects.length, 0);
  assert.equal(resetState.dedupe.size, 0);
  assert.equal(resetState.snapshotRevisions.size, 0);
  assert.equal(resetRadio.comparisonEpoch, epochBefore + 1);
  model.markTransportGap(resetState, { reason: 'duplicate_notice', dropped: 3 }, 1, BASE_TIME + 7);
  assert.equal(resetRadio.comparisonEpoch, epochBefore + 1);
  assert.equal(resetState.transport.gap.dropped, 5);
  assert.equal(resetState.semanticEvents.filter((event) => event.type === 'transport_gap').length, 1);
  assert.notEqual(model.applyObservation(resetState, affiliation({ event_id: 'process-local-1', sequence: 1,
    observed_at_ms: BASE_TIME + 8 }), 1, BASE_TIME + 8).reason, 'duplicate');

  // Invisible effects do not consume the visible particle budget.
  const effectState = model.createNetworkState(limitedConfig, BASE_TIME);
  model.applyObservation(effectState, affiliation({ event_id: 'effect-affiliation' }), 1, BASE_TIME + 1);
  const effectRadio = [...effectState.radios.values()][0];
  const effectGroup = effectState.groups.get(effectRadio.visualParentGroupKey);
  effectState.pendingEffects = [
    { id: 'offscreen-newest', type: 'tx_pulse', nodeKey: 'missing-radio', sourceKey: 'missing-radio',
      targetKey: effectGroup.key, createdAtMs: BASE_TIME + 4, expiresAtMs: BASE_TIME + 10_000 },
    { id: 'visible-one', type: 'tx_pulse', nodeKey: effectRadio.key, sourceKey: effectRadio.key,
      targetKey: effectGroup.key, createdAtMs: BASE_TIME + 3, expiresAtMs: BASE_TIME + 10_000 },
    { id: 'visible-two', type: 'tx_pulse', nodeKey: effectRadio.key, sourceKey: effectRadio.key,
      targetKey: effectGroup.key, createdAtMs: BASE_TIME + 2, expiresAtMs: BASE_TIME + 10_000 }
  ];
  assert.deepEqual(visibility.selectVisibleGraph(effectState, BASE_TIME + 5).effects.map((effect) => effect.id),
    ['visible-one', 'visible-two']);
  const effectGraph = visibility.selectVisibleGraph(effectState, BASE_TIME + 5);
  const filteredEffectGraph = entrypoint.filterSuppressedEffects(effectGraph, new Set(['visible-one']));
  assert.deepEqual(filteredEffectGraph.effects.map((effect) => effect.id), ['visible-two']);
  assert.deepEqual(effectGraph.effects.map((effect) => effect.id), ['visible-one', 'visible-two']);
  assert.equal(entrypoint.filterSuppressedEffects(effectGraph, new Set(['not-present'])), effectGraph);

  const overflowConfig = config.createConfig({ state: { hardActiveCalls: 1, hardOverflowCallKeys: 2 } });
  const overflowState = model.createNetworkState(overflowConfig, BASE_TIME);
  model.applyObservation(overflowState, call({ call_leg_id: 'kept', resource_context_key: 'kept',
    event_id: 'kept-start' }), 1, BASE_TIME + 1);
  model.applyObservation(overflowState, call({ call_leg_id: 'overflow-a', resource_context_key: 'overflow-a',
    event_id: 'overflow-a-start' }), 1, BASE_TIME + 2);
  model.applyObservation(overflowState, call({ call_leg_id: 'overflow-a', resource_context_key: 'overflow-a',
    event_id: 'overflow-a-update', observed_at_ms: BASE_TIME + 3 }), 1, BASE_TIME + 3);
  assert.equal(model.networkStateCounts(overflowState).overflowActive, 1);
  model.applyObservation(overflowState, call({ call_leg_id: 'overflow-b', resource_context_key: 'overflow-b',
    event_id: 'overflow-b-start' }), 1, BASE_TIME + 4);
  model.applyObservation(overflowState, call({ call_leg_id: 'overflow-c', resource_context_key: 'overflow-c',
    event_id: 'overflow-c-start' }), 1, BASE_TIME + 5);
  assert.equal(model.networkStateCounts(overflowState).overflowActive, 3);
  const overflowGraph = visibility.selectVisibleGraph(overflowState, BASE_TIME + 5);
  assert.equal(overflowGraph.counts.suppressedActiveRadios, 0);
  assert.equal(overflowGraph.counts.suppressedActiveCallLegs, 3);
  assert(overflowGraph.aggregates.some((aggregate) => aggregate.suppressedActiveCallLegs === 3 &&
    /active call legs/.test(aggregate.label)));
  model.markTransportGap(overflowState, { reason: 'overflow-gap' }, 1, BASE_TIME + 6);
  assert.equal(model.networkStateCounts(overflowState).overflowActive, 0);

  // Active activity beyond the retained-universe ceiling remains visible as an honest global aggregate.
  const universeOverflowConfig = config.createConfig({ state: { hardUniverses: 1 } });
  const universeOverflow = model.createNetworkState(universeOverflowConfig, BASE_TIME);
  model.applyObservation(universeOverflow, call({ event_id: 'retained-universe-call',
    call_leg_id: 'retained-universe-leg' }), 1, BASE_TIME + 1);
  const rejectedUniverse = model.applyObservation(universeOverflow, call({ event_id: 'overflow-universe-call',
    call_leg_id: 'overflow-universe-leg', configuration_id: CONFIG_B, radio_system_key: SYSTEM_B,
    system_name: 'System B', radio: ref('radio', 'radio:9001', 9001, 'Unit 9001', SYSTEM_B),
    group: ref('talkgroup', 'tg:901', 901, 'Group 901', SYSTEM_B) }), 1, BASE_TIME + 2);
  assert.equal(rejectedUniverse.aggregated, true);
  assert.equal(universeOverflow.universes.size, 1);
  assert.equal(model.networkStateCounts(universeOverflow).overflowActive, 1);
  const universeOverflowGraph = visibility.selectVisibleGraph(universeOverflow, BASE_TIME + 3);
  assert.equal(universeOverflowGraph.counts.suppressedActiveRadios, 1);
  assert(universeOverflowGraph.aggregates.some((aggregate) =>
    aggregate.kind === 'active_radios' && aggregate.suppressedActiveCount === 1));
  const universeOverflowOverview = visibility.selectVisibleGraph(universeOverflow, BASE_TIME + 3,
    { scope: { level: 'overview' } });
  assert(universeOverflowOverview.nodes.length <= universeOverflowConfig.render.hardNodes);
  assert.equal(universeOverflowOverview.counts.suppressedActiveRadios, 1);
  assert(universeOverflowOverview.aggregates.some((aggregate) =>
    aggregate.kind === 'active_radios' && aggregate.suppressedActiveCount === 1));

  const retainedUniverseOverflow = model.createNetworkState(config.createConfig({
    state: { hardGroups: 1, hardRadios: 2 }
  }), BASE_TIME);
  model.applyObservation(retainedUniverseOverflow, call({ kind: 'grant', transmission_state: 'pending',
    event_id: 'retained-group-grant', call_leg_id: 'retained-group-grant-leg' }), 1, BASE_TIME + 1);
  const retainedGroup = [...retainedUniverseOverflow.groups.values()][0];
  model.setEntityPinned(retainedUniverseOverflow, retainedGroup.key, true);
  model.applyObservation(retainedUniverseOverflow, call({ event_id: 'overflow-group-call', group_id: 202,
    call_leg_id: 'overflow-group-leg', resource_context_key: 'overflow-group-resource',
    radio_identity: 'radio:overflow-group', radio_id: 9202 }), 1, BASE_TIME + 2);
  const retainedUniverseOverview = visibility.selectVisibleGraph(retainedUniverseOverflow, BASE_TIME + 3,
    { scope: { level: 'overview' } });
  assert(retainedUniverseOverview.nodes.some((node) => node.type === 'universe' && node.active),
    'overflow activity must light its retained system in overview');
  assert.equal(retainedUniverseOverview.counts.suppressedActiveRadios, 1);
  assert(retainedUniverseOverview.aggregates.some((aggregate) => aggregate.suppressedActiveCount === 1));
  model.applyObservation(universeOverflow, call({ kind: 'call_end', transmission_state: 'ended',
    event_id: 'overflow-universe-end', call_leg_id: 'overflow-universe-leg', configuration_id: CONFIG_B,
    radio_system_key: SYSTEM_B, system_name: 'System B', end_proven: true,
    radio: ref('radio', 'radio:9001', 9001, 'Unit 9001', SYSTEM_B),
    group: ref('talkgroup', 'tg:901', 901, 'Group 901', SYSTEM_B) }), 1, BASE_TIME + 4);
  assert.equal(model.networkStateCounts(universeOverflow).overflowActive, 0);

  // One deterministic 3D layout model supports stable hierarchy slots, monotonic reparenting,
  // pinning, and bounded persistence without a continuously running force simulation.
  const layoutGraph = {
    nodes: [
      { key: 'u', id: 'u', type: 'universe', universeKey: 'u' },
      { key: 'g', id: 'g', type: 'group', universeKey: 'u', groupKey: 'g' },
      { key: 'r1', id: 'r1', type: 'radio', universeKey: 'u', groupKey: 'g' },
      { key: 'r2', id: 'r2', type: 'radio', universeKey: 'u', groupKey: 'g' }
    ], links: []
  };
  const firstLayout = layout.createLayoutState(limitedConfig, { profileKey: 'profile-a' });
  const secondLayout = layout.createLayoutState(limitedConfig, { profileKey: 'profile-a' });
  layout.synchronizeLayout(firstLayout, layoutGraph, BASE_TIME);
  const comparisonGraph = { nodes: layoutGraph.nodes.map(({ key, id, type, universeKey, groupKey }) =>
    ({ key, id, type, universeKey, groupKey })), links: [] };
  layout.synchronizeLayout(secondLayout, comparisonGraph, BASE_TIME);
  assert.deepEqual(layoutGraph.nodes.map(({ x, y, z }) => ({ x, y, z })),
    comparisonGraph.nodes.map(({ x, y, z }) => ({ x, y, z })));
  const systemVolumeGraph = { nodes: [
    { key: 'volume-u', type: 'universe', universeKey: 'volume-u' },
    ...['a', 'b', 'c', 'd'].map((suffix) => ({ key: `volume-g-${suffix}`, type: 'group',
      universeKey: 'volume-u', groupKey: `volume-g-${suffix}` }))
  ], links: [] };
  const systemVolumeLayout = layout.createLayoutState(limitedConfig, { profileKey: 'system-volume' });
  layout.restoreLayoutRecords(systemVolumeLayout, [{ profileKey: 'system-volume', key: 'volume-g-a', type: 'group',
    x: 10_000, y: 10_000, z: 10_000, pinned: false, updatedAtMs: BASE_TIME + 1 }]);
  layout.synchronizeLayout(systemVolumeLayout, systemVolumeGraph, BASE_TIME);
  const systemVolumeUniverse = systemVolumeGraph.nodes[0];
  const systemVolumeRadii = systemVolumeGraph.nodes.slice(1).map((node) => Math.hypot(
    node.x - systemVolumeUniverse.x, node.y - systemVolumeUniverse.y, node.z - systemVolumeUniverse.z));
  assert(systemVolumeRadii.every((radius) => radius <= limitedConfig.layout.groupOrbitRadius + 0.001));
  assert(systemVolumeRadii.every((radius) => radius + limitedConfig.layout.radioOrbitRadius * 1.35 <
    limitedConfig.layout.systemRadius), 'the system shell must contain talkgroup and radio layout volumes');
  assert(new Set(systemVolumeRadii.map((radius) => radius.toFixed(3))).size > 1,
    'talkgroups fill the deterministic system volume instead of sharing one orbital ring');
  const containedGroup = systemVolumeGraph.nodes.find((node) => node.key === 'volume-g-a');
  assert(Math.hypot(containedGroup.x - systemVolumeUniverse.x, containedGroup.y - systemVolumeUniverse.y,
    containedGroup.z - systemVolumeUniverse.z) <= limitedConfig.layout.groupOrbitRadius + 0.001,
    'saved talkgroup coordinates remain inside their parent system volume');
  layout.setLayoutPinned(firstLayout, 'r2', true, BASE_TIME + 1);
  const pinnedRecord = firstLayout.positions.get('r2');
  const pinnedPosition = { x: pinnedRecord.x, y: pinnedRecord.y, z: pinnedRecord.z };
  layoutGraph.nodes.find((node) => node.key === 'r2').groupKey = '';
  layout.synchronizeLayout(firstLayout, layoutGraph, BASE_TIME + 2);
  for (let frame = 0; frame < 30; frame += 1) {
    layout.stepLayout(firstLayout, layoutGraph, 16, BASE_TIME + 3 + frame * 16);
  }
  assert.deepEqual({ x: pinnedRecord.x, y: pinnedRecord.y, z: pinnedRecord.z }, pinnedPosition,
    'pinning fixes layout position while logical parentage continues to update');
  assert(layout.savedLayoutRecords(firstLayout).some((record) => record.key === 'r2' && record.pinned));
  const migrationNodes = () => [
    { key: 'migration-u', type: 'universe', universeKey: 'migration-u' },
    { key: 'migration-old', type: 'group', universeKey: 'migration-u', groupKey: 'migration-old' },
    { key: 'migration-new', type: 'group', universeKey: 'migration-u', groupKey: 'migration-new' },
    { key: 'migration-radio', type: 'radio', universeKey: 'migration-u', groupKey: 'migration-old' }
  ];

  const easingLayout = layout.createLayoutState(limitedConfig);
  const easingGraph = { nodes: migrationNodes(), links: [], effects: [] };
  layout.synchronizeLayout(easingLayout, easingGraph, BASE_TIME + 3_999);
  const easingRecord = easingLayout.positions.get('migration-radio');
  easingGraph.nodes.find((node) => node.key === 'migration-radio').groupKey = 'migration-new';
  layout.synchronizeLayout(easingLayout, easingGraph, BASE_TIME + 4_000);
  const destination = { x: easingRecord.targetX, y: easingRecord.targetY, z: easingRecord.targetZ };
  let previousDistance = Math.hypot(destination.x - easingRecord.x, destination.y - easingRecord.y,
    destination.z - easingRecord.z);
  assert(previousDistance > 1);
  for (let frame = 0; frame < 240; frame += 1) {
    layout.stepLayout(easingLayout, easingGraph, 16, BASE_TIME + 4_001 + frame * 16);
    const distance = Math.hypot(destination.x - easingRecord.x, destination.y - easingRecord.y,
      destination.z - easingRecord.z);
    assert(distance <= previousDistance + 1e-9, 'a reparented radio must approach its slot without overshoot');
    previousDistance = distance;
  }
  assert.deepEqual({ x: easingRecord.x, y: easingRecord.y, z: easingRecord.z }, destination);
  assert.equal(easingLayout.sleeping, true);
  const settledPosition = { x: easingRecord.x, y: easingRecord.y, z: easingRecord.z };
  for (let frame = 0; frame < 30; frame += 1) {
    layout.stepLayout(easingLayout, easingGraph, 16, BASE_TIME + 8_000 + frame * 16);
  }
  assert.deepEqual({ x: easingRecord.x, y: easingRecord.y, z: easingRecord.z }, settledPosition,
    'settled nodes must not rock or drift');

  const reducedReparentLayout = layout.createLayoutState(limitedConfig, { reducedMotion: true });
  const reducedReparentGraph = { nodes: [
    { key: 'reparent-u', type: 'universe', universeKey: 'reparent-u' },
    { key: 'reparent-a', type: 'group', universeKey: 'reparent-u', groupKey: 'reparent-a' },
    { key: 'reparent-b', type: 'group', universeKey: 'reparent-u', groupKey: 'reparent-b' },
    { key: 'reparent-radio', type: 'radio', universeKey: 'reparent-u', groupKey: 'reparent-a' }
  ], links: [] };
  layout.synchronizeLayout(reducedReparentLayout, reducedReparentGraph, BASE_TIME + 4_100);
  const reparentRecord = reducedReparentLayout.positions.get('reparent-radio');
  const reparentTarget = reducedReparentLayout.positions.get('reparent-b');
  reducedReparentGraph.nodes.find((node) => node.key === 'reparent-radio').groupKey = 'reparent-b';
  layout.synchronizeLayout(reducedReparentLayout, reducedReparentGraph, BASE_TIME + 4_101);
  assert.deepEqual({ x: reparentRecord.x, y: reparentRecord.y, z: reparentRecord.z }, {
    x: reparentTarget.x + reparentRecord.localX,
    y: reparentTarget.y + reparentRecord.localY,
    z: reparentTarget.z + reparentRecord.localZ
  }, 'a reduced-motion affiliation change must snap the radio to its new visual parent');

  const dynamicReducedLayout = layout.createLayoutState(limitedConfig);
  const dynamicReducedGraph = { nodes: migrationNodes(), links: [] };
  layout.synchronizeLayout(dynamicReducedLayout, dynamicReducedGraph, BASE_TIME + 4_200);
  const dynamicReducedRecord = dynamicReducedLayout.positions.get('migration-radio');
  const dynamicReducedParent = dynamicReducedLayout.positions.get('migration-new');
  dynamicReducedGraph.nodes.find((node) => node.key === 'migration-radio').groupKey = 'migration-new';
  layout.synchronizeLayout(dynamicReducedLayout, dynamicReducedGraph, BASE_TIME + 4_201);
  layout.setReducedMotion(dynamicReducedLayout, true);
  assert.deepEqual({ x: dynamicReducedRecord.x, y: dynamicReducedRecord.y, z: dynamicReducedRecord.z }, {
    x: dynamicReducedParent.targetX + dynamicReducedRecord.localX,
    y: dynamicReducedParent.targetY + dynamicReducedRecord.localY,
    z: dynamicReducedParent.targetZ + dynamicReducedRecord.localZ
  }, 'enabling reduced motion mid-migration must settle the radio at its current parent');

  const saved = layout.savedLayoutRecords(firstLayout);
  const restoredLayout = layout.createLayoutState(limitedConfig, { profileKey: 'profile-a' });
  assert.equal(layout.restoreLayoutRecords(restoredLayout, saved), saved.length);
  assert.equal(restoredLayout.positions.size, 0);
  const restoredGraph = { nodes: [
    { key: 'u', type: 'universe', universeKey: 'u' },
    { key: 'g', type: 'group', universeKey: 'u', groupKey: 'g' },
    { key: 'r2', type: 'radio', universeKey: 'u', groupKey: '', pinned: true }
  ], links: [], effects: [] };
  layout.synchronizeLayout(restoredLayout, restoredGraph, BASE_TIME + 3);
  const restoredUniverse = restoredLayout.positions.get('u');
  const restoredGroup = restoredLayout.positions.get('g');
  const restoredRadio = restoredLayout.positions.get('r2');
  assert.equal(restoredRadio.pinned, true);
  assert.deepEqual({ x: restoredRadio.x, y: restoredRadio.y, z: restoredRadio.z }, pinnedPosition);
  assert.equal(restoredGroup.pinned, false);
  assert.equal(restoredGraph.nodes.find((node) => node.key === 'g').fx, undefined);
  assert.equal(restoredGroup.localX, restoredGroup.x - restoredUniverse.x);
  assert.equal(restoredGroup.localY, restoredGroup.y - restoredUniverse.y);
  const restoredBeforeStep = { x: restoredGroup.x, y: restoredGroup.y };
  layout.stepLayout(restoredLayout, restoredGraph, 16, BASE_TIME + 4);
  assert(Math.hypot(restoredGroup.x - restoredBeforeStep.x, restoredGroup.y - restoredBeforeStep.y) < 0.001);
  const pinBudgetLayout = layout.createLayoutState(config.createConfig({ state: {
    hardPinnedEntities: 1, hardSavedLayoutRecords: 4
  } }), { profileKey: 'profile-a' });
  layout.restoreLayoutRecords(pinBudgetLayout, [
    { profileKey: 'profile-a', key: 'saved-pin-1', type: 'radio', x: 1, y: 2, z: 3,
      pinned: true, updatedAtMs: BASE_TIME + 2 },
    { profileKey: 'profile-a', key: 'saved-pin-2', type: 'radio', x: 4, y: 5, z: 6,
      pinned: true, updatedAtMs: BASE_TIME + 1 }
  ]);
  const pinBudgetGraph = { nodes: [
    { key: 'saved-pin-1', type: 'radio', pinned: true },
    { key: 'saved-pin-2', type: 'radio', pinned: false }
  ], links: [] };
  layout.synchronizeLayout(pinBudgetLayout, pinBudgetGraph, BASE_TIME + 5);
  assert.deepEqual([...pinBudgetLayout.pinned], ['saved-pin-1']);
  assert.equal(pinBudgetLayout.positions.get('saved-pin-2').pinned, false,
    'a saved pin must be admitted by the semantic pin budget before it fixes a restored node');
  const boundedUniverseLayout = layout.createLayoutState(config.createConfig({ state: { hardUniverses: 2 } }),
    { profileKey: 'profile-a' });
  ['u-1', 'u-2', 'u-3'].forEach((key, index) => {
    layout.synchronizeLayout(boundedUniverseLayout,
      { nodes: [{ key, type: 'universe', universeKey: key }], links: [] }, BASE_TIME + 10 + index);
  });
  assert.equal(boundedUniverseLayout.universeOrder.size, 2);
  boundedUniverseLayout.saved.set('saved-radio', Object.freeze({ profileKey: 'profile-a', key: 'saved-radio',
    type: 'radio', x: 1, y: 2, z: 3, pinned: true, updatedAtMs: BASE_TIME }));
  assert.equal(layout.resetLayoutSession(boundedUniverseLayout), true);
  assert.equal(boundedUniverseLayout.positions.size, 0);
  assert.equal(boundedUniverseLayout.universeOrder.size, 0);
  assert.equal(boundedUniverseLayout.saved.size, 1);
  layout.unlockLayout(firstLayout);
  assert.equal(layout.savedLayoutRecords(firstLayout).length, 0);
  layout.disposeLayout(firstLayout);
  assert.equal(firstLayout.positions.size, 0);

  // The fixture covers all required development scenarios and is deterministic.
  const fixtureA = fixture.createNetworkVisualizerFixture({ startedAtMs: BASE_TIME });
  const fixtureB = fixture.createNetworkVisualizerFixture({ startedAtMs: BASE_TIME });
  assert.deepEqual(fixtureA.observations, fixtureB.observations);
  assert(fixtureA.observations.some((item) => item.payload.outcome === 'denied'));
  assert(fixtureA.observations.some((item) => item.payload.kind === 'identity_reconciled'));
  assert(fixtureA.observations.some((item) => item.payload.protocol === 'nbfm'));
  assert(fixtureA.observations.some((item) => !item.payload.radio && item.payload.kind === 'call_start'));
  assert.equal(fixtureA.overflow(1_050).length, 1_050);

  console.log('Network Visualizer model checks passed.');
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
