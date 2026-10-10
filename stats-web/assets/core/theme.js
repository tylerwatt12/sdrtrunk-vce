// Theme colors are semantic CSS tokens; operational status colors stay independent.
export const APPEARANCE_SCHEMES = Object.freeze([
  Object.freeze({ name: 'Teal', hue: null, previewHue: 185 }),
  Object.freeze({ name: 'Ocean', hue: 220, previewHue: 220 }),
  Object.freeze({ name: 'Blue', hue: 250, previewHue: 250 }),
  Object.freeze({ name: 'Indigo', hue: 285, previewHue: 285 }),
  Object.freeze({ name: 'Plum', hue: 315, previewHue: 315 }),
  Object.freeze({ name: 'Rose', hue: 350, previewHue: 350 }),
  Object.freeze({ name: 'Amber', hue: 75, previewHue: 75 }),
  Object.freeze({ name: 'Forest', hue: 145, previewHue: 145 })
]);

export function applyThemeHue(hue, root = document.documentElement) {
  if (Number.isInteger(hue) && hue >= 0 && hue <= 359) {
    root.style.setProperty('--theme-hue', String(hue));
    root.setAttribute('data-custom-hue', '');
  } else {
    root.removeAttribute('data-custom-hue');
    root.style.removeProperty('--theme-hue');
  }
}
