/** Estimate display contrast from a lower percentile, avoiding a single unusually weak FFT bin. */
export function estimateSpectrumDisplayFloor(values, ceilingDb, minimumDb = -200, minimumSpanDb = 5) {
  if (!values || typeof values[Symbol.iterator] !== 'function' ||
      !Number.isFinite(ceilingDb) || !Number.isFinite(minimumDb) ||
      !(minimumSpanDb > 0) || ceilingDb - minimumDb < minimumSpanDb) return null;
  const finite = Array.from(values).filter(Number.isFinite).sort((left, right) => left - right);
  if (!finite.length) return null;
  const lowerPercentile = finite[Math.min(finite.length - 1, Math.floor(finite.length * 0.1))];
  const paddedFloor = Math.floor((lowerPercentile - 5) / 5) * 5;
  return Math.max(minimumDb, Math.min(ceilingDb - minimumSpanDb, paddedFloor));
}

/** Capture one valid FFT after a confirmed tuner/center change, independently of producer restarts or zoom. */
export function createSpectrumAutoRange() {
  let selectedTarget = '';
  let retainedTarget = '';
  let centerHz = null;
  let generation = -1;
  let revision = -1;
  let live = false;
  let pending = false;
  return {
    selectTarget(targetId) {
      const next = String(targetId || '');
      if (next === selectedTarget) return false;
      selectedTarget = next;
      live = false;
      // Lease/mode transitions briefly unbind a tuner; that does not change its confirmed frequency.
      if (!next || next === retainedTarget) return false;
      retainedTarget = next;
      centerHz = null;
      generation = revision = -1;
      pending = false;
      return true;
    },
    confirm({ targetId, centerFrequencyHz, sampleRateHz, generation: nextGeneration,
      revision: nextRevision, live: nextLive }) {
      if (!selectedTarget || String(targetId || '') !== selectedTarget ||
          !Number.isSafeInteger(nextGeneration) || nextGeneration < 0 ||
          !Number.isSafeInteger(nextRevision) || nextRevision < 0 || nextGeneration < generation ||
          (nextGeneration === generation && nextRevision < revision)) return false;
      generation = nextGeneration;
      revision = nextRevision;
      live = nextLive === true && Number.isFinite(centerFrequencyHz) && centerFrequencyHz > 0 &&
        Number.isFinite(sampleRateHz) && sampleRateHz > 0;
      if (live && centerFrequencyHz !== centerHz) {
        centerHz = centerFrequencyHz;
        pending = true;
      }
      return true;
    },
    acceptsFrame(frameGeneration) { return live && frameGeneration === generation; },
    sample(values, frameGeneration, ceilingDb) {
      if (!pending || !this.acceptsFrame(frameGeneration)) return null;
      const floor = estimateSpectrumDisplayFloor(values, ceilingDb);
      if (floor !== null) pending = false;
      return floor;
    },
    discard() { pending = false; }
  };
}
