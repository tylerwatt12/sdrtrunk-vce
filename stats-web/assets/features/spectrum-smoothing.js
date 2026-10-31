const SPECTRUM_SMOOTHING_ALPHA = 0.25;

/** Average accepted spectrum frames in dB; callers own source-change and tuning resets. */
export function smoothSpectrumValues(previous, values) {
  if (!(previous instanceof Float32Array) || previous.length !== values.length || previous === values)
    return Float32Array.from(values);
  for (let index = 0; index < values.length; index += 1) {
    const current = values[index];
    const before = previous[index];
    previous[index] = Number.isFinite(current) && Number.isFinite(before) ?
      before + SPECTRUM_SMOOTHING_ALPHA * (current - before) : current;
  }
  return previous;
}
