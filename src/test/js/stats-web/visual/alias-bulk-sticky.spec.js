const { expect, test } = require('@playwright/test');

for (const [name, viewport] of [
  ['desktop', { width: 1280, height: 720 }],
  ['mobile', { width: 390, height: 844 }]
]) {
  for (const [view, actionLabels] of [
    ['aliases', ['Move', 'Group', 'Scan Lists', 'Record', 'Stream', 'Appearance', 'Delete', 'Clear selection']],
    ['scan-list-members', null]
  ]) {
    test(`${view} bulk actions stay visible while scrolling the table ${name}`, async ({ page }) => {
      await page.setViewportSize(viewport);
      await page.goto(`/design-system.html?theme=light&view=${view}`);

      const section = page.locator(`.visual-${view}-example .alias-editor-table-section`);
      const bar = page.locator(`.visual-${view}-example .alias-bulk-bar`);
      await section.evaluate((element, labels) => {
        // The gallery's Alias Editor shows no selection; add the same bar shape the app renders on selection.
        if (labels) {
          const selectedBar = document.createElement('div');
          selectedBar.className = 'alias-bulk-bar ui-selection-bar';
          const count = document.createElement('strong');
          count.className = 'alias-bulk-count';
          count.textContent = '1 selected';
          selectedBar.append(count);
          for (const label of labels) {
            const button = document.createElement('button');
            button.className = 'ui-button ui-button-secondary';
            button.type = 'button';
            button.textContent = label;
            selectedBar.append(button);
          }
          element.before(selectedBar);
        }
      }, actionLabels);
      await expect(bar).toBeVisible();
      const unscrolled = await page.evaluate(({ view }) => {
        const host = document.querySelector(`.visual-${view}-example`);
        return { barBottom: host.querySelector('.alias-bulk-bar').getBoundingClientRect().bottom,
          tableTop: host.querySelector('.alias-editor-table-section').getBoundingClientRect().top };
      }, { view });
      expect(unscrolled.barBottom).toBeLessThanOrEqual(unscrolled.tableTop + 1);

      await section.evaluate((element) => {
        const tbody = element.querySelector('tbody');
        const firstRow = tbody.querySelector('tr');
        for (let index = 0; index < 80; index += 1) tbody.append(firstRow.cloneNode(true));
      });

      const sectionTop = await section.evaluate((element) => element.getBoundingClientRect().top + window.scrollY);
      const assertBarSticksInViewport = async (distance) => {
        await page.evaluate((y) => window.scrollTo(0, y), sectionTop + distance);
        await expect.poll(() => bar.evaluate((element) => {
          const rect = element.getBoundingClientRect();
          const sectionRect = element.nextElementSibling.getBoundingClientRect();
          const stickyTop = parseFloat(getComputedStyle(element).top);
          return sectionRect.top < 0 && sectionRect.bottom > window.innerHeight &&
            Math.abs(rect.top - stickyTop) <= 2 && rect.bottom <= window.innerHeight;
        })).toBe(true);
      };

      await assertBarSticksInViewport(500);
      await assertBarSticksInViewport(1400);
      await assertBarSticksInViewport(800);
    });
  }
}

test('real Alias Editor bulk actions stay above a long table while scrolling', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 600 });
  const aliasList = { alias_list_id: 1, name: 'County Public Safety', family: 'P25',
    alias_count: 50, assigned_channel_count: 0 };
  const rows = Array.from({ length: 50 }, (_, index) => ({
    alias_id: index + 1, alias_list_id: 1, name: `Alias ${index + 1}`,
    description: '', identifier_display: `TG ${index + 100}`, matcher_type: 'TALKGROUP',
    matcher_label: 'Talkgroup', group: 'Dispatch'
  }));
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
          rows, total_count: rows.length, limit: 50, offset: 0, has_more: false, next_offset: null
        } : path === '/api/v1/admin/aliases/options' ? { revision: 1, alias_list: aliasList } : null;
    await route.fulfill({ status: data ? 200 : 404, contentType: 'application/json',
      body: JSON.stringify(data ? { data } : { error: { message: 'Not available in this browser contract' } }) });
  });

  await page.goto('/app.html?view=aliases&list=1');
  const section = page.locator('.alias-editor-table-section');
  await expect(section.locator('tbody tr')).toHaveCount(50);
  const bar = page.locator('.alias-editor-main > .alias-bulk-bar');
  await page.getByRole('checkbox', { name: 'Select Alias 1', exact: true }).check();
  await expect(bar).toBeVisible();
  await expect(bar.locator('.alias-bulk-count')).toHaveText('1 selected');
  expect(await bar.evaluate((element) =>
    element.nextElementSibling?.classList.contains('alias-editor-table-section'))).toBe(true);

  const sectionTop = await section.evaluate((element) => element.getBoundingClientRect().top + window.scrollY);
  await page.evaluate((y) => window.scrollTo(0, y), sectionTop + 500);
  await expect.poll(() => bar.evaluate((element) => {
    const bounds = element.getBoundingClientRect();
    const table = element.nextElementSibling.getBoundingClientRect();
    const stickyTop = parseFloat(getComputedStyle(element).top);
    return table.top < stickyTop && table.bottom > window.innerHeight &&
      Math.abs(bounds.top - stickyTop) <= 2 && bounds.bottom <= window.innerHeight;
  })).toBe(true);
});
