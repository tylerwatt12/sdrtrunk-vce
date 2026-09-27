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
  const planner = { model: 'rtl-r8x', rate_hz: 2400000 };
  const catalog = { channels: [
    { frequencies_hz: [851012500, 851262500] },
    { frequencies_hz: [854087500, 851012500] }
  ] };
  const url = new URL(buildRadioResolvePlannerUrl(planner, catalog));
  assert.equal(url.origin, 'https://radioresolve.com');
  assert.equal(url.pathname, '/rf-planner/');
  assert.equal(url.searchParams.get('model'), 'rtl-r8x');
  assert.equal(url.searchParams.get('rate'), '2400000');
  assert.equal(url.searchParams.get('version'), 'sdrtrunk-vce');
  assert.equal(url.searchParams.get('frequencies'), '851.0125\n851.2625\n854.0875\n851.0125');
  assert.equal(url.searchParams.getAll('model').length, 1, 'Only the selected tuner is sent');
  assert.equal(url.searchParams.getAll('rate').length, 1, 'Only the selected tuner is sent');

  const withoutChannels = new URL(buildRadioResolvePlannerUrl(planner, { channels: [] }));
  assert.equal(withoutChannels.searchParams.has('frequencies'), false);
  assert.throws(() => buildRadioResolvePlannerUrl({ model: '../../other', rate_hz: 2400000 }, catalog),
    /supported RF analysis profile/);
  assert.throws(() => buildRadioResolvePlannerUrl(planner, {}),
    /Configured channel frequencies are unavailable/);
  assert.throws(() => buildRadioResolvePlannerUrl(planner, { channels: Array.from({ length: 1200 },
    (_, index) => ({ frequencies_hz: [851000000 + index] })) }),
  /Too many configured frequencies/);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
