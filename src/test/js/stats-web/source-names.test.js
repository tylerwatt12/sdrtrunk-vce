'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function main() {
  const modulePath = path.resolve(process.argv[2] || path.resolve(__dirname,
    '../../../../stats-web/assets/core/source-names.js'));
  const { formatSourceName } = await import(pathToFileURL(modulePath).href);
  const cases = [
    [['Engine 4', 'ENG 4'], 'ENG 4'],
    [['Engine 4', '  '], 'Engine 4'],
    [[null, 'ENG 4'], 'ENG 4'],
    [[null, null], ''],
    [['Engine 4', 'ENG 4', 'source_alias'], 'Engine 4'],
    [[' ', 'ENG 4', 'source_alias'], 'ENG 4'],
    [[' Engine 4 ', ' ENG 4 ', 'both'], 'Engine 4 · ENG 4'],
    [['Engine 4', ' engine 4 ', 'both'], 'Engine 4'],
    [[[' Engine 4 ', '', 'Command', 'engine 4'], 'COMMAND', 'both'], 'Engine 4, Command'],
    [[['Engine 4', 'Command'], 'Portable 4', 'both'], 'Engine 4, Command · Portable 4'],
    [[['Engine 4', 'Command'], '', 'talker_alias'], 'Engine 4, Command'],
    [[['Engine 4', 'Command'], 'Portable 4', 'source_alias'], 'Engine 4, Command'],
    [[[], 'Portable 4', 'both'], 'Portable 4'],
    [['Engine 4', '', 'both'], 'Engine 4'],
    [['Engine 4', 'ENG 4', 'unknown'], 'ENG 4']
  ];
  cases.forEach(([inputs, expected]) => assert.equal(formatSourceName(...inputs), expected,
    `Source name choice must handle ${JSON.stringify(inputs)}`));
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
