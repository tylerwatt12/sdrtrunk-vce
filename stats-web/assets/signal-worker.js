/*
 * Decodes and validates SFFT v1 frames away from the UI/render loop.  The ArrayBuffer is transferred in both
 * directions, so browser-side parsing does not copy every FFT payload.
 */
'use strict';

const HEADER_BYTES = 80;
const MAXIMUM_BINS = 65536;

function fail(message) {
  self.postMessage({ type: 'error', message });
}

self.onmessage = (event) => {
  if (event.data?.type !== 'frame' || !(event.data.buffer instanceof ArrayBuffer)) return;
  const buffer = event.data.buffer;
  try {
    const view = new DataView(buffer);
    if (view.byteLength < HEADER_BYTES || view.getUint8(0) !== 83 || view.getUint8(1) !== 70 ||
        view.getUint8(2) !== 70 || view.getUint8(3) !== 84) throw new Error('Invalid signal frame');
    const version = view.getUint16(4, true);
    const headerBytes = view.getUint16(6, true);
    const bins = view.getUint32(60, true);
    const encoding = view.getUint8(64);
    const payloadBytes = view.getUint32(76, true);
    if (version !== 1 || headerBytes !== HEADER_BYTES || encoding !== 1 || bins < 1 ||
        bins > MAXIMUM_BINS || payloadBytes !== bins * 4 || buffer.byteLength !== HEADER_BYTES + payloadBytes) {
      throw new Error('Unsupported signal frame');
    }
    self.postMessage({
      type: 'frame',
      buffer,
      metadata: {
        flags: view.getUint32(8, true),
        generation: Number(view.getBigUint64(12, true)),
        sequence: Number(view.getBigUint64(20, true)),
        centerHz: Number(view.getBigUint64(44, true)),
        sampleRateHz: Number(view.getBigUint64(52, true)),
        bins
      }
    }, [buffer]);
  } catch (error) {
    fail(error.message || 'Unable to decode signal frame');
  }
};
