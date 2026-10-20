const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

let defaults;
test.beforeAll(async () => {
  defaults = (await import(pathToFileURL(path.resolve(__dirname,
    '../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults;
});

async function openChannelCatalog(page, theme) {
  const preferences = structuredClone(defaults);
  preferences.appearance.theme = theme;
  let revision = 1;
  const system = 'Countywide Public Safety and Regional Interoperability Communications System';
  const channel = {
    configuration_id: 'mobile-simulcast', protocol_id: 'p25-phase1', protocol_label: 'P25 Phase 1',
    channel_kind: 'TRUNKED', name: system, system,
    site: 'Northeast County and Lake Shore Simulcast',
    frequencies_hz: [770306250, 772431250, 772781250, 773181250, 773431250, 774181250, 774431250],
    display_frequency_hz: 772781250, display_frequency_source: 'active-control',
    processing_state: 'RUNNING', auto_start_order: 18, alias_list_id: 1, alias_list_name: 'County P25',
    editable: true
  };
  const moves = [];
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    const respond = (data) => route.fulfill({ json: { data } });
    if (pathname === '/api/v1/auth/session') {
      await respond({ configured: true, authenticated: true, username: 'fixture-admin', tier: 'admin',
        primary: true, csrf_token: 'fixture-token', capabilities: { 'admin-channels': true } });
    } else if (pathname === '/api/v1/me/preferences') {
      await route.fulfill({ json: { revision: 1, preferences } });
    } else if (pathname === '/api/v1/admin/channels') {
      await respond({ revision, channels: [channel] });
    } else if (pathname === '/api/v1/admin/channels/options') {
      await respond({ revision, alias_lists: [{ id: 1, name: 'County P25', family: 'P25' }], tuners: [] });
    } else if (pathname === '/api/v1/admin/channels/protocols') {
      await respond({ profiles: [] });
    } else if (pathname === '/api/v1/admin/channels/mobile-simulcast/auto-start/move') {
      const move = request.postDataJSON();
      moves.push(move.direction);
      channel.auto_start_order += move.direction === 'EARLIER' ? -1 : 1;
      revision += 1;
      await respond({ revision, configuration_ids: [channel.configuration_id], affected: 1 });
    } else {
      await route.fulfill({ status: 404, json: { error: { message: 'Unavailable in this browser contract.' } } });
    }
  });
  await page.goto('/app.html?view=channel-setup');
  return { moves };
}

async function expectFullyContained(cell, child) {
  const [cellBox, childBox] = await Promise.all([cell.boundingBox(), child.boundingBox()]);
  expect(childBox.x).toBeGreaterThanOrEqual(cellBox.x);
  expect(childBox.y).toBeGreaterThanOrEqual(cellBox.y);
  expect(childBox.x + childBox.width).toBeLessThanOrEqual(cellBox.x + cellBox.width);
  expect(childBox.y + childBox.height).toBeLessThanOrEqual(cellBox.y + cellBox.height);
}

async function expectPointerReachable(button) {
  await button.evaluate((element) => element.scrollIntoView({ block: 'center' }));
  await expect.poll(() => button.evaluate((element) => {
    const rect = element.getBoundingClientRect();
    return [[rect.x + rect.width / 2, rect.y + rect.height / 2], [rect.right - 3, rect.bottom - 3]]
      .every(([x, y]) => element.contains(document.elementFromPoint(x, y)));
  })).toBe(true);
}

async function expectHeaderContentsCentered(table) {
  const headers = await table.locator('thead th').evaluateAll((cells) => cells
    .filter((cell) => cell.getClientRects().length)
    .map((cell) => {
      const child = cell.querySelector('.ui-selection-check, .table-column-label');
      const bounds = cell.getBoundingClientRect();
      const content = child.getBoundingClientRect();
      return { label: cell.dataset.column,
        offset: Math.abs(content.y + content.height / 2 - bounds.y - bounds.height / 2) };
    }));
  expect(headers.length).toBeGreaterThan(0);
  for (const { label, offset } of headers) {
    expect(offset, `${label} header content should be vertically centered`).toBeLessThanOrEqual(1);
  }
}

for (const theme of ['light', 'dark']) {
  for (const width of [320, 390, 1280]) {
    test(`Channel Setup catalog centers headers and fits frequencies and startup controls ${theme} ${width}`, async ({ page }) => {
      await page.setViewportSize({ width, height: 844 });
      const fixture = await openChannelCatalog(page, theme);
      const table = page.locator('.channel-catalog-table');
      const row = table.locator('tr[data-id="mobile-simulcast"]');
      await expect(row).toBeVisible();
      await expectHeaderContentsCentered(table);
      const frequencies = row.locator('td[data-column="frequency"]');
      await expect(frequencies).toContainText('772.78125');
      await expect(frequencies).toContainText('Active control');
      await expect(frequencies).not.toContainText('770.30625');
      const site = row.locator('.channel-site-value');
      await expect(site).toHaveText('Northeast County and Lake Shore Simulcast');
      await expect(site).toHaveCSS('white-space', 'normal');
      const siteGeometry = await site.evaluate((element) => ({
        clientWidth: element.clientWidth, scrollWidth: element.scrollWidth,
        clientHeight: element.clientHeight, scrollHeight: element.scrollHeight,
        textOverflow: getComputedStyle(element).textOverflow
      }));
      expect(siteGeometry.textOverflow).not.toBe('ellipsis');
      expect(siteGeometry.scrollWidth).toBeLessThanOrEqual(siteGeometry.clientWidth + 1);
      expect(siteGeometry.scrollHeight).toBeLessThanOrEqual(siteGeometry.clientHeight + 1);
      await expect(row.locator('.channel-row-context')).toHaveCount(0);
      await expect(row.getByRole('button', { name: 'Edit', exact: true })).toHaveCount(0);
      for (const label of ['Details', 'Live']) {
        const link = row.getByRole('link', { name: label, exact: true });
        await expect(link).toBeVisible();
        await expect(link).toHaveClass(/ui-link-text/);
        await expect(link).not.toHaveClass(/ui-button/);
      }
      await expect(row.locator('.channel-row-links')).toHaveClass(/ui-muted/);
      const textGeometry = await frequencies.evaluate((cell) => {
        const bounds = cell.getBoundingClientRect();
        const range = document.createRange();
        range.selectNodeContents(cell);
        const lines = Array.from(range.getClientRects());
        return { lineCount: lines.length,
          contained: lines.every((line) => line.top >= bounds.top && line.bottom <= bounds.bottom &&
            line.left >= bounds.left && line.right <= bounds.right),
          clientHeight: cell.clientHeight, scrollHeight: cell.scrollHeight };
      });
      expect(textGeometry.lineCount).toBeGreaterThan(1);
      expect(textGeometry.contained).toBe(true);
      expect(textGeometry.scrollHeight).toBeLessThanOrEqual(textGeometry.clientHeight + 1);
      const orderCell = row.locator('td[data-column="auto-start"]');
      const earlier = orderCell.getByRole('button', { name: 'Start earlier', exact: true });
      const later = orderCell.getByRole('button', {
        name: 'Start later; moving the last channel later disables auto start', exact: true
      });
      for (const button of [earlier, later]) {
        await expectFullyContained(orderCell, button);
        await expectPointerReachable(button);
        await expect(button).toHaveCSS('height', '40px');
      }
      await earlier.click();
      await expect(row.locator('.channel-order-number')).toHaveText('17');
      await later.click();
      await expect(row.locator('.channel-order-number')).toHaveText('18');
      expect(fixture.moves).toEqual(['EARLIER', 'LATER']);
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
      await table.screenshot({ path: test.info().outputPath(`channel-catalog-${theme}-${width}.png`) });
    });
  }
}
