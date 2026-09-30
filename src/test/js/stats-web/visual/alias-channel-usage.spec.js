const { expect, test } = require('@playwright/test');

for (const canEditChannels of [true, false]) {
  test(`Alias Lists show channel names ${canEditChannels ? 'with edit links' : 'without channel edit access'}`,
    async ({ page }) => {
    const firstList = { alias_list_id: 1, name: 'County Public Safety', family: 'P25',
      alias_count: 12, assigned_channel_count: 5 };
    const secondList = { alias_list_id: 2, name: 'City Services', family: 'P25',
      alias_count: 3, assigned_channel_count: 1 };
    const channel = (suffix, name, listId) => ({
      configuration_id: `00000000-0000-0000-0000-00000000000${suffix}`,
      name, alias_list_id: listId
    });
    await page.route('**/api/v1/**', async (route) => {
      const path = new URL(route.request().url()).pathname;
      const data = path === '/api/v1/auth/session' ? {
        configured: true, authenticated: true, username: 'alias-admin', tier: 'admin', primary: true,
        csrf_token: 'test-token', capabilities: {
          'admin-aliases': true, 'admin-channels': canEditChannels, radio: true
        }
      } : path === '/api/v1/alias-lists' ? {
        rows: [firstList, secondList], limit: 500, offset: 0, has_more: false, next_offset: null
      } :
        path === '/api/v1/admin/alias-lists' ? { revision: 1, alias_lists: [firstList, secondList] } :
          path === (canEditChannels ? '/api/v1/admin/channels' : '/api/v1/channel-catalog') ?
            { revision: 1, channels: [
            channel(5, 'Sheriff Dispatch', 1), channel(2, 'Fire Dispatch', 1),
            channel(3, 'EMS Operations', 1), channel(1, 'Police East', 1),
            channel(4, 'Police West', 1), channel(6, 'Public Works', 2)
          ] } : path === '/api/v1/aliases' ? {
            rows: [], total_count: 0, limit: 50, offset: 0, has_more: false, next_offset: null
          } :
            path === '/api/v1/admin/aliases/options' ? { revision: 1, alias_list: firstList } : null;
      await route.fulfill({ status: data ? 200 : 404, contentType: 'application/json',
        body: JSON.stringify(data ? { data } : { error: { message: 'Not available in this browser contract' } }) });
    });

    await page.goto('/app.html?view=aliases&list=1');
    const rail = page.getByRole('navigation', { name: 'Alias lists' });
    await expect(rail.locator('.alias-list-item.active .alias-list-item-channels'))
      .toHaveText('Used by EMS Operations, Fire Dispatch +3 more');
    await expect(rail.locator('.alias-list-item').filter({ hasText: 'City Services' })
      .locator('.alias-list-item-channels'))
      .toHaveText('Used by Public Works');
    const usage = page.locator('.alias-channel-usage');
    const channels = [
      ['EMS Operations', 3], ['Fire Dispatch', 2], ['Police East', 1],
      ['Police West', 4], ['Sheriff Dispatch', 5]
    ];
    await expect(usage.locator('.alias-channel-usage-links')).toHaveCount(1);
    await expect(usage.locator('.alias-channel-usage-links li')).toHaveCount(channels.length);
    await expect(usage.locator('details, summary')).toHaveCount(0);
    for (const [name, suffix] of channels) {
      await expect(usage.getByText(name, { exact: true })).toBeVisible();
      if (canEditChannels) {
        await expect(usage.getByRole('link', { name })).toHaveAttribute('href',
          new RegExp(`view=channel-setup&channel=00000000-0000-0000-0000-00000000000${suffix}$`));
      }
    }
    await expect(usage.getByRole('link')).toHaveCount(canEditChannels ? channels.length : 0);
  });
}
