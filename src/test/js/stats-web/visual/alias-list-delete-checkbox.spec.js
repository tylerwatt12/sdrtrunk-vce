const { expect, test } = require('@playwright/test');

async function openDeleteDialog(page, channelCount = 0) {
  const aliasList = { alias_list_id: 1, name: 'Test', family: 'P25',
    alias_count: 2, assigned_channel_count: channelCount };
  await page.route('**/api/v1/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    const data = path === '/api/v1/auth/session' ? {
      configured: true, authenticated: true, username: 'alias-admin', tier: 'admin', primary: true,
      csrf_token: 'test-token', capabilities: { 'admin-aliases': true, 'admin-channels': true, radio: true }
    } : path === '/api/v1/alias-lists' ? {
      rows: [aliasList], limit: 500, offset: 0, has_more: false, next_offset: null
    } : path === '/api/v1/admin/alias-lists' ? { revision: 1, alias_lists: [aliasList] } :
      path === '/api/v1/admin/channels' ? { revision: 1, channels: [] } :
        path === '/api/v1/aliases' ? {
          rows: [], total_count: 0, limit: 50, offset: 0, has_more: false, next_offset: null
        } : path === '/api/v1/admin/aliases/options' ? { revision: 1, alias_list: aliasList } :
          path === '/api/v1/admin/alias-lists/1/delete-impact' ? {
            revision: 1, alias_count: 2, channel_count: channelCount
          } : null;
    await route.fulfill({ status: data ? 200 : 404, contentType: 'application/json',
      body: JSON.stringify(data ? { data } : { error: { message: 'Not available in this browser contract' } }) });
  });

  await page.goto('/app.html?view=aliases&list=1');
  await page.getByRole('button', { name: 'Delete List' }).click();
  const dialog = page.getByRole('dialog', { name: 'Delete Test' });
  await expect(dialog).toBeVisible();
  return dialog;
}

for (const theme of ['light', 'dark']) {
  test(`Alias List deletion uses the theme checkbox in ${theme} mode`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 720 });
    const dialog = await openDeleteDialog(page);
    if (theme === 'dark') {
      await page.locator('html').evaluate((element) => { element.dataset.theme = 'dark'; });
    }
    const checkbox = dialog.getByRole('checkbox', { name: 'I understand this cannot be undone.' });
    const remove = dialog.getByRole('button', { name: 'Delete Alias List' });
    await expect(checkbox).toHaveClass(/\bui-selection-check\b/);
    await expect(remove).toBeDisabled();
    await checkbox.check();
    await expect(remove).toBeEnabled();
    await expect(dialog).toHaveScreenshot(`alias-list-delete-${theme}.png`);
    await checkbox.uncheck();
    await expect(remove).toBeDisabled();
  });
}

test('Alias List deletion remains unavailable while channels use the list', async ({ page }) => {
  const dialog = await openDeleteDialog(page, 1);
  await expect(dialog.getByRole('checkbox', { name: 'I understand this cannot be undone.' }))
    .toBeDisabled();
  await expect(dialog.getByRole('button', { name: 'Delete Alias List' })).toBeDisabled();
});
