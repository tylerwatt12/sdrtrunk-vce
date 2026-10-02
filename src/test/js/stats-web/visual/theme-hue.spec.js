'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

let defaults;
test.beforeAll(async () => {
  defaults = (await import(pathToFileURL(path.resolve(__dirname,
    '../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults;
});

function session(username = 'listener') {
  return { configured: true, authenticated: username !== null, username,
    tier: username === null ? 'public' : 'user', primary: false,
    csrf_token: username === null ? null : 'fixture-only-token', capabilities: {
      'web-access': true, dashboard: true, credits: true, 'user-settings': username !== null,
      'receiver-health': username !== null, radio: true, live: true,
      'call-audio': false, recordings: false, 'admin-settings': false } };
}

async function openAppearanceApp(page, options = {}) {
  const listener = structuredClone(defaults);
  listener.appearance = { theme: options.theme || 'light', hue: options.hue ?? null };
  const second = structuredClone(defaults);
  second.appearance = { theme: 'dark', hue: 285 };
  const state = { session: session(), profiles: { listener, second }, revisions: { listener: 1, second: 1 },
    writes: [], pending: [], holdSaves: options.holdSaves === true, preferenceLoadFailures: 0 };
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    const data = (value) => route.fulfill({ json: { data: value } });
    if (pathname === '/api/v1/auth/session') return data(state.session);
    if (pathname === '/api/v1/auth/logout') {
      state.session = session(null);
      return data(state.session);
    }
    if (pathname === '/api/v1/auth/login') {
      state.session = session(request.postDataJSON().username);
      return data(state.session);
    }
    if (pathname === '/api/v1/me/preferences') {
      const username = state.session.username;
      if (request.method() === 'GET' && state.preferenceLoadFailures > 0) {
        state.preferenceLoadFailures--;
        return route.fulfill({ status: 503, json: { error: { message: 'Load unavailable' } } });
      }
      if (request.method() === 'PUT') {
        const preferences = request.postDataJSON();
        state.writes.push({ username, preferences, revision: request.headers()['if-match'] });
        const status = state.holdSaves ? await new Promise((resolve) => state.pending.push(resolve)) : 200;
        if (status !== 200) return route.fulfill({ status, json: { error: { message: 'Save unavailable' } } });
        state.profiles[username] = preferences;
        state.revisions[username]++;
      }
      return route.fulfill({ json: { revision: state.revisions[username], preferences: state.profiles[username] } });
    }
    if (pathname === '/api/v1/receiver-health') return data({ generated_at_ms: Date.now(),
      summary: { active_count: 0, warning_count: 0, critical_count: 0 }, active: [], resolved: [], measurements: [] });
    if (pathname === '/api/v1/status') return data({});
    if (pathname === '/api/v1/scan-lists') return data({ revision: 1, scan_lists: [] });
    if (pathname === '/api/v1/channel-catalog') return data({ revision: 1, channels: [] });
    if (pathname === '/api/v1/dashboard') return data({ call_activity: { totals: {}, buckets: [] },
      top_destinations: [], top_sources: [], source_activity_24h: [] });
    return data({ rows: [], limit: 25, offset: 0, has_more: false, next_offset: null, total_count: 0 });
  });
  await page.goto('/app.html?view=settings');
  await expect(page.getByRole('heading', { name: 'My Settings', exact: true })).toBeVisible();
  await expect(page.locator('#receiver-health-indicator')).toHaveClass(/receiver-health-healthy/);
  return state;
}

async function openEditor(page) {
  await page.getByRole('button', { name: 'Change Appearance', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Appearance', exact: true });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('slider', { name: 'Hue', exact: true })).toBeFocused();
  return dialog;
}

async function colors(page) {
  return page.evaluate(() => {
    let quality = document.querySelector('#theme-hue-scanner-quality');
    if (!quality) {
      quality = document.createElement('span');
      quality.id = 'theme-hue-scanner-quality';
      quality.className = 'scanner-quality-bars';
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
      surface: getComputedStyle(document.querySelector('.settings-card')).backgroundColor,
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
  const confirmation = page.getByRole('alertdialog', { name: 'Discard unsaved changes', exact: true });
  await expect(confirmation).toBeVisible();
  await expect(confirmation.getByRole('button', { name: 'Keep editing', exact: true })).toBeFocused();
  await confirmation.getByRole('button', { name: 'Discard changes', exact: true }).click();
  await expect(dialog).toBeHidden();
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
      const hue = dialog.getByRole('slider', { name: 'Hue', exact: true });
      const save = dialog.getByRole('button', { name: 'Save appearance', exact: true });
      await expect(save).toBeDisabled();
      await expect(dialog.locator('output')).toHaveText('Original colors');
      await hue.press('End');
      await expect(hue).toHaveValue('359');
      await expect(hue).toHaveAttribute('aria-valuetext', '359 degrees');
      await expect(save).toBeEnabled();
      const red = await colors(page);
      expect(red.header).not.toBe(original.header);
      expect(red.status).toBe(original.status);
      expect(red.quality).toBe(original.quality);
      expect(red.qualityBorder).toBe(original.qualityBorder);
      for (const value of ['0', '120', '215', '285']) {
        await hue.fill(value);
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
      await dialog.getByRole('button', { name: 'Use original colors', exact: true }).click();
      await expect(dialog.locator('output')).toHaveText('Original colors');
      await expect(save).toBeDisabled();
      expect(await colors(page)).toEqual(original);
      await hue.fill('215');
      await discardPreview(page, dialog);
      expect(await colors(page)).toEqual(original);
      expect(state.writes).toHaveLength(0);
      await expect(page.getByRole('button', { name: 'Change Appearance', exact: true })).toBeFocused();
    });
  }
}

test('saved hue persists across reload and follows the signed-in account', async ({ page }) => {
  const state = await openAppearanceApp(page);
  let dialog = await openEditor(page);
  await dialog.getByRole('slider', { name: 'Hue', exact: true }).fill('215');
  await dialog.getByRole('button', { name: 'Save appearance', exact: true }).click();
  await expect(dialog).toBeHidden();
  expect(state.writes[0].preferences.appearance).toEqual({ theme: 'light', hue: 215 });
  expect(state.writes[0].revision).toBe('"1"');
  const saved = await colors(page);
  await page.reload();
  await expect(page.getByRole('heading', { name: 'My Settings', exact: true })).toBeVisible();
  expect((await colors(page)).header).toBe(saved.header);
  dialog = await openEditor(page);
  await expect(dialog.getByRole('slider', { name: 'Hue', exact: true })).toHaveValue('215');
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
  await expect(dialog.getByRole('slider', { name: 'Hue', exact: true })).toHaveValue('285');
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await dialog.getByRole('button', { name: 'Use original colors', exact: true }).click();
  await dialog.getByRole('button', { name: 'Save appearance', exact: true }).click();
  await expect(dialog).toBeHidden();
  expect(state.writes[1].username).toBe('second');
  expect(state.profiles.second.appearance).toEqual({ theme: 'dark', hue: null });
  expect(state.profiles.listener.appearance).toEqual({ theme: 'light', hue: 215 });
});

test('appearance save blocks dismissal and duplicate submits, preserving the draft for retry', async ({ page }) => {
  const state = await openAppearanceApp(page, { holdSaves: true });
  const dialog = await openEditor(page);
  const hue = dialog.getByRole('slider', { name: 'Hue', exact: true });
  const save = dialog.getByRole('button', { name: 'Save appearance', exact: true });
  await hue.fill('285');
  const preview = await colors(page);
  await save.click();
  await expect.poll(() => state.pending.length).toBe(1);
  await expect(dialog).toHaveAttribute('aria-busy', 'true');
  await expect(hue).toBeDisabled();
  await expect(dialog.getByRole('button', { name: 'Cancel', exact: true })).toBeDisabled();
  await expect(dialog.getByRole('button', { name: 'Use original colors', exact: true })).toBeDisabled();
  await page.keyboard.press('Escape');
  await expect(dialog).toBeVisible();
  await dialog.locator('form').evaluate((form) => form.dispatchEvent(new Event('submit', { cancelable: true })));
  expect(state.writes).toHaveLength(1);
  state.pending.shift()(503);
  await expect(dialog.getByRole('alert')).toHaveText('Appearance could not be saved. Your hue is still here. Try saving again.');
  await expect(hue).toBeEnabled();
  await expect(hue).toHaveValue('285');
  expect(await colors(page)).toEqual(preview);
  expect(state.profiles.listener.appearance.hue).toBeNull();
  await save.click();
  await expect.poll(() => state.pending.length).toBe(1);
  state.pending.shift()(200);
  await expect(dialog).toBeHidden();
  expect(state.writes).toHaveLength(2);
  expect(state.profiles.listener.appearance.hue).toBe(285);
  expect(state.writes[1].revision).toBe('"1"');
});

test('conflicting hue save loads the latest saved hue before another edit', async ({ page }) => {
  const state = await openAppearanceApp(page, { hue: 215, holdSaves: true });
  const dialog = await openEditor(page);
  const hue = dialog.getByRole('slider', { name: 'Hue', exact: true });
  const save = dialog.getByRole('button', { name: 'Save appearance', exact: true });
  await hue.fill('285');
  await save.click();
  await expect.poll(() => state.pending.length).toBe(1);
  state.profiles.listener.appearance.hue = 35;
  state.revisions.listener = 4;
  state.pending.shift()(409);
  await expect(dialog.locator('.admin-form-message')).toHaveText('Appearance changed in another session. The saved hue was loaded.');
  await expect(hue).toHaveValue('35');
  await expect(save).toBeDisabled();
  await hue.fill('215');
  await save.click();
  await expect.poll(() => state.pending.length).toBe(1);
  expect(state.writes[1].revision).toBe('"4"');
  state.pending.shift()(200);
  await expect(dialog).toBeHidden();
  expect(state.profiles.listener.appearance.hue).toBe(215);
});

test('an account change during save clears the preview and leaves the next account untouched', async ({ page }) => {
  await page.clock.install();
  const state = await openAppearanceApp(page, { holdSaves: true });
  const dialog = await openEditor(page);
  await dialog.getByRole('slider', { name: 'Hue', exact: true }).fill('35');
  await dialog.getByRole('button', { name: 'Save appearance', exact: true }).click();
  await expect.poll(() => state.pending.length).toBe(1);
  state.session = session('second');
  await page.clock.runFor(10_100);
  await expect(page.getByRole('button', { name: 'Open My Settings for second', exact: true })).toBeVisible();
  state.pending.shift()(200);
  await expect(dialog).toBeHidden();
  expect(state.profiles.second.appearance).toEqual({ theme: 'dark', hue: 285 });
  expect(state.writes).toHaveLength(1);
  const secondDialog = await openEditor(page);
  await expect(secondDialog.getByRole('slider', { name: 'Hue', exact: true })).toHaveValue('285');
});

test('an account change closes the former account editor and its discard confirmation', async ({ page }) => {
  await page.clock.install();
  const state = await openAppearanceApp(page);
  const dialog = await openEditor(page);
  await dialog.getByRole('slider', { name: 'Hue', exact: true }).fill('35');
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  const confirmation = page.getByRole('alertdialog', { name: 'Discard unsaved changes', exact: true });
  await expect(confirmation).toBeVisible();
  await expect(confirmation.getByRole('button', { name: 'Keep editing', exact: true })).toBeFocused();
  state.session = session('second');
  await page.clock.runFor(10_100);
  await expect(page.getByRole('button', { name: 'Open My Settings for second', exact: true })).toBeVisible();
  await expect(dialog).toBeHidden();
  await expect(confirmation).toBeHidden();
  expect(state.writes).toHaveLength(0);
  expect(state.profiles.listener.appearance.hue).toBeNull();
  const secondDialog = await openEditor(page);
  await expect(secondDialog.getByRole('slider', { name: 'Hue', exact: true })).toHaveValue('285');
  await expect(secondDialog.getByRole('button', { name: 'Save appearance', exact: true })).toBeDisabled();
});

test('failed conflict reload retains the hue draft and a save retry loads the current revision', async ({ page }) => {
  const state = await openAppearanceApp(page, { hue: 215, holdSaves: true });
  const dialog = await openEditor(page);
  const hue = dialog.getByRole('slider', { name: 'Hue', exact: true });
  const save = dialog.getByRole('button', { name: 'Save appearance', exact: true });
  await hue.fill('285');
  const draft = await colors(page);
  await save.click();
  await expect.poll(() => state.pending.length).toBe(1);
  state.profiles.listener.appearance.hue = 35;
  state.revisions.listener = 4;
  state.preferenceLoadFailures = 1;
  state.pending.shift()(409);
  await expect(dialog.getByRole('alert')).toHaveText('Appearance changed in another session, but the saved hue could not be loaded. Try saving again or reload the page.');
  await expect(hue).toHaveValue('285');
  await expect(save).toBeEnabled();
  expect(await colors(page)).toEqual(draft);
  await save.click();
  await expect.poll(() => state.pending.length).toBe(1);
  expect(state.writes[1].revision).toBe('"1"');
  state.pending.shift()(409);
  await expect(dialog.locator('.admin-form-message')).toHaveText('Appearance changed in another session. The saved hue was loaded.');
  await expect(hue).toHaveValue('35');
  await expect(save).toBeDisabled();
  await hue.fill('285');
  await save.click();
  await expect.poll(() => state.pending.length).toBe(1);
  expect(state.writes[2].revision).toBe('"4"');
  state.pending.shift()(200);
  await expect(dialog).toBeHidden();
  expect(state.profiles.listener.appearance.hue).toBe(285);
});

for (const theme of ['light', 'dark']) {
  test(`custom hues keep readable text and semantic status colors throughout the ${theme} palette`, async ({ page }) => {
    await openAppearanceApp(page, { theme });
    await colors(page);
    const result = await page.evaluate(async () => {
      const { applyThemeHue } = await import('/assets/core/theme.js?v=1');
      const root = document.documentElement;
      const button = document.createElement('button');
      button.className = 'ui-button ui-button-primary';
      document.querySelector('#content').append(button);
      const canvas = document.createElement('canvas');
      canvas.width = canvas.height = 1;
      const context = canvas.getContext('2d', { willReadFrequently: true });
      const luminance = (color) => {
        context.clearRect(0, 0, 1, 1);
        context.fillStyle = color;
        context.fillRect(0, 0, 1, 1);
        const values = [...context.getImageData(0, 0, 1, 1).data].slice(0, 3).map((channel) => {
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
      const minima = { general: Infinity, muted: Infinity, link: Infinity, primary: Infinity };
      const statusChanges = [];
      for (let hue = 0; hue < 360; hue++) {
        applyThemeHue(hue);
        const palette = getComputedStyle(root);
        const token = (name) => palette.getPropertyValue(name).trim();
        const primary = getComputedStyle(button);
        const pairs = {
          general: [token('--ink'), token('--bg')],
          muted: [token('--muted'), token('--surface-2')],
          link: [token('--link'), token('--surface')],
          primary: [primary.color, primary.backgroundColor]
        };
        for (const [name, pair] of Object.entries(pairs)) minima[name] = Math.min(minima[name], contrast(...pair));
        if (getComputedStyle(document.querySelector('#receiver-health-indicator')).color !== status ||
            getComputedStyle(qualityBar).backgroundColor !== quality ||
            getComputedStyle(qualityBar).borderColor !== qualityBorder) statusChanges.push(hue);
      }
      button.remove();
      applyThemeHue(null);
      return { original, minima, statusChanges };
    });
    expect(result.original).toBe(theme === 'light' ? '#edf1f4' : '#10161c');
    expect(result.statusChanges).toEqual([]);
    for (const [name, minimum] of Object.entries(result.minima)) {
      expect(minimum, `${theme} ${name} must stay readable at every hue`).toBeGreaterThanOrEqual(4.5);
    }
  });
}
