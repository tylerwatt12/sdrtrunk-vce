'use strict';
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function main() {
  const { enrollmentQrMatrix, enrollmentQrCanvas } = await import(pathToFileURL(path.resolve(__dirname,
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
  const rectangles = []; const cleared = []; const attributes = {};
  const context = { fillStyle: '', clearRect: (...value) => cleared.push(value),
    fillRect: (...value) => rectangles.push({ ink: context.fillStyle, value }) };
  const canvas = { setAttribute: (name, value) => { attributes[name] = value; }, getContext: type => { assert.equal(type, '2d'); return context; } };
  const documentValue = { documentElement: {}, createElement: tag => { assert.equal(tag, 'canvas'); return canvas; },
    defaultView: { getComputedStyle: () => ({ getPropertyValue: name => { assert.equal(name, '--qr-code-ink'); return '#000000'; } }) } };
  assert.equal(enrollmentQrCanvas(documentValue, target), canvas);
  assert.equal(canvas.width, 225); assert.equal(canvas.height, 225);
  assert.deepEqual(cleared, [[0, 0, 225, 225]], 'Background pixels and the four-module quiet zone remain transparent');
  const expected = matrix.flatMap((row, y) => row.flatMap((dark, x) => dark ? [{ ink: '#000000', value: [(x + 4) * 5, (y + 4) * 5, 5, 5] }] : []));
  assert.deepEqual(rectangles, expected, 'Only dark modules are painted, with the full quiet-zone margin');
  assert.equal(attributes.role, 'img'); assert.match(attributes['aria-label'], /connect a phone/);
  console.log('Pinned QR matrix, transparent background, four-module quiet zone, accessible canvas and safe URL contracts passed.');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
