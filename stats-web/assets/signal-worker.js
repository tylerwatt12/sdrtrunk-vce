/*
 * Decodes and validates SFFT frames away from the UI/render loop.  The ArrayBuffer is transferred in both
 * directions, so browser-side parsing does not copy every FFT payload. Version two identifies the cropped slice of
 * a larger adaptive FFT. Version one remains accepted during rolling upgrades.
 */
'use strict';

const VERSION_ONE_HEADER_BYTES = 80;
const VERSION_TWO_HEADER_BYTES = 96;
const MAXIMUM_BINS = 65536;
const MAXIMUM_SAFE_BIGINT = 9007199254740991n;

function fail(message, connectionEpoch) {
  self.postMessage({ type: 'error', message, connectionEpoch });
}

function safeUnsignedLong(view, offset) {
  const value = view.getBigUint64(offset, true);
  if (value > MAXIMUM_SAFE_BIGINT) throw new Error('Signal frame number exceeds browser precision');
  return Number(value);
}

self.onmessage = (event) => {
  if (event.data?.type !== 'frame' || !(event.data.buffer instanceof ArrayBuffer)) return;
  const buffer = event.data.buffer;
  const connectionEpoch = event.data.connectionEpoch;
  if (!Number.isSafeInteger(connectionEpoch) || connectionEpoch < 0) return;
  try {
    const view = new DataView(buffer);
    if (view.byteLength < VERSION_ONE_HEADER_BYTES || view.getUint8(0) !== 83 || view.getUint8(1) !== 70 ||
        view.getUint8(2) !== 70 || view.getUint8(3) !== 84) throw new Error('Invalid signal frame');
    const version = view.getUint16(4, true);
    const headerBytes = view.getUint16(6, true);
    let viewRevision = null;
    let fftSize;
    let firstBin;
    let bins;
    let encoding;
    let payloadBytes;
    if (version === 1 && headerBytes === VERSION_ONE_HEADER_BYTES) {
      bins = view.getUint32(60, true);
      fftSize = bins;
      firstBin = 0;
      encoding = view.getUint8(64);
      payloadBytes = view.getUint32(76, true);
    } else if (version === 2 && headerBytes === VERSION_TWO_HEADER_BYTES &&
        view.byteLength >= VERSION_TWO_HEADER_BYTES) {
      viewRevision = safeUnsignedLong(view, 60);
      fftSize = view.getUint32(68, true);
      firstBin = view.getUint32(72, true);
      bins = view.getUint32(76, true);
      encoding = view.getUint8(80);
      payloadBytes = view.getUint32(92, true);
    } else {
      throw new Error('Unsupported signal frame version');
    }
    if (encoding !== 1 || bins < 1 || bins > MAXIMUM_BINS || fftSize < bins || firstBin > fftSize - bins ||
        payloadBytes !== bins * 4 || buffer.byteLength !== headerBytes + payloadBytes) {
      throw new Error('Unsupported signal frame');
    }
    self.postMessage({
      type: 'frame',
      buffer,
      connectionEpoch,
      metadata: {
        version,
        headerBytes,
        flags: view.getUint32(8, true),
        generation: safeUnsignedLong(view, 12),
        sequence: safeUnsignedLong(view, 20),
        centerHz: safeUnsignedLong(view, 44),
        sampleRateHz: safeUnsignedLong(view, 52),
        viewRevision,
        fftSize,
        firstBin,
        bins
      }
    }, [buffer]);
  } catch (error) {
    fail(error.message || 'Unable to decode signal frame', connectionEpoch);
  }
};
