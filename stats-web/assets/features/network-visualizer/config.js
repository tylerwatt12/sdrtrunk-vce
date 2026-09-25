'use strict';

const BALANCED_DEFAULTS = {
  profile: 'balanced',
  render: {
    softRadiosPerGroup: 100,
    softRadiosTotal: 1_000,
    softExpandedGroups: 80,
    softExpandedUniverses: 8,
    hardNodes: 1_000,
    hardLinks: 900,
    hardLabels: 80,
    hardParticles: 150,
    hardMigrationTrails: 50,
    migrationTrailTtlMs: 8_000,
    radioQuietAfterMs: 60_000,
    radioHideAfterMs: 5 * 60_000,
    groupCollapseAfterMs: 10 * 60_000,
    universeCollapseAfterMs: 15 * 60_000,
    minimumResidenceMs: 10_000,
    rankHysteresis: 25
  },
  state: {
    hardRadios: 20_000,
    hardGroups: 5_000,
    hardUniverses: 64,
    hardSemanticEvents: 5_000,
    hardTransitionsPerRadio: 20,
    hardDedupeEntries: 40_000,
    dedupeTtlMs: 30 * 60_000,
    hardPinnedEntities: 100,
    hardSavedLayoutRecords: 512,
    inactiveRetentionMs: 30 * 60_000,
    hardSiteEvidencePerEntity: 16,
    hardActiveCalls: 4_096,
    hardOverflowCallKeys: 4_096,
    hardPendingEffects: 1_024,
    hardMetadataRequests: 64,
    hardMetadataCacheEntries: 5_000,
    hardIncomingQueue: 4_096,
    incomingBatchSize: 512,
    callStaleAfterMs: 15_000,
    activeOverflowTtlMs: 30_000
  },
  layout: {
    universeSpacing: 105,
    groupOrbitRadius: 190,
    radioOrbitRadius: 54,
    maximumVelocity: 180,
    damping: 0.84,
    universeStrength: 0.025,
    groupStrength: 0.055,
    radioStrength: 0.09,
    flattenStrength: 0.14,
    collisionCellSize: 30,
    collisionPadding: 5,
    maximumDeltaMs: 50
  },
  animation: {
    txAttackMs: 180,
    txReleaseMs: 2_400,
    pulseDurationMs: 700,
    particleFlightMs: 1_500,
    pulseScale: 0.10,
    migrationMotionMs: 1_400,
    effectCoalesceMs: 500,
    softAnimatedEffects: 24,
    cameraTransitionMs: 720,
    cameraBackTransitionMs: 560,
    autoRotateDefault: true,
    autoRotateIdleDelayMs: 2_200,
    autoRotateSpeed: 0.35
  }
};

const EXACT_KEYS = Object.freeze({
  root: Object.freeze(['profile', 'render', 'state', 'layout', 'animation']),
  render: Object.freeze(Object.keys(BALANCED_DEFAULTS.render)),
  state: Object.freeze(Object.keys(BALANCED_DEFAULTS.state)),
  layout: Object.freeze(Object.keys(BALANCED_DEFAULTS.layout)),
  animation: Object.freeze(Object.keys(BALANCED_DEFAULTS.animation))
});

function plain(value, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new TypeError(`${label} must be an object.`);
  }
  return value;
}

function exact(value, expected, label) {
  const actual = Object.keys(plain(value, label)).sort();
  const keys = [...expected].sort();
  if (actual.length !== keys.length || actual.some((key, index) => key !== keys[index])) {
    throw new TypeError(`${label} contains unknown or missing settings.`);
  }
}

function integer(value, minimum, maximum, label) {
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) {
    throw new TypeError(`${label} is invalid.`);
  }
  return value;
}

function finite(value, minimum, maximum, label) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < minimum || value > maximum) {
    throw new TypeError(`${label} is invalid.`);
  }
  return value;
}

function boolean(value, label) {
  if (typeof value !== 'boolean') throw new TypeError(`${label} is invalid.`);
  return value;
}

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function merge(base, update) {
  if (!update || typeof update !== 'object' || Array.isArray(update)) return clone(base);
  const result = clone(base);
  Object.entries(update).forEach(([key, value]) => {
    if (value && typeof value === 'object' && !Array.isArray(value) &&
        result[key] && typeof result[key] === 'object' && !Array.isArray(result[key])) {
      result[key] = { ...result[key], ...value };
    } else {
      result[key] = value;
    }
  });
  return result;
}

function deepFreeze(value) {
  Object.values(value).forEach((child) => {
    if (child && typeof child === 'object' && !Object.isFrozen(child)) deepFreeze(child);
  });
  return Object.freeze(value);
}

function validateConfig(candidate) {
  exact(candidate, EXACT_KEYS.root, 'Network Visualizer configuration');
  exact(candidate.render, EXACT_KEYS.render, 'Network Visualizer render configuration');
  exact(candidate.state, EXACT_KEYS.state, 'Network Visualizer state configuration');
  exact(candidate.layout, EXACT_KEYS.layout, 'Network Visualizer layout configuration');
  exact(candidate.animation, EXACT_KEYS.animation, 'Network Visualizer animation configuration');
  if (candidate.profile !== 'balanced') throw new TypeError('Only the balanced Network Visualizer profile is supported.');

  const render = {
    softRadiosPerGroup: integer(candidate.render.softRadiosPerGroup, 1, 20_000, 'softRadiosPerGroup'),
    softRadiosTotal: integer(candidate.render.softRadiosTotal, 1, 20_000, 'softRadiosTotal'),
    softExpandedGroups: integer(candidate.render.softExpandedGroups, 1, 5_000, 'softExpandedGroups'),
    softExpandedUniverses: integer(candidate.render.softExpandedUniverses, 1, 64, 'softExpandedUniverses'),
    hardNodes: integer(candidate.render.hardNodes, 8, 10_000, 'hardNodes'),
    hardLinks: integer(candidate.render.hardLinks, 1, 20_000, 'hardLinks'),
    hardLabels: integer(candidate.render.hardLabels, 1, 5_000, 'hardLabels'),
    hardParticles: integer(candidate.render.hardParticles, 0, 5_000, 'hardParticles'),
    hardMigrationTrails: integer(candidate.render.hardMigrationTrails, 0, 1_000, 'hardMigrationTrails'),
    migrationTrailTtlMs: integer(candidate.render.migrationTrailTtlMs, 1, 8_000, 'migrationTrailTtlMs'),
    radioQuietAfterMs: integer(candidate.render.radioQuietAfterMs, 1_000, 24 * 60 * 60_000, 'radioQuietAfterMs'),
    radioHideAfterMs: integer(candidate.render.radioHideAfterMs, 1_000, 24 * 60 * 60_000, 'radioHideAfterMs'),
    groupCollapseAfterMs: integer(candidate.render.groupCollapseAfterMs, 1_000, 24 * 60 * 60_000,
      'groupCollapseAfterMs'),
    universeCollapseAfterMs: integer(candidate.render.universeCollapseAfterMs, 1_000, 24 * 60 * 60_000,
      'universeCollapseAfterMs'),
    minimumResidenceMs: integer(candidate.render.minimumResidenceMs, 0, 5 * 60_000, 'minimumResidenceMs'),
    rankHysteresis: finite(candidate.render.rankHysteresis, 0, 10_000, 'rankHysteresis')
  };
  if (render.radioQuietAfterMs >= render.radioHideAfterMs) {
    throw new TypeError('radioQuietAfterMs must be less than radioHideAfterMs.');
  }
  if (render.groupCollapseAfterMs >= render.universeCollapseAfterMs) {
    throw new TypeError('groupCollapseAfterMs must be less than universeCollapseAfterMs.');
  }
  if (render.hardLabels > render.hardNodes) throw new TypeError('hardLabels cannot exceed hardNodes.');

  const state = {
    hardRadios: integer(candidate.state.hardRadios, 1, 100_000, 'hardRadios'),
    hardGroups: integer(candidate.state.hardGroups, 1, 25_000, 'hardGroups'),
    hardUniverses: integer(candidate.state.hardUniverses, 1, 1_024, 'hardUniverses'),
    hardSemanticEvents: integer(candidate.state.hardSemanticEvents, 1, 100_000, 'hardSemanticEvents'),
    hardTransitionsPerRadio: integer(candidate.state.hardTransitionsPerRadio, 1, 1_000,
      'hardTransitionsPerRadio'),
    hardDedupeEntries: integer(candidate.state.hardDedupeEntries, 1, 500_000, 'hardDedupeEntries'),
    dedupeTtlMs: integer(candidate.state.dedupeTtlMs, 1_000, 24 * 60 * 60_000, 'dedupeTtlMs'),
    hardPinnedEntities: integer(candidate.state.hardPinnedEntities, 0, 10_000, 'hardPinnedEntities'),
    hardSavedLayoutRecords: integer(candidate.state.hardSavedLayoutRecords, 0, 25_000,
      'hardSavedLayoutRecords'),
    inactiveRetentionMs: integer(candidate.state.inactiveRetentionMs, 1_000, 7 * 24 * 60 * 60_000,
      'inactiveRetentionMs'),
    hardSiteEvidencePerEntity: integer(candidate.state.hardSiteEvidencePerEntity, 1, 1_000,
      'hardSiteEvidencePerEntity'),
    hardActiveCalls: integer(candidate.state.hardActiveCalls, 1, 100_000, 'hardActiveCalls'),
    hardOverflowCallKeys: integer(candidate.state.hardOverflowCallKeys, 1, 100_000, 'hardOverflowCallKeys'),
    hardPendingEffects: integer(candidate.state.hardPendingEffects, 0, 100_000, 'hardPendingEffects'),
    hardMetadataRequests: integer(candidate.state.hardMetadataRequests, 0, 10_000, 'hardMetadataRequests'),
    hardMetadataCacheEntries: integer(candidate.state.hardMetadataCacheEntries, 0, 100_000,
      'hardMetadataCacheEntries'),
    hardIncomingQueue: integer(candidate.state.hardIncomingQueue, 1, 100_000, 'hardIncomingQueue'),
    incomingBatchSize: integer(candidate.state.incomingBatchSize, 1, 10_000, 'incomingBatchSize'),
    callStaleAfterMs: integer(candidate.state.callStaleAfterMs, 1_000, 10 * 60_000, 'callStaleAfterMs'),
    activeOverflowTtlMs: integer(candidate.state.activeOverflowTtlMs, 1_000, 10 * 60_000,
      'activeOverflowTtlMs')
  };
  if (state.incomingBatchSize > state.hardIncomingQueue) {
    throw new TypeError('incomingBatchSize cannot exceed hardIncomingQueue.');
  }

  const layout = {
    universeSpacing: finite(candidate.layout.universeSpacing, 10, 10_000, 'universeSpacing'),
    groupOrbitRadius: finite(candidate.layout.groupOrbitRadius, 5, 5_000, 'groupOrbitRadius'),
    radioOrbitRadius: finite(candidate.layout.radioOrbitRadius, 2, 1_000, 'radioOrbitRadius'),
    maximumVelocity: finite(candidate.layout.maximumVelocity, 1, 10_000, 'maximumVelocity'),
    damping: finite(candidate.layout.damping, 0, 0.999, 'damping'),
    universeStrength: finite(candidate.layout.universeStrength, 0, 1, 'universeStrength'),
    groupStrength: finite(candidate.layout.groupStrength, 0, 1, 'groupStrength'),
    radioStrength: finite(candidate.layout.radioStrength, 0, 1, 'radioStrength'),
    flattenStrength: finite(candidate.layout.flattenStrength, 0, 1, 'flattenStrength'),
    collisionCellSize: finite(candidate.layout.collisionCellSize, 2, 1_000, 'collisionCellSize'),
    collisionPadding: finite(candidate.layout.collisionPadding, 0, 100, 'collisionPadding'),
    maximumDeltaMs: integer(candidate.layout.maximumDeltaMs, 1, 1_000, 'maximumDeltaMs')
  };

  const animation = {
    txAttackMs: integer(candidate.animation.txAttackMs, 0, 5_000, 'txAttackMs'),
    txReleaseMs: integer(candidate.animation.txReleaseMs, 0, 30_000, 'txReleaseMs'),
    pulseDurationMs: integer(candidate.animation.pulseDurationMs, 0, 10_000, 'pulseDurationMs'),
    particleFlightMs: integer(candidate.animation.particleFlightMs, 100, 10_000, 'particleFlightMs'),
    pulseScale: finite(candidate.animation.pulseScale, 0, 0.5, 'pulseScale'),
    migrationMotionMs: integer(candidate.animation.migrationMotionMs, 0, 30_000, 'migrationMotionMs'),
    effectCoalesceMs: integer(candidate.animation.effectCoalesceMs, 0, 10_000, 'effectCoalesceMs'),
    softAnimatedEffects: integer(candidate.animation.softAnimatedEffects, 0, 1_000, 'softAnimatedEffects'),
    cameraTransitionMs: integer(candidate.animation.cameraTransitionMs, 0, 10_000, 'cameraTransitionMs'),
    cameraBackTransitionMs: integer(candidate.animation.cameraBackTransitionMs, 0, 10_000,
      'cameraBackTransitionMs'),
    autoRotateDefault: boolean(candidate.animation.autoRotateDefault, 'autoRotateDefault'),
    autoRotateIdleDelayMs: integer(candidate.animation.autoRotateIdleDelayMs, 0, 60_000,
      'autoRotateIdleDelayMs'),
    autoRotateSpeed: finite(candidate.animation.autoRotateSpeed, 0.01, 10, 'autoRotateSpeed')
  };
  if (animation.migrationMotionMs > render.migrationTrailTtlMs) {
    throw new TypeError('migrationMotionMs cannot exceed migrationTrailTtlMs.');
  }

  return deepFreeze({ profile: 'balanced', render, state, layout, animation });
}

function createConfig(overrides = {}) {
  return validateConfig(merge(BALANCED_DEFAULTS, overrides));
}

const BALANCED_CONFIG = createConfig();

export { BALANCED_CONFIG, createConfig, validateConfig };
