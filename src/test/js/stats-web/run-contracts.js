/* Copyright (C) 2026 Dennis Sheirer. Licensed under GPL-3.0-or-later. */
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const root = path.resolve(__dirname, '../../../..');
const node = process.env.NODE_BINARY || process.execPath;
const contracts = [
  ["shared audio controls", "audio-controls.test.js", "stats-web/assets/core/audio-controls.js"],
  ["bulk action workflows", "action-workflows.test.js", "stats-web/assets/core/action-workflows.js"],
  ["admin system status", "admin-system-status.test.js", "stats-web/assets/app.js"],
  ["application log", "application-log.test.js", "stats-web/assets/core/application-log.js"],
  ["web diagnostic log", "web-diagnostic-log.test.js", "stats-web/assets/features/web-diagnostic-log.js"],
  ["Activity filters", "activity-filters.test.js", "stats-web/assets/app.js"],
  ["Alias editor", "alias-editor.test.js", "stats-web/assets/app.js"],
  ["analog Alias inventory", "analog-alias-inventory.test.js", "stats-web/assets/app.js"],
  ["topic alert workflows", "alerts.test.js", "stats-web/assets/features/alerts-model.js"],
  ["anonymous alert device persistence", "alerts-device-storage.test.js", "stats-web/assets/features/alerts-device-storage.js"],
  ["Direct push renewal", "alerts-direct.test.js", "stats-web/assets/features/alerts-direct.js"],
  ["push worker credential persistence", "alerts-worker-storage.test.js", "stats-web/assets/features/alerts-worker-storage.js"],
  ["alert enrollment QR codes", "alerts-qr.test.js", "stats-web/assets/features/alerts-qr.js"],
  ["channel editor composition", "channel-editor.test.js", "stats-web/assets/app.js"],
  ["frontend foundation", "frontend-foundation.test.js", "stats-web/assets/core"],
  ["operational preferences", "operational-preferences.test.js", "stats-web/assets/app.js"],
  ["health alert settings", "health-alert-settings.test.js", "stats-web/assets/app.js"],
  ["Live detail filters", "live-detail-filters.test.js", "stats-web/assets/app.js"],
  ["Live presentation", "live-presentation.test.js", "stats-web/assets/app.js"],
  ["Live bandwidth", "live-bandwidth.test.js", "stats-web/assets/app.js"],
  ["Live impaired network", "live-network-simulation.test.js", "stats-web/assets/app.js"],
  ["Live focus recovery", "live-focus-recovery.test.js", "stats-web/assets/app.js"],
  ["Live channel history", "live-channel-history.test.js", "stats-web/assets/app.js"],
  ["Call matching deltas", "call-matching-deltas.test.js", "stats-web/assets/app.js"],
  ["Call count scopes and dates", "call-count-scope.test.js", "stats-web/assets/app.js"],
  ["Tuner allocation", "tuner-allocation.test.js", "stats-web/assets/app.js"],
  ["Receiver nicknames", "receiver-nicknames.test.js", "stats-web/assets/app.js"],
  ["source names", "source-names.test.js", "stats-web/assets/core/source-names.js"],
  ["system names", "system-names.test.js", "stats-web/assets/core/system-labels.js"],
  ["system context presentation", "system-context-presentation.test.js", "stats-web/assets/app.js"],
  ["radio labels", "radio-labels.test.js", "stats-web/assets/core/radio-labels.js"],
  ["source name preference", "source-name-preference.test.js", "stats-web/assets/app.js"],
  ["public Alias navigation", "public-alias-navigation.test.js", "stats-web/assets/app.js"],
  ["recordings playback", "recordings-playback.test.js", "stats-web/assets/features/recordings.js"],
  ["Listen map", "listen-map.test.js", "stats-web/assets/features/listen-map.js"],
  ["P25 Visualizer startup", "p25-visualizer-bootstrap.test.js", "stats-web/assets/app.js"],
  ["P25 Visualizer scene", "p25-visualizer-history.test.js", "stats-web/assets/features/network-visualizer"],
  ["P25 Visualizer rolling expiry", "p25-visualizer-expiry.test.js", "stats-web/assets/features/network-visualizer"],
  ["P25 Visualizer live", "p25-visualizer-live.test.js", "stats-web/assets/features/network-visualizer"],
  ["P25 Visualizer camera", "p25-visualizer-camera.test.js", "stats-web/assets/features/network-visualizer"],
  ["P25 Visualizer emphasis", "p25-visualizer-emphasis.test.js", "stats-web/assets/features/network-visualizer"],
  ["P25 Visualizer affiliation", "p25-visualizer-affiliation.test.js", "stats-web/assets/features/network-visualizer"],
  ["P25 Visualizer settings", "p25-visualizer-settings.test.js", "stats-web/assets/features/network-visualizer"],
  ["channel patches", "channel-patches.test.js", "stats-web/assets/app.js"],
  ["CSS architecture", "css-architecture.test.js", "stats-web/assets/app.css"],
  ["page lifecycle", "page-lifecycle.test.js", "stats-web/assets/core/page-lifecycle.js"],
  ["page loading", "page-loading.test.js", "stats-web/assets/app.js"],
  ["radio identity presentation", "radio-identity-presentation.test.js", "stats-web/assets/app.js"],
  ["ISSI workspace", "issi-workspace.test.js", "stats-web/assets/app.js"],
  ["retained system identity", "retained-system-identity.test.js", "stats-web/assets/features/retained-statistics.js"],
  ["trunked discovery", "trunked-discovery-ui.test.js", "stats-web/assets"],
  ["remote support", "remote-support.test.js", "stats-web/assets/features/remote-support.js"],
  ["external RF analysis", "radioresolve-analysis.test.js", "stats-web/assets/features/radioresolve-analysis.js"],
  ["receiver-health alert catalog", "receiver-health-alerts.test.js", "stats-web/assets/core"],
  ["receiver-health pagination", "receiver-health-pagination.test.js", "stats-web/assets/app.js"],
  ["status availability", "status-availability.test.js", "stats-web/assets/app.js"],
  ["Streaming delivery metrics", "streaming.test.js", "stats-web/assets/features/streaming.js"],
  ["support report", "support-report.test.js", "stats-web/assets/app.js"],
  ["tuner idle markers", "tuner-idle-markers.test.js", "stats-web/assets/app.js"],
  ["spectrum display preferences", "spectrum-display-preferences.test.js", "stats-web/assets/core/spectrum-display-preferences.js"],
  ["spectrum automatic range", "spectrum-auto-range.test.js", "stats-web/assets/app.js"],
  ["spectrum live tuning", "spectrum-live-tune.test.js", "stats-web/assets/features/spectrum-live-tune.js"],
  ["diagnostic strength encoding", "diagnostic-strength.test.js", "stats-web/assets/app.js"],
  ["Direct Listen entrypoints", "direct-listen-entry.test.js", "stats-web/assets/core/direct-listen-entry.js"],
  ["Direct Listen session", "direct-listen-session.test.js", "stats-web/assets/core/direct-listen.js"],
  ["Direct Listen", "direct-listen.test.js", "stats-web/assets/core/direct-listen.js"],
  ["web call player", "web-call-player.test.js", "stats-web/assets/web-call-player.js"],
  ["paging-tone detection settings", "paging-tone-detection.test.js", "stats-web/assets/features/paging-tone-detection.js"],
  ["paging-tone summaries", "paging-tone-summary.test.js", "stats-web/assets/core/call-audio-details.js"],
  ["call filtering settings", "call-filtering.test.js", "stats-web/assets/features/call-filtering.js"]
];

// Some contracts invoke exported checks in sibling test files. Derive those helpers
// from their literal imports so each file needs only one execution entry point.
function verifyContractInventory() {
  const files = new Set(fs.readdirSync(__dirname).filter((filename) => filename.endsWith('.test.js')));
  const registered = new Set();
  const included = new Set();
  function include(filename) {
    if (included.has(filename)) return;
    if (!files.has(filename)) throw new Error(`Missing contract: ${filename}`);
    included.add(filename);
    const source = fs.readFileSync(path.join(__dirname, filename), 'utf8');
    for (const match of source.matchAll(/\brequire\s*\(\s*(['"])\.\/([^'"]+\.test(?:\.js)?)\1\s*\)/g)) {
      include(match[2].endsWith('.js') ? match[2] : `${match[2]}.js`);
    }
  }
  for (const [, filename] of contracts) {
    if (registered.has(filename)) throw new Error(`Duplicate contract entry: ${filename}`);
    registered.add(filename);
    include(filename);
  }
  const missing = [...files].filter((filename) => !included.has(filename)).sort();
  if (missing.length) throw new Error(`Unregistered web contracts: ${missing.join(', ')}`);
  console.log(`${included.size} web test files covered by ${registered.size} entry points and ` +
    `${included.size - registered.size} bundled helpers.`);
}

try {
  verifyContractInventory();
} catch (error) {
  console.error(`[FAIL] web contract inventory: ${error.message}`);
  process.exit(1);
}

let failures = 0;
for (const [name, filename, resourcePath] of contracts) {
  console.log(`[RUN] ${name}`);
  try {
    const script = path.join(__dirname, filename);
    const resource = path.join(root, resourcePath);
    if (!fs.statSync(script).isFile()) throw new Error(`Missing script: ${script}`);
    if (!fs.existsSync(resource)) throw new Error(`Missing resource: ${resource}`);
    const result = spawnSync(node, [script, resource], {
      cwd: root,
      stdio: 'inherit',
      timeout: 30_000,
      killSignal: 'SIGKILL'
    });
    if (result.error) throw result.error;
    if (result.status !== 0) {
      throw new Error(result.signal ? `Child terminated by ${result.signal}` : `Child exited with ${result.status}`);
    }
    console.log(`[PASS] ${name}`);
  } catch (error) {
    failures += 1;
    console.error(`[FAIL] ${name}: ${error.message}`);
  }
}

console.log(`${contracts.length - failures}/${contracts.length} web behavior contracts passed.`);
process.exitCode = failures === 0 ? 0 : 1;
