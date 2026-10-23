'use strict';

const MAX_BUFFERED_BYTES = 16 * 1024 * 1024;
const ENCODER = new TextEncoder();

// Native state has its own revision. Saved observations use an independent database cursor.
function createP25ActivityFeed(options) {
  let source, nativeRequest, historyRequest, retryTimer = 0, retryDelay = 500;
  let epoch = '', sequence = 0, minimumSequence = 0, seeded = false, closed = false;
  let nativeAttempt = 0, historyAttempt = 0, pendingSnapshot = false, nextSeedOptions = {};
  let historyCursor = 0, historyWatermark = null, liveFloor = 0, liveCursor = 0, recoveringHistory = false;
  let historyGeneration = null, historyMinimumWatermark = 0, nativeRecovery = false;
  let bufferedBytes = 0, appendBytes = 0, resetHistoryPending = false;
  const buffered = new Map(), appends = [];
  const hidden = () => options.isHidden?.() === true;
  const status = (value) => { if (!closed) options.onStatus?.(value); };
  const stopSource = () => { source?.close(); source = null; };
  function clearBuffer() { buffered.clear(); appends.length = 0; bufferedBytes = appendBytes = 0; }
  function cancelNative() { nativeAttempt++; nativeRequest?.abort(); nativeRequest = null; pendingSnapshot = false; }
  function cancelHistory() { historyAttempt++; historyRequest?.abort(); historyRequest = null; historyWatermark = null; appends.length = 0; appendBytes = 0; }
  function cancel() { cancelNative(); cancelHistory(); }
  function retry(error) {
    nativeRecovery = true; recoveringHistory = Boolean(epoch); cancel(); stopSource(); clearBuffer();
    if (closed || hidden() || retryTimer) return;
    if (error) options.onError?.(error);
    status(seeded ? 'retrying' : 'error');
    retryTimer = options.setTimeout(() => { retryTimer = 0; connect(); }, retryDelay);
    retryDelay = Math.min(30_000, retryDelay * 2);
  }
  function discardThrough(through) {
    for (const [revision, batch] of buffered) if (revision <= through) {
      for (const frame of batch.frames.values()) bufferedBytes -= frame.bytes;
      buffered.delete(revision);
    }
  }
  function collect(value, wire) {
    if (value?.source_epoch !== epoch || value.sequence <= sequence) return;
    const { sequence: seq, base_sequence: base, part, parts } = value;
    if (!Number.isSafeInteger(seq) || !Number.isSafeInteger(base) || base < 0 || seq <= base ||
        !Number.isSafeInteger(parts) || parts < 1 || !Number.isInteger(part) || part < 0 || part >= parts ||
        typeof value.available !== 'boolean' || !Array.isArray(value.affiliations) || !Array.isArray(value.removed_radio_keys))
      throw new Error('Invalid P25 affiliation update.');
    const signature = JSON.stringify([base, parts, value.available, value.collection_enabled, value.traffic_grant_age_out_milliseconds]);
    let batch = buffered.get(seq);
    if (!batch) { batch = { base, parts, signature, frames: new Map() }; buffered.set(seq, batch); }
    if (batch.signature !== signature) throw new Error('Invalid P25 affiliation parts.');
    const previous = batch.frames.get(part);
    if (previous) {
      if (JSON.stringify(previous.value) !== JSON.stringify(value)) throw new Error('Conflicting P25 affiliation part.');
      return;
    }
    const bytes = ENCODER.encode(typeof wire === 'string' ? wire : JSON.stringify(value)).byteLength;
    // Recover a slow connection with a complete seed; never discard individual affiliation facts.
    if (bufferedBytes + appendBytes + bytes > MAX_BUFFERED_BYTES) throw new Error('P25 transport recovery required.');
    batch.frames.set(part, { value, bytes }); bufferedBytes += bytes;
  }
  function drain() {
    if (nativeRequest || !seeded || closed || hidden()) return;
    discardThrough(sequence);
    for (;;) {
      const seq = [...buffered.keys()].sort((a, b) => a - b)[0], batch = buffered.get(seq);
      if (!batch || batch.frames.size !== batch.parts) return;
      if (batch.base !== sequence) { void synchronizeNative(); return; }
      const frames = [...batch.frames.entries()].sort((a, b) => a[0] - b[0]).map((entry) => entry[1].value);
      const joined = { ...frames[0], affiliations: frames.flatMap(frame => frame.affiliations),
        removed_radio_keys: frames.flatMap(frame => frame.removed_radio_keys) };
      options.onDelta(joined); sequence = seq; discardThrough(sequence); status('live');
    }
  }
  async function synchronizeNative() {
    if (closed || hidden() || nativeRequest || !epoch) return;
    pendingSnapshot = false;
    const attempt = ++nativeAttempt, request = new AbortController(); nativeRequest = request;
    const fromEpoch = epoch, parameters = nextSeedOptions; nextSeedOptions = {};
    status(seeded ? 'refreshing' : 'loading');
    try {
      const document = await options.loadSeed(request.signal, parameters);
      if (closed || attempt !== nativeAttempt || request.signal.aborted) return;
      if (document?.source_epoch !== fromEpoch || !Number.isSafeInteger(document.sequence) ||
          document.sequence < sequence || typeof document.available !== 'boolean' || !Array.isArray(document.affiliations))
        throw new Error('Invalid complete P25 affiliations.');
      await options.onSeed(document, request.signal);
      if (closed || attempt !== nativeAttempt || request.signal.aborted) return;
      sequence = document.sequence; discardThrough(sequence); seeded = true; nativeRecovery = false; retryDelay = 500;
      if (!pendingSnapshot && sequence >= minimumSequence) status('live');
    } catch (error) {
      if (error?.name !== 'AbortError' && !closed && attempt === nativeAttempt) retry(error);
    } finally {
      if (attempt === nativeAttempt) {
        nativeRequest = null;
        if (pendingSnapshot || sequence < minimumSequence) void synchronizeNative(); else drain();
      }
    }
  }
  function queueAppend(value) {
    const bytes = ENCODER.encode(JSON.stringify(value)).byteLength;
    if (bufferedBytes + appendBytes + bytes > MAX_BUFFERED_BYTES) throw new Error('P25 transport recovery required.');
    appends.push(value); appendBytes += bytes;
  }
  function flushAppends() {
    const waiting = appends.splice(0); appendBytes = 0;
    waiting.forEach(append);
  }
  function recoverHistory() {
    recoveringHistory = true; cancelHistory(); void synchronizeHistory();
  }
  function acceptHistoryGeneration(value) {
    if (!Number.isSafeInteger(value.history_generation) || value.history_generation < 0)
      throw new Error('Invalid saved P25 activity generation.');
    if (historyGeneration !== null && value.history_generation < historyGeneration) return false;
    if (value.history_generation !== historyGeneration) {
      historyGeneration = value.history_generation; historyMinimumWatermark = 0;
      cancelHistory(); resetHistoryPending = true; void synchronizeHistory();
    }
    return true;
  }
  function append(value) {
    if (value?.source_epoch !== epoch) return;
    if (!Array.isArray(value.rows) || !Number.isSafeInteger(value.after_id) || value.after_id < 0 ||
        !Number.isSafeInteger(value.next_after_id) || value.next_after_id < value.after_id ||
        !Number.isSafeInteger(value.watermark_id) || value.next_after_id > value.watermark_id ||
        value.rows.some(row => !Number.isSafeInteger(row?.id) || row.id <= value.after_id || row.id > value.next_after_id))
      throw new Error('Invalid saved P25 activity update.');
    if (!acceptHistoryGeneration(value)) return;
    if (historyWatermark === null || recoveringHistory) { queueAppend(value); return; }
    if (value.next_after_id <= liveCursor) return;
    if (value.after_id > liveCursor) { recoverHistory(); queueAppend(value); return; }
    const rows = value.rows.filter(row => row.id > liveCursor);
    if (rows.length) options.onAppend(rows);
    liveCursor = value.next_after_id;
    if (!historyRequest) historyCursor = liveFloor = liveCursor;
  }
  async function synchronizeHistory() {
    if (closed || hidden() || !epoch || historyRequest) return;
    if (resetHistoryPending) {
      resetHistoryPending = false; historyCursor = liveFloor = liveCursor = 0; recoveringHistory = false; options.onHistoryReset?.();
    }
    const attempt = ++historyAttempt, request = new AbortController(); historyRequest = request;
    const fromEpoch = epoch, parameters = options.historyParameters();
    let after = historyCursor, watermark = null;
    options.onHistoryStatus?.(true);
    try {
      do {
        const page = await options.loadHistory({ ...parameters, after_id: after,
          ...(watermark === null ? {} : { watermark_id: watermark }) }, request.signal);
        if (closed || attempt !== historyAttempt || request.signal.aborted) return;
        if (page?.reset_required) { cancelHistory(); resetHistoryPending = true; void synchronizeHistory(); return; }
        if (!Array.isArray(page?.rows) || !Number.isSafeInteger(page.watermark_id) ||
            !Number.isSafeInteger(page.next_after_id) || page.next_after_id < after ||
            page.next_after_id > page.watermark_id || typeof page.has_more !== 'boolean' ||
            watermark !== null && watermark !== page.watermark_id || page.has_more && page.next_after_id <= after)
          throw new Error('Invalid saved P25 activity page.');
        watermark = page.watermark_id;
        await options.onHistory(page.rows.filter(row => !(row.id > liveFloor && row.id <= liveCursor)), request.signal);
        if (closed || attempt !== historyAttempt || request.signal.aborted || fromEpoch !== epoch) return;
        after = page.next_after_id; historyCursor = after;
        if (historyWatermark === null) {
          historyWatermark = watermark;
          if (!recoveringHistory) { liveFloor = watermark; liveCursor = Math.max(liveCursor, watermark); flushAppends(); }
          if (attempt !== historyAttempt || request.signal.aborted) return;
        }
        if (!page.has_more) break;
      } while (!request.signal.aborted);
      historyCursor = Math.max(historyCursor, watermark, recoveringHistory ? 0 : liveCursor);
      if (historyCursor < historyMinimumWatermark) { recoverHistory(); return; }
      if (recoveringHistory) {
        liveFloor = liveCursor = watermark; recoveringHistory = false; historyRequest = null; flushAppends();
      } else liveFloor = liveCursor;
    } catch (error) {
      if (error?.name !== 'AbortError' && !closed && attempt === historyAttempt) retry(error);
    } finally {
      if (attempt === historyAttempt) { historyRequest = null; options.onHistoryStatus?.(false); }
    }
  }
  function boundary(value) {
    if (closed || hidden() || typeof value?.source_epoch !== 'string' || !Number.isSafeInteger(value.sequence) || value.sequence < 0 ||
        !Number.isSafeInteger(value.watermark_id) || value.watermark_id < 0 ||
        !Number.isSafeInteger(value.history_generation) || value.history_generation < 0) return;
    const changed = epoch !== value.source_epoch;
    if (changed) {
      cancel(); clearBuffer(); sequence = minimumSequence = historyCursor = 0;
      liveFloor = liveCursor = value.watermark_id; historyMinimumWatermark = value.watermark_id; historyGeneration = value.history_generation; recoveringHistory = false; seeded = false;
    }
    epoch = value.source_epoch;
    if (!changed && !acceptHistoryGeneration(value)) return;
    options.onBoundary?.(value);
    minimumSequence = Math.max(minimumSequence, value.sequence);
    if (changed || !seeded || nativeRecovery || value.sequence > sequence) void synchronizeNative();
    if (changed || historyWatermark === null) void synchronizeHistory();
  }
  function connect() {
    if (closed || hidden() || source) return;
    status(seeded ? 'reconnecting' : 'connecting');
    source = options.openStream({ boundary,
      delta(value, wire) { if (!closed && !hidden()) try { collect(value, wire); drain(); } catch (error) { retry(error); } },
      append(value) { if (!closed && !hidden()) try { append(value); } catch (error) { retry(error); } },
      historyReset(value) {
        if (closed || hidden() || value?.source_epoch !== epoch ||
            !Number.isSafeInteger(value.watermark_id) || value.watermark_id < 0 ||
            !Number.isSafeInteger(value.history_generation) || value.history_generation < historyGeneration) return;
        if (!acceptHistoryGeneration(value)) return;
        historyMinimumWatermark = Math.max(historyMinimumWatermark, value.watermark_id);
        if (!historyRequest && historyCursor < historyMinimumWatermark) recoverHistory();
      },
      resync(value) {
        if (closed || hidden() || value?.source_epoch && value.source_epoch !== epoch) return;
        const previousGeneration = historyGeneration;
        if (value?.history_generation !== undefined && !acceptHistoryGeneration(value)) return;
        if (Number.isSafeInteger(value?.sequence) && value.sequence >= 0) minimumSequence = Math.max(minimumSequence, value.sequence);
        else if (nativeRequest) pendingSnapshot = true;
        // A transport gap can lose either stream; the saved cursor fills only the missing observations.
        if (previousGeneration === historyGeneration) recoverHistory();
        nativeRecovery = true; void synchronizeNative();
      },
      error() { if (!closed) { nativeRecovery = true; recoveringHistory = true; cancel(); clearBuffer(); status(seeded ? 'reconnecting' : 'connecting'); } }
    });
  }
  const feed = {
    reseed(parameters = {}) {
      if (closed) return;
      nextSeedOptions = parameters; cancel(); clearBuffer();
      if (retryTimer) options.clearTimeout(retryTimer); retryTimer = 0;
      resetHistoryPending = true;
      if (source && !hidden()) { void synchronizeNative(); void synchronizeHistory(); } else connect();
    },
    reloadHistory() { cancelHistory(); resetHistoryPending = true; void synchronizeHistory(); },
    visibilityChanged() {
      if (closed) return;
      if (hidden()) { nativeRecovery = true; recoveringHistory = true; cancel(); stopSource(); clearBuffer(); status('paused'); } else connect();
    },
    close() {
      closed = true; cancel(); stopSource(); clearBuffer();
      if (retryTimer) options.clearTimeout(retryTimer); retryTimer = 0;
    }
  };
  connect(); return Object.freeze(feed);
}

export { createP25ActivityFeed };
