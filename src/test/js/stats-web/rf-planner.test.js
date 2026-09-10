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
  assert.doesNotMatch(source, /DSheirer|upstream|VCE \+ DSheirer modes|Planning model verified/i);
  assert.doesNotMatch(source, /rfp-placement-engine|polyphase channelizer/i);
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
  assert.equal(planner.findCenter(channel, r8x).center, 851025000);
  assert.equal(planner.isValidCenter(channel, 851000000, r8x), false);

  assert.deepEqual(planner.tunerSettingsFromTargets({ rows: [
    { tuner_type: 'RAFAELMICRO_R820T', sample_rate_hz: 2400000, name: 'RTL-SDR', label: 'RTL-SDR · 0001' },
    { tuner_type: 'AIRSPY_HF_PLUS', sample_rate_hz: 768000, name: 'Airspy HF+' },
    { tuner_type: 'RSP_DUO_1', sample_rate_hz: 1000000, usable_bandwidth_hz: 950000,
      center_exclusion_half_bandwidth_hz: 0, name: 'RSPduo dual' },
    { tuner_type: 'RSP_DUO_2', sample_rate_hz: 1000000, usable_bandwidth_hz: 900000,
      center_exclusion_half_bandwidth_hz: 0, name: 'RSPduo single' },
    { tuner_type: 'RECORDING', sample_rate_hz: 2400000, name: 'Recording' },
    { tuner_type: 'TEST', sample_rate_hz: 1000000, name: 'Other tuner' },
    { tuner_type: 'AIRSPY_R820T', sample_rate_hz: 0, name: 'Unavailable' }
  ] }), [
    { profileKey: 'rtl-r8x', rate: 2400000, customRate: 2400000, usableBandwidth: null, dcHalf: null,
      displayName: 'RTL-SDR · 0001' },
    { profileKey: 'airspy-hf', rate: 768000, customRate: 768000, usableBandwidth: null, dcHalf: null,
      displayName: 'Airspy HF+' },
    { profileKey: 'sdrplay-duo', rate: 1000000, customRate: 1000000, usableBandwidth: 950000, dcHalf: 0,
      displayName: 'RSPduo dual' },
    { profileKey: 'sdrplay', rate: 1000000, customRate: 1000000, usableBandwidth: 900000, dcHalf: 0,
      displayName: 'RSPduo single' },
    { profileKey: 'custom', rate: 1000000, customRate: 1000000, usableBandwidth: null, dcHalf: null,
      displayName: 'Other tuner' }
  ]);

  const manyTargets = [
    { tuner_type: 'RECORDING', sample_rate_hz: 2400000 },
    ...Array.from({ length: 11 }, (_, index) => ({
      tuner_type: 'AIRSPY_R820T', sample_rate_hz: 2500000, name: `Tuner ${index + 1}`
    }))
  ];
  const limited = planner.tunerSettingsFromTargets(manyTargets);
  assert.equal(limited.length, 10);
  assert.equal(limited[0].displayName, 'Tuner 1');
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
