'use strict';

const { test, expect } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { openAudioApp } = require('./fixtures/audio-dock-app.cjs');
test.setTimeout(12_000);
const { openSettingsApp, selectSettingsCategory } = require('./fixtures/my-settings-app.cjs');
const { presentations } = require('./fixtures/site-style-cases.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');

for (const presentation of presentations) {
  test(`focused categories keep one editor visible in ${presentation.name}`, async ({ page }) => {
    await page.setViewportSize(presentation.viewport);
    await openSettingsApp(page, { theme: presentation.theme });
    const workspace = page.locator('.ui-settings-workspace-focused');
    await expect(page.getByRole('form', { name: 'Appearance', exact: true })).toBeVisible();
    await expect(page.getByRole('form')).toHaveCount(1);
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expectNoHorizontalOverflow(page);
    await expect(workspace).toHaveScreenshot(`focused-appearance-${presentation.name}.png`);
    await selectSettingsCategory(page, 'status', 'Status icon');
    const status = page.getByRole('form', { name: 'Status icon', exact: true });
    await expect(status).toBeVisible();
    await expect(page.getByRole('form')).toHaveCount(1);
    const groups = status.locator('details');
    await expect(groups.locator('summary strong')).toHaveText([
      'Tuners and radio data', 'USB connection', 'Channel decoding'
    ]);
    await expect(groups.locator('.ui-settings-count')).toHaveText(['3 of 3 shown', '2 of 2 shown', '3 of 3 shown']);
    for (const group of await groups.all()) await expect(group).not.toHaveAttribute('open');
    await expect(status.locator('input[type="checkbox"]')).toHaveCount(8);
    await expect(workspace).toHaveScreenshot(`focused-status-${presentation.name}.png`);
    await selectSettingsCategory(page, 'listening', 'Listening settings');
    const listening = page.getByRole('form', { name: 'Listening settings', exact: true });
    const hangtime = listening.getByRole('spinbutton', { name: 'Call display hangtime (seconds)', exact: true });
    await expect(listening).toBeVisible();
    await expect(page.getByRole('form')).toHaveCount(1);
    await expect(hangtime).toHaveValue('5');
    await expect(hangtime).toHaveAttribute('min', '0');
    await expect(hangtime).toHaveAttribute('max', '60');
    await expect(hangtime).toHaveAttribute('step', '1');
    await expect(hangtime).toHaveAccessibleDescription(
      'Keep the last call and its details visible after playback ends. Applies to Scanner and the player in Live and Recordings. Set to 0 to clear immediately.');
    await expect(listening.getByRole('button', { name: 'Save changes', exact: true })).toBeDisabled();
    await expectNoHorizontalOverflow(page);
    await expectContrast(page, { reportName: `listening-settings-${presentation.name}` });
    await expect(workspace).toHaveScreenshot(`focused-listening-${presentation.name}.png`);
    await selectSettingsCategory(page, 'pages', 'Page preferences');
    await expect(page.getByRole('form')).toHaveCount(0);
    const directory = workspace.locator('.ui-settings-destinations');
    for (const name of ['Scanner', 'Live', 'Tuner spectrum', 'Table layouts']) {
      await expect(directory.getByText(name, { exact: true })).toBeVisible();
    }
    await expect(directory.locator('details, input, select, button')).toHaveCount(0);
    await expect(directory).not.toContainText('Playback volume');
    await expect(directory).not.toContainText('Hidden issue types');
    await expectNoHorizontalOverflow(page);
    await expect(workspace).toHaveScreenshot(`focused-pages-${presentation.name}.png`);
  });
}

test('listening hangtime saves zero and sixty while a failed save retains its draft', async ({ page }) => {
  const savedPlayback = { volume: 0.45, selected_scan_list_ids: [], target_grouping: false,
    target_burst_limit: 7, call_display_hangtime_seconds: 5 };
  const state = await openSettingsApp(page, { holdSaves: true, preferences: { playback: savedPlayback } });
  await selectSettingsCategory(page, 'listening', 'Listening settings');
  const editor = page.getByRole('form', { name: 'Listening settings', exact: true });
  const hangtime = editor.getByRole('spinbutton', { name: 'Call display hangtime (seconds)', exact: true });
  const save = editor.getByRole('button', { name: 'Save changes', exact: true });
  await hangtime.fill('0');
  await save.click();
  await expect.poll(() => state.pending.length).toBe(1);
  state.pending.shift()(503);
  await expect(editor).toContainText('Your settings could not be saved. Your choices are still here. Try saving again.');
  await expect(hangtime).toHaveValue('0');
  await expect(save).toBeEnabled();
  expect(state.profiles.listener.playback).toEqual(savedPlayback);
  await save.click();
  await expect.poll(() => state.pending.length).toBe(1);
  state.pending.shift()(200);
  await expect(save).toBeDisabled();
  expect(state.profiles.listener.playback).toEqual({ ...savedPlayback, call_display_hangtime_seconds: 0 });
  await hangtime.fill('60');
  await save.click();
  await expect.poll(() => state.pending.length).toBe(1);
  state.pending.shift()(200);
  await expect(save).toBeDisabled();
  expect(state.profiles.listener.playback).toEqual({ ...savedPlayback, call_display_hangtime_seconds: 60 });
  expect(state.writes).toHaveLength(3);
});

test('phone section picker restores its selection when a dirty category change is cancelled', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 740 });
  const state = await openSettingsApp(page);
  const appearance = page.getByRole('form', { name: 'Appearance', exact: true });
  await appearance.getByRole('radio', { name: 'Indigo', exact: true }).check();
  const picker = page.getByRole('combobox', { name: 'Section', exact: true });
  await picker.selectOption('names');
  const confirmation = page.getByRole('alertdialog', { name: 'Discard changes?', exact: true });
  await expect(confirmation).toBeVisible();
  await expect(confirmation.getByRole('button', { name: 'Keep editing', exact: true })).toBeFocused();
  await confirmation.getByRole('button', { name: 'Keep editing', exact: true }).click();
  await expect(picker).toHaveValue('appearance');
  await expect(appearance.getByRole('radio', { name: 'Indigo', exact: true })).toBeChecked();
  await expect(appearance).toHaveClass(/is-dirty/);
  await picker.selectOption('names');
  await confirmation.getByRole('button', { name: 'Discard changes', exact: true }).click();
  await expect(picker).toHaveValue('names');
  await expect(page.getByRole('form', { name: 'Source names', exact: true })).toBeVisible();
  expect(state.writes).toHaveLength(0);
  await selectSettingsCategory(page, 'appearance', 'Appearance');
  await expect(appearance.getByRole('radio', { name: 'Teal', exact: true })).toBeChecked();
  await expectNoHorizontalOverflow(page);
});

test('all status issues remain editable and unknown saved choices survive a save', async ({ page }) => {
  const state = await openSettingsApp(page, { preferences: {
    health_alerts: { disabled_codes: ['future-status-issue', 'tuner-error'] }
  } });
  await selectSettingsCategory(page, 'status', 'Status icon');
  const editor = page.getByRole('form', { name: 'Status icon', exact: true });
  const groups = editor.locator('details');
  for (const group of await groups.all()) await group.locator('summary').click();
  const controls = editor.getByRole('checkbox');
  await expect(controls).toHaveCount(8);
  for (const checkbox of await controls.all()) {
    await expect(checkbox).toBeVisible();
    await expect(checkbox).toBeEnabled();
    if (await checkbox.isChecked()) { await checkbox.focus(); await checkbox.press('Space'); }
  }
  await editor.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect(editor.getByRole('button', { name: 'Save changes', exact: true })).toBeDisabled();
  expect(state.profiles.listener.health_alerts.disabled_codes).toHaveLength(9);
  expect(state.profiles.listener.health_alerts.disabled_codes).toContain('future-status-issue');
  expect(state.profiles.listener.appearance).toEqual({ theme: 'light', hue: null });
  expect(state.writes).toHaveLength(1);
});

test('changing the theme previews immediately and discarding restores the saved theme', async ({ page }) => {
  const state = await openSettingsApp(page);
  const editor = page.getByRole('form', { name: 'Appearance', exact: true });
  await editor.getByRole('radio', { name: 'Dark', exact: true }).check();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await editor.getByRole('button', { name: 'Cancel', exact: true }).click();
  const confirmation = page.getByRole('alertdialog', { name: 'Discard changes?', exact: true });
  await confirmation.getByRole('button', { name: 'Discard changes', exact: true }).click();
  await expect(page.locator('html')).not.toHaveAttribute('data-theme', 'dark');
  await expect(editor.getByRole('radio', { name: 'Light', exact: true })).toBeChecked();
  expect(state.writes).toHaveLength(0);
  await editor.getByRole('radio', { name: 'Dark', exact: true }).check();
  await editor.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect(editor.getByRole('button', { name: 'Save changes', exact: true })).toBeDisabled();
  expect(state.profiles.listener.appearance).toEqual({ theme: 'dark', hue: null });
});

test('leaving My Settings confirms a dirty draft and resumes accepted navigation once', async ({ page }) => {
  const state = await openSettingsApp(page);
  const editor = page.getByRole('form', { name: 'Appearance', exact: true });
  await editor.getByRole('radio', { name: 'Ocean', exact: true }).check();
  const dashboard = page.getByRole('link', { name: 'VCE home', exact: true });
  await dashboard.click();
  const confirmation = page.getByRole('alertdialog', { name: 'Discard changes?', exact: true });
  await expect(confirmation).toBeVisible();
  await confirmation.getByRole('button', { name: 'Keep editing', exact: true }).click();
  await expect(page).toHaveURL(/view=settings/);
  await expect(editor.getByRole('radio', { name: 'Ocean', exact: true })).toBeChecked();
  await dashboard.click();
  await confirmation.getByRole('button', { name: 'Discard changes', exact: true }).click();
  await expect(page).toHaveURL(/view=dashboard/);
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  await expect(page.locator('.ui-setup-guide')).toHaveCount(0);
  await expect(page.locator('html[data-custom-hue]')).toHaveCount(0);
  expect(state.writes).toHaveLength(0);
});

for (const [name, theme, viewport] of [
  ['desktop', 'light', { width: 1280, height: 900 }], ['phone', 'dark', { width: 390, height: 844 }]
]) {
  test(`listening keeps personal settings actions reachable in every player size on ${name}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await openAudioApp(page, { view: 'settings', theme, admin: false });
    const form = page.getByRole('form', { name: 'Appearance', exact: true });
    await expect(form).toBeVisible();
    await form.getByRole('radio', { name: 'Ocean', exact: true }).check();
    const player = page.locator('#audio-dock');
    const handle = player.locator('#audio-dock-size-handle:visible, #audio-dock-slim-expand:visible');
    await handle.press('End');
    await player.getByRole('button', { name: 'Listen live', exact: true }).click();
    await expect(player.getByRole('button', { name: 'Hold current target', exact: true })).toBeEnabled();
    const observations = [];
    for (const size of ['collapsed', 'minimal', 'full']) {
      await handle.press(size === 'full' ? 'End' : 'Home');
      if (size === 'minimal') await handle.press('ArrowUp');
      await expect(player).toHaveAttribute('data-state', size);
      await expect.poll(() => player.evaluate(element => Math.abs(element.getBoundingClientRect().height -
        parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--audio-dock-height')))))
        .toBeLessThanOrEqual(1);
      for (const action of ['Cancel', 'Save changes']) {
        const control = form.getByRole('button', { name: action, exact: true });
        // Explicit scrolling uses the page's reserved space and scroll padding around the fixed player.
        await control.evaluate(element => element.scrollIntoView({ block: 'end', behavior: 'instant' }));
        await expect.poll(() => control.evaluate(element => {
          const bounds = element.getBoundingClientRect();
          const hit = document.elementFromPoint(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
          return bounds.top >= 0 && bounds.bottom <= innerHeight && element.contains(hit);
        }), { message: `${name} ${size}: ${action} remains reachable above the player` }).toBe(true);
      }
      await expectNoHorizontalOverflow(page);
      const geometry = await player.evaluate(element => ({
        playerTop: element.getBoundingClientRect().top,
        scrollMax: document.documentElement.scrollHeight - innerHeight
      }));
      observations.push({ size, ...geometry });
      const auditDirectory = process.env.MY_SETTINGS_LISTENING_AUDIT_DIR;
      if (auditDirectory) {
        fs.mkdirSync(auditDirectory, { recursive: true });
        await page.screenshot({ path: path.join(auditDirectory, `listening-${name}-${size}.png`) });
      }
    }
    if (process.env.MY_SETTINGS_LISTENING_AUDIT_DIR) fs.writeFileSync(
      path.join(process.env.MY_SETTINGS_LISTENING_AUDIT_DIR, `listening-${name}-check.json`),
      JSON.stringify(observations, null, 2) + '\n');
  });
}
