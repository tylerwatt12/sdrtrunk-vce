'use strict';

const { expect, test } = require('@playwright/test');
test.setTimeout(12_000);
const { openSettingsApp: openAppearanceApp, session } = require('./fixtures/my-settings-app.cjs');

const schemes = [
  ['Teal', null], ['Ocean', 220], ['Blue', 250], ['Indigo', 285],
  ['Plum', 315], ['Rose', 350], ['Amber', 75], ['Forest', 145]
];
const schemeChoice = (dialog, name) => dialog.getByRole('radio', { name, exact: true });
const chooseScheme = (dialog, name) => schemeChoice(dialog, name).check();
async function openEditor(page) {
  const dialog = page.getByRole('form', { name: 'Appearance', exact: true });
  await expect(dialog).toBeVisible();
  return dialog;
}

async function expectClean(editor) {
  await expect(editor).toBeVisible();
  await expect(editor.getByRole('button', { name: 'Save changes', exact: true })).toBeDisabled();
  await expect(editor).not.toHaveClass(/is-dirty/);
}

async function colors(page) {
  return page.evaluate(() => {
    let quality = document.querySelector('#theme-hue-scanner-quality');
    if (!quality) {
      quality = document.createElement('span');
      quality.id = 'theme-hue-scanner-quality';
      quality.className = 'scanner-quality-bars ui-audio-quality-meter';
      quality.setAttribute('role', 'img');
      quality.setAttribute('aria-label', 'Fixture voice quality 100 percent');
      const bar = document.createElement('i');
      bar.className = 'active';
      quality.append(bar);
      document.querySelector('#content').append(quality);
    }
    const qualityBar = getComputedStyle(quality.querySelector('i.active'));
    return {
      header: getComputedStyle(document.querySelector('.topbar')).backgroundColor,
      page: getComputedStyle(document.body).backgroundColor,
      surface: getComputedStyle(document.querySelector('.ui-settings-panel')).backgroundColor,
      status: getComputedStyle(document.querySelector('#receiver-health-indicator')).color,
      quality: qualityBar.backgroundColor, qualityBorder: qualityBar.borderColor,
      success: getComputedStyle(document.documentElement).getPropertyValue('--success').trim(),
      warning: getComputedStyle(document.documentElement).getPropertyValue('--warning').trim(),
      danger: getComputedStyle(document.documentElement).getPropertyValue('--danger').trim()
    };
  });
}

async function discardPreview(page, dialog) {
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  const confirmation = page.getByRole('alertdialog', { name: 'Discard changes?', exact: true });
  await expect(confirmation).toBeVisible();
  await expect(confirmation.getByRole('button', { name: 'Keep editing', exact: true })).toBeFocused();
  await confirmation.getByRole('button', { name: 'Discard changes', exact: true }).click();
  await expectClean(dialog);
}

for (const theme of ['light', 'dark']) {
  for (const [size, viewport] of [['desktop', { width: 1280, height: 900 }],
    ['phone', { width: 320, height: 740 }]]) {
    test(`appearance preview preserves status colors and original palette in ${theme} on ${size}`, async ({ page }) => {
      await page.setViewportSize(viewport);
      const state = await openAppearanceApp(page, { theme });
      const original = await colors(page);
      expect(original).toMatchObject(theme === 'light' ? {
        page: 'rgb(237, 241, 244)', header: 'rgb(32, 42, 51)', surface: 'rgb(255, 255, 255)'
      } : {
        page: 'rgb(16, 22, 28)', header: 'rgb(9, 14, 19)', surface: 'rgb(23, 32, 41)'
      });
      const dialog = await openEditor(page);
      const save = dialog.getByRole('button', { name: 'Save changes', exact: true });
      await expect(dialog.getByRole('radiogroup', { name: 'Color scheme', exact: true })).toBeVisible();
      await expect(dialog.getByRole('radiogroup', { name: 'Color scheme', exact: true }).getByRole('radio')).toHaveCount(8);
      for (const [name] of schemes) await expect(schemeChoice(dialog, name)).toBeVisible();
      const swatches = await dialog.locator('.ui-palette-swatch').evaluateAll((elements) => {
        const canvas = document.createElement('canvas');
        canvas.width = canvas.height = 1;
        const context = canvas.getContext('2d', { willReadFrequently: true });
        return elements.map((element) => {
          const background = getComputedStyle(element).backgroundColor;
          context.clearRect(0, 0, 1, 1);
          context.fillStyle = background;
          context.fillRect(0, 0, 1, 1);
          return { background, alpha: context.getImageData(0, 0, 1, 1).data[3] };
        });
      });
      expect(new Set(swatches.map(({ background }) => background)).size).toBe(8);
      expect(swatches.map(({ alpha }) => alpha)).toEqual(Array(8).fill(255));
      await expect(save).toBeDisabled();
      await expect(schemeChoice(dialog, 'Teal')).toBeChecked();
      await schemeChoice(dialog, 'Teal').focus();
      await page.keyboard.press('ArrowRight');
      await expect(schemeChoice(dialog, 'Ocean')).toBeChecked();
      await expect(page.locator('html')).toHaveAttribute('data-custom-hue', '');
      await expect(save).toBeEnabled();
      expect((await colors(page)).header).not.toBe(original.header);
      for (const [name] of schemes.slice(1)) {
        await chooseScheme(dialog, name);
        await expect(schemeChoice(dialog, name)).toBeChecked();
        const preview = await colors(page);
        expect(preview.status).toBe(original.status);
        expect(preview.quality).toBe(original.quality);
        expect(preview.qualityBorder).toBe(original.qualityBorder);
        expect(preview.success).toBe(original.success);
        expect(preview.warning).toBe(original.warning);
        expect(preview.danger).toBe(original.danger);
      }
      await expect(dialog).toHaveScreenshot(`appearance-custom-${theme}-${size}.png`);
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.width);
      await chooseScheme(dialog, 'Teal');
      await expect(schemeChoice(dialog, 'Teal')).toBeChecked();
      await expect(save).toBeDisabled();
      expect(await colors(page)).toEqual(original);
      await chooseScheme(dialog, 'Ocean');
      await discardPreview(page, dialog);
      expect(await colors(page)).toEqual(original);
      expect(state.writes).toHaveLength(0);
      await expect(schemeChoice(dialog, 'Teal')).toBeChecked();
    });
  }
}

test('saved hue persists across reload and follows the signed-in account', async ({ page }) => {
  const state = await openAppearanceApp(page);
  let dialog = await openEditor(page);
  await chooseScheme(dialog, 'Ocean');
  await dialog.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expectClean(dialog);
  expect(state.writes[0].preferences.appearance).toEqual({ theme: 'light', hue: 220 });
  expect(state.writes[0].revision).toBe('"1"');
  const saved = await colors(page);
  await page.reload();
  await expect(page.getByRole('heading', { name: 'My Settings', exact: true })).toBeVisible();
  expect((await colors(page)).header).toBe(saved.header);
  dialog = await openEditor(page);
  await expect(schemeChoice(dialog, 'Ocean')).toBeChecked();
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  await page.getByRole('button', { name: 'Sign Out', exact: true }).click();
  await expect(page.locator('#auth-action')).toHaveText('Sign In');
  await expect(page.locator('html[data-custom-hue]')).toHaveCount(0);
  await page.locator('#auth-action').click();
  const login = page.getByRole('dialog', { name: 'Sign In', exact: true });
  await login.getByRole('textbox', { name: 'Username', exact: true }).fill('second');
  await login.getByLabel('Password', { exact: true }).fill('fixture-password');
  await login.getByRole('button', { name: 'Sign In', exact: true }).click();
  await expect(login).toBeHidden();
  await page.getByRole('button', { name: 'Open My Settings for second', exact: true }).click();
  dialog = await openEditor(page);
  await expect(schemeChoice(dialog, 'Indigo')).toBeChecked();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await chooseScheme(dialog, 'Teal');
  await dialog.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expectClean(dialog);
  expect(state.writes[1].username).toBe('second');
  expect(state.profiles.second.appearance).toEqual({ theme: 'dark', hue: null });
  expect(state.profiles.listener.appearance).toEqual({ theme: 'light', hue: 220 });
});

test('appearance save blocks dismissal and duplicate submits, preserving the draft for retry', async ({ page }) => {
  const state = await openAppearanceApp(page, { holdSaves: true });
  const dialog = await openEditor(page);
  const indigo = schemeChoice(dialog, 'Indigo');
  const save = dialog.getByRole('button', { name: 'Save changes', exact: true });
  await chooseScheme(dialog, 'Indigo');
  const preview = await colors(page);
  await save.click();
  await expect.poll(() => state.pending.length).toBe(1);
  await expect(dialog).toHaveAttribute('aria-busy', 'true');
  for (const [name] of schemes) await expect(schemeChoice(dialog, name)).toBeDisabled();
  await expect(dialog.getByRole('button', { name: 'Cancel', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Source names', exact: true })).toBeDisabled();
  await page.keyboard.press('Escape');
  await expect(dialog).toBeVisible();
  await dialog.evaluate((form) => form.dispatchEvent(new Event('submit', { cancelable: true })));
  expect(state.writes).toHaveLength(1);
  state.pending.shift()(503);
  await expect(dialog.getByRole('alert')).toHaveText('Your settings could not be saved. Your choices are still here. Try saving again.');
  await expect(indigo).toBeEnabled();
  await expect(indigo).toBeChecked();
  expect(await colors(page)).toEqual(preview);
  expect(state.profiles.listener.appearance.hue).toBeNull();
  await save.click();
  await expect.poll(() => state.pending.length).toBe(1);
  state.pending.shift()(200);
  await expectClean(dialog);
  expect(state.writes).toHaveLength(2);
  expect(state.profiles.listener.appearance.hue).toBe(285);
  expect(state.writes[1].revision).toBe('"1"');
});

test('saved custom colors remain unchanged until a choice, and conflicts load the latest saved colors', async ({ page }) => {
  const state = await openAppearanceApp(page, { hue: 215, holdSaves: true });
  const original = await colors(page);
  let dialog = await openEditor(page);
  await expect(dialog.getByRole('radiogroup', { name: 'Color scheme', exact: true }).getByRole('radio', { checked: true })).toHaveCount(0);
  await expect(dialog.getByText('Saved custom color. Choose a scheme to replace it.', { exact: true })).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Save changes', exact: true })).toBeDisabled();
  expect(await colors(page)).toEqual(original);
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expectClean(dialog);
  expect(state.writes).toHaveLength(0);
  expect(state.profiles.listener.appearance.hue).toBe(215);
  expect(await colors(page)).toEqual(original);
  dialog = await openEditor(page);
  const save = dialog.getByRole('button', { name: 'Save changes', exact: true });
  await chooseScheme(dialog, 'Indigo');
  await save.click();
  await expect.poll(() => state.pending.length).toBe(1);
  state.profiles.listener.appearance.hue = 35;
  state.revisions.listener = 4;
  state.pending.shift()(409);
  await expect(dialog.locator('.admin-form-message')).toHaveText('These settings changed in another session. The saved values were loaded.');
  await expect(dialog.getByRole('radiogroup', { name: 'Color scheme', exact: true }).getByRole('radio', { checked: true })).toHaveCount(0);
  await expect(dialog.getByText('Saved custom color. Choose a scheme to replace it.', { exact: true })).toBeVisible();
  await expect(save).toBeDisabled();
  await chooseScheme(dialog, 'Ocean');
  await save.click();
  await expect.poll(() => state.pending.length).toBe(1);
  expect(state.writes[1].revision).toBe('"4"');
  state.pending.shift()(200);
  await expectClean(dialog);
  expect(state.profiles.listener.appearance.hue).toBe(220);
});

test('an account change during save clears the preview and leaves the next account untouched', async ({ page }) => {
  await page.clock.install();
  const state = await openAppearanceApp(page, { holdSaves: true });
  const dialog = await openEditor(page);
  await chooseScheme(dialog, 'Amber');
  await dialog.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect.poll(() => state.pending.length).toBe(1);
  state.session = session('second');
  await page.clock.runFor(10_100);
  await expect(page.getByRole('button', { name: 'Open My Settings for second', exact: true })).toBeVisible();
  state.pending.shift()(200);
  await expectClean(dialog);
  expect(state.profiles.second.appearance).toEqual({ theme: 'dark', hue: 285 });
  expect(state.writes).toHaveLength(1);
  const secondDialog = await openEditor(page);
  await expect(schemeChoice(secondDialog, 'Indigo')).toBeChecked();
});

test('an account change closes the former account editor and its discard confirmation', async ({ page }) => {
  await page.clock.install();
  const state = await openAppearanceApp(page);
  const dialog = await openEditor(page);
  await chooseScheme(dialog, 'Amber');
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  const confirmation = page.getByRole('alertdialog', { name: 'Discard changes?', exact: true });
  await expect(confirmation).toBeVisible();
  await expect(confirmation.getByRole('button', { name: 'Keep editing', exact: true })).toBeFocused();
  state.session = session('second');
  await page.clock.runFor(10_100);
  await expect(page.getByRole('button', { name: 'Open My Settings for second', exact: true })).toBeVisible();
  await expectClean(dialog);
  await expect(confirmation).toBeHidden();
  expect(state.writes).toHaveLength(0);
  expect(state.profiles.listener.appearance.hue).toBeNull();
  const secondDialog = await openEditor(page);
  await expect(schemeChoice(secondDialog, 'Indigo')).toBeChecked();
  await expect(secondDialog.getByRole('button', { name: 'Save changes', exact: true })).toBeDisabled();
});

test('failed conflict reload retains the hue draft and a save retry loads the current revision', async ({ page }) => {
  const state = await openAppearanceApp(page, { hue: 215, holdSaves: true });
  const dialog = await openEditor(page);
  const indigo = schemeChoice(dialog, 'Indigo');
  const save = dialog.getByRole('button', { name: 'Save changes', exact: true });
  await chooseScheme(dialog, 'Indigo');
  const draft = await colors(page);
  await save.click();
  await expect.poll(() => state.pending.length).toBe(1);
  state.profiles.listener.appearance.hue = 35;
  state.revisions.listener = 4;
  state.preferenceLoadFailures = 1;
  state.pending.shift()(409);
  await expect(dialog.getByRole('alert')).toHaveText('These settings changed in another session, but the saved values could not be loaded. Try saving again or reload the page.');
  await expect(indigo).toBeChecked();
  await expect(save).toBeEnabled();
  expect(await colors(page)).toEqual(draft);
  await save.click();
  await expect.poll(() => state.pending.length).toBe(1);
  expect(state.writes[1].revision).toBe('"1"');
  state.pending.shift()(409);
  await expect(dialog.locator('.admin-form-message')).toHaveText('These settings changed in another session. The saved values were loaded.');
  await expect(dialog.getByRole('radiogroup', { name: 'Color scheme', exact: true }).getByRole('radio', { checked: true })).toHaveCount(0);
  await expect(dialog.getByText('Saved custom color. Choose a scheme to replace it.', { exact: true })).toBeVisible();
  await expect(save).toBeDisabled();
  await chooseScheme(dialog, 'Indigo');
  await save.click();
  await expect.poll(() => state.pending.length).toBe(1);
  expect(state.writes[2].revision).toBe('"4"');
  state.pending.shift()(200);
  await expectClean(dialog);
  expect(state.profiles.listener.appearance.hue).toBe(285);
});

for (const theme of ['light', 'dark']) {
  test(`custom hues keep readable text and semantic status colors throughout the ${theme} palette`, async ({ page }) => {
    await openAppearanceApp(page, { theme });
    await colors(page);
    const result = await page.evaluate(async () => {
      const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
      const root = document.documentElement;
      const button = document.createElement('button');
      button.className = 'ui-button ui-button-primary';
      const link = document.createElement('a');
      link.href = '#';
      const tile = document.createElement('span');
      tile.className = 'ui-icon-tile ui-icon-tile-secondary';
      const primaryToken = document.createElement('span');
      primaryToken.style.color = 'var(--accent)';
      const secondaryToken = document.createElement('span');
      secondaryToken.style.color = 'var(--accent-secondary)';
      const samples = document.createElement('div');
      samples.style.display = 'grid';
      samples.style.gap = '12px';
      const pageSample = document.createElement('div');
      const panelSample = document.createElement('section');
      panelSample.className = 'ui-surface';
      const tileSample = document.createElement('div');
      tileSample.className = 'ui-surface ui-surface-soft';
      const accentSample = document.createElement('div');
      accentSample.style.backgroundColor = 'var(--accent-soft)';
      const sectionHeaderSample = document.createElement('div');
      sectionHeaderSample.className = 'ui-catalog-rail-header';
      const sectionTitleSample = document.createElement('div');
      sectionTitleSample.className = 'ui-section-title';
      panelSample.append(tileSample, sectionHeaderSample, sectionTitleSample);
      samples.append(pageSample, panelSample, accentSample);
      const textSamples = Object.entries({ page: pageSample, panel: panelSample, tile: tileSample,
        accentSoft: accentSample, sectionHeader: sectionHeaderSample, sectionTitle: sectionTitleSample })
        .map(([name, owner]) => {
          const text = document.createElement('span');
          text.className = 'muted';
          text.textContent = `${name} metadata`;
          owner.append(text);
          return { name, text };
        });
      const inputSamples = Object.entries({ page: pageSample, panel: panelSample, tile: tileSample })
        .map(([name, owner]) => {
          const input = document.createElement('input');
          input.className = 'ui-input';
          input.setAttribute('aria-label', `${name} boundary sample`);
          input.value = 'Input value';
          // A synchronous hue sweep must inspect the settled CSS palette.
          input.style.transition = 'none';
          owner.append(input);
          return { name, input };
        });
      button.style.transition = 'none';
      document.querySelector('#content').append(button, link, tile, primaryToken, secondaryToken, samples);
      const canvas = document.createElement('canvas');
      canvas.width = canvas.height = 1;
      const context = canvas.getContext('2d', { willReadFrequently: true });
      const colorCache = new Map();
      const rgba = (color) => {
        if (!colorCache.has(color)) {
          context.clearRect(0, 0, 1, 1);
          context.fillStyle = color;
          context.fillRect(0, 0, 1, 1);
          colorCache.set(color, [...context.getImageData(0, 0, 1, 1).data]);
        }
        return colorCache.get(color);
      };
      const opaqueBackground = (element) => {
        for (let owner = element; owner; owner = owner.parentElement) {
          const background = getComputedStyle(owner).backgroundColor;
          const alpha = rgba(background)[3];
          if (alpha === 255) return background;
          if (alpha !== 0) throw new Error('Palette samples require an opaque painted owner.');
        }
        throw new Error('Palette sample has no painted owner.');
      };
      const luminance = (color) => {
        const values = rgba(color).slice(0, 3).map((channel) => {
          const value = channel / 255;
          return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
        });
        return 0.2126 * values[0] + 0.7152 * values[1] + 0.0722 * values[2];
      };
      const contrast = (foreground, background) => {
        const values = [luminance(foreground), luminance(background)].sort((left, right) => right - left);
        return (values[0] + 0.05) / (values[1] + 0.05);
      };
      const original = getComputedStyle(root).getPropertyValue('--bg').trim();
      const status = getComputedStyle(document.querySelector('#receiver-health-indicator')).color;
      const qualityBar = document.querySelector('#theme-hue-scanner-quality i.active');
      const quality = getComputedStyle(qualityBar).backgroundColor;
      const qualityBorder = getComputedStyle(qualityBar).borderColor;
      const minima = { general: Infinity, link: Infinity, primary: Infinity };
      const mutedMinima = Object.fromEntries(textSamples.map(({ name }) => [name, Infinity]));
      const inputMinima = Object.fromEntries(inputSamples.map(({ name }) => [name, Infinity]));
      let panelPageMinimum = Infinity;
      const worstCases = {};
      const keepMinimum = (target, name, ratio, hue, foreground, background, kind) => {
        if (ratio >= target[name]) return;
        target[name] = ratio;
        worstCases[`${kind}-${name}`] = { hue, foreground, background, ratio };
      };
      const statusChanges = [];
      const primaryColors = new Set();
      const secondaryColors = new Set();
      const paletteMismatches = [];
      for (const hue of [null, ...Array.from({ length: 360 }, (_, value) => value)]) {
        applyThemeHue(hue);
        const palette = getComputedStyle(root);
        const token = (name) => palette.getPropertyValue(name).trim();
        const primary = getComputedStyle(button);
        const linkColor = getComputedStyle(link).color;
        const tileColor = getComputedStyle(tile).color;
        primaryColors.add(linkColor);
        secondaryColors.add(tileColor);
        // The original palette deliberately uses its blue secondary for links;
        // custom hues coordinate links with the primary accent instead.
        if (tileColor !== getComputedStyle(secondaryToken).color ||
            (hue !== null && (linkColor !== getComputedStyle(primaryToken).color || tileColor === linkColor))) {
          paletteMismatches.push(hue);
        }
        const pairs = {
          general: [token('--ink'), token('--bg')],
          link: [linkColor, token('--surface')],
          primary: [primary.color, primary.backgroundColor]
        };
        for (const [name, pair] of Object.entries(pairs)) minima[name] = Math.min(minima[name], contrast(...pair));
        for (const { name, text } of textSamples) {
          const foreground = getComputedStyle(text).color;
          const background = opaqueBackground(text);
          keepMinimum(mutedMinima, name, contrast(foreground, background), hue, foreground, background, 'muted');
        }
        for (const { name, input } of inputSamples) {
          const css = getComputedStyle(input);
          const outside = opaqueBackground(input.parentElement);
          const inside = opaqueBackground(input);
          for (const edge of ['Top', 'Right', 'Bottom', 'Left']) {
            const border = css[`border${edge}Color`];
            const visible = parseFloat(css[`border${edge}Width`]) > 0 &&
              !['none', 'hidden'].includes(css[`border${edge}Style`]) && rgba(border)[3] === 255;
            const ratio = Math.max(contrast(inside, outside), visible ? contrast(border, outside) : 1);
            keepMinimum(inputMinima, name, ratio, hue, border, outside, 'input');
          }
        }
        panelPageMinimum = Math.min(panelPageMinimum,
          contrast(opaqueBackground(panelSample), opaqueBackground(panelSample.parentElement)));
        if (getComputedStyle(document.querySelector('#receiver-health-indicator')).color !== status ||
            getComputedStyle(qualityBar).backgroundColor !== quality ||
            getComputedStyle(qualityBar).borderColor !== qualityBorder) statusChanges.push(hue);
      }
      button.remove();
      link.remove();
      tile.remove();
      primaryToken.remove();
      secondaryToken.remove();
      samples.remove();
      applyThemeHue(null);
      return { original, minima, mutedMinima, inputMinima, panelPageMinimum, worstCases,
        statusChanges, primaryColors: primaryColors.size,
        secondaryColors: secondaryColors.size, paletteMismatches };
    });
    expect(result.original).toBe(theme === 'light' ? '#edf1f4' : '#10161c');
    expect(result.statusChanges).toEqual([]);
    expect(result.primaryColors).toBeGreaterThan(12);
    expect(result.secondaryColors).toBeGreaterThan(12);
    expect(result.paletteMismatches).toEqual([]);
    for (const [name, minimum] of Object.entries(result.minima)) {
      expect(minimum, `${theme} ${name} must stay readable at every hue`).toBeGreaterThanOrEqual(4.5);
    }
    for (const [name, minimum] of Object.entries(result.mutedMinima)) {
      expect(minimum, `${theme} muted ${name} text: ${JSON.stringify(result.worstCases[`muted-${name}`])}`)
        .toBeGreaterThanOrEqual(4.5);
    }
    for (const [name, minimum] of Object.entries(result.inputMinima)) {
      expect(minimum, `${theme} input boundary on ${name}: ${JSON.stringify(result.worstCases[`input-${name}`])}`)
        .toBeGreaterThanOrEqual(3);
    }
    // This is the project's surface-grouping floor, not a WCAG contrast rule.
    expect(result.panelPageMinimum, `${theme} standalone panels must remain distinct from the page`)
      .toBeGreaterThanOrEqual(1.10);
    await page.goto(`/design-system.html?theme=${theme}&view=radio-directory&hue=335`);
    await expect(page.locator('.radio-directory-conventional-card').first()).toBeVisible();
    await expect(page.locator('body')).toHaveScreenshot(`coordinated-directory-${theme}.png`, { fullPage: true });
  });
}
