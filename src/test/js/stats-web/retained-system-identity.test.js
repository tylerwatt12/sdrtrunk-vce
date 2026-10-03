'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function main() {
  const featurePath = path.resolve(__dirname, '../../../../stats-web/assets/features/retained-statistics.js');
  const { retainedSystemIdentityFacts } = await import(pathToFileURL(featurePath).href);
  const facts = retainedSystemIdentityFacts({
    system_identity: { key: 'p25:bee00:49f', name: 'GCRCN', protocol: 'P25', wacn: 0xBEE00, system_id: 0x49F },
    home_system_name: 'Home Network', home_wacn: 0xBEE00, home_system_id: 0x4A2,
    foreign_system: { key: 'p25:bee00:123', name: 'Foreign Network', protocol: 'P25', wacn: 0xBEE00, system_id: 0x123 }
  });
  assert.deepEqual(facts, [
    ['System', 'GCRCN'], ['WACN', 'BEE00'], ['SysID', '49F'],
    ['Home system', 'Home Network'], ['Home WACN', 'BEE00'], ['Home SysID', '4A2'],
    ['Foreign system', 'Foreign Network'], ['Foreign WACN', 'BEE00'], ['Foreign SysID', '123']
  ], 'Removal review retains exact owning, home and foreign identities beside the friendly names');
  assert.deepEqual(retainedSystemIdentityFacts({ system_identity: {
    key: 'dmr:tier3:large:17', name: 'DMR Network', protocol: 'DMR', network_id: 17, model: 'LARGE'
  } }), [['System', 'DMR Network'], ['Network ID', 17], ['Model', 'LARGE']]);
  assert.deepEqual(retainedSystemIdentityFacts({ system_identity: {
    key: 'nxdn-c:regional:42', name: 'NXDN Network', protocol: 'NXDN', system_id: 42, location_category: 'REGIONAL'
  } }), [['System', 'NXDN Network'], ['System ID', 42], ['Location category', 'REGIONAL']]);
  assert.deepEqual(retainedSystemIdentityFacts({}), [], 'Unnamed unrelated cleanup rows do not invent an identity');
  console.log('retained system identity tests passed');
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
