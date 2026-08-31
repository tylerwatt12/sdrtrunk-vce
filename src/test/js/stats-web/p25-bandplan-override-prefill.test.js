'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.resolve(process.argv[2] ||
  path.resolve(__dirname, '../../../../stats-web/assets/app.js')), 'utf8');

function closingBrace(start) {
  let depth = 0;
  let quote = '';
  for (let index = start; index < source.length; index += 1) {
    const character = source[index];
    if (quote) {
      if (character === '\\') index += 1;
      else if (character === quote) quote = '';
      continue;
    }
    if (character === '\'' || character === '"' || character === '`') quote = character;
    else if (character === '{') depth += 1;
    else if (character === '}' && --depth === 0) return index;
  }
  throw new Error('Unclosed function');
}

function functionSource(name) {
  const start = source.indexOf(`function ${name}(`);
  assert.notEqual(start, -1, `Missing ${name}`);
  const open = source.indexOf('{', start);
  return source.slice(start, closingBrace(open) + 1);
}

function constantSource(name, ending) {
  const start = source.indexOf(`const ${name} =`);
  assert.notEqual(start, -1, `Missing ${name}`);
  const end = source.indexOf(ending, start);
  assert.notEqual(end, -1, `Unclosed ${name}`);
  return source.slice(start, end + ending.length);
}

const behavior = vm.runInNewContext(`(() => {
  ${constantSource('P25_OVERRIDE_CREATE_ROUTE_KEYS', ']);')}
  ${functionSource('p25OverrideCreateRouteProfile')}
  ${functionSource('p25OverrideCreateRouteGuid')}
  ${functionSource('p25OverrideDetectedBands')}
  ${functionSource('p25OverrideSameScope')}
  let route = new URLSearchParams();
  let replacement = null;
  const currentHref = () => \`/?\${route.toString()}\`;
  const window = { history: { replaceState: (_state, _title, href) => { replacement = href; } } };
  ${functionSource('clearP25OverrideCreateRoute')}
  return {
    profile: (value) => p25OverrideCreateRouteProfile(new URLSearchParams(value)),
    guid: (value) => p25OverrideCreateRouteGuid(new URLSearchParams(value)),
    detectedBands: (value, profile) => p25OverrideDetectedBands(value, profile),
    sameScope: p25OverrideSameScope,
    clear: (value) => {
      route = new URLSearchParams(value);
      replacement = null;
      clearP25OverrideCreateRoute();
      return { query: route.toString(), replacement };
    }
  };
})()`, { URLSearchParams });

function plain(value) {
  return JSON.parse(JSON.stringify(value));
}

const siteGuid = '728d2d66-de4e-476b-a696-919f32dd4d12';
const validQuery = `createP25Override=1&wacn=BEE00&system=49F&rfss=01&site=02&guid=${siteGuid}`;
const valid = behavior.profile(validQuery);
assert.deepEqual(plain(valid), {
  wacn: 0xBEE00,
  system: 0x49F,
  rfss: 0x01,
  site: 0x02
});
assert.equal(behavior.guid(validQuery), siteGuid);
assert.deepEqual(plain(behavior.profile(
  `createP25Override=1&wacn=bee00&system=49f&rfss=0a&site=0b&guid=${siteGuid}`)), {
  wacn: 0xBEE00,
  system: 0x49F,
  rfss: 0x0A,
  site: 0x0B
});

for (const query of [
  `wacn=BEE00&system=49F&rfss=01&site=02&guid=${siteGuid}`,
  `createP25Override=0&wacn=BEE00&system=49F&rfss=01&site=02&guid=${siteGuid}`,
  `createP25Override=1&wacn=BEE00&system=49F&rfss=01&guid=${siteGuid}`,
  `createP25Override=1&wacn=EE00&system=49F&rfss=01&site=02&guid=${siteGuid}`,
  `createP25Override=1&wacn=BEE00&system=49F&rfss=01&site=GG&guid=${siteGuid}`
]) {
  assert.equal(behavior.profile(query), null, `Invalid site identity was accepted: ${query}`);
}

for (const query of [
  'createP25Override=1',
  'createP25Override=0&guid=728d2d66-de4e-476b-a696-919f32dd4d12',
  'createP25Override=1&guid=728D2D66-DE4E-476B-A696-919F32DD4D12',
  'createP25Override=1&guid=728d2d66-de4e-476b-a696-919f32dd4d1',
  'createP25Override=1&guid=not-a-guid'
]) {
  assert.equal(behavior.guid(query), null, `Invalid site GUID was accepted: ${query}`);
}

assert.deepEqual(plain(behavior.detectedBands({
  band_source: 'OTA',
  wacn: 0xBEE00,
  system_id: 0x49F,
  rfss: 0x01,
  site_id: 0x02,
  home_bands: [
    {
      band: 0, tdma: 0, base_hz: 851_006_250, bandwidth_hz: 12_500,
      spacing_hz: 6_250, transmit_offset_hz: -45_000_000, timeslots: 1
    },
    {
      band: 3, tdma: true, base_hz: 762_006_250, bandwidth_hz: 12_500,
      spacing_hz: 12_500, transmit_offset_hz: 30_000_000, timeslots: 2
    }
  ]
}, valid)), [
  {
    identifier: 0, type: 'FDMA', base_frequency: 851_006_250, bandwidth: 12_500,
    channel_spacing: 6_250, transmit_offset: -45_000_000
  },
  {
    identifier: 3, type: 'TDMA', base_frequency: 762_006_250, bandwidth: 12_500,
    channel_spacing: 12_500, transmit_offset: 30_000_000
  }
]);

const validDetectedRow = {
  band: 2, tdma: false, base_hz: 851_000_000, bandwidth_hz: 12_500,
  spacing_hz: 12_500, transmit_offset_hz: 0, timeslots: 1
};
assert.deepEqual(plain(behavior.detectedBands({
  band_source: 'OTA',
  wacn: 0xBEE00,
  system_id: 0x49F,
  rfss: 0x01,
  site_id: 0x02,
  home_bands: [
    validDetectedRow,
    { ...validDetectedRow, band: 3, transmit_offset_hz: null }
  ]
}, valid)), [], 'An incomplete row must reject the complete prefill instead of producing a partial override.');
assert.deepEqual(plain(behavior.detectedBands({
  band_source: 'OTA', wacn: 0xBEE00, system_id: 0x49F, rfss: 1, site_id: 2,
  home_bands: [validDetectedRow, { ...validDetectedRow }]
}, valid)), [], 'Duplicate band IDs must reject the complete prefill.');
assert.deepEqual(plain(behavior.detectedBands({
  band_source: 'P25_OVERRIDE', wacn: 0xBEE00, system_id: 0x49F, rfss: 1, site_id: 2,
  home_bands: [validDetectedRow]
}, valid)), [], 'An existing override must not be presented as a detected OTA band plan.');
assert.deepEqual(plain(behavior.detectedBands({
  band_source: 'OTA', wacn: 0xBEE00, system_id: 0x49E, rfss: 1, site_id: 2,
  home_bands: [validDetectedRow]
}, valid)), [], 'Bands from a different decoded system must not prefill this site override.');
assert.deepEqual(plain(behavior.detectedBands({
  band_source: 'OTA', wacn: 0xBEE00, system_id: 0x49F, rfss: 1, site_id: 2, home_bands: null
}, valid)), []);

assert.equal(behavior.sameScope(valid, {
  wacn: 0xBEE00, system: 0x49F, rfss: 0x01, site: 0x02
}), true);
assert.equal(behavior.sameScope(valid, {
  wacn: 0xBEE00, system: 0x49F, rfss: 0x01, site: 0x03
}), false, 'A different site must not suppress the requested site-scoped draft.');
assert.equal(behavior.sameScope(valid, {
  wacn: 0xBEE00, system: 0x49F, rfss: null, site: null
}), false, 'A system-wide profile is not the same scope as the requested site profile.');

assert.deepEqual(plain(behavior.clear(
  `view=admin&tab=p25-bandplans&createP25Override=1&wacn=BEE00&system=49F&rfss=01&site=02&guid=${siteGuid}&q=keep`)), {
  query: 'view=admin&tab=p25-bandplans&q=keep',
  replacement: '/?view=admin&tab=p25-bandplans&q=keep'
});

const render = functionSource('renderAdminP25BandplanOverrides');
assert.match(render, /const createRequested = route\.has\('createP25Override'\)/);
assert.match(render, /p25OverrideSameScope\(profile, requestedProfile\)/);
assert.match(render, /if \(!requestedCard\)/,
  'Only a missing exact site card may produce a new draft.');
assert.match(render, /api\(siteApiPath\(requestedGuid, 'frequency-bands'\)\)/,
  'A missing exact site profile must load its existing detected band-plan resource.');
assert.match(render, /p25OverrideDetectedBands\(detected, requestedProfile\)/);
assert.match(render, /list\.prepend\(requestedCard\)/);
assert.match(render, /prepared with.*currently detected OTA band/);
assert.match(render, /no usable detected OTA bands were available for this site/,
  'No detected bands must leave a clearly explained blank-row draft.');
assert.match(render, /requestedCard\.scrollIntoView/,
  'An existing exact site card must be brought into view.');
assert.match(render, /clearP25OverrideCreateRoute\(\)/,
  'The one-shot route must be consumed after the existing profiles load.');
