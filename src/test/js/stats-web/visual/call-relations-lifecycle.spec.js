'use strict';

const { expect, test } = require('@playwright/test');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');

for (const delayed of ['module', 'catalog', 'failed-module']) {
  test(`Related activity stays closed after navigation while its ${delayed} loads`, async ({ page }) => {
    const fixture = await installSiteStyleApplication(page);
    let release, started;
    const held = new Promise(resolve => { release = resolve; });
    const loading = new Promise(resolve => { started = resolve; });
    const relatedRequests = [];
    await page.route('**/api/v1/call-relations/**', route => {
      relatedRequests.push(route.request().url());
      return route.fulfill({ json: { data: { activity_rows: [], recordings: [], alerts: [] } } });
    });
    await page.route('**/api/v1/activity/message-types', async route => {
      if (delayed === 'catalog') { started(); await held; }
      await route.fulfill({ json: { data: [] } });
    });
    if (delayed !== 'catalog') await page.route('**/assets/features/call-relations.js*', async route => {
      started();
      await held;
      if (delayed === 'failed-module') await route.fulfill({ status: 503, body: 'Unavailable' });
      else await route.continue();
    });
    try {
      await page.goto('/app.html?view=recordings');
      await expect(page.locator('.recordings-call')).toHaveCount(1);
      await page.locator('.recordings-call-info').click();
      await page.getByRole('dialog', { name: 'Call actions', exact: true })
        .getByRole('button', { name: 'Related activity and alerts', exact: true }).click();
      await loading;
      await expect(page.getByRole('dialog', { name: 'Call actions', exact: true })).toHaveCount(0);
      await page.getByRole('link', { name: 'VCE home', exact: true }).click();
      await expect(page.locator('body')).toHaveAttribute('data-view', 'dashboard');
      await expect(page.locator('.radio-directory-site-row')).toHaveCount(1);
      const response = page.waitForResponse(delayed === 'catalog' ? '**/api/v1/activity/message-types' :
        '**/assets/features/call-relations.js*');
      release();
      await response;
      // Wait for the real module and queued catalog handlers, without a timing-based assertion delay.
      await page.evaluate(async failureExpected => {
        await import('/assets/features/call-relations.js?v=4').catch(error => {
          if (!failureExpected) throw error;
        });
        await new Promise(requestAnimationFrame);
        await new Promise(requestAnimationFrame);
      }, delayed === 'failed-module');
      await expect(page.getByRole('dialog', { name: 'Call details', exact: true })).toHaveCount(0);
      expect(relatedRequests).toEqual([]);
      expect(fixture.pageErrors).toEqual([]);
      expect(fixture.unexpected).toEqual([]);
    } finally { release(); }
  });
}
