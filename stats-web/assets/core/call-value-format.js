// Presentation only: preserve the underlying call metadata and identifier values.
export function formatHexDecimal(value, width = 0) {
  if (value === null || value === undefined || String(value).trim() === '') return '';
  let number = Number(value);
  if (!Number.isFinite(number) && typeof value === 'string' && /^[0-9a-f]+$/i.test(value)) {
    number = Number.parseInt(value, 16);
  }
  if (!Number.isSafeInteger(number) || number < 0) return String(value);
  return `0x${number.toString(16).toUpperCase().padStart(width, '0')} (${number})`;
}

export function formatVoiceQuality(value) {
  if (value === null || value === undefined || String(value).trim() === '') return '';
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 ? `${number.toFixed(2)}%` : '';
}
