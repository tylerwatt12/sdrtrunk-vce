// Saved RGB stays unchanged. Only its display hue is grouped into the shared palette.
export function aliasColorFamily(value) {
  if (!Number.isInteger(value) || value < -2147483648 || value > 2147483647) return null;
  const rgb = [16, 8, 0].map(shift => ((value >>> shift) & 255) / 255);
  const [r, g, b] = rgb;
  const maximum = Math.max(...rgb), minimum = Math.min(...rgb), chroma = maximum - minimum;
  if (chroma < 0.06) return null;
  const lightness = (maximum + minimum) / 2;
  if (chroma / (1 - Math.abs(2 * lightness - 1)) < 0.14) return null;
  const sector = maximum === r ? ((g - b) / chroma) % 6 :
    maximum === g ? (b - r) / chroma + 2 : (r - g) / chroma + 4;
  return Math.round(((sector * 60 + 360) % 360) / 30) % 12;
}

export function applyAliasColor(element, color) {
  if (!element) return element;
  const family = aliasColorFamily(color);
  element.classList.toggle('ui-alias-color', family !== null);
  if (family === null) delete element.dataset.aliasFamily;
  else element.dataset.aliasFamily = String(family);
  return element;
}

// A shared background requires agreement; conflicting aliases retain individual name colors.
export function commonAliasColor(references) {
  if (!references?.length) return null;
  const color = references[0].color, family = aliasColorFamily(color);
  return family !== null && references.every(reference => aliasColorFamily(reference.color) === family) ? color : null;
}

export function callAliasColor(call, side) {
  const keys = side === 'source' ? ['source_alias_color', 'radio_alias_color'] :
    ['target_alias_color', 'talkgroup_alias_color', 'group_alias_color', 'destination_radio_alias_color'];
  const key = keys.find(key => Object.hasOwn(call || {}, key));
  return key ? call[key] : null;
}
