'use strict';

const { test, expect } = require('@playwright/test');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');

async function holdMutation(page, pattern) {
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  const writes = [];
  await page.route(pattern, async route => {
    if (route.request().method() === 'GET') return route.fallback();
    writes.push(route.request().postDataJSON());
    const outcome = await gate;
    if (outcome === 'disconnect') return route.abort('connectionreset');
    if (outcome === 'success') return route.fulfill({ json: { data: { revision: 2, results:
      (writes.at(-1).configuration_ids || []).map(configuration_id => ({ configuration_id, success: true }))
    } } });
    return route.fulfill({ status: 409, json: { error: {
      code: 'stale_revision', message: 'Saved settings changed. Reload current values before trying again.'
    } } });
  });
  return { release, writes };
}

async function expectPending(dialog, label) {
  const feedback = dialog.locator('.ui-feedback-loading');
  await expect(feedback).toHaveText(label);
  await expect(feedback).toHaveAttribute('role', 'status');
  await expect(feedback).toBeVisible();
  expect(await feedback.evaluate(element => getComputedStyle(element, '::before').animationName))
    .toBe('ui-feedback-spin');
  await expect(dialog).toHaveAttribute('aria-busy', 'true');
  await pageEscape(dialog.page());
  await expect(dialog).toBeVisible();
}
const pageEscape = page => page.keyboard.press('Escape');

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`channel bulk work leaves browsing and navigation responsive ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    const fixture = await installSiteStyleApplication(page, theme);
    const held = await holdMutation(page, '**/api/v1/admin/channels/actions');
    await page.goto('/app.html?view=channel-setup');
    await page.getByRole('checkbox', { name: 'Select all matching channels', exact: true }).check();
    const bar = page.locator('.channel-selection-bar');
    await bar.getByRole('button', { name: 'Start', exact: true }).click();
    const feedback = page.locator('.channel-admin-status');
    await expect(feedback).toContainText('Starting 1 channel…');
    await expect(feedback).toHaveClass(/ui-feedback-loading/);
    await expect(bar.getByRole('button', { name: 'Stop', exact: true })).toBeDisabled();
    const search = page.getByRole('searchbox', { name: 'Search channels, systems, protocols, frequencies, or alias lists' });
    await search.fill('North');
    await expect(page.locator('.channel-catalog-table tbody tr[data-id]')).toHaveCount(1);
    // Filtering replaces table rows. A newly rendered action cannot bypass the pending guard.
    await expect(page.locator('.channel-edit-name')).toBeDisabled();
    await page.locator('.channel-edit-name').evaluate(element => element.click());
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(bar.getByRole('button', { name: 'Clone', exact: true })).toBeDisabled();
    await feedback.scrollIntoViewIfNeeded();
    await expectContrast(page, { rootSelector: '.channel-admin-status',
      reportName: `channel-bulk-pending-${theme}` });
    await page.screenshot({ path: test.info().outputPath(`channel-bulk-pending-${theme}.png`) });
    // Invoke the real navigation handler while the write response is still held.
    await page.locator('a[data-view="aliases"]').evaluate(element => element.click());
    await expect(page.locator('.alias-editor-workspace')).toBeVisible();
    expect(held.writes).toHaveLength(1);
    const response = page.waitForResponse('**/api/v1/admin/channels/actions');
    held.release('success');
    await (await response).finished();
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    await expect(page.locator('.alias-editor-workspace')).toBeVisible();
    expect(fixture.pageErrors).toEqual([]);
  });

  test(`alias bulk deletion shows progress and preserves confirmation on conflict ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    const fixture = await installSiteStyleApplication(page, theme);
    const held = await holdMutation(page, '**/api/v1/admin/aliases/bulk');
    await page.goto('/app.html?view=aliases&list=1&aliasTab=configure');
    await expect(page.locator('.alias-editor-table-host')).toContainText('Fire Dispatch');
    await page.locator('.alias-editor-table-host tbody .ui-selection-check').first().check();
    await page.locator('.alias-bulk-bar').getByRole('button', { name: 'Delete', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Delete · 1 alias', exact: true });
    const confirmation = dialog.getByRole('checkbox');
    await confirmation.check();
    await dialog.getByRole('button', { name: 'Delete 1 Alias', exact: true }).click();
    await expectPending(dialog, 'Deleting 1 alias…');
    await expect(dialog.getByRole('button', { name: 'Cancel', exact: true })).toBeDisabled();
    expect(held.writes).toHaveLength(1);
    held.release('failure');
    await expect(dialog).toContainText('Reload current values before trying again.');
    await expect(confirmation).toBeChecked();
    await expect(confirmation).toBeEnabled();
    await expect(dialog.getByRole('button', { name: 'Delete 1 Alias', exact: true })).toBeEnabled();
    expect(held.writes).toHaveLength(1);
    expect(fixture.pageErrors).toEqual([]);
  });

  test(`recording bulk deletion reports unconfirmed disconnects ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    const fixture = await installSiteStyleApplication(page, theme);
    const held = await holdMutation(page, '**/api/v1/admin/recordings/calls');
    await page.goto('/app.html?view=recordings');
    await page.getByRole('checkbox', { name: 'Select all calls on this page', exact: true }).check();
    await page.getByRole('button', { name: 'Review delete', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Delete recordings', exact: true });
    await dialog.getByRole('button', { name: 'Delete recordings', exact: true }).click();
    await expectPending(dialog, 'Deleting 1 recording…');
    held.release('disconnect');
    await expect(dialog).toContainText('The receiver did not confirm the result. Check the current state before trying again.');
    await expect(dialog.getByRole('button', { name: 'Delete recordings', exact: true })).toBeEnabled();
    expect(held.writes).toHaveLength(1);
    await expect(page.locator('.recordings-call-select:checked')).toHaveCount(1);
    expect(fixture.pageErrors).toEqual([]);
  });

  test(`recording maintenance shows progress while its request is pending ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    const fixture = await installSiteStyleApplication(page, theme);
    const held = await holdMutation(page, '**/api/v1/admin/recordings/recount');
    await page.goto('/app.html?view=admin&tab=recordings');
    await page.locator('.recordings-admin details.ui-section-disclosure > summary').click();
    await page.getByRole('button', { name: 'Run recount calls', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Recount calls', exact: true });
    await dialog.getByRole('button', { name: 'Run recount calls', exact: true }).click();
    await expectPending(dialog, 'Recount calls…');
    await expect(dialog.getByRole('button', { name: 'Cancel', exact: true })).toBeDisabled();
    held.release('failure');
    await expect(dialog).toContainText('Reload current values before trying again.');
    await expect(dialog.getByRole('button', { name: 'Run recount calls', exact: true })).toBeEnabled();
    expect(held.writes).toHaveLength(1);
    expect(fixture.pageErrors).toEqual([]);
  });

  test(`catalog CSV export shows progress until the complete download is ready ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    const fixture = await installSiteStyleApplication(page, theme);
    let release;
    const gate = new Promise(resolve => { release = resolve; });
    let requests = 0;
    await page.route('**/api/v1/exports/channels.csv', async route => {
      requests++;
      await gate;
      return route.fulfill({ contentType: 'text/csv', headers: {
        'Content-Disposition': 'attachment; filename="channels.csv"'
      }, body: 'name\nNorth County Simulcast\n' });
    });
    await page.goto('/app.html?view=channel-setup');
    const action = page.locator('.channel-catalog-tools .export-csv-action');
    await action.click();
    await expect(action).toHaveClass(/is-loading/);
    await expect(action).toHaveAttribute('aria-busy', 'true');
    await page.getByRole('searchbox', { name: 'Search channels, systems, protocols, frequencies, or alias lists' }).fill('North');
    await expect(page.locator('.channel-catalog-table tbody tr[data-id]')).toHaveCount(1);
    const downloaded = page.waitForEvent('download');
    release();
    expect((await downloaded).suggestedFilename()).toBe('channels.csv');
    await expect(action).not.toHaveAttribute('aria-busy', 'true');
    expect(requests).toBe(1);
    expect(fixture.pageErrors).toEqual([]);
  });
}
