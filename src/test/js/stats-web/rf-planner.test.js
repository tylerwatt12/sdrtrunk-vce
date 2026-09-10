'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const modulePath = path.resolve(process.argv[2] ||
  path.resolve(__dirname, '../../../../stats-web/assets/features/rf-planner.js'));

async function main() {
  const source = fs.readFileSync(modulePath, 'utf8');
  const planner = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

  assert.doesNotMatch(source, /\bfetch\s*\(|\bWebSocket\s*\(|\bEventSource\s*\(/);
  assert.doesNotMatch(source, /localStorage|sessionStorage/);
  assert.match(source, /\.rfp-add-tuner/);
  assert.match(source, /\.rfp-remove-tuner/);

  const checks = planner.modelChecks();
  assert.equal(checks.length, 10);
  assert.deepEqual(checks, Array(checks.length).fill(true));

  const overridden = planner.parseChannels('851.7750 @ 6.25k');
  assert.deepEqual(overridden.invalid, []);
  assert.deepEqual(overridden.channels, [{ frequency: 851775000, bandwidth: 6250 }]);

  const mhzOnly = planner.parseChannels('851.7750\n851.8MHz\n851775kHz\n851775000Hz');
  assert.deepEqual(mhzOnly.channels, [
    { frequency: 851775000, bandwidth: 12500 },
    { frequency: 851800000, bandwidth: 12500 }
  ]);
  assert.equal(mhzOnly.invalid.length, 2);

  const r8x = {
    rate: 2400000,
    usableBandwidth: 2352000,
    usablePercent: 0.98,
    dcHalf: 5000,
    min: 3180000,
    max: 1782030000
  };
  const channel = [{ frequency: 851000000, bandwidth: 12500 }];
  assert.equal(planner.findCenter(channel, r8x, 0, 'vce').center, 851025000);
  assert.equal(planner.findCenter(channel, r8x, 0, 'upstream').center, 850975000);
  assert.equal(planner.isValidCenter(channel, 851000000, r8x), false);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
