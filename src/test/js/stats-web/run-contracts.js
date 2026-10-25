/* Copyright (C) 2026 Dennis Sheirer. Licensed under GPL-3.0-or-later. */
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const root = path.resolve(__dirname, '../../../..');
const node = process.env.NODE_BINARY || process.execPath;
const contracts = [
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
  ["health alert settings", "health-alert-settings.test.js", "stats-web/assets/app.js"],
  ["Live detail filters", "live-detail-filters.test.js", "stats-web/assets/app.js"],
  ["Live presentation", "live-presentation.test.js", "stats-web/assets/app.js"],
  ["Live bandwidth", "live-bandwidth.test.js", "stats-web/assets/app.js"],
  ["Live impaired network", "live-network-simulation.test.js", "stats-web/assets/app.js"],
  ["Live focus recovery", "live-focus-recovery.test.js", "stats-web/assets/app.js"],
  ["Call matching deltas", "call-matching-deltas.test.js", "stats-web/assets/app.js"],
  ["Call count scopes and dates", "call-count-scope.test.js", "stats-web/assets/app.js"],
  ["Tuner allocation", "tuner-allocation.test.js", "stats-web/assets/app.js"],
  ["source names", "source-names.test.js", "stats-web/assets/core/source-names.js"],
  ["source name preference", "source-name-preference.test.js", "stats-web/assets/app.js"],
  ["public Alias navigation", "public-alias-navigation.test.js", "stats-web/assets/app.js"],
  ["recordings playback", "recordings-playback.test.js", "stats-web/assets/features/recordings.js"],
  ["Listen map", "listen-map.test.js", "stats-web/assets/features/listen-map.js"],
  ["P25 Visualizer history", "p25-visualizer-history.test.js", "stats-web/assets/features/network-visualizer"],
  ["P25 Visualizer rolling expiry", "p25-visualizer-expiry.test.js", "stats-web/assets/features/network-visualizer"],
  ["P25 Visualizer live", "p25-visualizer-live.test.js", "stats-web/assets/features/network-visualizer"],
  ["P25 Visualizer camera", "p25-visualizer-camera.test.js", "stats-web/assets/features/network-visualizer"],
  ["P25 Visualizer affiliation", "p25-visualizer-affiliation.test.js", "stats-web/assets/features/network-visualizer"],
  ["P25 Visualizer settings", "p25-visualizer-settings.test.js", "stats-web/assets/features/network-visualizer"],
  ["channel patches", "channel-patches.test.js", "stats-web/assets/app.js"],
  ["CSS architecture", "css-architecture.test.js", "stats-web/assets/app.css"],
  ["page lifecycle", "page-lifecycle.test.js", "stats-web/assets/core/page-lifecycle.js"],
  ["radio identity presentation", "radio-identity-presentation.test.js", "stats-web/assets/app.js"],
  ["external RF analysis", "radioresolve-analysis.test.js", "stats-web/assets/features/radioresolve-analysis.js"],
  ["receiver-health alert catalog", "receiver-health-alerts.test.js", "stats-web/assets/core"],
  ["receiver-health pagination", "receiver-health-pagination.test.js", "stats-web/assets/app.js"],
  ["status availability", "status-availability.test.js", "stats-web/assets/app.js"],
  ["support report", "support-report.test.js", "stats-web/assets/app.js"],
  ["tuner idle markers", "tuner-idle-markers.test.js", "stats-web/assets/app.js"],
  ["spectrum display preferences", "spectrum-display-preferences.test.js", "stats-web/assets/core/spectrum-display-preferences.js"],
  ["spectrum automatic range", "spectrum-auto-range.test.js", "stats-web/assets/app.js"],
  ["diagnostic strength encoding", "diagnostic-strength.test.js", "stats-web/assets/app.js"],
  ["web call player", "web-call-player.test.js", "stats-web/assets/web-call-player.js"],
  ["paging-tone detection settings", "paging-tone-detection.test.js", "stats-web/assets/features/paging-tone-detection.js"]
];

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
