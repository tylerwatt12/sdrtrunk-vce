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

function transferOptions(request) {
  expect(request.headers()['content-type']).toMatch(/^multipart\/form-data; boundary=/);
  const options = request.postData().match(/name="options"\r\n\r\n([^\r]+)\r\n/);
  expect(options).not.toBeNull();
  return JSON.parse(options[1]);
}

for (const width of [320, 390]) {
  for (const theme of ['light']) {
    test(`Alias import review keeps expanded changes and actions bounded ${theme} ${width}`, async ({ page }) => {
      await page.setViewportSize({ width, height: 568 });
      const fixture = await installSiteStyleApplication(page, theme);
      const transferRequests = [];
      await page.route('**/api/v1/admin/alias-lists/1/transfer', async (route) => {
        expect(route.request().method()).toBe('POST');
        const request = transferOptions(route.request());
        transferRequests.push(request);
        expect(request.format).toBe('RADIOREFERENCE');
        expect(request.mode).toBe('UPDATE_ADD');
        expect(route.request().postData()).toContain('Fireground 2');
        expect(request).not.toHaveProperty('csv');
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
      await page.getByRole('dialog', { name: 'How would you like to import aliases?', exact: true })
        .getByRole('button', { name: 'Import from a file', exact: true }).click();
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


test('Alias import preview and apply provide pending feedback without losing the reviewed draft', async ({ page }) => {
  const fixture = await installSiteStyleApplication(page);
  const held = [];
  const requests = [];
  await page.route('**/api/v1/admin/alias-lists/1/transfer', async route => {
    const request = transferOptions(route.request());
    requests.push(request);
    await new Promise(resolve => held.push(resolve));
    if (request.action === 'preview') return route.fulfill({ json: { data: preview } });
    return route.fulfill({ status: 409, json: { error: { code: 'stale_revision',
      message: 'Saved aliases changed' } } });
  });
  await page.goto('/app.html?view=aliases&list=1&aliasTab=configure');
  await page.locator('.alias-transfer-import-button').click();
  await page.getByRole('dialog', { name: 'How would you like to import aliases?', exact: true })
    .getByRole('button', { name: 'Import from a file', exact: true }).click();
  const modal = page.getByRole('dialog', { name: 'Import aliases into County Public Safety', exact: true });
  await modal.locator('input[type="file"]').setInputFiles({ name: 'talkgroups.csv', mimeType: 'text/csv',
    buffer: Buffer.from('Decimal,Hex,Alpha Tag,Mode,Description,Tag,Category\n' +
      '1202,4B2,Fireground 2,D,County fireground,Fire-Tac,Fire\n') });
  await modal.getByRole('button', { name: 'Continue', exact: true }).click();
  await modal.getByRole('button', { name: 'Review import', exact: true }).click();
  await expect(modal.locator('.ui-feedback-loading')).toHaveText('Checking import changes…');
  await expect(modal).toHaveAttribute('aria-busy', 'true');
  await page.keyboard.press('Escape');
  await expect(modal).toBeVisible();
  await expect.poll(() => held.length).toBe(1);
  held.shift()();
  const apply = modal.getByRole('button', { name: 'Import 2 changes', exact: true });
  await apply.click();
  await expect(modal.locator('.ui-feedback-loading')).toHaveText('Importing aliases…');
  await expect(apply).toBeDisabled();
  await expect.poll(() => held.length).toBe(1);
  held.shift()();
  await expect(modal).toContainText('Saved aliases changed. Preview again before retrying.');
  await expect(modal.locator('input[type="file"]')).toHaveValue(/talkgroups.csv/);
  expect(requests.map(request => request.action)).toEqual(['preview', 'apply']);
  expect(fixture.pageErrors).toEqual([]);
});

test('server import row-limit rejection preserves the file and options for another preview', async ({ page }) => {
  const fixture = await installSiteStyleApplication(page);
  const requests = [];
  await page.route('**/api/v1/admin/alias-lists/1/transfer', async route => {
    const request = transferOptions(route.request());
    requests.push(request);
    if (requests.length === 1) return route.fulfill({ status: 400, json: { error: {
      code: 'invalid_request',
      message: 'CSV must contain at most 100,000 aliases. Split it into smaller files and try again'
    } } });
    return route.fulfill({ json: { data: preview } });
  });
  await page.goto('/app.html?view=aliases&list=1&aliasTab=configure');
  await page.locator('.alias-transfer-import-button').click();
  await page.getByRole('button', { name: 'Import from a file', exact: true }).click();
  const modal = page.getByRole('dialog', { name: 'Import aliases into County Public Safety', exact: true });
  await expect(modal).toContainText('Up to 100,000 aliases and 128 MiB');
  const file = modal.locator('input[type="file"]');
  await file.setInputFiles({ name: 'talkgroups.csv', mimeType: 'text/csv',
    buffer: Buffer.from('Decimal,Hex,Alpha Tag,Mode,Description,Tag,Category\n' +
      '1202,4B2,Fireground 2,D,County fireground,Fire-Tac,Fire\n') });
  await modal.getByRole('button', { name: 'Continue', exact: true }).click();
  const replacement = modal.getByRole('radio', { name: 'Replace this list’s aliases', exact: false });
  await replacement.check();
  await modal.getByText('Optional: override defaults for this import', { exact: true }).click();
  const recording = modal.getByRole('combobox', { name: 'Recording', exact: true });
  await recording.selectOption('true');
  const review = modal.getByRole('button', { name: 'Review import', exact: true });
  await review.click();
  await expect(modal).toContainText('CSV must contain at most 100,000 aliases. Split it into smaller files and try again');
  await expect(file).toHaveValue(/talkgroups.csv/);
  await expect(replacement).toBeChecked();
  await expect(recording).toHaveValue('true');
  await expect(review).toBeEnabled();
  await expect(modal).not.toHaveAttribute('aria-busy', 'true');
  await review.click();
  await expect(modal.locator('.alias-transfer-row')).toHaveCount(3);
  expect(requests).toHaveLength(2);
  expect(requests[0]).toEqual(requests[1]);
  expect(requests[1]).toMatchObject({ mode: 'REPLACE', defaults: { recordable: true }, action: 'preview' });
  expect(fixture.pageErrors).toEqual([]);
});
