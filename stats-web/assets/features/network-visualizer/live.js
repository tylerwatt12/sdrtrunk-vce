'use strict';

const MAX_BUFFERED_BYTES = 4 * 1024 * 1024;
const ENCODER = new TextEncoder();

// Observe from the stream's current boundary. Reconnecting starts a new live edge;
// it never turns missed history or a full affiliation snapshot into fresh motion.
function createP25ActivityFeed(options) {
  let source = null, retryTimer = 0, closed = false, epoch = '', sequence = 0, cursor = 0;
  let generation = 0, bufferedBytes = 0, retryDelay = 500;
  const buffered = new Map();
  const hidden = () => options.isHidden?.() === true;
  const status = value => { if (!closed) options.onStatus?.(value); };
  const clearBuffer = () => { buffered.clear(); bufferedBytes = 0; };
  const stopSource = () => { generation++; source?.close(); source = null; epoch = ''; clearBuffer(); };
  function reconnect(reason) {
    if (closed) return;
    stopSource(); options.onReset?.(reason); status(hidden() ? 'paused' : 'reconnecting');
    if (hidden() || retryTimer) return;
    retryTimer = options.setTimeout(() => { retryTimer = 0; connect(); }, retryDelay);
    retryDelay = Math.min(10_000, retryDelay * 2);
  }
  function boundary(value) {
    if (typeof value?.source_epoch !== 'string' || !Number.isSafeInteger(value.sequence) || value.sequence < 0 ||
        !Number.isSafeInteger(value.watermark_id) || value.watermark_id < 0) throw new Error('Invalid P25 live boundary.');
    // A boundary is a present-tense baseline, never an instruction to replay old data.
    if (epoch !== value.source_epoch) {
      epoch = value.source_epoch; sequence = value.sequence; cursor = value.watermark_id; clearBuffer();
    }
    retryDelay = 500; options.onBoundary?.(value); status('live');
  }
  function delta(value, wire) {
    if (!epoch || value?.source_epoch !== epoch || value.sequence <= sequence) return;
    const { sequence: seq, base_sequence: base, part, parts } = value;
    if (!Number.isSafeInteger(seq) || !Number.isSafeInteger(base) || base < 0 || seq <= base ||
        !Number.isSafeInteger(parts) || parts < 1 || !Number.isInteger(part) || part < 0 || part >= parts ||
        typeof value.available !== 'boolean' || !Array.isArray(value.affiliations) || !Array.isArray(value.removed_radio_keys))
      throw new Error('Invalid P25 affiliation update.');
    if (base !== sequence && !buffered.has(base)) throw new Error('P25 affiliation gap.');
    let batch = buffered.get(seq);
    const signature = JSON.stringify([value.available, value.collection_enabled, value.traffic_grant_age_out_milliseconds]);
    if (!batch) { batch = { base, parts, signature, frames: new Map() }; buffered.set(seq, batch); }
    if (batch.base !== base || batch.parts !== parts || batch.signature !== signature) throw new Error('Conflicting P25 affiliation parts.');
    if (batch.frames.has(part)) {
      if (JSON.stringify(batch.frames.get(part).value) !== JSON.stringify(value)) throw new Error('Conflicting P25 affiliation part.');
      return;
    }
    const bytes = ENCODER.encode(typeof wire === 'string' ? wire : JSON.stringify(value)).byteLength;
    if (bufferedBytes + bytes > MAX_BUFFERED_BYTES) throw new Error('P25 live update is too large.');
    batch.frames.set(part, { value, bytes }); bufferedBytes += bytes;
    for (;;) {
      const next = [...buffered.entries()].find(([, item]) => item.base === sequence);
      if (!next || next[1].frames.size !== next[1].parts) return;
      const [revision, item] = next;
      const frames = [...item.frames].sort((a, b) => a[0] - b[0]).map(([, frame]) => frame.value);
      options.onDelta?.({ ...frames[0], affiliations: frames.flatMap(frame => frame.affiliations),
        removed_radio_keys: frames.flatMap(frame => frame.removed_radio_keys) });
      sequence = revision;
      for (const frame of item.frames.values()) bufferedBytes -= frame.bytes;
      buffered.delete(revision);
    }
  }
  function append(value) {
    if (!epoch || value?.source_epoch !== epoch) return;
    if (!Array.isArray(value.rows) || !Number.isSafeInteger(value.after_id) || value.after_id < 0 ||
        !Number.isSafeInteger(value.next_after_id) || value.next_after_id < value.after_id ||
        !Number.isSafeInteger(value.watermark_id) || value.next_after_id > value.watermark_id ||
        value.rows.some(row => !Number.isSafeInteger(row?.id) || row.id <= value.after_id || row.id > value.next_after_id))
      throw new Error('Invalid P25 activity update.');
    if (value.next_after_id <= cursor) return;
    if (value.after_id > cursor) throw new Error('P25 activity gap.');
    const rows = value.rows.filter(row => row.id > cursor);
    if (rows.length) options.onAppend?.(rows);
    cursor = value.next_after_id;
  }
  function connect() {
    if (closed || hidden() || source) return;
    status('connecting');
    const attempt = ++generation;
    const accept = callback => (...args) => {
      if (closed || hidden() || attempt !== generation) return;
      try { callback(...args); } catch (error) { options.onError?.(error); reconnect('gap'); }
    };
    source = options.openStream({ boundary: accept(boundary), delta: accept(delta), append: accept(append),
      historyReset: accept(() => reconnect('activity_reset')), resync: accept(() => reconnect('gap')),
      error: accept(() => reconnect('connection')) });
  }
  const feed = Object.freeze({
    // Refresh reopens at the present; it does not preload past observations.
    reseed() { if (!closed) { if (retryTimer) options.clearTimeout(retryTimer); retryTimer = 0;
      stopSource(); options.onReset?.('refresh'); connect(); } },
    visibilityChanged() {
      if (closed) return;
      if (retryTimer) options.clearTimeout(retryTimer); retryTimer = 0;
      stopSource(); options.onReset?.('visibility');
      if (hidden()) status('paused'); else connect();
    },
    close() { if (closed) return; closed = true; stopSource();
      if (retryTimer) options.clearTimeout(retryTimer); retryTimer = 0; }
  });
  connect(); return feed;
}

export { createP25ActivityFeed };
