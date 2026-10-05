'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function main() {
  const modulePath = path.resolve(process.argv[2] ||
    path.resolve(__dirname, '../../../../stats-web/assets/core/radio-labels.js'));
  const { formatP25RadioIdentifier, p25ServingSystemKey } = await import(pathToFileURL(modulePath).href);
  const home = { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 4_326_018 };
  const local = { servingSystemKey: 'p25:bee00:348', homeSystemName: 'Home System' };
  const foreign = { servingSystemKey: 'p25:bee00:349', homeSystemName: 'Home System' };

  assert.equal(formatP25RadioIdentifier(home, local), '4326018');
  assert.equal(formatP25RadioIdentifier(home, { ...local, workingId: 4_326_018 }), '4326018',
    'A matching Working ID must not repeat the subscriber number.');
  assert.equal(formatP25RadioIdentifier(home, { ...foreign, workingId: 4_326_018 }), 'Home System · 4326018',
    'A matching Working ID does not establish local system ownership.');
  assert.equal(formatP25RadioIdentifier({ ...home, wacn: 0xBEE01 }, local), 'Home System · 4326018',
    'Both WACN and SysID must match before shortening a local identity.');
  assert.equal(formatP25RadioIdentifier(home, { ...foreign, homeSystemName: '' }), 'BEE00.348.4326018');
  assert.equal(formatP25RadioIdentifier(home), 'BEE00.348.4326018',
    'Without the receiving system, the full home identity remains available.');
  assert.equal(formatP25RadioIdentifier(home, { homeSystemName: 'Home System' }), 'Home System · 4326018');
  assert.equal(formatP25RadioIdentifier(home, { ...local, workingId: 501 }), '4326018 (Working ID 501)');
  assert.equal(formatP25RadioIdentifier(home, { ...foreign, workingId: 501 }),
    'Home System · 4326018 (Working ID 501)');
  assert.equal(formatP25RadioIdentifier(home, { workingId: 501 }), 'BEE00.348.4326018 (Working ID 501)');
  assert.equal(formatP25RadioIdentifier(home, { servingSystemKey: ' P25:BEE00:348 ' }), '4326018');
  assert.equal(formatP25RadioIdentifier({ wacn: 1, system_id: 1, subscriber_id: 501 }, {
    servingSystemKey: 'p25:00001:001'
  }), '501', 'Equivalent padded hexadecimal keys identify the same system.');

  for (const scope of ['', 'p25:bee00:', 'p25:100000:348', 'p25:bee00:1000', 'dmr:bee00:348']) {
    assert.equal(formatP25RadioIdentifier(home, { servingSystemKey: scope }), 'BEE00.348.4326018',
      'Missing or invalid serving identities must not shorten a home identity.');
  }
  for (const field of ['wacn', 'system_id', 'subscriber_id']) {
    for (const value of [null, undefined, '', ' ', '\t', false, true, [], {}, -1, 1.5, Infinity, NaN]) {
      assert.equal(formatP25RadioIdentifier({ ...home, [field]: value }, local), '',
        'An incomplete or non-integer home identity must not fabricate an address.');
    }
  }
  assert.equal(formatP25RadioIdentifier({ ...home, wacn: 0x100000 }), '');
  assert.equal(formatP25RadioIdentifier({ ...home, system_id: 0x1000 }), '');
  assert.equal(formatP25RadioIdentifier({ ...home, subscriber_id: 0 }), '');
  assert.equal(formatP25RadioIdentifier({ ...home, subscriber_id: 0xFFFFFD }), '');
  assert.equal(formatP25RadioIdentifier({ wacn: 0, system_id: 0, subscriber_id: 501 }), '00000.000.501');
  assert.equal(formatP25RadioIdentifier({ wacn: 0, system_id: 0, subscriber_id: 501 }, {
    servingSystemKey: 'p25:00000:000'
  }), '501', 'Explicit zero home fields remain distinct from missing fields.');
  for (const workingId of [null, undefined, '', ' ', 0, -1, 1.5, false, true, [], 0xFFFFFD]) {
    assert.equal(formatP25RadioIdentifier(home, { ...local, workingId }), '4326018');
  }
  assert.equal(formatP25RadioIdentifier({ wacn: String(home.wacn), system_id: '840', subscriber_id: '4326018' }, {
    ...local, workingId: '4326018'
  }), '4326018', 'Numeric string fields retain numeric matching.');

  assert.equal(p25ServingSystemKey({ radio_system_key: ' p25:bee00:348 ',
    playback_target: { radio_system_key: 'p25:bee00:349' } }), 'p25:bee00:348');
  assert.equal(p25ServingSystemKey({ radio_system_key: ' ',
    playback_target: { radio_system_key: 'p25:bee00:349' } }), 'p25:bee00:349');
  assert.equal(p25ServingSystemKey({ radio_system_key: 'channel:1',
    playback_target: { radio_system_key: 'p25:bee00:349' } }), 'channel:1',
    'An explicit row scope must not be replaced by a conflicting playback scope.');
  assert.equal(p25ServingSystemKey({ system_key: 'p25:bee00:348',
    playback_target: { radio_system_key: 'p25:bee00:349' } }), 'p25:bee00:348',
    'Recording rows retain their explicit receiving system.');
  assert.equal(p25ServingSystemKey({ home_system_key: 'p25:bee00:348', system_name: 'Home System' }), '');
  assert.equal(p25ServingSystemKey(null), '');
  console.log('P25 radio labels: local scope, foreign names, Working IDs and incomplete identities passed.');
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
