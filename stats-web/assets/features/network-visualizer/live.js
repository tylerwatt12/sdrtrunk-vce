'use strict';

const MAX_BUFFERED_ROWS = 250_000;
const MAX_BUFFERED_FRAMES = 2_048;

// One page-scoped subscription, with a bounded saved-history seed and journal catchup.
// The stream starts first. Catchup reads fresh row values after the seed has completed.
function createP25ActivityFeed(options) {
  let closed = false;
  let source = null;
  let controller = null;
  let attempt = 0;
  let epoch = '';
  let sequence = 0;
  let seeded = false;
  let syncing = false;
  let needsSeed = true;
  let retryTimer = 0;
  let retryDelay = 500;
  let bufferedRows = 0;
  const buffered = new Map();
  const hidden = () => options.isHidden?.() === true;
  const status = (value) => { if (!closed) options.onStatus?.(value); };

  function clearBuffer() { buffered.clear(); bufferedRows = 0; }
  function stopSource() { const previous = source; source = null; previous?.close(); }
  function cancel() { attempt++; controller?.abort(); controller = null; syncing = false; }
  function scheduleRetry(reseed = false) {
    needsSeed ||= reseed;
    cancel();
    stopSource();
    clearBuffer();
    if (closed || hidden() || retryTimer) return;
    status(seeded ? 'retrying' : 'error');
    retryTimer = options.setTimeout(() => {
      retryTimer = 0;
      connect();
    }, retryDelay);
    retryDelay = Math.min(30_000, retryDelay * 2);
  }

  function assemble(value) {
    const seq = Number(value?.sequence), base = Number(value?.base_sequence);
    const part = value?.part ?? 0, parts = value?.parts ?? 1;
    if (value?.source_epoch !== epoch || !Number.isSafeInteger(seq) || seq <= sequence) return null;
    if (!Number.isSafeInteger(base) || base < 0 || seq <= base ||
        !Number.isInteger(parts) || parts < 1 || parts > 1_024 ||
        !Number.isInteger(part) || part < 0 || part >= parts ||
        !Array.isArray(value.rows) || !Array.isArray(value.removed_ids)) throw new Error('Invalid live P25 activity.');
    let batch = buffered.get(seq);
    if (!batch) {
      batch = { sequence: seq, base_sequence: base, parts, frames: new Map() };
      buffered.set(seq, batch);
    }
    if (batch.parts !== parts || batch.base_sequence !== base) throw new Error('Invalid P25 activity parts.');
    if (!batch.frames.has(part)) {
      batch.frames.set(part, value);
      bufferedRows += value.rows.length + value.removed_ids.length;
    }
    if (bufferedRows > MAX_BUFFERED_ROWS || buffered.size > MAX_BUFFERED_FRAMES) {
      throw new Error('P25 activity buffer is full.');
    }
    return batch;
  }

  function discardThrough(through) {
    for (const [seq, batch] of buffered) {
      if (seq > through) continue;
      for (const value of batch.frames.values()) bufferedRows -= value.rows.length + value.removed_ids.length;
      buffered.delete(seq);
    }
  }

  function drain() {
    if (syncing || !seeded || closed || hidden()) return;
    discardThrough(sequence);
    for (;;) {
      const batch = [...buffered.values()].sort((a, b) => a.sequence - b.sequence)[0];
      if (!batch || batch.frames.size !== batch.parts) break;
      if (batch.base_sequence !== sequence) { void synchronize(false); return; }
      const frames = [...batch.frames.entries()].sort((a, b) => a[0] - b[0]).map((entry) => entry[1]);
      options.onDelta(frames.flatMap((frame) => frame.rows), frames.flatMap((frame) => frame.removed_ids));
      sequence = batch.sequence;
      discardThrough(sequence);
      status('live');
    }
  }

  async function synchronize(reseed) {
    if (closed || hidden() || syncing) return;
    syncing = true;
    const localAttempt = ++attempt;
    const signalController = new AbortController();
    controller = signalController;
    const signal = signalController.signal;
    const fromSequence = sequence;
    const fromEpoch = epoch;
    const seed = reseed || needsSeed || !seeded;
    status(seed ? (seeded ? 'refreshing' : 'loading') : 'reconnecting');
    try {
      const rows = seed ? await options.loadSeed(signal) : null;
      let afterId = 0, throughSequence = null;
      const changes = [], removed = [];
      do {
        const page = await options.requestCatchup({ source_epoch: fromEpoch,
          after_sequence: fromSequence, ...(throughSequence === null ? {} : { through_sequence: throughSequence }),
          after_id: afterId, limit: 500 }, { signal });
        if (closed || localAttempt !== attempt) return;
        if (page?.resync_required || page?.source_epoch !== fromEpoch) {
          needsSeed = true;
          scheduleRetry(true);
          return;
        }
        if (!Array.isArray(page?.rows) || !Array.isArray(page?.removed_ids) ||
            typeof page.has_more !== 'boolean' || !Number.isSafeInteger(page.through_sequence) ||
            page.through_sequence < fromSequence ||
            throughSequence !== null && page.through_sequence !== throughSequence) {
          throw new Error('Invalid P25 activity recovery.');
        }
        throughSequence = page.through_sequence;
        changes.push(...page.rows); removed.push(...page.removed_ids);
        if (changes.length + removed.length > MAX_BUFFERED_ROWS) throw new Error('P25 activity recovery is full.');
        const nextId = Number(page.next_after_id);
        if (!Number.isSafeInteger(nextId) || nextId < afterId || page.has_more && nextId === afterId) {
          throw new Error('P25 activity recovery did not advance.');
        }
        afterId = nextId;
        if (!page.has_more) break;
      } while (!signal.aborted);
      if (closed || localAttempt !== attempt || signal.aborted) return;
      if (seed) await options.onSeed(rows, changes, removed, signal);
      else options.onDelta(changes, removed);
      if (closed || localAttempt !== attempt || signal.aborted) return;
      sequence = throughSequence;
      seeded = true; needsSeed = false; retryDelay = 500;
      discardThrough(sequence);
      status('live');
    } catch (error) {
      if (error?.name === 'AbortError' || closed || localAttempt !== attempt) return;
      options.onError?.(error);
      scheduleRetry(seed);
    } finally {
      if (localAttempt === attempt) { syncing = false; controller = null; drain(); }
    }
  }

  function boundary(value) {
    if (closed || hidden() || !value || typeof value.source_epoch !== 'string' ||
        !Number.isSafeInteger(value.sequence) || value.sequence < 0) return;
    options.onBoundary?.(value);
    const changed = epoch !== value.source_epoch;
    if (changed || needsSeed) { cancel(); clearBuffer(); epoch = value.source_epoch; sequence = value.sequence; needsSeed = true; }
    if (!syncing) void synchronize(changed || needsSeed);
  }

  function connect() {
    if (closed || hidden() || source) return;
    status(seeded ? 'reconnecting' : 'connecting');
    source = options.openStream({
      boundary,
      delta(value) {
        if (closed || hidden()) return;
        if (value?.source_epoch !== epoch) return;
        try { assemble(value); drain(); } catch (_) { scheduleRetry(false); }
      },
      resync(value) {
        if (value?.reason === 'transport_gap') {
          if (!syncing) void synchronize(false);
        } else scheduleRetry(true);
      },
      error() { if (!closed) { cancel(); clearBuffer(); status(seeded ? 'reconnecting' : 'connecting'); } }
    });
  }

  function visibilityChanged() {
    if (closed) return;
    if (hidden()) { cancel(); stopSource(); clearBuffer(); status('paused'); }
    else connect();
  }

  const feed = {
    reseed() {
      if (closed) return;
      needsSeed = true;
      cancel(); clearBuffer();
      if (retryTimer) options.clearTimeout(retryTimer);
      retryTimer = 0;
      if (source && epoch && !hidden()) void synchronize(true);
      else connect();
    },
    visibilityChanged,
    close() {
      if (closed) return;
      closed = true; cancel(); stopSource(); clearBuffer();
      if (retryTimer) options.clearTimeout(retryTimer);
      retryTimer = 0;
    }
  };
  connect();
  return Object.freeze(feed);
}

export { createP25ActivityFeed };
