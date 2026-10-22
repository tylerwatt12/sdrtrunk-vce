'use strict';

const { test, expect } = require('@playwright/test');
test.setTimeout(12_000);
const { openSettingsApp, selectSettingsCategory } = require('./fixtures/my-settings-app.cjs');
const { presentations } = require('./fixtures/site-style-cases.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

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
    await expect(groups).toHaveCount(5);
    for (const group of await groups.all()) await expect(group).not.toHaveAttribute('open');
    await expect(status.locator('input[type="checkbox"]')).toHaveCount(27);
    await expect(workspace).toHaveScreenshot(`focused-status-${presentation.name}.png`);
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
  await expect(controls).toHaveCount(27);
  for (const checkbox of await controls.all()) {
    await expect(checkbox).toBeVisible();
    await expect(checkbox).toBeEnabled();
    if (await checkbox.isChecked()) { await checkbox.focus(); await checkbox.press('Space'); }
  }
  await editor.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect(editor.getByRole('button', { name: 'Save changes', exact: true })).toBeDisabled();
  expect(state.profiles.listener.health_alerts.disabled_codes).toHaveLength(28);
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
  await expect(page.locator('html[data-custom-hue]')).toHaveCount(0);
  expect(state.writes).toHaveLength(0);
});
