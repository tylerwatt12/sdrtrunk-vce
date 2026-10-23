'use strict';

const { expect, test } = require('@playwright/test');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');

for (const version of ['changed', 'unchanged', 'missing', 'malformed']) {
  test(`Recordings-only visitors handle ${version} API revisions without a status probe`, async ({ page }) => {
    const fixture = await installSiteStyleApplication(page);
    await page.clock.install();
    let documentRevision = '466', responseRevision = '466', documents = 0, sessions = 0;
    await page.route('**/app.html*', async route => {
      documents++;
      const response = await route.fetch();
      const body = (await response.text()).replace(/name="sdrtrunk-web-revision" content="[^"]+"/,
        `name="sdrtrunk-web-revision" content="${documentRevision}"`);
      await route.fulfill({ response, body });
    });
    await page.route('**/api/v1/auth/session', route => {
      sessions++;
      return route.fulfill({ headers: responseRevision === null ? {} : { 'X-Sdrtrunk-Web-Revision': responseRevision },
        json: { data: { configured: true, authenticated: false, capabilities: {
          'web-access': true, recordings: true, dashboard: false, radio: false
        } } } });
    });
    await page.goto('/app.html?view=recordings');
    await expect(page.locator('.recordings-call')).toHaveCount(1);
    expect(documents).toBe(1);
    const initialSessions = sessions;
    responseRevision = { changed: '467', unchanged: '466', missing: null, malformed: '<invalid revision>' }[version];
    if (version === 'changed') documentRevision = '467';
    await page.clock.fastForward(10_000);
    await expect.poll(() => sessions).toBeGreaterThan(initialSessions);
    if (version === 'changed') {
      await expect.poll(() => documents).toBe(2);
      await expect(page.locator('meta[name="sdrtrunk-web-revision"]')).toHaveAttribute('content', '467');
      await expect(page.locator('.recordings-call')).toHaveCount(1);
    } else expect(documents).toBe(1);
    await page.clock.fastForward(10_000);
    await expect(page.locator('.recordings-call')).toHaveCount(1);
    expect(documents).toBe(version === 'changed' ? 2 : 1);
    expect(fixture.requests.filter(request => request.includes('/api/v1/status'))).toEqual([]);
    expect(fixture.requests.filter(request => request.startsWith('HEAD '))).toEqual([]);
    expect(fixture.pageErrors).toEqual([]);
    expect(fixture.unexpected).toEqual([]);
  });
}
