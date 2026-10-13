'use strict';
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function main() {
  const { enrollmentQrMatrix } = await import(pathToFileURL(path.resolve(__dirname,
    '../../../../stats-web/assets/features/alerts-qr.js')).href);
  const target = 'https://radioresolve.example.net/enroll?code=demo-id.demo-pairing-code-22';
  const matrix = enrollmentQrMatrix(target);
  assert.equal(matrix.length, 37);
  assert.ok(matrix.every(row => row.length === matrix.length && row.every(value => typeof value === 'boolean')));
  // This complete matrix was independently decoded from both rendered themes with macOS Vision.
  const hash = crypto.createHash('sha256').update(matrix.map(row => row.map(value => value ? '1' : '0').join('')).join('\n')).digest('hex');
  assert.equal(hash, 'a29dea342fd6c253306e5932fedc77ac6ce59e84080156cd30d02aff8024c7bc');
  assert.notDeepEqual(enrollmentQrMatrix(target + 'x'), matrix, 'The actual connection URL determines the QR modules');
  assert.throws(() => enrollmentQrMatrix('http://radioresolve.example.net'), /unavailable/);
  assert.throws(() => enrollmentQrMatrix('https://user:password@radioresolve.example.net'), /unavailable/);
  console.log('Pinned QR encoder renders the independently decoded connection URL and rejects unsafe addresses.');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
