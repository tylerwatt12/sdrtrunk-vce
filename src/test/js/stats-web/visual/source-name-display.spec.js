const { test, expect } = require('@playwright/test');
const { openAudioApp } = require('./fixtures/audio-dock-app.cjs');
const { presentations } = require('./fixtures/site-style-cases.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

for (const presentation of presentations) {
  test(`source names ${presentation.name}`, async ({ page }) => {
    await page.setViewportSize(presentation.viewport);
    const state = await openAudioApp(page, { view: 'settings', theme: presentation.theme });
    await expect(page.getByText('Talker Alias preferred', { exact: true })).toBeVisible();
    const action = page.getByRole('button', { name: 'Change Source Names', exact: true });
    await action.click();
    const dialog = page.getByRole('dialog', { name: 'Source names', exact: true });
    const choice = dialog.getByLabel('Source name display', { exact: true });
    const save = dialog.getByRole('button', { name: 'Save source names', exact: true });
    await expect(choice).toBeFocused();
    await expect(choice).toHaveValue('talker_alias');
    await expect(save).toBeDisabled();
    await expectNoHorizontalOverflow(page);
    await expect(dialog).toHaveScreenshot(`source-names-${presentation.name}.png`);
    await choice.selectOption('both');
    await save.click();
    await expect(dialog).toBeHidden();
    expect(state.preferenceWrites.at(-1).presentation.source_name_display).toBe('both');
    expect(state.preferenceWrites.at(-1).playback.target_grouping).toBe(true);
    await expect(action).toBeFocused();
    await page.reload();
    await action.click();
    await expect(choice).toHaveValue('both');
    await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
    await expect(dialog).toBeHidden();
    await expect(action).toBeFocused();
  });
}
