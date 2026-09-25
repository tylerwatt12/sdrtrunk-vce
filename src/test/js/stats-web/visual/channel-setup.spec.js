const { expect, test } = require('@playwright/test');

function apiData(data) {
  return { data };
}

async function trackOrderHighlight(row) {
  await row.evaluate((element) => {
    element.orderHighlightEvents = [];
    let wasHighlighted = element.classList.contains('channel-order-row-moved');
    const observer = new MutationObserver(() => {
      const highlighted = element.classList.contains('channel-order-row-moved');
      if (highlighted === wasHighlighted) return;
      wasHighlighted = highlighted;
      const style = getComputedStyle(element.querySelector('td'));
      element.orderHighlightEvents.push({
        type: highlighted ? 'added' : 'removed',
        time: performance.now(),
        animationName: style.animationName,
        animationDuration: style.animationDuration,
        backgroundColor: style.backgroundColor
      });
      if (!highlighted) observer.disconnect();
    });
    observer.observe(element, { attributes: true, attributeFilter: ['class'] });
  });
}

function orderHighlightEvents(row) {
  return row.evaluate((element) => element.orderHighlightEvents || []);
}

function channelFixture(index) {
  const order = index <= 30 ? index : null;
  const protocol = index % 3 === 0 ? ['dmr', 'DMR'] :
    index % 3 === 1 ? ['p25-phase1', 'P25 Phase 1'] : ['nbfm', 'NBFM'];
  return {
    configuration_id: `channel-${String(index).padStart(2, '0')}`,
    protocol_id: protocol[0],
    protocol_label: protocol[1],
    channel_kind: index % 3 === 2 ? 'CONVENTIONAL' : 'TRUNKED',
    system: `System ${index % 4 + 1}`,
    site: `Site ${index % 6 + 1}`,
    name: `Channel ${String(index).padStart(2, '0')}`,
    frequencies_hz: [851_000_000 + index * 12_500],
    processing_state: index % 5 === 0 ? 'RUNNING' : 'STOPPED',
    auto_start_order: order,
    alias_list_id: index,
    alias_list_name: `Aliases ${index % 4 + 1}`,
    editable: true,
    restriction_message: null
  };
}

test('startup-order view moves keyed rows without replacing the page or losing position', async ({ page }) => {
  await page.setViewportSize({ width: 1100, height: 720 });
  let revision = 40;
  const channels = Array.from({ length: 36 }, (_, index) => channelFixture(index + 1));
  let moveRequest = null;
  let delayMoveResponse = null;

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const respond = (data, status = 200) => route.fulfill({
      status,
      contentType: 'application/json',
      body: JSON.stringify(data)
    });
    if(path === '/api/v1/auth/session') {
      await respond(apiData({
        configured: true,
        authenticated: true,
        username: 'channel-admin',
        tier: 'admin',
        primary: true,
        csrf_token: 'test-token',
        capabilities: { 'admin-channels': true }
      }));
      return;
    }
    if(path === '/api/v1/admin/channels' && request.method() === 'GET') {
      await respond(apiData({ revision, channels }));
      return;
    }
    if(path === '/api/v1/admin/channels/protocols') {
      await respond(apiData({ revision, protocols: [] }));
      return;
    }
    if(path === '/api/v1/admin/channels/options') {
      await respond(apiData({ revision, alias_lists: [], tuners: [] }));
      return;
    }
    const move = path.match(/^\/api\/v1\/admin\/channels\/([^/]+)\/auto-start\/move$/);
    if(move && request.method() === 'POST') {
      moveRequest = request.postDataJSON();
      if(delayMoveResponse) await delayMoveResponse;
      const id = decodeURIComponent(move[1]);
      const enabled = channels.filter((row) => row.auto_start_order != null)
        .sort((left, right) => left.auto_start_order - right.auto_start_order);
      const position = enabled.findIndex((row) => row.configuration_id === id);
      let affected = 0;
      if(moveRequest.direction === 'EARLIER' && position > 0) {
        const previous = enabled[position - 1];
        const current = enabled[position];
        [previous.auto_start_order, current.auto_start_order] =
          [current.auto_start_order, previous.auto_start_order];
        affected = 2;
      }
      revision += 1;
      await respond(apiData({ revision, configuration_ids: [id], affected }));
      return;
    }
    await respond({ error: { message: 'Not available in this browser contract' } }, 404);
  });

  await page.goto('/app.html?view=channel-setup');
  const catalog = page.locator('.channel-admin-catalog');
  await expect(catalog).toBeVisible();
  await expect(page.getByRole('button', { name: 'Grouped', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(catalog.locator('.table-row-group-disclosure')).toHaveCount(3);

  await page.getByRole('button', { name: 'Startup order', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Startup order', exact: true }))
    .toHaveAttribute('aria-pressed', 'true');
  await expect(catalog.locator('.table-row-group-disclosure')).toHaveCount(0);
  await expect(catalog.locator('tbody tr[data-id]')).toHaveCount(36);
  await expect(catalog.locator('tbody tr[data-id]').first()).toHaveAttribute('data-id', 'channel-01');
  await expect(catalog.locator('tbody tr[data-id]').nth(29)).toHaveAttribute('data-id', 'channel-30');
  await expect(catalog.locator('tbody tr[data-id]').nth(30)).toHaveAttribute('data-id', 'channel-31');

  const search = page.getByLabel('Search channels, systems, protocols, frequencies, or alias lists');
  await search.fill('Channel');
  const targetRow = catalog.locator('tr[data-id="channel-22"]');
  const targetHandle = await targetRow.elementHandle();
  const catalogHandle = await catalog.elementHandle();
  const earlier = targetRow.getByRole('button', { name: 'Start earlier' });
  await trackOrderHighlight(targetRow);
  await earlier.scrollIntoViewIfNeeded();
  await earlier.focus();
  const beforeScroll = await page.evaluate(() => ({ x: window.scrollX, y: window.scrollY }));
  await earlier.click();

  await expect.poll(() => moveRequest).toEqual({ revision: 40, direction: 'EARLIER' });
  await expect(catalog.locator('tbody tr[data-id]').nth(20)).toHaveAttribute('data-id', 'channel-22');
  await expect(catalog.locator('tbody tr[data-id]').nth(21)).toHaveAttribute('data-id', 'channel-21');
  await expect.poll(async () => (await orderHighlightEvents(targetRow))[0] || null).toMatchObject({
    type: 'added',
    animationName: 'channel-order-row-highlight',
    animationDuration: '1s'
  });
  await search.evaluate((input) => input.dispatchEvent(new Event('input', { bubbles: true })));
  await expect(targetRow).toHaveClass(/channel-order-row-moved/);
  await expect(targetRow.locator('.channel-order-number')).toHaveText('21');
  await expect(targetRow.getByRole('button', { name: 'Start earlier' })).toBeFocused();
  await expect(search).toHaveValue('Channel');
  await expect(page.getByRole('button', { name: 'Startup order', exact: true }))
    .toHaveAttribute('aria-pressed', 'true');

  const retained = await page.evaluate(({ root, row }) => ({
    root: root === document.querySelector('.channel-admin-catalog'),
    row: row === document.querySelector('tr[data-id="channel-22"]'),
    scroll: { x: window.scrollX, y: window.scrollY }
  }), { root: catalogHandle, row: targetHandle });
  expect(retained.root).toBe(true);
  expect(retained.row).toBe(true);
  expect(Math.abs(retained.scroll.x - beforeScroll.x)).toBeLessThanOrEqual(1);
  expect(Math.abs(retained.scroll.y - beforeScroll.y)).toBeLessThanOrEqual(1);
  await expect.poll(async () => (await orderHighlightEvents(targetRow)).map((event) => event.type))
    .toEqual(['added', 'removed']);
  const highlightEvents = await orderHighlightEvents(targetRow);
  expect(highlightEvents[1].time - highlightEvents[0].time).toBeGreaterThanOrEqual(900);

  moveRequest = null;
  let releaseMoveResponse;
  delayMoveResponse = new Promise((resolve) => { releaseMoveResponse = resolve; });
  const delayedRow = catalog.locator('tr[data-id="channel-23"]');
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await trackOrderHighlight(delayedRow);
  await delayedRow.getByRole('button', { name: 'Start earlier' }).focus();
  await delayedRow.getByRole('button', { name: 'Start earlier' }).click();
  await expect.poll(() => moveRequest).toEqual({ revision: 41, direction: 'EARLIER' });
  await search.focus();
  await expect(search).toBeFocused();
  releaseMoveResponse();
  delayMoveResponse = null;
  await expect(delayedRow.locator('.channel-order-number')).toHaveText('22');
  await expect(search).toBeFocused();
  await expect.poll(async () => (await orderHighlightEvents(delayedRow))[0] || null).toMatchObject({
    type: 'added',
    animationName: 'none',
    animationDuration: '0s'
  });
  expect((await orderHighlightEvents(delayedRow))[0].backgroundColor).not.toBe('rgba(0, 0, 0, 0)');
  await expect.poll(async () => (await orderHighlightEvents(delayedRow)).map((event) => event.type))
    .toEqual(['added', 'removed']);

  moveRequest = null;
  const firstRow = catalog.locator('tr[data-id="channel-01"]');
  await trackOrderHighlight(firstRow);
  await firstRow.getByRole('button', { name: 'Start earlier' }).click();
  await expect.poll(() => moveRequest).toEqual({ revision: 42, direction: 'EARLIER' });
  await expect(firstRow.getByRole('button', { name: 'Start earlier' })).toBeEnabled();
  await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  expect(await orderHighlightEvents(firstRow)).toEqual([]);
});
