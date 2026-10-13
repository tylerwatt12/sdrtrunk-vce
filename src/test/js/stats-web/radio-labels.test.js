'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function main() {
  const modulePath = path.resolve(process.argv[2] ||
    path.resolve(__dirname, '../../../../stats-web/assets/core/radio-labels.js'));
  const { formatP25RadioIdentifier, formatP25GroupIdentifier, p25IdentityFacts, p25IdentifierText, formatP25QualifiedIdentifierText, p25ServingSystemKey, parseP25RadioIdentifier } =
    await import(pathToFileURL(modulePath).href);
  const home = { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 4_326_018 };
  const local = { servingSystemKey: 'p25:bee00:348', homeSystemName: 'Home System' };
  const foreign = { servingSystemKey: 'p25:bee00:349', homeSystemName: 'Home System' };
  assert.deepEqual(parseP25RadioIdentifier('BEE00.348.4326018 (Working ID 501)'),
    { canonical_identity: home, observed_working_id: 501 });
  assert.deepEqual(parseP25RadioIdentifier('bee00.348.4326018'),
    { canonical_identity: home, observed_working_id: null });
  for (const invalid of ['12345', 'BEE00.348.0', 'BEE00.348.16777213',
    'BEE00.348.4326018 (Working ID 0)', 'BEE00.348.4326018 (Working ID 501)x',
    'ISSI 778240.840.4326018', '1(778240.840.4326018)']) {
    assert.equal(parseP25RadioIdentifier(invalid), null);
  }

  assert.equal(formatP25RadioIdentifier(home, local), '4326018');
  assert.equal(formatP25RadioIdentifier(home, { ...local, workingId: 4_326_018 }), '4326018',
    'A matching Working ID must not repeat the subscriber number.');
  assert.equal(formatP25RadioIdentifier(home, { ...foreign, workingId: 4_326_018 }), 'Home System · 4326018',
    'A matching Working ID does not establish local system ownership.');
  assert.equal(formatP25RadioIdentifier({ ...home, wacn: 0xBEE01 }, local), 'Home System · 4326018',
    'Both WACN and SysID must match before shortening a local identity.');
  assert.equal(formatP25RadioIdentifier(home, { ...foreign, homeSystemName: '' }), 'BEE00.348.4326018');
  assert.equal(formatP25RadioIdentifier(home), '4326018',
    'Unknown receiving context cannot establish a foreign home system.');
  assert.equal(formatP25RadioIdentifier(home, { homeSystemName: 'Home System' }), '4326018');
  assert.equal(formatP25RadioIdentifier(home, { ...local, workingId: 501 }), '4326018 (Working ID 501)',
    'A different Working ID does not make matching home-system context useful.');
  assert.equal(formatP25RadioIdentifier(home, { ...local, homeSystemName: '', workingId: 501 }),
    '4326018 (Working ID 501)', 'Local identities retain the permanent and different working numbers.');
  assert.equal(formatP25RadioIdentifier(home, { ...foreign, workingId: 501 }),
    'Home System · 4326018 (Working ID 501)');
  assert.equal(formatP25RadioIdentifier(home, { workingId: 501 }), '4326018 (Working ID 501)');
  assert.equal(formatP25RadioIdentifier(home, { servingSystemKey: ' P25:BEE00:348 ' }), '4326018');
  assert.equal(formatP25RadioIdentifier({ wacn: 1, system_id: 1, subscriber_id: 501 }, {
    servingSystemKey: 'p25:00001:001'
  }), '501', 'Equivalent padded hexadecimal keys identify the same system.');

  for (const scope of ['', 'p25:bee00:', 'p25:100000:348', 'p25:bee00:1000', 'dmr:bee00:348']) {
    assert.equal(formatP25RadioIdentifier(home, { servingSystemKey: scope }), '4326018',
      'Missing or invalid serving identities cannot add a home prefix.');
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
  assert.equal(formatP25RadioIdentifier({ wacn: 0, system_id: 0, subscriber_id: 501 }), '501');
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
  const sourceRef = { kind: 'radio', radio_system_key: 'p25:bee00:348' };
  const targetRef = { kind: 'radio', radio_system_key: 'p25:bee00:349' };
  const references = { source_entity_ref: sourceRef, target_entity_ref: targetRef };
  assert.equal(p25ServingSystemKey(references, 'source'), sourceRef.radio_system_key);
  assert.equal(p25ServingSystemKey(references, 'target'), targetRef.radio_system_key);
  assert.equal(p25ServingSystemKey({ entity_ref: sourceRef }), sourceRef.radio_system_key,
    'A radio detail reference supplies its authoritative receiving scope.');
  assert.equal(p25ServingSystemKey({ entity_ref: { ...sourceRef, kind: 'talkgroup' } }), '');
  assert.equal(p25ServingSystemKey({ entity_ref: sourceRef }, 'target'), '',
    'A generic radio reference cannot substitute for a target radio.');
  assert.equal(p25ServingSystemKey(references), '', 'There is no generic endpoint-scope fallback.');
  assert.equal(p25ServingSystemKey({ source_entity_ref: sourceRef }, 'target'), '',
    'Source scope must not be borrowed by a target.');
  assert.equal(p25ServingSystemKey({ source_entity_ref: { ...sourceRef, kind: 'talkgroup' } }, 'source'), '');
  assert.equal(p25ServingSystemKey({ source_entity_ref: { ...sourceRef, radio_system_key: ' ' } }, 'source'), '');
  assert.equal(p25ServingSystemKey({ ...references, radio_system_key: 'p25:00001:047' }, 'source'),
    'p25:00001:047', 'Explicit row scope remains authoritative.');
  assert.equal(p25ServingSystemKey({ ...references, playback_target: { radio_system_key: 'p25:00001:047' } },
    'source'), 'p25:00001:047');
  assert.equal(p25ServingSystemKey({ source_home_system_entity_ref: sourceRef }, 'source'), '',
    'Home-system context cannot substitute for a receiving system.');
  assert.equal(p25ServingSystemKey({ serving_system_key: null, radio_system_key: 'p25:00001:047',
    playback_target: { radio_system_key: 'p25:bee00:348' } }), '',
    'A captured unknown serving system must remain authoritative over routing or playback keys.');
  assert.equal(p25ServingSystemKey({ serving_system_key: 'p25:bee00:348', radio_system_key: 'p25:00001:047' }),
    'p25:bee00:348');
  const group = 'ISSI 2002(781824.840.1001)';
  const patch = `P:${group} [ISSI 781824.840.3003, ISSI 0(781824.840.3004)]`;
  for (const scope of ['', 'p25:bee00:348', 'p25:unknown', 'dmr:tier3:small:1']) {
    assert.equal(formatP25QualifiedIdentifierText(group, scope), '1001 (Local TG ID 2002)');
    assert.equal(formatP25QualifiedIdentifierText(patch, scope), 'P:1001 (Local TG ID 2002) [3003, 3004]');
  }
  assert.equal(formatP25QualifiedIdentifierText(patch, 'p25:00001:047'), 'P:BEE00.348.1001 (Local TG ID 2002) [BEE00.348.3003, BEE00.348.3004]');
  assert.equal(formatP25QualifiedIdentifierText(group, 'p25:bee00:349'), 'BEE00.348.1001 (Local TG ID 2002)');
  assert.equal(formatP25QualifiedIdentifierText(group, 'p25:bee01:348'), 'BEE00.348.1001 (Local TG ID 2002)');
  const legacyRadio = 'ISSI 781824.840.1234567';
  for (const scope of ['', null, 'p25:bee00:348']) {
    assert.equal(formatP25QualifiedIdentifierText(legacyRadio, scope), '1234567',
      'Bare 24-bit ISSI radio text suppresses its prefix for same or unknown captured scope.');
    assert.equal(formatP25QualifiedIdentifierText('ROAM 501(781824.840.1234567)', scope),
      '1234567 (Working ID 501)');
  }
  assert.equal(formatP25QualifiedIdentifierText(legacyRadio, 'p25:00001:047'), 'BEE00.348.1234567');
  assert.equal(formatP25QualifiedIdentifierText(legacyRadio, 'p25:00001:047', () => 'County Radio'),
    'County Radio · 1234567');
  for (const scope of ['', 'p25:bee00:348', 'p25:00001:047']) {
    const oversizedGroup = 'ISSI 77(781824.840.1234567)';
    assert.equal(formatP25QualifiedIdentifierText(oversizedGroup, scope), oversizedGroup,
      'A parenthesized ISSI group mapping must not gain a 24-bit group identity.');
  }
  assert.equal(p25IdentityFacts({ identity_key: 'v1-g-bee00-348-1234567' }, '', 'talkgroup'), null);
  for (const kind of ['ISSI', 'ROAM']) {
    for (const scope of ['', 'p25:bee00:348']) {
      assert.equal(formatP25QualifiedIdentifierText(`${kind} 781824.840.16777215`, scope), '16777215');
    }
    assert.equal(formatP25QualifiedIdentifierText(`${kind} 781824.840.16777215`, 'p25:00001:047'),
      'BEE00.348.16777215', 'Raw reserved diagnostic values obey scope without becoming subscriber facts.');
  }
  assert.equal(p25IdentityFacts({ identity_key: 'v1-r-bee00-348-16777215' }), null);
  assert.equal(parseP25RadioIdentifier('BEE00.348.16777215'), null);
  for (const value of ['ISSI 2002(781824.840.1001', 'ISSI 2002(1048576.840.1001)',
    'ISSI 2002(781824.4096.1001)', 'ISSI 781824.840.1001x', '192.168.1.100']) {
    assert.equal(formatP25QualifiedIdentifierText(value), value, 'Malformed or unrelated text is preserved.');
  }
  const groupHome = { wacn: home.wacn, system_id: home.system_id, talkgroup_id: 7001 };
  for (const servingSystemKey of ['', null, local.servingSystemKey]) {
    assert.equal(formatP25GroupIdentifier(groupHome, { servingSystemKey, localId: 77 }),
      '7001 (Local TG ID 77)');
  }
  assert.equal(formatP25GroupIdentifier(groupHome, { ...foreign, localId: 77 }),
    'Home System · 7001 (Local TG ID 77)');
  assert.equal(formatP25GroupIdentifier(groupHome, { ...foreign, homeSystemName: '' }), 'BEE00.348.7001');
  for (const localId of [null, 0, 7001, true, ' ', 0x10000]) {
    assert.equal(formatP25GroupIdentifier(groupHome, { localId }), '7001');
  }
  const groupRow = { protocol: 'P25', serving_system_key: null, radio_system_key: 'p25:00001:047',
    target_id: 77, target_canonical_group_identity: groupHome, target_observed_local_id: 77 };
  assert.equal(p25IdentifierText(groupRow, 77, { prefix: 'target', kind: 'talkgroup' }), '7001 (Local TG ID 77)');
  assert.equal(p25IdentityFacts({ identity_key: 'v1-p-bee00-348-7001' }, '', 'patch_group').canonical.talkgroup_id, 7001);
  assert.equal(p25IdentityFacts({ identity_key: 'v1-g-x-x-77' }, '', 'talkgroup'), null);
  assert.equal(p25IdentifierText({ ...groupRow, target_observed_local_id: null }, 77,
    { prefix: 'target', kind: 'talkgroup' }), '7001', 'Explicit absent local evidence cannot become a copied Local TG hint.');
  assert.equal(p25IdentifierText({ ...groupRow, target_observed_local_id: undefined }, 77,
    { prefix: 'target', kind: 'talkgroup' }), '7001');
  for (const talkgroup_id of [0, 0xFFFF, 0x10000, true]) {
    assert.equal(formatP25GroupIdentifier({ ...groupHome, talkgroup_id }), '');
  }
  const peerRow = { native_id: 9001, canonical_identity: { ...home, subscriber_id: 9001 }, observed_working_id: 501 };
  assert.equal(p25IdentifierText(peerRow, 42), '', 'A peer cannot inherit the row’s subscriber identity.');
  assert.equal(p25IdentifierText(peerRow, 501), '9001 (Working ID 501)');
  console.log('P25 radio labels: local scope, foreign names, Working IDs and incomplete identities passed.');
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
