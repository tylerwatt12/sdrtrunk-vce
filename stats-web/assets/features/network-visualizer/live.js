'use strict';

const MAX_BUFFERED_CHANGES = 25_000;
const MAX_BUFFERED_BYTES = 16 * 1024 * 1024;
const ENCODER = new TextEncoder();

// Subscribe first, then replace from one shared complete snapshot. Gaps use that same snapshot.
function createP25ActivityFeed(options) {
  let source, controller, retryTimer = 0, attempt = 0, retryDelay = 500;
  let epoch = '', sequence = 0, minimumSequence = 0, seeded = false, syncing = false, closed = false;
  let nextSeedOptions = {}, bufferedBytes = 0, bufferedChanges = 0, pendingSnapshot = false;
  const buffered = new Map();
  const hidden = () => options.isHidden?.() === true;
  const status = (value) => { if (!closed) options.onStatus?.(value); };
  const stopSource = () => { source?.close(); source = null; };
  function clearBuffer() { buffered.clear(); bufferedBytes = bufferedChanges = 0; }
  function cancel() { attempt++; controller?.abort(); controller = null; syncing = false; pendingSnapshot = false; }
  function retry() {
    cancel(); stopSource(); clearBuffer();
    if (closed || hidden() || retryTimer) return;
    status(seeded ? 'retrying' : 'error');
    retryTimer = options.setTimeout(() => { retryTimer = 0; connect(); }, retryDelay);
    retryDelay = Math.min(30_000, retryDelay * 2);
  }
  function discardThrough(through) {
    for (const [sequence, batch] of buffered) if (sequence <= through) {
      for (const frame of batch.frames.values()) { bufferedBytes -= frame.bytes; bufferedChanges -= frame.changes; }
      buffered.delete(sequence);
    }
  }
  function collect(value, wire) {
    if (value?.source_epoch !== epoch || value.sequence <= sequence) return;
    const { sequence: seq, base_sequence: base, part, parts } = value;
    if (!Number.isSafeInteger(seq) || !Number.isSafeInteger(base) || base < 0 || seq <= base ||
        !Number.isInteger(parts) || parts < 1 || parts > 256 || !Number.isInteger(part) || part < 0 || part >= parts ||
        typeof value.available !== 'boolean' || typeof value.affiliations_truncated !== 'boolean' || !Array.isArray(value.rows) || !Array.isArray(value.removed_ids) ||
        !Array.isArray(value.affiliations) || !Array.isArray(value.removed_radio_keys)) throw new Error('Invalid P25 state update.');
    const changes = value.rows.length + value.removed_ids.length + value.affiliations.length + value.removed_radio_keys.length;
    if (changes > 100) throw new Error('P25 state part exceeds its bounds.');
    const signature = JSON.stringify([base, parts, value.available, value.affiliations_truncated, value.collection_enabled, value.traffic_grant_age_out_milliseconds]);
    let batch = buffered.get(seq);
    if (!batch) { batch = { base, parts, signature, frames: new Map() }; buffered.set(seq, batch); }
    if (batch.signature !== signature) throw new Error('Invalid P25 state parts.');
    const previous = batch.frames.get(part);
    if (previous) {
      if (JSON.stringify(previous.value) !== JSON.stringify(value)) throw new Error('Conflicting P25 state part.');
      return;
    }
    const bytes = ENCODER.encode(typeof wire === 'string' ? wire : JSON.stringify(value)).byteLength;
    if (bytes > 256 * 1024 || bufferedBytes + bytes > MAX_BUFFERED_BYTES ||
        bufferedChanges + changes > MAX_BUFFERED_CHANGES || buffered.size > 64) throw new Error('P25 state buffer is full.');
    batch.frames.set(part, { value, bytes, changes }); bufferedBytes += bytes; bufferedChanges += changes;
  }

  function drain() {
    if (syncing || !seeded || closed || hidden()) return;
    discardThrough(sequence);
    for (;;) {
      const seq = [...buffered.keys()].sort((a, b) => a - b)[0], batch = buffered.get(seq);
      if (!batch || batch.frames.size !== batch.parts) return;
      if (batch.base !== sequence) { void synchronize(); return; }
      const frames = [...batch.frames.entries()].sort((a, b) => a[0] - b[0]).map((entry) => entry[1].value);
      const joined = { ...frames[0] };
      for (const key of ['rows', 'removed_ids', 'affiliations', 'removed_radio_keys']) joined[key] = frames.flatMap((frame) => frame[key]);
      options.onDelta(joined);
      sequence = seq; discardThrough(sequence); status('live');
    }
  }
  async function synchronize() {
    if (closed || hidden() || syncing || !epoch) return;
    syncing = true; pendingSnapshot = false;
    const localAttempt = ++attempt, request = new AbortController(); controller = request;
    const fromEpoch = epoch, requiredSequence = minimumSequence, parameters = nextSeedOptions; nextSeedOptions = {};
    status(seeded ? 'refreshing' : 'loading');
    try {
      const document = await options.loadSeed(request.signal, parameters);
      if (closed || localAttempt !== attempt || request.signal.aborted) return;
      if (document?.source_epoch !== fromEpoch || !Number.isSafeInteger(document.sequence) ||
          document.sequence < Math.max(sequence, requiredSequence) || typeof document.available !== 'boolean' ||
          typeof document.affiliations_truncated !== 'boolean' ||
          !Array.isArray(document.rows) || document.rows.length > 500 ||
          !Array.isArray(document.affiliations) || document.affiliations.length > 10_000) throw new Error('Invalid complete P25 state.');
      await options.onSeed(document, request.signal);
      if (closed || localAttempt !== attempt || request.signal.aborted) return;
      sequence = document.sequence; discardThrough(sequence); seeded = true; retryDelay = 500;
      if (!pendingSnapshot && sequence >= minimumSequence) status('live');
    } catch (error) {
      if (error?.name !== 'AbortError' && !closed && localAttempt === attempt) { options.onError?.(error); retry(); }
    } finally {
      if (localAttempt === attempt) {
        syncing = false; controller = null;
        if (pendingSnapshot || sequence < minimumSequence) void synchronize(); else drain();
      }
    }
  }
  function boundary(value) {
    if (closed || hidden() || typeof value?.source_epoch !== 'string' ||
        !Number.isSafeInteger(value.sequence) || value.sequence < 0) return;
    options.onBoundary?.(value);
    if (epoch !== value.source_epoch) { cancel(); clearBuffer(); sequence = minimumSequence = 0; seeded = false; }
    epoch = value.source_epoch; minimumSequence = Math.max(minimumSequence, value.sequence);
    void synchronize();
  }
  function connect() {
    if (closed || hidden() || source) return;
    status(seeded ? 'reconnecting' : 'connecting');
    source = options.openStream({ boundary,
      delta(value, wire) { if (!closed && !hidden()) { try { collect(value, wire); drain(); } catch (_) { retry(); } } },
      resync(value) {
        if (closed || hidden() || value?.source_epoch && value.source_epoch !== epoch) return;
        if (Number.isSafeInteger(value?.sequence) && value.sequence >= 0) minimumSequence = Math.max(minimumSequence, value.sequence);
        else if (syncing) pendingSnapshot = true;
        void synchronize();
      },
      error() { if (!closed) { cancel(); clearBuffer(); status(seeded ? 'reconnecting' : 'connecting'); } }
    });
  }
  const feed = {
    reseed(parameters = {}) {
      if (closed) return;
      nextSeedOptions = parameters; cancel(); clearBuffer();
      if (retryTimer) options.clearTimeout(retryTimer); retryTimer = 0;
      if (source && !hidden()) void synchronize(); else connect();
    },
    visibilityChanged() {
      if (closed) return;
      if (hidden()) { cancel(); stopSource(); clearBuffer(); status('paused'); } else connect();
    },
    close() {
      closed = true; cancel(); stopSource(); clearBuffer();
      if (retryTimer) options.clearTimeout(retryTimer); retryTimer = 0;
    }
  };
  connect();
  return Object.freeze(feed);
}

export { createP25ActivityFeed };
