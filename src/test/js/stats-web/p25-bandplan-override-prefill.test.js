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
  ${functionSource('p25OverrideSameScope')}
  let route = new URLSearchParams();
  let replacement = null;
  const currentHref = () => \`/?\${route.toString()}\`;
  const window = { history: { replaceState: (_state, _title, href) => { replacement = href; } } };
  ${functionSource('clearP25OverrideCreateRoute')}
  return {
    profile: (value) => p25OverrideCreateRouteProfile(new URLSearchParams(value)),
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

const valid = behavior.profile(
  'createP25Override=1&wacn=BEE00&system=49F&rfss=01&site=02');
assert.deepEqual(plain(valid), {
  wacn: 0xBEE00,
  system: 0x49F,
  rfss: 0x01,
  site: 0x02
});
assert.deepEqual(plain(behavior.profile(
  'createP25Override=1&wacn=bee00&system=49f&rfss=0a&site=0b')), {
  wacn: 0xBEE00,
  system: 0x49F,
  rfss: 0x0A,
  site: 0x0B
});

for (const query of [
  'wacn=BEE00&system=49F&rfss=01&site=02',
  'createP25Override=0&wacn=BEE00&system=49F&rfss=01&site=02',
  'createP25Override=1&wacn=BEE00&system=49F&rfss=01',
  'createP25Override=1&wacn=EE00&system=49F&rfss=01&site=02',
  'createP25Override=1&wacn=BEE00&system=49F&rfss=01&site=GG'
]) {
  assert.equal(behavior.profile(query), null, `Invalid site identity was accepted: ${query}`);
}

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
  'view=admin&tab=p25-bandplans&createP25Override=1&wacn=BEE00&system=49F&rfss=01&site=02&q=keep')), {
  query: 'view=admin&tab=p25-bandplans&q=keep',
  replacement: '/?view=admin&tab=p25-bandplans&q=keep'
});

const render = functionSource('renderAdminP25BandplanOverrides');
assert.match(render, /const createRequested = route\.has\('createP25Override'\)/);
assert.match(render, /p25OverrideSameScope\(profile, requestedProfile\)/);
assert.match(render, /if \(!requestedCard\)/,
  'Only a missing exact site card may produce a new draft.');
assert.match(render, /list\.prepend\(requestedCard\)/);
assert.match(render, /Enter its replacement bands, then save\./);
assert.match(render, /requestedCard\.scrollIntoView/,
  'An existing exact site card must be brought into view.');
assert.match(render, /clearP25OverrideCreateRoute\(\)/,
  'The one-shot route must be consumed after the existing profiles load.');
