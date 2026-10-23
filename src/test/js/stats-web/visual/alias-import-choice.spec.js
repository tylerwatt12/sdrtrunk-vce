'use strict';

const { test, expect } = require('@playwright/test');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');

const choiceDialog = page => page.getByRole('dialog', {
  name: 'How would you like to import aliases?', exact: true
});

async function openAliases(page, theme = 'light') {
  const fixture = await installSiteStyleApplication(page, theme);
  await page.goto('/app.html?view=aliases&list=1&aliasTab=configure');
  await expect(page.locator('.alias-editor-table-section')).toContainText('Fire Dispatch');
  return fixture;
}

async function expectWithinViewport(locator, viewport) {
  const bounds = await locator.boundingBox();
  expect(bounds).not.toBeNull();
  expect(bounds.x).toBeGreaterThanOrEqual(0);
  expect(bounds.y).toBeGreaterThanOrEqual(0);
  expect(bounds.x + bounds.width).toBeLessThanOrEqual(viewport.width);
  expect(bounds.y + bounds.height).toBeLessThanOrEqual(viewport.height);
}

for (const theme of ['light', 'dark']) {
  for (const viewport of [{ width: 1280, height: 800 }, { width: 320, height: 568 }]) {
    test(`alias import offers RadioReference and file with keyboard focus ${theme} ${viewport.width}`, async ({ page }) => {
      await page.setViewportSize(viewport);
      const fixture = await openAliases(page, theme);
      const writes = [];
      page.on('request', request => {
        if (new URL(request.url()).pathname.startsWith('/api/v1/admin/') &&
            request.method() !== 'GET') writes.push(request.url());
      });
      const trigger = page.locator('.alias-transfer-import-button');
      await trigger.focus();
      await page.keyboard.press('Enter');
      const chooser = choiceDialog(page);
      await expect(chooser).toBeVisible();
      const radioReference = chooser.getByRole('button', { name: 'Import from RadioReference', exact: true });
      const file = chooser.getByRole('button', { name: 'Import from a file', exact: true });
      await expect(radioReference).toBeFocused();
      await expect(radioReference).toBeEnabled();
      await expect(file).toBeEnabled();
      await expect(page.getByRole('dialog', { name: 'Import aliases into County Public Safety', exact: true })).toHaveCount(0);
      await expectWithinViewport(chooser, viewport);
      await expectWithinViewport(radioReference, viewport);
      await expectWithinViewport(file, viewport);
      await expectNoHorizontalOverflow(page);
      await expectContrast(page, { rootSelector: '[role="dialog"][aria-labelledby^="read-only-modal-title-import-aliases-choice-"]',
        reportName: `alias-import-choice-${theme}-${viewport.width}` });
      await chooser.screenshot({ path: test.info().outputPath(`alias-import-choice-${theme}-${viewport.width}.png`),
        animations: 'disabled' });
      await page.keyboard.press('Escape');
      await expect(chooser).toHaveCount(0);
      await expect(trigger).toBeFocused();
      expect(writes).toEqual([]);
      await page.keyboard.press('Enter');
      await file.click();
      const transfer = page.getByRole('dialog', { name: 'Import aliases into County Public Safety', exact: true });
      await expect(chooser).toHaveCount(0);
      await expect(transfer).toBeVisible();
      await expect(transfer.locator('input[type="file"]')).toHaveCount(1);
      await page.keyboard.press('Escape');
      await expect(transfer).toHaveCount(0);
      await expect(trigger).toBeFocused();
      expect(writes).toEqual([]);
      expect(fixture.unexpected).toEqual([]);
      expect(fixture.pageErrors).toEqual([]);
    });
  }
}

test('alias import opens RadioReference with the current Alias List and keeps it after reload', async ({ page }) => {
  const fixture = await openAliases(page);
  await page.locator('.alias-transfer-import-button').click();
  await choiceDialog(page).getByRole('button', { name: 'Import from RadioReference', exact: true }).click();
  await expect(choiceDialog(page)).toHaveCount(0);
  await expect(page).toHaveURL(/view=radioreference&from=aliases&list=1$/);
  await expect(page.getByRole('heading', { name: 'Connect your account', exact: true })).toBeVisible();
  // The visual server mounts the app at /app.html and uses / for the gallery.
  await page.goto(`/app.html${new URL(page.url()).search}`);
  await expect(page).toHaveURL(/view=radioreference&from=aliases&list=1$/);
  await expect(page.getByRole('heading', { name: 'Connect your account', exact: true })).toBeVisible();
  expect(fixture.unexpected).toEqual([]);
  expect(fixture.pageErrors).toEqual([]);
});
