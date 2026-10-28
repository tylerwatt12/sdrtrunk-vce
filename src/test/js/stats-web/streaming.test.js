/* Copyright (C) 2026 Dennis Sheirer. Licensed under GPL-3.0-or-later. */
'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function main() {
  const root = path.resolve(__dirname, '../../../..');
  const modulePath = process.argv[2] || path.join(root, 'stats-web/assets/features/streaming.js');
  const { streamingDeliveryMetrics } = await import(pathToFileURL(modulePath).href);
  const baseKeys = ['queued', 'sent', 'aged_off', 'errors'];
  for (const provider of ['BROADCASTIFY_CALL', 'BROADCASTIFY_CALL_SITE']) {
    for (const duplicate_rejected of [null, 0, 12345]) {
      const definitions = streamingDeliveryMetrics({ provider, duplicate_rejected, errors: 2, attention: true });
      assert.deepEqual(definitions.map(([key]) => key),
        ['queued', 'sent', 'aged_off', 'duplicate_rejected', 'errors'],
        'Calls destinations keep the supported metric visible even without an active session count');
      assert.deepEqual(definitions.find(([key]) => key === 'duplicate_rejected'),
        ['duplicate_rejected', 'Duplicates skipped', 'icon-skip']);
    }
  }
  for (const provider of ['BROADCASTIFY', 'ICECAST_HTTP', 'ICECAST_TCP', 'SHOUTCAST_V1', 'SHOUTCAST_V2',
    'OPENMHZ', 'RDIO_SCANNER', 'RADIORESOLVE', 'unknown', undefined]) {
    assert.deepEqual(streamingDeliveryMetrics({ provider, duplicate_rejected: 12345 }).map(([key]) => key), baseKeys,
      `${provider || 'Unknown'} destinations retain the four applicable delivery metrics`);
  }
  console.log('Streaming delivery metric contracts passed.');
}

main().catch(error => { console.error(error); process.exitCode = 1; });
