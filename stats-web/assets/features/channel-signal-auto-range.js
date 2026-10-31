const DEFAULT_RANGE = Object.freeze({ floorDb: -120, ceilingDb: 0 });
const MINIMUM_DISPLAY_SPAN_DB = 5;
const NOISE_DISPLAY_MARGIN_DB = 2;
const EMPTY_STRENGTH_DB = -196;

const validStrength = (value) => Number.isFinite(value) && value >= -200 && value <= 0 &&
  value !== EMPTY_STRENGTH_DB;

function quantile(sorted, fraction) {
  const position = (sorted.length - 1) * fraction;
  const lower = Math.floor(position);
  return sorted[lower] + (sorted[Math.ceil(position)] - sorted[lower]) * (position - lower);
}

function median(values) {
  return values.length ? quantile(values.sort((left, right) => left - right), 0.5) : NaN;
}

/**
 * Calibrate from persistent spectral energy, not the minimum rejected bin. The transport does not supply actual
 * filter edges, so this is deliberately a conservative display estimate rather than a measurement of receiver SNR.
 */
function estimateRange(frames) {
  const size = frames[0].length;
  const profile = Array.from({ length: size }, (_value, index) => {
    const samples = frames.map((frame) => frame[index]).filter(validStrength);
    return samples.length >= Math.ceil(frames.length / 2) ? median(samples) : NaN;
  });
  const finite = profile.filter(validStrength).sort((left, right) => left - right);
  if (finite.length < Math.max(16, size / 2)) return null;

  const edgeSize = Math.max(2, Math.floor(size * 0.05));
  const leftEdge = median(profile.slice(0, edgeSize).filter(validStrength));
  const rightEdge = median(profile.slice(-edgeSize).filter(validStrength));
  const edgeLevels = [leftEdge, rightEdge].filter(Number.isFinite);
  const edgeLevel = edgeLevels.length ? Math.min(...edgeLevels) : -200;
  const centerLevel = median(profile.slice(Math.floor(size * 0.3), Math.ceil(size * 0.7))
    .filter(validStrength));
  const rejectedBelow = centerLevel - edgeLevel >= 12 ? edgeLevel + 10 : -Infinity;

  // Exclude the desired carrier's strongest center and a clearly rejected outer-noise plateau.
  const candidates = profile.filter((value, index) => validStrength(value) && value > rejectedBelow &&
    index >= edgeSize && index < size - edgeSize &&
    (index < size * 0.4 || index >= size * 0.6)).sort((left, right) => left - right);
  const minimumPlateauBins = Math.max(8, Math.ceil(size * 0.04));
  let first = 0;
  let plateau = [];
  let highestPlateauStart = null;
  for (let last = 0; last < candidates.length; last += 1) {
    while (candidates[last] - candidates[first] > 6) first += 1;
    if (last - first + 1 < minimumPlateauBins) continue;
    // A broad wanted signal may contain more bins than the noise shoulders. Refine the first supported lower
    // background cluster locally instead of replacing it with a denser, stronger carrier plateau.
    if (highestPlateauStart === null) highestPlateauStart = candidates[first] + 6;
    if (candidates[first] > highestPlateauStart) break;
    if (last - first + 1 > plateau.length) plateau = candidates.slice(first, last + 1);
  }
  if (plateau.length < minimumPlateauBins) return null;

  const noiseLevel = median(plateau);
  // Temporal medians reject short spikes. Preserve a persistent narrow tuned carrier even when it occupies
  // fewer bins than the global percentile retains; isolated outliers away from the center remain excluded.
  const centerPeak = Math.max(...profile.slice(Math.floor(size * 0.4), Math.ceil(size * 0.6))
    .filter(validStrength));
  const strongerLevel = Math.max(quantile(finite, 0.99), centerPeak);
  // Without distinct lower shoulders, a full-band transmission and noise alone cannot be distinguished from
  // these samples. Retain the previous/default range instead of confidently treating the entire signal as noise.
  if (strongerLevel - noiseLevel < 6) return null;
  // Raise the display floor above passed noise so the noise shelf and filter rolloff share the background color.
  // Signal strength never chooses an automatic ceiling; keep the existing fixed upper display limit.
  const ceilingDb = DEFAULT_RANGE.ceilingDb;
  const floorDb = Math.max(-200, Math.min(ceilingDb - MINIMUM_DISPLAY_SPAN_DB,
    Math.ceil(noiseLevel + NOISE_DISPLAY_MARGIN_DB)));
  return { floorDb, ceilingDb };
}

/** A bounded, one-shot floor calibration shared by the selected channel's FFT and waterfall. */
export function createChannelSignalAutoRange({ sampleFrames = 8 } = {}) {
  const frameLimit = Number.isInteger(sampleFrames) ? Math.max(3, Math.min(32, sampleFrames)) : 8;
  let frames = [];
  let currentRange = { ...DEFAULT_RANGE };
  let pending = true;
  return {
    reset({ keepRange = false } = {}) {
      frames = [];
      pending = true;
      if (!keepRange) currentRange = { ...DEFAULT_RANGE };
    },
    observe(values) {
      if (!pending || !values || typeof values[Symbol.iterator] !== 'function' || values.length < 16) return false;
      const copy = Float32Array.from(values, (value) => validStrength(value) ? value : NaN);
      if (copy.filter(Number.isFinite).length < Math.max(16, copy.length / 2)) return false;
      if (frames.length && frames[0].length !== copy.length) frames = [];
      frames.push(copy);
      if (frames.length < frameLimit) return false;
      const estimate = estimateRange(frames);
      frames = [];
      pending = false;
      if (!estimate || (estimate.floorDb === currentRange.floorDb && estimate.ceilingDb === currentRange.ceilingDb))
        return false;
      currentRange = estimate;
      return true;
    },
    range() { return { ...currentRange }; },
    pending() { return pending; }
  };
}
