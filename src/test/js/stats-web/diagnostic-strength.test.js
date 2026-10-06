'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const test = require('node:test');
const vm = require('node:vm');
const source = fs.readFileSync(process.argv[2], 'utf8');

function functionSource(signature) {
  const start = source.indexOf(signature);
  assert.ok(start >= 0, signature);
  const opening = source.indexOf('{', start + signature.length);
  let depth = 0;
  for (let index = opening; index < source.length; index++) {
    if (source[index] === '{') depth++;
    else if (source[index] === '}' && --depth === 0) return source.slice(start, index + 1);
  }
  throw new Error('Unterminated ' + signature);
}

function harness() {
  const context = vm.createContext({ Uint8Array, Float32Array, DataView,
    DIAGNOSTIC_FRAME_HEADER_BYTES: 64, DIAGNOSTIC_FRAME_MAXIMUM_BYTES: 16 * 1024 * 1024,
    DIAGNOSTIC_FRAME_MAGIC: 0x53444447 });
  for (const name of ['decodeDiagnosticFrame', 'diagnosticValueFormat', 'diagnosticValueAt',
    'diagnosticFloatPayload', 'diagnosticStrengthHistory', 'tunerFrameDomain']) {
    vm.runInContext(functionSource(`function ${name}(`), context);
  }
  return context;
}

function compactFrame() {
  // Independent fixture for codes 0,1,2,3,4,5,6,7,63, including byte-straddling six-bit values.
  const payload = Uint8Array.of(0x40, 0x20, 0x0c, 0x44, 0x61, 0x1c, 0x3f);
  const bytes = new Uint8Array(84 + payload.length);
  const header = new DataView(bytes.buffer);
  header.setUint32(0, 0x53444447, true);
  header.setUint8(4, 1);
  header.setUint8(5, 4);
  header.setUint16(6, 84, true);
  header.setUint32(8, payload.length, true);
  header.setUint32(12, 9, true);
  header.setBigUint64(48, 100n, true);
  header.setInt32(56, 9, true);
  header.setInt32(60, 9, true);
  header.setInt32(68, 9, true);
  header.setUint8(72, 6);
  header.setUint8(73, 1);
  header.setFloat32(76, -80, true);
  header.setFloat32(80, 0, true);
  bytes.set(payload, 84);
  return bytes;
}

test('adaptive six-bit frames preserve all bins, weak values, and independently packed boundaries', () => {
  const context = harness();
  const frame = context.decodeDiagnosticFrame(compactFrame());
  const values = context.diagnosticFloatPayload(frame);
  assert.equal(frame.strengthBits, 6);
  assert.equal(values.length, 9);
  for (const [index, code] of [0, 1, 2, 3, 4, 5, 6, 7, 63].entries()) {
    assert.ok(Math.abs(values[index] - (-80 + code * 80 / 63)) < 0.00001);
  }
  const history = context.diagnosticStrengthHistory(frame);
  assert.equal(history.payload.byteLength, 7);
  assert.ok(history.payload.byteLength < values.byteLength / 4);
  frame.payload.fill(0);
  assert.deepEqual(Array.from(context.diagnosticFloatPayload(history)), Array.from(values));
});

test('legacy eight-bit and float frames remain supported, malformed compact frames fail', () => {
  const context = harness();
  assert.deepEqual(Array.from(context.diagnosticFloatPayload({ valueCount: 3,
    payload: Uint8Array.of(0, 128, 255) })), [-196, new Float32Array([-196 + 128 * 216 / 255])[0], 20]);
  const floats = new Float32Array([-137.5, -79.25, 0]);
  assert.deepEqual(Array.from(context.diagnosticFloatPayload({ valueCount: 3,
    payload: new Uint8Array(floats.buffer) })), Array.from(floats));
  const frame = context.decodeDiagnosticFrame(compactFrame());
  assert.throws(() => context.diagnosticFloatPayload({ ...frame, maximumDb: NaN }), /unsupported/);
  assert.throws(() => context.diagnosticFloatPayload({ ...frame, minimumDb: 1 }), /unsupported/);
  assert.throws(() => context.diagnosticFloatPayload({ ...frame, payload: frame.payload.slice(1) }), /unsupported/);
});

test('compact history preserves peak rendering and can be recolored after contrast changes', () => {
  const context = harness();
  const frame = context.decodeDiagnosticFrame(compactFrame());
  const palette = new Uint8ClampedArray(256 * 4);
  for (let color = 0; color < 256; color++) palette.fill(color, color * 4, color * 4 + 3);
  let rendered;
  Object.assign(context, {
    viewport: { startHz: 95.5, endHz: 104.5 }, waterfallBuffer: { width: 3, height: 256 },
    waterfallRowImage: { data: new Uint8ClampedArray(12) }, palette, dbFloor: -80, dbCeiling: 0,
    waterfallContext: { putImageData: image => { rendered = Array.from(image.data); } },
    nextWaterfallRow: 0, newestWaterfallRow: -1,
    waterfallObservedAtRows: new Float64Array(256), waterfallMetadataRows: [], waterfallRetuneRows: []
  });
  vm.runInContext(functionSource('const renderWaterfallRow =') + ';', context);
  context.history = context.diagnosticStrengthHistory(frame);
  context.metadata = frame;
  vm.runInContext('renderWaterfallRow(history, metadata, 123);', context);
  assert.deepEqual([rendered[0], rendered[4], rendered[8]], [8, 20, 255]);
  context.dbFloor = -40;
  vm.runInContext('renderWaterfallRow(history, metadata, 123);', context);
  assert.deepEqual([rendered[0], rendered[4], rendered[8]], [0, 0, 255]);
  assert.equal(context.history.payload.byteLength, 7);
});
