'use strict';

const { test, expect } = require('@playwright/test');
test.setTimeout(12_000);
const { openSettingsApp, selectSettingsCategory: selectCategory } = require('./fixtures/my-settings-app.cjs');
const { presentations } = require('./fixtures/site-style-cases.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

for (const presentation of presentations) {
  test(`source names ${presentation.name}`, async ({ page }) => {
    await page.setViewportSize(presentation.viewport);
    const state = await openSettingsApp(page, { theme: presentation.theme });
    await selectCategory(page, 'names', 'Source names');
    const editor = page.getByRole('form', { name: 'Source names', exact: true });
    const choice = editor.getByRole('radio', { name: 'Talker Alias preferred', exact: true });
    const both = editor.getByRole('radio', { name: 'Both', exact: true });
    const save = editor.getByRole('button', { name: 'Save changes', exact: true });
    await expect(choice).toBeChecked();
    await expect(save).toBeVisible();
    await expect(save).toBeDisabled();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expectNoHorizontalOverflow(page);
    await expect(page.locator('.ui-settings-workspace-focused')).toHaveScreenshot(`source-names-${presentation.name}.png`);
    await both.check();
    await expect(editor).toHaveClass(/is-dirty/);
    await save.click();
    await expect(save).toBeDisabled();
    await expect(editor).not.toHaveClass(/is-dirty/);
    expect(state.profiles.listener.presentation.source_name_display).toBe('both');
    expect(state.profiles.listener.playback.target_grouping).toBe(true);
    await page.reload();
    await selectCategory(page, 'names', 'Source names');
    await expect(both).toBeChecked();
    await editor.getByRole('radio', { name: 'Source Alias preferred', exact: true }).check();
    await editor.getByRole('button', { name: 'Cancel', exact: true }).click();
    const discard = page.getByRole('alertdialog', { name: 'Discard changes?', exact: true });
    await expect(discard.getByRole('button', { name: 'Keep editing', exact: true })).toBeFocused();
    await discard.getByRole('button', { name: 'Discard changes', exact: true }).click();
    await expect(discard).toBeHidden();
    await expect(both).toBeChecked();
    await expect(save).toBeDisabled();
    expect(state.writes).toHaveLength(1);
  });
}

test('source names retain failed drafts, load conflict values, and preserve other preferences', async ({ page }) => {
  const state = await openSettingsApp(page, { holdSaves: true });
  await selectCategory(page, 'names', 'Source names');
  const editor = page.getByRole('form', { name: 'Source names', exact: true });
  const both = editor.getByRole('radio', { name: 'Both', exact: true });
  const save = editor.getByRole('button', { name: 'Save changes', exact: true });
  await both.check();
  await save.click();
  await expect.poll(() => state.pending.length).toBe(1);
  await expect(both).toBeDisabled();
  await editor.evaluate(form => form.dispatchEvent(new Event('submit', { cancelable: true })));
  expect(state.writes).toHaveLength(1);
  state.pending.shift()(503);
  await expect(editor.getByRole('alert')).toContainText('could not be saved');
  await expect(both).toBeChecked();
  await expect(save).toBeEnabled();
  await save.click();
  await expect.poll(() => state.pending.length).toBe(1);
  state.profiles.listener.presentation.source_name_display = 'source_alias';
  state.profiles.listener.playback.volume = 0.42;
  state.revisions.listener = 4;
  state.pending.shift()(409);
  await expect(editor.locator('.admin-form-message')).toContainText('saved');
  await expect(editor.getByRole('radio', { name: 'Source Alias preferred', exact: true })).toBeChecked();
  await expect(save).toBeDisabled();
  await both.check();
  await save.click();
  await expect.poll(() => state.pending.length).toBe(1);
  expect(state.writes.at(-1).revision).toBe('"4"');
  state.pending.shift()(200);
  await expect(save).toBeDisabled();
  expect(state.profiles.listener.presentation.source_name_display).toBe('both');
  expect(state.profiles.listener.playback.volume).toBe(0.42);
});
