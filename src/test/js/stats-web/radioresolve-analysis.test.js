'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const modulePath = path.resolve(process.argv[2] ||
  path.resolve(__dirname, '../../../../stats-web/assets/features/radioresolve-analysis.js'));

async function main() {
  const source = fs.readFileSync(modulePath, 'utf8');
  const { buildRadioResolvePlannerUrl } = await import(
    `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
  const snapshot = {
    tuners: [
      { id: 'rtl-a', model: 'rtl-r8x', rate_hz: 2400000 },
      { id: 'airspy-a', model: 'airspy', rate_hz: 10000000 },
      { id: 'rtl-b', model: 'rtl-r8x', rate_hz: 2400000 }
    ],
    frequencies_hz: [851012500, 851262500, 854087500, 851012500]
  };
  const url = new URL(buildRadioResolvePlannerUrl(snapshot));
  assert.equal(url.origin, 'https://radioresolve.com');
  assert.equal(url.pathname, '/rf-planner/');
  assert.deepEqual(url.searchParams.getAll('model'), ['rtl-r8x', 'airspy', 'rtl-r8x']);
  assert.deepEqual(url.searchParams.getAll('rate'), ['2400000', '10000000', '2400000']);
  assert.deepEqual([...url.searchParams.keys()], ['model', 'rate', 'model', 'rate', 'model', 'rate',
    'version', 'frequencies']);
  assert.equal(url.searchParams.get('version'), 'sdrtrunk-vce');
  assert.equal(url.searchParams.get('frequencies'), '851.0125\n851.2625\n854.0875\n851.0125');

  const withoutChannels = new URL(buildRadioResolvePlannerUrl({ ...snapshot, frequencies_hz: [] }));
  assert.equal(withoutChannels.searchParams.has('frequencies'), false);
  assert.throws(() => buildRadioResolvePlannerUrl({ ...snapshot,
    tuners: [{ model: '../../other', rate_hz: 2400000 }] }),
    /supported RF analysis profile/);
  assert.throws(() => buildRadioResolvePlannerUrl({ ...snapshot, tuners: [] }),
    /No supported physical tuners/);
  assert.throws(() => buildRadioResolvePlannerUrl({ ...snapshot, frequencies_hz: null }),
    /Running channel frequencies are unavailable/);
  assert.throws(() => buildRadioResolvePlannerUrl({ ...snapshot,
    frequencies_hz: Array.from({ length: 1200 }, (_, index) => 851000000 + index) }),
  /Too many running frequencies/);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
