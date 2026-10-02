'use strict';

const { test, expect } = require('@playwright/test');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

const preview = {
  revision: 1,
  digest: 'alias-transfer-review-fixture',
  destination_list: 'County Public Safety',
  counts: { added: 1, updated: 1, unchanged: 1, deleted: 0, error: 0 },
  total: 3,
  rows: [
    { row: 2, result: 'added', name: 'Fireground 2',
      changes: [{ field: 'name', before: '', after: 'Fireground 2' }] },
    { row: 3, result: 'updated', name: 'Fire Dispatch',
      changes: [{ field: 'description', before: 'Dispatch', after: 'County fire dispatch' }] },
    { row: 4, result: 'unchanged', name: 'Engine 4', changes: [] }
  ]
};

for (const width of [320, 390]) {
  for (const theme of ['light', 'dark']) {
    test(`Alias import review keeps expanded changes and actions bounded ${theme} ${width}`, async ({ page }) => {
      await page.setViewportSize({ width, height: 568 });
      const fixture = await installSiteStyleApplication(page, theme);
      const transferRequests = [];
      await page.route('**/api/v1/admin/alias-lists/1/transfer', async (route) => {
        expect(route.request().method()).toBe('POST');
        const request = route.request().postDataJSON();
        transferRequests.push(request);
        expect(request.format).toBe('RADIOREFERENCE');
        expect(request.mode).toBe('UPDATE_ADD');
        expect(request.csv).toContain('Fireground 2');
        if (request.action === 'preview') {
          return route.fulfill({ json: { data: preview } });
        }
        expect(request.action).toBe('apply');
        expect(request.revision).toBe(preview.revision);
        expect(request.digest).toBe(preview.digest);
        return route.fulfill({ json: { data: { revision: 2 } } });
      });

      await page.goto('/app.html?view=aliases&list=1&aliasTab=configure');
      await expect(page.locator('.alias-editor-table-section')).toContainText('Fire Dispatch');
      await page.locator('.alias-transfer-import-button').click();
      const modal = page.getByRole('dialog', { name: 'Import aliases into County Public Safety', exact: true });
      await expect(modal).toBeVisible();
      await modal.locator('input[type="file"]').setInputFiles({
        name: 'county-talkgroups.csv', mimeType: 'text/csv',
        buffer: Buffer.from('Decimal,Hex,Alpha Tag,Mode,Description,Tag,Category\n' +
          '1202,4B2,Fireground 2,D,County fireground,Fire-Tac,Fire\n')
      });
      await modal.getByRole('button', { name: 'Continue', exact: true }).click();
      await modal.getByRole('button', { name: 'Review import', exact: true }).click();
      const review = modal.locator('.alias-transfer-review');
      await expect(review.getByRole('heading', { name: 'Review changes', exact: true })).toBeVisible();
      await expect(review).toContainText('Importing into County Public Safety');
      await expect(review.locator('.alias-transfer-row')).toHaveCount(3);
      await expect(review.getByRole('button', { name: 'All 3', exact: true })).toBeVisible();
      await expect(review).toContainText('Fire Dispatch');
      await expect(review).toContainText('Engine 4');
      const changes = review.locator('.alias-transfer-row').first();
      await changes.locator('summary').click();
      await expect(changes.locator('table')).toContainText('Fireground 2');
      await expect(changes.locator('table')).toContainText('Proposed');

      const apply = review.getByRole('button', { name: 'Import 2 changes', exact: true });
      await apply.scrollIntoViewIfNeeded();
      const bounds = await modal.evaluate((element) => {
        const dialog = element.getBoundingClientRect();
        const footer = element.querySelector('.alias-transfer-review .ui-modal-footer');
        const actionBoxes = [...footer.querySelectorAll('button')].map(button => {
          const box = button.getBoundingClientRect();
          return { label: button.textContent, left: box.left, right: box.right,
            top: box.top, bottom: box.bottom };
        });
        const table = element.querySelector('.alias-transfer-review .ui-table-wrap').getBoundingClientRect();
        return { dialog: { left: dialog.left, right: dialog.right },
          viewport: { width: innerWidth, height: innerHeight }, actionBoxes,
          table: { left: table.left, right: table.right } };
      });
      for (const box of bounds.actionBoxes) {
        expect(box.left, `${box.label} stays inside the dialog`).toBeGreaterThanOrEqual(bounds.dialog.left);
        expect(box.right, `${box.label} stays inside the dialog`).toBeLessThanOrEqual(bounds.dialog.right);
        expect(box.top).toBeGreaterThanOrEqual(0);
        expect(box.bottom).toBeLessThanOrEqual(bounds.viewport.height);
      }
      expect(bounds.table.left).toBeGreaterThanOrEqual(bounds.dialog.left);
      expect(bounds.table.right).toBeLessThanOrEqual(bounds.dialog.right);
      await expectNoHorizontalOverflow(page);
      await apply.click();
      await expect(page.getByRole('dialog', { name: 'Alias import complete', exact: true })).toContainText(
        '1 added · 1 updated · 0 deleted · 1 unchanged');
      expect(transferRequests.map(request => request.action)).toEqual(['preview', 'apply']);
      expect(fixture.unexpected).toEqual([]);
      expect(fixture.pageErrors).toEqual([]);
    });
  }
}
