'use strict';

const RTL_RATES = [230400, 240000, 256000, 288000, 300000, 960000, 1024000, 1200000, 1440000,
  1600000, 1800000, 1920000, 2048000, 2304000, 2400000, 2560000, 2880000];
const HACKRF_RATES = [1750000, 2500000, 3500000, 5000000, 5500000, 6000000, 7000000, 8000000,
  9000000, 10000000, 12000000, 14000000, 15000000, 20000000];
const AIRSPY_RATES = [2500000, 3000000, 6000000, 10000000];
const RSP_RATES = [
  [250000, 234000], [500000, 476000], [1000000, 900000], [1500000, 1360000],
  [2048000, 1800000], [3000000, 2700000], [4000000, 3560000], [5000000, 4120000],
  [6000000, 5200000], [7000000, 5960000], [8000000, 6940000], [9000000, 7380000],
  [10000000, 8500000]
];
const RSP_DUO_RATES = [[500000, 450000], [1000000, 950000], [1500000, 1500000], [2000000, 2000000]];
const MAX_TUNERS = 10;
const DEFAULT_CHANNEL_BANDWIDTH = 12500;

function airspyUsable(rate) {
  if (rate === 2500000) return 0.60;
  if (rate === 3000000) return 0.66;
  if (rate === 6000000) return 0.83;
  return 0.90;
}

const PROFILES = Object.freeze({
  'rtl-r8x': { label: 'RTL-SDR · R8x', rates: RTL_RATES, defaultRate: 2400000, usable: 0.98,
    dc: 5000, min: 3180000, max: 1782030000 },
  'rtl-e4k': { label: 'RTL-SDR · E4000', rates: RTL_RATES, defaultRate: 2400000, usable: 0.95,
    dc: 15000, min: 52000000, max: 2200000000 },
  'rtl-fc0013': { label: 'RTL-SDR · FC0013', rates: RTL_RATES, defaultRate: 2400000, usable: 0.95,
    dc: 15000, min: 13500000, max: 1907999890 },
  airspy: { label: 'Airspy / HydraSDR', rates: AIRSPY_RATES, defaultRate: 10000000,
    usableForRate: airspyUsable, dc: 0, min: 24000000, max: 1800000000 },
  'airspy-hf': { label: 'Airspy HF+', rates: [912000], defaultRate: 912000, usable: 0.90,
    dc: 3000, min: 500000, max: 260000000 },
  hackrf: { label: 'HackRF', rates: HACKRF_RATES, defaultRate: 5000000, usable: 0.90,
    dc: 5000, min: 10000000, max: 6000000000 },
  sdrplay: { label: 'SDRplay RSP · single tuner', rates: RSP_RATES.map((row) => row[0]),
    defaultRate: 8000000, usableHzForRate: (rate) => RSP_RATES.find((row) => row[0] === rate)?.[1],
    dc: 0, min: 100000, max: 2000000000 },
  'sdrplay-duo': { label: 'SDRplay RSPduo · dual mode', rates: RSP_DUO_RATES.map((row) => row[0]),
    defaultRate: 2000000, usableHzForRate: (rate) => RSP_DUO_RATES.find((row) => row[0] === rate)?.[1],
    dc: 0, min: 100000, max: 2000000000 },
  custom: { label: 'Custom sdrtrunk profile', rates: [2400000], defaultRate: 2400000, usable: 0.98,
    dc: 5000, min: 0, max: 6000000000, custom: true }
});

function trimZeros(value) {
  return value.includes('.') ? value.replace(/0+$/, '').replace(/\.$/, '') : value;
}

function formatRate(rate) {
  if (rate >= 1000000) return `${trimZeros((rate / 1000000).toFixed(3))} MHz`;
  return `${trimZeros((rate / 1000).toFixed(3))} kHz`;
}

function formatMHz(hz, digits = 6) {
  return trimZeros((hz / 1000000).toFixed(digits));
}

function formatInteger(value) {
  return Math.round(value).toLocaleString('en-US');
}

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

function parseScaledNumber(raw, defaultUnit = 'hz') {
  const match = String(raw).trim().match(/^([+-]?(?:\d+(?:\.\d*)?|\.\d+))\s*(mhz|khz|hz|m|k)?$/i);
  if (!match) return Number.NaN;
  const value = Number(match[1]);
  const explicit = (match[2] || '').toLowerCase();
  let unit = explicit || defaultUnit;
  if (unit === 'm') unit = 'mhz';
  if (unit === 'k') unit = 'khz';
  const scale = unit === 'mhz' ? 1000000 : unit === 'khz' ? 1000 : 1;
  return Math.round(value * scale);
}

function parseFrequencyMHz(raw) {
  const match = String(raw).trim().match(/^([+-]?(?:\d+(?:\.\d*)?|\.\d+))\s*(mhz)?$/i);
  return match ? Math.round(Number(match[1]) * 1000000) : Number.NaN;
}

function parseBandwidth(raw, defaultBandwidth) {
  if (!raw || !raw.trim()) return defaultBandwidth;
  const value = parseScaledNumber(raw, 'hz');
  return Number.isFinite(value) && value > 0 ? value : Number.NaN;
}

function parseChannels(text, defaultBandwidth = DEFAULT_CHANNEL_BANDWIDTH) {
  const warnings = [];
  const byFrequency = new Map();
  const invalid = [];
  text.split(/\r?\n/).forEach((sourceLine, lineIndex) => {
    const line = sourceLine.replace(/#.*$/, '').trim();
    if (!line) return;
    const parts = line.split('@');
    if (parts.length > 2) {
      invalid.push(`Line ${lineIndex + 1}: too many @ separators`);
      return;
    }
    const bandwidth = parseBandwidth(parts[1] || '', defaultBandwidth);
    if (!Number.isFinite(bandwidth)) {
      invalid.push(`Line ${lineIndex + 1}: invalid channel width`);
      return;
    }
    const tokens = parts[0].split(/[\s,;]+/).filter(Boolean);
    tokens.forEach((token) => {
      const frequency = parseFrequencyMHz(token);
      if (!Number.isFinite(frequency) || frequency <= 0) {
        invalid.push(`Line ${lineIndex + 1}: “${token}” is not a frequency`);
        return;
      }
      const existing = byFrequency.get(frequency);
      if (existing) {
        if (bandwidth > existing.bandwidth) existing.bandwidth = bandwidth;
        warnings.push(`${formatMHz(frequency)} MHz appeared more than once; the widest channel definition was kept.`);
      } else {
        byFrequency.set(frequency, { frequency, bandwidth });
      }
    });
  });
  return {
    channels: [...byFrequency.values()].sort((left, right) => left.frequency - right.frequency),
    warnings: [...new Set(warnings)],
    invalid
  };
}

function channelMin(channel) {
  return channel.frequency - Math.trunc(channel.bandwidth / 2);
}

function channelMax(channel) {
  return channel.frequency + Math.trunc(channel.bandwidth / 2);
}

function setBandwidth(channels) {
  return channelMax(channels[channels.length - 1]) - channelMin(channels[0]);
}

function channelizerGrid(sampleRate) {
  let count = Math.trunc(sampleRate / 25000);
  if (count % 2 !== 0) count--;
  if (count < 2) return null;
  return { count, exactStep: sampleRate / count, step: Math.trunc(sampleRate / count) };
}

function overlaps(channel, minimum, maximum) {
  const min = channelMin(channel);
  const max = channelMax(channel);
  return (minimum <= min && min <= maximum) ||
    (minimum <= max && max <= maximum) ||
    (minimum <= min && max <= maximum) ||
    (min <= minimum && maximum <= max);
}

function canTuneSet(channels, hardware) {
  if (!channels.length) return false;
  if (channels.some((channel) => channelMin(channel) < hardware.min || channelMax(channel) > hardware.max)) {
    return false;
  }
  const usableHalf = Math.trunc(hardware.usableBandwidth / 2);
  if (channels.length === 1) {
    return hardware.dcHalf > 0 ? channels[0].bandwidth < usableHalf :
      channels[0].bandwidth < hardware.usableBandwidth;
  }
  const span = setBandwidth(channels);
  if (span > hardware.usableBandwidth) return false;
  if (hardware.dcHalf === 0 || span < usableHalf) return true;
  let firstRight = null;
  for (const channel of channels) {
    if (!firstRight) {
      const leftSpan = channelMax(channel) - channelMin(channels[0]);
      if (leftSpan > usableHalf) {
        if (channel.bandwidth > usableHalf) return false;
        firstRight = channel;
      }
    } else if (channelMax(channel) - channelMin(firstRight) > usableHalf) {
      return false;
    }
  }
  return true;
}

function isValidCenter(channels, center, hardware) {
  const half = Math.trunc(hardware.usableBandwidth / 2);
  if (center < hardware.min || center > hardware.max) return false;
  return channels.every((channel) => {
    const fitsEdges = channelMin(channel) >= center - half && channelMax(channel) <= center + half;
    if (!fitsEdges) return false;
    if (hardware.dcHalf <= 0) return true;
    return !overlaps(channel, center - hardware.dcHalf, center + hardware.dcHalf);
  });
}

function integralFrequency(channels, exactGridStep) {
  let bestFrequency = channels[0].frequency;
  let bestScore = Number.POSITIVE_INFINITY;
  if (channels.length === 1) return bestFrequency;
  for (const first of channels) {
    let score = 0;
    for (const second of channels) {
      if (first !== second) score += Math.abs(first.frequency - second.frequency) % exactGridStep;
    }
    if (score < bestScore) {
      bestScore = score;
      bestFrequency = first.frequency;
    }
  }
  return bestFrequency;
}

function alignedToGrid(channel, center, gridStep) {
  return Math.abs(channel.frequency - center) % gridStep === 0;
}

function findVceCenter(channels, hardware, currentCenter = 0) {
  if (!channels.length || !canTuneSet(channels, hardware)) return null;
  const grid = channelizerGrid(hardware.rate);
  if (!grid) return null;
  const span = setBandwidth(channels);
  if (span > hardware.usableBandwidth) return null;
  if (currentCenter > 0 && isValidCenter(channels, currentCenter, hardware) &&
      (channels.length > 1 || alignedToGrid(channels[0], currentCenter, grid.step))) {
    return { center: currentCenter, grid, strategy: 'current' };
  }
  const usableHalf = Math.trunc(hardware.usableBandwidth / 2);
  const integral = integralFrequency(channels, grid.exactStep);
  const lowest = channelMax(channels[channels.length - 1]) - usableHalf;
  const highest = channelMin(channels[0]) + usableHalf;
  let offset = (lowest - integral) % grid.step;
  if (offset < 0) offset += grid.step;
  const firstGridCenter = lowest + (offset === 0 ? 0 : grid.step - offset);
  if (firstGridCenter <= highest) {
    const midpoint = Math.trunc((channelMin(channels[0]) + channelMax(channels[channels.length - 1])) / 2);
    let idealOffset = (midpoint - integral) % grid.step;
    if (idealOffset < 0) idealOffset += grid.step;
    const ideal = midpoint - idealOffset;
    const candidate = Math.max(firstGridCenter, Math.min(ideal, highest));
    const candidateAbove = candidate + grid.step;
    const candidateValid = isValidCenter(channels, candidate, hardware);
    const aboveValid = candidateAbove <= highest && isValidCenter(channels, candidateAbove, hardware);
    if (candidateValid || aboveValid) {
      const center = candidateValid && aboveValid ?
        (Math.abs(candidate - midpoint) <= Math.abs(candidateAbove - midpoint) ? candidate : candidateAbove) :
        (candidateValid ? candidate : candidateAbove);
      return { center, grid, strategy: 'grid' };
    }
    const lastGridCenter = highest - (((highest - integral) % grid.step + grid.step) % grid.step);
    for (let step = grid.step; firstGridCenter + step <= lastGridCenter; step += grid.step) {
      const low = ideal - step;
      const high = ideal + step;
      if (low >= firstGridCenter && isValidCenter(channels, low, hardware)) {
        return { center: low, grid, strategy: 'grid-search' };
      }
      if (high <= lastGridCenter && isValidCenter(channels, high, hardware)) {
        return { center: high, grid, strategy: 'grid-search' };
      }
    }
  }
  const available = hardware.usableBandwidth - span;
  let testFrequency = channelMin(channels[0]) + usableHalf;
  const minimum = testFrequency - available;
  if (isValidCenter(channels, testFrequency, hardware)) {
    return { center: testFrequency, grid, strategy: 'exact' };
  }
  while (testFrequency >= minimum) {
    testFrequency--;
    if (isValidCenter(channels, testFrequency, hardware)) {
      return { center: testFrequency, grid, strategy: 'exact' };
    }
  }
  return null;
}

function findUpstreamCenter(channels, hardware, currentCenter = 0) {
  if (!channels.length || !canTuneSet(channels, hardware)) return null;
  const grid = channelizerGrid(hardware.rate);
  if (!grid) return null;
  const span = setBandwidth(channels);
  if (span > hardware.usableBandwidth) return null;
  if (currentCenter > 0 && isValidCenter(channels, currentCenter, hardware)) {
    return { center: currentCenter, grid, strategy: 'current' };
  }
  const usableHalf = Math.trunc(hardware.usableBandwidth / 2);
  const integral = integralFrequency(channels, grid.exactStep);
  let start = channels[0].frequency - grid.step;
  if (isValidCenter(channels, start, hardware)) return { center: start, grid, strategy: 'upstream-step' };
  while (start - channelMin(channels[0]) < usableHalf) {
    start += grid.step;
    if (isValidCenter(channels, start, hardware)) {
      return { center: start, grid, strategy: 'upstream-step' };
    }
  }
  let startFrequency = channelMin(channels[0]) + usableHalf;
  const adjustment = Math.abs(startFrequency - integral) % grid.exactStep;
  startFrequency += adjustment;
  const available = hardware.usableBandwidth - span;
  const availableTestChannels = Math.trunc(available / grid.exactStep) + 1;
  if (isValidCenter(channels, Math.trunc(startFrequency), hardware)) {
    return { center: Math.trunc(startFrequency), grid, strategy: 'upstream-grid' };
  }
  for (let channel = 1; channel <= availableTestChannels; channel++) {
    const test = Math.trunc(startFrequency - channel * grid.exactStep);
    if (isValidCenter(channels, test, hardware)) return { center: test, grid, strategy: 'upstream-grid' };
  }
  let testFrequency = channelMin(channels[0]) + usableHalf;
  const minimum = testFrequency - available;
  if (isValidCenter(channels, testFrequency, hardware)) {
    return { center: testFrequency, grid, strategy: 'exact' };
  }
  while (testFrequency >= minimum) {
    testFrequency--;
    if (isValidCenter(channels, testFrequency, hardware)) {
      return { center: testFrequency, grid, strategy: 'exact' };
    }
  }
  return null;
}

function findCenter(channels, hardware, currentCenter = 0, engine = 'vce') {
  return engine === 'upstream' ? findUpstreamCenter(channels, hardware, currentCenter) :
    findVceCenter(channels, hardware, currentCenter);
}

function planTunerPool(channels, hardwares, engine = 'vce') {
  const placementCache = new Map();
  const resultCache = new Map();
  function placementFor(tunerIndex, start, end) {
    const key = `${tunerIndex}:${start}:${end}:${engine}`;
    if (!placementCache.has(key)) {
      placementCache.set(key, findCenter(channels.slice(start, end + 1), hardwares[tunerIndex], 0, engine));
    }
    return placementCache.get(key);
  }
  function solve(start, usedMask) {
    if (start >= channels.length) return { covered: 0, plans: [], rejected: [] };
    const cacheKey = `${start}:${usedMask}`;
    if (resultCache.has(cacheKey)) return resultCache.get(cacheKey);
    const skippedTail = solve(start + 1, usedMask);
    let best = { covered: skippedTail.covered, plans: skippedTail.plans,
      rejected: [channels[start], ...skippedTail.rejected] };
    for (let tunerIndex = 0; tunerIndex < hardwares.length; tunerIndex++) {
      if (usedMask & (1 << tunerIndex)) continue;
      for (let end = start; end < channels.length; end++) {
        const placement = placementFor(tunerIndex, start, end);
        if (!placement) break;
        const tail = solve(end + 1, usedMask | (1 << tunerIndex));
        const candidate = {
          covered: end - start + 1 + tail.covered,
          plans: [{ channels: channels.slice(start, end + 1), ...placement,
            hardware: hardwares[tunerIndex], tunerIndex }, ...tail.plans],
          rejected: tail.rejected
        };
        if (candidate.covered > best.covered ||
            (candidate.covered === best.covered && candidate.plans.length < best.plans.length)) {
          best = candidate;
        }
      }
    }
    resultCache.set(cacheKey, best);
    return best;
  }
  const best = solve(0, 0);
  return { plans: best.plans, rejected: best.rejected };
}

function hardwareForTuner(tuner, index = 0) {
  const profile = PROFILES[tuner.profileKey];
  const rate = profile.custom ? Number(tuner.customRate) : Number(tuner.rate);
  const exactUsableBandwidth = !profile.custom && profile.usableHzForRate ?
    profile.usableHzForRate(rate) : null;
  const usablePercent = exactUsableBandwidth ? exactUsableBandwidth / rate : profile.custom ?
    Number(tuner.usablePercent) / 100 : (profile.usableForRate ? profile.usableForRate(rate) : profile.usable);
  return {
    tunerIndex: index,
    key: tuner.profileKey,
    label: profile.label,
    rate,
    usablePercent,
    usableBandwidth: exactUsableBandwidth ?? Math.trunc(rate * usablePercent),
    dcHalf: Math.max(0, Math.trunc(profile.custom ? Number(tuner.dcHalf) : profile.dc)),
    min: Math.round((profile.custom ? Number(tuner.minMHz) : profile.min / 1000000) * 1000000),
    max: Math.round((profile.custom ? Number(tuner.maxMHz) : profile.max / 1000000) * 1000000)
  };
}

function modelChecks() {
  const r8x = { rate: 2400000, usableBandwidth: 2352000, usablePercent: 0.98, dcHalf: 5000,
    min: 3180000, max: 1782030000 };
  const airspy = { rate: 10000000, usableBandwidth: 9000000, usablePercent: 0.90, dcHalf: 0,
    min: 24000000, max: 1800000000 };
  const one = [{ frequency: 851000000, bandwidth: 12500 }];
  const edgeChannel = [{ frequency: 852169750, bandwidth: 12500 }];
  const parsed = parseChannels('851.0125\n851.2625 @ 6.25k');
  const invalidUnit = parseChannels('851012500Hz');
  const mixedPool = planTunerPool([
    { frequency: 851000000, bandwidth: 12500 },
    { frequency: 854000000, bandwidth: 12500 }
  ], [r8x, airspy], 'vce');
  const twoR8x = planTunerPool([
    { frequency: 851000000, bandwidth: 12500 },
    { frequency: 854000000, bandwidth: 12500 }
  ], [r8x, { ...r8x }], 'vce');
  return [
    findCenter(one, r8x, 0, 'vce')?.center === 851025000,
    findCenter(one, r8x, 0, 'upstream')?.center === 850975000,
    findCenter(one, airspy, 0, 'vce')?.center === 851000000,
    isValidCenter(one, 851000000, r8x) === false,
    isValidCenter(edgeChannel, 851000000, r8x) === true,
    parsed.channels.length === 2 && parsed.channels[1].bandwidth === 6250,
    invalidUnit.channels.length === 0 && invalidUnit.invalid.length === 1,
    mixedPool.plans.length === 1 && mixedPool.plans[0].tunerIndex === 1 && mixedPool.rejected.length === 0,
    twoR8x.plans.length === 2 && twoR8x.rejected.length === 0,
    channelizerGrid(2048000)?.step === 25600
  ];
}

function createPlanner() {
  const root = document.createElement('div');
  root.className = 'rf-planner';
  root.innerHTML = `
    <div class="rfp-intro">
      <p>Configure your tuner pool, then calculate simultaneous coverage with each device’s real edge guards,
        DC-spike exclusion, and channelizer behavior.</p>
      <span class="rfp-compatibility" title="Select the center-placement behavior for either codebase.">✓ VCE + DSheirer modes</span>
    </div>
    <div class="rfp-layout">
      <section class="rfp-panel rfp-controls" aria-labelledby="rfp-input-heading">
        <header class="rfp-panel-head">
          <h2 id="rfp-input-heading">Receiver setup</h2>
          <p>Add the physical tuners available to this receiver.</p>
        </header>
        <form class="rfp-form">
          <div class="rfp-tuner-pool">
            <div class="rfp-tuner-pool-head"><span class="rfp-label">Available tuners</span>
              <button class="rfp-button rfp-small rfp-add-tuner" type="button">+ Add tuner</button></div>
            <div class="rfp-tuner-list"></div>
          </div>
          <div class="rfp-field">
            <label for="rfp-placement-engine">sdrtrunk behavior</label>
            <select id="rfp-placement-engine" class="rfp-placement-engine">
              <option value="vce" selected>sdrtrunk-vce · current main</option>
              <option value="upstream">DSheirer/sdrtrunk · master</option>
            </select>
            <div class="rfp-hint">Both modes use the same device guards and DC exclusions. They differ in how a fresh center is chosen.</div>
          </div>
          <div class="rfp-field">
            <label for="rfp-frequencies">Channel frequencies · MHz</label>
            <textarea id="rfp-frequencies" class="rfp-frequencies" spellcheck="false" placeholder="851.0125&#10;851.2625&#10;851.7750 @ 6.25k"></textarea>
            <div class="rfp-hint">Frequency values are MHz only. Channels default to 12.5 kHz wide; override one with <code>851.7750 @ 6.25k</code>.</div>
          </div>
          <div class="rfp-actions">
            <button class="rfp-button rfp-example" type="button">Load example</button>
            <button class="rfp-button rfp-primary" type="submit">Calculate centers</button>
          </div>
        </form>
      </section>
      <section class="rfp-results" aria-live="polite" aria-label="Calculation results">
        <div class="rfp-panel rfp-empty"><div><div class="rfp-empty-mark" aria-hidden="true">⌁</div>
          <h2>Ready for a channel list</h2>
          <p>The planner will assign the most channels it can across the tuners you add, then validate every channel edge against each device’s usable passband and center exclusion.</p>
        </div></div>
      </section>
    </div>
    <footer class="rfp-footer">
      <span>Planning model verified against <code>sdrtrunk-vce main</code> and <code>DSheirer/sdrtrunk master 80360029efb0</code>.</span>
      <span class="rfp-self-test">Checking calculator…</span>
    </footer>
    <template class="rfp-tuner-template">
      <section class="rfp-tuner-card">
        <div class="rfp-tuner-card-head"><div class="rfp-tuner-card-title"><span class="rfp-tuner-index"></span><span class="rfp-tuner-name"></span></div>
          <div class="rfp-tuner-actions"><button class="rfp-button rfp-icon rfp-duplicate-tuner" type="button" title="Duplicate this tuner">Duplicate</button>
            <button class="rfp-button rfp-icon rfp-remove rfp-remove-tuner" type="button" title="Remove this tuner">Remove</button></div></div>
        <div class="rfp-tuner-fields"><div class="rfp-field"><label>Tuner model</label><select class="rfp-tuner-profile"></select></div>
          <div class="rfp-field"><label>Sample rate</label><select class="rfp-tuner-rate"></select></div></div>
        <div class="rfp-tuner-spec"></div>
        <div class="rfp-custom-tuner-fields" hidden>
          <div class="rfp-field"><label>Sample rate · Hz</label><input class="rfp-custom-rate" type="number" min="50000" max="100000000" step="1" inputmode="numeric"></div>
          <div class="rfp-field"><label>Usable bandwidth · %</label><input class="rfp-custom-usable" type="number" min="1" max="100" step="0.001" inputmode="decimal"></div>
          <div class="rfp-field"><label>DC half-width · Hz</label><input class="rfp-custom-dc" type="number" min="0" max="10000000" step="1" inputmode="numeric"></div>
          <div class="rfp-field"><label>Tune range · MHz</label><div class="rfp-row">
            <input class="rfp-custom-min" aria-label="Minimum tune in MHz" type="number" min="0" step="0.001" inputmode="decimal">
            <input class="rfp-custom-max" aria-label="Maximum tune in MHz" type="number" min="0" step="0.001" inputmode="decimal">
          </div></div>
        </div>
      </section>
    </template>`;

  const form = root.querySelector('.rfp-form');
  const tunerList = root.querySelector('.rfp-tuner-list');
  const tunerTemplate = root.querySelector('.rfp-tuner-template');
  const engineSelect = root.querySelector('.rfp-placement-engine');
  const frequenciesInput = root.querySelector('.rfp-frequencies');
  const results = root.querySelector('.rfp-results');
  const selfTest = root.querySelector('.rfp-self-test');
  let nextTunerId = 1;

  function createTuner(profileKey = 'rtl-r8x', source = null) {
    const profile = PROFILES[profileKey];
    return {
      id: nextTunerId++, profileKey,
      rate: source?.rate ?? profile.defaultRate,
      customRate: source?.customRate ?? profile.defaultRate,
      usablePercent: source?.usablePercent ?? (profile.usable * 100 || 98),
      dcHalf: source?.dcHalf ?? profile.dc,
      minMHz: source?.minMHz ?? profile.min / 1000000,
      maxMHz: source?.maxMHz ?? profile.max / 1000000
    };
  }

  let tunerConfigs = [createTuner()];

  function profileOptions(selectedKey) {
    return Object.entries(PROFILES).map(([key, profile]) =>
      `<option value="${key}"${key === selectedKey ? ' selected' : ''}>${escapeHtml(profile.label)}</option>`
    ).join('');
  }

  function renderTunerPool() {
    tunerList.innerHTML = '';
    tunerConfigs.forEach((tuner, index) => {
      const profile = PROFILES[tuner.profileKey];
      const fragment = tunerTemplate.content.cloneNode(true);
      const card = fragment.querySelector('.rfp-tuner-card');
      card.dataset.tunerId = String(tuner.id);
      card.querySelector('.rfp-tuner-index').textContent = String(index + 1).padStart(2, '0');
      card.querySelector('.rfp-tuner-name').textContent = `Tuner ${index + 1}`;
      const profileSelect = card.querySelector('.rfp-tuner-profile');
      profileSelect.innerHTML = profileOptions(tuner.profileKey);
      const rateSelect = card.querySelector('.rfp-tuner-rate');
      rateSelect.innerHTML = profile.rates.map((rate) =>
        `<option value="${rate}"${rate === tuner.rate ? ' selected' : ''}>${formatRate(rate)}</option>`).join('');
      rateSelect.disabled = profile.custom;
      card.querySelector('.rfp-remove-tuner').disabled = tunerConfigs.length === 1;
      const customFields = card.querySelector('.rfp-custom-tuner-fields');
      customFields.hidden = !profile.custom;
      card.querySelector('.rfp-custom-rate').value = String(tuner.customRate);
      card.querySelector('.rfp-custom-usable').value = trimZeros(Number(tuner.usablePercent).toFixed(3));
      card.querySelector('.rfp-custom-dc').value = String(tuner.dcHalf);
      card.querySelector('.rfp-custom-min').value = trimZeros(Number(tuner.minMHz).toFixed(6));
      card.querySelector('.rfp-custom-max').value = trimZeros(Number(tuner.maxMHz).toFixed(6));
      updateTunerSpec(card, tuner, index);
      tunerList.append(fragment);
    });
    root.querySelector('.rfp-add-tuner').disabled = tunerConfigs.length >= MAX_TUNERS;
  }

  function updateTunerSpec(card, tuner, index) {
    const hardware = hardwareForTuner(tuner, index);
    card.querySelector('.rfp-tuner-spec').textContent =
      `${formatRate(hardware.usableBandwidth)} usable · ${hardware.dcHalf > 0 ?
        `DC ±${formatRate(hardware.dcHalf)}` : 'no DC exclusion'} · ` +
      `${formatMHz(hardware.min)}–${formatMHz(hardware.max)} MHz`;
  }

  function tunerForCard(card) {
    return tunerConfigs.find((tuner) => tuner.id === Number(card?.dataset.tunerId));
  }

  function syncCustomTunerFromCard(card, tuner) {
    tuner.customRate = Number(card.querySelector('.rfp-custom-rate').value);
    tuner.usablePercent = Number(card.querySelector('.rfp-custom-usable').value);
    tuner.dcHalf = Number(card.querySelector('.rfp-custom-dc').value);
    tuner.minMHz = Number(card.querySelector('.rfp-custom-min').value);
    tuner.maxMHz = Number(card.querySelector('.rfp-custom-max').value);
  }

  function renderNotice(messages, type = 'warning') {
    if (!messages.length) return '';
    return `<div class="rfp-notice ${type}" role="${type === 'error' ? 'alert' : 'status'}"><span aria-hidden="true">${type === 'error' ? '×' : '!'}</span><div>${messages.map(escapeHtml).join('<br>')}</div></div>`;
  }

  function channelTop(index) {
    return 23 + (index % 3) * 31;
  }

  function renderSpectrum(plan, hardware) {
    const rawLow = plan.center - hardware.rate / 2;
    const rawHigh = plan.center + hardware.rate / 2;
    const usableHalf = Math.trunc(hardware.usableBandwidth / 2);
    const usableLow = plan.center - usableHalf;
    const usableHigh = plan.center + usableHalf;
    const toPercent = (hz) => Math.max(0, Math.min(100, (hz - rawLow) / hardware.rate * 100));
    const lowGuardWidth = toPercent(usableLow);
    const highGuardStart = toPercent(usableHigh);
    const dcLeft = toPercent(plan.center - hardware.dcHalf);
    const dcWidth = hardware.dcHalf > 0 ?
      Math.max(0.16, toPercent(plan.center + hardware.dcHalf) - dcLeft) : 0;
    const channelBands = plan.channels.map((channel, index) => {
      const left = toPercent(channelMin(channel));
      const right = toPercent(channelMax(channel));
      const title = `${formatMHz(channel.frequency)} MHz · ${formatRate(channel.bandwidth)} wide`;
      return `<span class="rfp-channel-band" style="left:${left}%;width:${Math.max(0.22, right - left)}%;top:${channelTop(index)}px" title="${escapeHtml(title)}"></span>`;
    }).join('');
    return `<div class="rfp-spectrum"><div class="rfp-spectrum-track" role="img" aria-label="Tuner passband showing edge guards, center DC exclusion, and ${plan.channels.length} channels">
      <span class="rfp-usable-zone" style="left:${lowGuardWidth}%;right:${100 - highGuardStart}%"></span>
      <span class="rfp-edge-guard" style="left:0;width:${lowGuardWidth}%"></span>
      <span class="rfp-edge-guard" style="left:${highGuardStart}%;right:0"></span>
      ${hardware.dcHalf > 0 ? `<span class="rfp-dc-zone" style="left:${dcLeft}%;width:${dcWidth}%" title="DC exclusion ±${formatInteger(hardware.dcHalf)} Hz"></span>` : ''}
      <span class="rfp-center-line"></span>${channelBands}</div>
      <div class="rfp-band-labels"><span>${formatMHz(rawLow, 4)} MHz</span><span>▲ ${formatMHz(plan.center, 6)}</span><span>${formatMHz(rawHigh, 4)} MHz</span></div>
      <div class="rfp-spectrum-legend"><span><i class="rfp-legend-swatch channel"></i>Channel envelope</span>
        <span><i class="rfp-legend-swatch"></i>Usable passband</span><span><i class="rfp-legend-swatch guard"></i>Edge guard</span>
        ${hardware.dcHalf > 0 ? '<span><i class="rfp-legend-swatch dc"></i>DC exclusion</span>' : ''}</div></div>`;
  }

  function renderPlan(plan) {
    const hardware = plan.hardware;
    const usableHalf = Math.trunc(hardware.usableBandwidth / 2);
    const chips = plan.channels.map((channel) => `<span class="rfp-chip">${formatMHz(channel.frequency)} <small>MHz · ${formatRate(channel.bandwidth)}</small></span>`).join('');
    return `<article class="rfp-panel rfp-tuner-result"><header class="rfp-result-head">
      <div class="rfp-result-title"><span class="rfp-tuner-number">${String(plan.tunerIndex + 1).padStart(2, '0')}</span>
        <div><h3>Tuner ${plan.tunerIndex + 1}</h3><p>${plan.channels.length} channel${plan.channels.length === 1 ? '' : 's'} · ${escapeHtml(hardware.label)} · ${formatRate(hardware.rate)}</p></div></div>
      <button class="rfp-button rfp-small rfp-copy-center" type="button" data-center="${plan.center}">Copy center</button></header>
      <div class="rfp-center-value"><span class="rfp-center-label">Center frequency</span>
        <div class="rfp-center-frequency">${formatMHz(plan.center, 6)} <small>MHz · ${formatInteger(plan.center)} Hz</small></div></div>
      ${renderSpectrum(plan, hardware)}
      <div class="rfp-detail-grid"><div><span>Usable range</span><strong>${formatMHz(plan.center - usableHalf, 5)}–${formatMHz(plan.center + usableHalf, 5)} MHz</strong></div>
        <div><span>Edge guard</span><strong>${formatRate((hardware.rate - hardware.usableBandwidth) / 2)} each side</strong></div>
        <div><span>DC exclusion</span><strong>${hardware.dcHalf > 0 ? `±${formatRate(hardware.dcHalf)}` : 'None'}</strong></div></div>
      <div class="rfp-channels" aria-label="Assigned channels">${chips}</div></article>`;
  }

  async function copyText(value) {
    try {
      await navigator.clipboard.writeText(value);
      return true;
    } catch (_) {
      const helper = document.createElement('textarea');
      helper.value = value;
      helper.style.position = 'fixed';
      helper.style.opacity = '0';
      document.body.append(helper);
      helper.select();
      const copied = document.execCommand('copy');
      helper.remove();
      return copied;
    }
  }

  function renderResults(parsed, hardwares, engine) {
    const errors = [...parsed.invalid];
    if (!parsed.channels.length) errors.push('Enter at least one valid channel frequency.');
    hardwares.forEach((hardware, index) => {
      const prefix = `Tuner ${index + 1}:`;
      if (!Number.isFinite(hardware.rate) || hardware.rate < 50000) {
        errors.push(`${prefix} the effective sample rate must be at least 50 kHz.`);
      }
      if (!(hardware.usablePercent > 0 && hardware.usablePercent <= 1)) {
        errors.push(`${prefix} usable bandwidth must be greater than 0% and no more than 100%.`);
      }
      if (!(hardware.min >= 0 && hardware.max > hardware.min)) errors.push(`${prefix} frequency limits are invalid.`);
      if (!channelizerGrid(hardware.rate)) {
        errors.push(`${prefix} sample rate is too low for sdrtrunk’s polyphase channelizer.`);
      }
    });
    if (errors.length) {
      results.innerHTML = `<div class="rfp-result-stack">${renderNotice(errors, 'error')}</div>`;
      return;
    }
    const { plans, rejected } = planTunerPool(parsed.channels, hardwares, engine);
    const warnings = [...parsed.warnings];
    if (rejected.length) {
      warnings.push(`${rejected.length} channel${rejected.length === 1 ? '' : 's'} could not be assigned across the configured tuner pool: ${rejected.map((channel) => `${formatMHz(channel.frequency)} MHz`).join(', ')}.`);
    }
    warnings.push('This is a static simultaneous-coverage plan. Live sdrtrunk allocation can differ when tuners already have active channels or valid center frequencies.');
    const assigned = parsed.channels.length - rejected.length;
    const engineLabel = engine === 'upstream' ? 'DSheirer' : 'VCE main';
    results.innerHTML = `<div class="rfp-result-stack"><section class="rfp-panel rfp-summary" aria-label="Plan summary">
      <div><span>Channels</span><strong>${parsed.channels.length}</strong></div><div><span>Assigned</span><strong>${assigned}</strong></div>
      <div><span>Tuners used</span><strong>${plans.length}/${hardwares.length}</strong></div><div><span>Engine</span><strong>${engineLabel}</strong></div></section>
      ${renderNotice(warnings)}${plans.map(renderPlan).join('')}
      ${rejected.length ? renderNotice(['No complete plan is possible with the selected tuner limits and channel widths.'], 'error') : ''}</div>`;
    results.querySelectorAll('.rfp-copy-center').forEach((button) => {
      button.addEventListener('click', async () => {
        const old = button.textContent;
        button.textContent = await copyText(button.dataset.center) ? 'Copied Hz' : 'Copy failed';
        window.setTimeout(() => { button.textContent = old; }, 1400);
      });
    });
  }

  function calculate() {
    renderResults(parseChannels(frequenciesInput.value),
      tunerConfigs.map((tuner, index) => hardwareForTuner(tuner, index)), engineSelect.value);
  }

  root.querySelector('.rfp-add-tuner').addEventListener('click', () => {
    if (tunerConfigs.length >= MAX_TUNERS) return;
    tunerConfigs.push(createTuner(tunerConfigs.at(-1)?.profileKey || 'rtl-r8x'));
    renderTunerPool();
  });

  tunerList.addEventListener('click', (event) => {
    const card = event.target.closest('.rfp-tuner-card');
    const tuner = tunerForCard(card);
    if (!tuner) return;
    if (event.target.closest('.rfp-duplicate-tuner')) {
      if (tunerConfigs.length >= MAX_TUNERS) return;
      tunerConfigs.splice(tunerConfigs.indexOf(tuner) + 1, 0, createTuner(tuner.profileKey, tuner));
      renderTunerPool();
    } else if (event.target.closest('.rfp-remove-tuner') && tunerConfigs.length > 1) {
      tunerConfigs = tunerConfigs.filter((candidate) => candidate !== tuner);
      renderTunerPool();
    }
  });

  tunerList.addEventListener('change', (event) => {
    const card = event.target.closest('.rfp-tuner-card');
    const tuner = tunerForCard(card);
    if (!tuner) return;
    if (event.target.matches('.rfp-tuner-profile')) {
      const replacement = createTuner(event.target.value);
      replacement.id = tuner.id;
      tunerConfigs[tunerConfigs.indexOf(tuner)] = replacement;
    } else if (event.target.matches('.rfp-tuner-rate')) {
      tuner.rate = Number(event.target.value);
    } else if (PROFILES[tuner.profileKey].custom) {
      syncCustomTunerFromCard(card, tuner);
    }
    renderTunerPool();
  });

  tunerList.addEventListener('input', (event) => {
    const card = event.target.closest('.rfp-tuner-card');
    const tuner = tunerForCard(card);
    if (!tuner || !PROFILES[tuner.profileKey].custom) return;
    syncCustomTunerFromCard(card, tuner);
    updateTunerSpec(card, tuner, tunerConfigs.indexOf(tuner));
  });

  form.addEventListener('submit', (event) => {
    event.preventDefault();
    calculate();
  });

  root.querySelector('.rfp-example').addEventListener('click', () => {
    tunerConfigs = [createTuner('rtl-r8x'), createTuner('rtl-r8x')];
    renderTunerPool();
    engineSelect.value = 'vce';
    frequenciesInput.value = '851.0125\n851.2625\n851.7750 @ 6.25k\n852.1250\n852.6125\n854.0875';
    calculate();
  });

  renderTunerPool();
  const checks = modelChecks();
  const passed = checks.every(Boolean);
  selfTest.textContent = passed ? '✓ Built-in model checks passed' : '× Calculator model check failed';
  selfTest.classList.add(passed ? 'pass' : 'fail');
  if (!passed) console.error('sdrtrunk RF planner self-test failed', checks);
  return root;
}

export { createPlanner, parseChannels, channelizerGrid, isValidCenter, findCenter, planTunerPool, modelChecks };
