'use strict';

const MAX_BUFFERED_ROWS = 65_536;
const MAX_SEED_ROWS = 500;
const MAX_AFFILIATIONS = 10_000;
const MAX_BUFFERED_FRAMES = 2_048;

// One page-scoped subscription, with a shared recent seed and bounded journal catchup.
// The stream starts first. Catchup reads fresh row values after the seed has completed.
function createP25ActivityFeed(options) {
  let closed = false;
  let source = null;
  let controller = null;
  let attempt = 0;
  let epoch = '';
  let sequence = 0;
  let affiliationRevision = 0;
  let affiliationsComplete = false;
  let affiliationController = null;
  let seeded = false;
  let syncing = false;
  let needsSeed = true;
  let retryTimer = 0;
  let retryDelay = 500;
  let nextSeedOptions = {};
  let bufferedRows = 0;
  const buffered = new Map();
  const affiliationBuffer = new Map();
  const hidden = () => options.isHidden?.() === true;
  const status = (value) => { if (!closed) options.onStatus?.(value); };

  function clearBuffer() { buffered.clear(); affiliationBuffer.clear(); bufferedRows = 0; }
  function stopSource() { const previous = source; source = null; previous?.close(); }
  function cancel() { attempt++; controller?.abort(); controller = null; syncing = false;
    affiliationController?.abort(); affiliationController = null; }
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

  function affiliationSnapshot(value, sourceEpoch) {
    if (!Number.isSafeInteger(value?.affiliation_revision) || value.affiliation_revision < 0 ||
        typeof value.affiliations_available !== 'boolean' || typeof value.affiliations_complete !== 'boolean' || !Array.isArray(value.affiliations) ||
        value.affiliations.length > MAX_AFFILIATIONS || !value.affiliations_complete &&
        (value.affiliations_available || value.affiliations.length)) throw new Error('Invalid current P25 affiliations.');
    return { source_epoch: sourceEpoch, revision: value.affiliation_revision,
      available: value.affiliations_available, complete: value.affiliations_complete, rows: value.affiliations };
  }

  function discardAffiliationsThrough(through) {
    for (const revision of affiliationBuffer.keys()) if (revision <= through) affiliationBuffer.delete(revision);
  }

  function collectAffiliations(value) {
    const revision = value?.affiliation_revision, base = value?.base_affiliation_revision;
    if (value?.source_epoch !== epoch || revision <= affiliationRevision) return;
    const part = value.part ?? 0, parts = value.parts ?? 1;
    if (!Number.isSafeInteger(revision) || !Number.isSafeInteger(base) || base < 0 || revision <= base ||
        typeof value.affiliations_available !== 'boolean' || !Array.isArray(value.upserts) || !Array.isArray(value.removed_radio_keys) ||
        !Number.isInteger(part) || !Number.isInteger(parts) || parts < 1 || parts > 1_024 || part < 0 || part >= parts) {
      throw new Error('Invalid current P25 affiliation update.');
    }
    let batch = affiliationBuffer.get(revision);
    if (!batch) { batch = { base, parts, available: value.affiliations_available, frames: new Map() }; affiliationBuffer.set(revision, batch); }
    if (batch.base !== base || batch.parts !== parts || batch.available !== value.affiliations_available) {
      throw new Error('Invalid P25 affiliation parts.');
    }
    batch.frames.set(part, value);
    let count = 0;
    for (const item of affiliationBuffer.values()) for (const frame of item.frames.values()) count += frame.upserts.length + frame.removed_radio_keys.length;
    if (count > MAX_BUFFERED_ROWS || affiliationBuffer.size > MAX_BUFFERED_FRAMES) throw new Error('P25 affiliation buffer is full.');
  }

  function drainAffiliations() {
    if (!seeded || needsSeed || closed || hidden()) return;
    discardAffiliationsThrough(affiliationRevision);
    if (!affiliationsComplete && affiliationBuffer.size) { void refreshAffiliations(); return; }
    for (;;) {
      const revision = [...affiliationBuffer.keys()].sort((a, b) => a - b)[0];
      const batch = affiliationBuffer.get(revision);
      if (!batch || batch.frames.size !== batch.parts) return;
      if (batch.base !== affiliationRevision) { void refreshAffiliations(); return; }
      const frames = [...batch.frames.entries()].sort((a, b) => a[0] - b[0]).map((entry) => entry[1]);
      options.onAffiliations(frames.flatMap((frame) => frame.upserts), {
        removedRadioKeys: frames.flatMap((frame) => frame.removed_radio_keys), available: batch.available,
        revision, source_epoch: epoch });
      affiliationRevision = revision;
      discardAffiliationsThrough(revision);
      status('live');
    }
  }

  async function refreshAffiliations() {
    if (closed || hidden() || affiliationController) return;
    const request = new AbortController(), fromEpoch = epoch;
    affiliationController = request;
    try {
      const document = await options.loadSeed(request.signal, {});
      if (request.signal.aborted || closed || epoch !== fromEpoch) return;
      if (document?.source_epoch !== fromEpoch) { scheduleRetry(true); return; }
      const snapshot = affiliationSnapshot(document, fromEpoch);
      if (snapshot.revision >= affiliationRevision) {
        options.onAffiliations(snapshot.rows, { replace: true, initial: true, available: snapshot.available, complete: snapshot.complete,
          revision: snapshot.revision, source_epoch: epoch });
        affiliationRevision = snapshot.revision; affiliationsComplete = snapshot.complete;
        discardAffiliationsThrough(affiliationRevision);
        const next = [...affiliationBuffer.values()][0];
        if (affiliationsComplete && next && next.base > affiliationRevision) { scheduleRetry(false); return; }
        status('live');
      }
    } catch (error) {
      if (error?.name !== 'AbortError' && !request.signal.aborted && !closed) scheduleRetry(false);
    } finally {
      if (affiliationController === request) { affiliationController = null; if (affiliationsComplete) drainAffiliations(); }
    }
  }

  async function synchronize(reseed) {
    if (closed || hidden() || syncing) return;
    syncing = true;
    const localAttempt = ++attempt;
    const signalController = new AbortController();
    controller = signalController;
    const signal = signalController.signal;
    let fromSequence = sequence;
    const fromEpoch = epoch;
    const seed = reseed || needsSeed || !seeded;
    status(seed ? (seeded ? 'refreshing' : 'loading') : 'reconnecting');
    try {
      const seedOptions = nextSeedOptions; nextSeedOptions = {};
      const seedDocument = seed ? await options.loadSeed(signal, seedOptions) : null;
      if (closed || localAttempt !== attempt || signal.aborted) return;
      if (seed) {
        if (seedDocument?.source_epoch !== fromEpoch) { scheduleRetry(true); return; }
        if (!Array.isArray(seedDocument.rows) || seedDocument.rows.length > MAX_SEED_ROWS ||
            !Number.isSafeInteger(seedDocument.sequence) || seedDocument.sequence < 0) {
          throw new Error('Invalid recent P25 activity.');
        }
        // Shared prepared seeds may precede or follow this subscription's boundary.
        // Recover from the seed's own saved sequence, never the subscription time.
        fromSequence = seedDocument.sequence;
        affiliationSnapshot(seedDocument, fromEpoch);
      }
      let afterId = 0, throughSequence = null;
      const changes = [], removed = [];
      let currentAffiliations = seed ? affiliationSnapshot(seedDocument, fromEpoch) : null;
      let requiredAffiliationRevision = affiliationRevision, recoveryComplete = affiliationsComplete;
      do {
        const page = await options.requestCatchup({ source_epoch: fromEpoch,
          after_sequence: fromSequence, affiliation_revision: seed ? currentAffiliations.revision : affiliationRevision, ...(throughSequence === null ? {} : { through_sequence: throughSequence }),
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
        if (!page.has_more) {
          if (!Number.isSafeInteger(page.affiliation_revision) || page.affiliation_revision < 0 ||
              typeof page.affiliations_available !== 'boolean' || typeof page.affiliations_complete !== 'boolean' || typeof page.affiliations_refresh_required !== 'boolean') {
            throw new Error('Invalid current P25 affiliation recovery.');
          }
          requiredAffiliationRevision = page.affiliation_revision; recoveryComplete = page.affiliations_complete;
          break;
        }
      } while (!signal.aborted);
      if (closed || localAttempt !== attempt || signal.aborted) return;
      if (seed) {
        currentAffiliations.preserve = seeded && currentAffiliations.revision < affiliationRevision;
        await options.onSeed(seedDocument.rows, changes, removed, signal, currentAffiliations);
      } else options.onRecovery(changes, removed);
      if (closed || localAttempt !== attempt || signal.aborted) return;
      sequence = throughSequence;
      if (seed && !currentAffiliations.preserve) { affiliationRevision = currentAffiliations.revision; affiliationsComplete = currentAffiliations.complete; }
      discardAffiliationsThrough(affiliationRevision);
      seeded = true; needsSeed = false; retryDelay = 500;
      discardThrough(sequence);
      if (!recoveryComplete && requiredAffiliationRevision >= affiliationRevision) {
        affiliationRevision = requiredAffiliationRevision; affiliationsComplete = false;
        discardAffiliationsThrough(affiliationRevision);
        options.onAffiliations([], { available: false, complete: false, revision: affiliationRevision, source_epoch: epoch });
      }
      drainAffiliations();
      if (recoveryComplete && (requiredAffiliationRevision > affiliationRevision || !affiliationsComplete)) void refreshAffiliations();
      status('live');
    } catch (error) {
      if (error?.name === 'AbortError' || closed || localAttempt !== attempt) return;
      options.onError?.(error);
      scheduleRetry(seed);
    } finally {
      if (localAttempt === attempt) { syncing = false; controller = null; drain(); drainAffiliations(); }
    }
  }

  function boundary(value) {
    if (closed || hidden() || !value || typeof value.source_epoch !== 'string' ||
        !Number.isSafeInteger(value.sequence) || value.sequence < 0) return;
    options.onBoundary?.(value);
    const changed = epoch !== value.source_epoch;
    if (changed || needsSeed) { cancel(); clearBuffer(); if (changed) { affiliationRevision = 0; affiliationsComplete = false; } epoch = value.source_epoch; sequence = value.sequence; needsSeed = true; }
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
      affiliations(value) {
        if (closed || hidden()) return;
        try { collectAffiliations(value); drainAffiliations(); } catch (_) { scheduleRetry(false); }
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
    reseed(requestOptions = {}) {
      if (closed) return;
      nextSeedOptions = requestOptions;
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
