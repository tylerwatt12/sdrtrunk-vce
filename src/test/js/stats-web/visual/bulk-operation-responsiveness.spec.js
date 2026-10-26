'use strict';

const { test, expect } = require('@playwright/test');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');

// Check browser event-loop progress alongside each workflow's functional assertions.
// Record interval delays as diagnostics because they also include host scheduling.
async function startHeartbeat(page) {
  await page.evaluate(() => {
    const probe = { last: performance.now(), maximum: 0, ticks: 0 };
    probe.timer = setInterval(() => {
      const now = performance.now();
      probe.maximum = Math.max(probe.maximum, now - probe.last);
      probe.last = now;
      probe.ticks++;
    }, 10);
    window.bulkHeartbeat = probe;
  });
}

async function expectResponsive(page) {
  await expect.poll(() => page.evaluate(() => window.bulkHeartbeat.ticks)).toBeGreaterThan(5);
  const result = await page.evaluate(() => {
    const probe = window.bulkHeartbeat;
    clearInterval(probe.timer);
    return { maximum: Math.max(probe.maximum, performance.now() - probe.last), ticks: probe.ticks };
  });
  await test.info().attach('browser-heartbeat', { body: JSON.stringify(result), contentType: 'application/json' });
}

async function heldWrite(page, pattern) {
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  const writes = [];
  await page.route(pattern, async route => {
    if (route.request().method() === 'GET') return route.fallback();
    writes.push(route.request().postDataJSON());
    await gate;
    return route.fulfill({ status: 409, json: { error: {
      code: 'stale_revision', message: 'Reload current values before trying again.'
    } } });
  });
  return { writes, release };
}

async function installLargeAliases(page, count = 100_000) {
  const fixture = await installSiteStyleApplication(page);
  const list = { alias_list_id: 1, id: 1, name: 'County Public Safety', family: 'P25',
    alias_count: count, assigned_channel_count: 1 };
  const scan = { id: 1, name: 'Public Safety', published: true, alias_count: count,
    unmatched_alias_list_count: 0, edit_revision: 'members-v1' };
  const rows = Array.from({ length: 50 }, (_, index) => ({ alias_id: index + 1,
    alias_list_id: 1, alias_list_name: list.name, name: `Alias ${index + 1}`,
    matcher_type: 'TALKGROUP', identifier_display: `TG ${index + 1}` }));
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url());
    const respond = data => route.fulfill({ json: { data } });
    if (url.pathname === '/api/v1/admin/alias-lists') return respond({ revision: 1, alias_lists: [list] });
    if (url.pathname === '/api/v1/admin/scan-lists') return respond({ revision: 1, scan_lists: [scan] });
    if (url.pathname === '/api/v1/admin/aliases/options') return respond({ revision: 1,
      alias_list: list, scan_lists: [scan], streams: [] });
    if (url.pathname === '/api/v1/aliases/ids') return respond({ count: 10_000,
      alias_ids: Array.from({ length: 10_000 }, (_, index) => index + 1) });
    if (url.pathname === '/api/v1/aliases') return respond({ rows, total_count: count,
      offset: 0, limit: 50, has_more: true, next_offset: 50 });
    return route.fallback();
  });
  return fixture;
}

test('10,000 selected aliases keep bounded rows and responsive bulk membership feedback', async ({ page }) => {
  const fixture = await installLargeAliases(page);
  const held = await heldWrite(page, '**/api/v1/admin/scan-lists/1/members');
  await page.goto('/app.html?view=aliases&list=1&aliasTab=configure');
  await expect(page.locator('.alias-editor-table-host tbody tr')).toHaveCount(50);
  await startHeartbeat(page);
  await page.getByRole('button', { name: 'Select All Matching', exact: true }).click();
  await expect(page.locator('.alias-bulk-count')).toHaveText('10,000 selected');
  await page.locator('.alias-bulk-bar').getByRole('button', { name: 'Scan Lists', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Bulk · 10,000 aliases', exact: true });
  await dialog.getByRole('combobox', { name: 'Scan list', exact: true }).selectOption('1');
  await dialog.getByRole('button', { name: 'Apply Change', exact: true }).click();
  await expect(dialog.locator('.ui-feedback-loading')).toHaveText('Updating 10,000 aliases…');
  await page.keyboard.press('Escape');
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Apply Change', exact: true })).toBeDisabled();
  await expectResponsive(page);
  expect(held.writes).toHaveLength(1);
  expect(held.writes[0].alias_ids).toHaveLength(10_000);
  expect(held.writes[0]).toMatchObject({ edit_revision: 'members-v1', operation: 'add' });
  held.release();
  await expect(dialog).toContainText('Reload current values before trying again.');
  await expect(dialog.getByRole('combobox', { name: 'Scan list', exact: true })).toHaveValue('1');
  expect(fixture.pageErrors).toEqual([]);
});

for (const operation of ['add', 'remove']) {
  test(`entire 100,000-alias Scan List ${operation} sends a bounded scope and stays responsive`, async ({ page }) => {
    const fixture = await installLargeAliases(page);
    const held = await heldWrite(page, '**/api/v1/admin/scan-lists/1/members');
    await page.goto('/app.html?view=aliases&scanListId=1&aliasTab=configure');
    await expect(page.locator('.scan-list-members-workspace')).toBeVisible();
    await startHeartbeat(page);
    await page.locator(operation === 'add' ? '.scan-list-add-all' : '.scan-list-remove-all').click();
    const dialog = page.getByRole('dialog', { name: `${operation === 'add' ? 'Add' : 'Remove'} All · Public Safety`, exact: true });
    if (operation === 'add') await dialog.getByRole('combobox', { name: 'Alias list', exact: true }).selectOption('1');
    await dialog.getByRole('button', { name: operation === 'add' ? 'Add All Aliases' : 'Remove All Members', exact: true }).click();
    await expect(dialog.locator('.ui-feedback-loading')).toHaveText(operation === 'add' ? 'Adding aliases…' : 'Removing memberships…');
    await expectResponsive(page);
    expect(held.writes).toEqual([{ edit_revision: 'members-v1', operation,
      alias_scope: operation === 'add' ? { alias_list_id: 1 } : {} }]);
    expect(JSON.stringify(held.writes[0]).length).toBeLessThan(150);
    held.release();
    await expect(dialog).toContainText('Reload current values before trying again.');
    expect(fixture.pageErrors).toEqual([]);
  });
}

for (const action of ['START', 'STOP']) {
  test(`100-channel ${action.toLowerCase()} leaves filtering, pagination and navigation responsive`, async ({ page }) => {
    const fixture = await installSiteStyleApplication(page);
    const channels = Array.from({ length: 100 }, (_, index) => ({
      configuration_id: `11111111-1111-4111-8111-${String(index).padStart(12, '0')}`,
      protocol: 'NBFM', protocol_id: 'nbfm', protocol_label: 'NBFM', channel_kind: 'CONVENTIONAL',
      name: `County channel ${String(index + 1).padStart(3, '0')}`, system: 'County', site: '',
      frequencies_hz: [150000000 + index * 12500], processing_state: 'STOPPED', editable: true
    }));
    await page.route('**/api/v1/admin/channels', route => route.fulfill({ json: { data: { revision: 1, channels } } }));
    const held = await heldWrite(page, '**/api/v1/admin/channels/actions');
    await page.goto('/app.html?view=channel-setup');
    await expect(page.locator('.channel-catalog-table tbody tr[data-id]')).toHaveCount(20);
    await startHeartbeat(page);
    await page.getByRole('checkbox', { name: 'Select all matching channels', exact: true }).check();
    await page.locator('.channel-selection-bar').getByRole('button', { name: action === 'START' ? 'Start' : 'Stop', exact: true }).click();
    await expect(page.locator('.channel-admin-status')).toHaveText(`${action === 'START' ? 'Starting' : 'Stopping'} 100 channels…`);
    await page.getByRole('searchbox', { name: 'Search channels, systems, protocols, frequencies, or alias lists' }).fill('County channel 0');
    const next = page.getByRole('button', { name: 'Next', exact: true });
    await next.focus();
    await next.press('Enter');
    await expect(page.locator('.channel-catalog-table tbody tr[data-id]')).toHaveCount(20);
    await expect(page.locator('.channel-edit-name').first()).toBeDisabled();
    await page.locator('a[data-view="aliases"]').evaluate(element => element.click());
    await expect(page.locator('.alias-editor-workspace')).toBeVisible();
    await expectResponsive(page);
    expect(held.writes).toHaveLength(1);
    expect(held.writes[0]).toMatchObject({ action, revision: 1 });
    expect(held.writes[0].configuration_ids).toHaveLength(100);
    held.release();
    await expect(page.locator('.alias-editor-workspace')).toBeVisible();
    expect(fixture.pageErrors).toEqual([]);
  });
}

test('128 MiB alias import sends the original file and keeps review, paging and apply responsive', async ({ page }) => {
  const fixture = await installLargeAliases(page);
  await page.addInitScript(() => {
    const fetch = window.fetch.bind(window);
    window.importWrites = [];
    window.importRelease = null;
    window.fetch = async (input, options) => {
      if (!String(input).endsWith('/api/v1/admin/alias-lists/1/transfer')) return fetch(input, options);
      const metadata = JSON.parse(options.body.get('options'));
      window.importWrites.push({ metadata, originalFile: options.body.get('file') === window.largeImportFile,
        bytes: options.body.get('file').size, type: options.headers.get('Content-Type'),
        csrf: options.headers.get('X-CSRF-Token') });
      await new Promise(resolve => { window.importRelease = resolve; });
      if (metadata.action === 'apply') return new Response(JSON.stringify({ data: { revision: 2 } }),
        { headers: { 'Content-Type': 'application/json' } });
      return new Response(JSON.stringify({ data: { revision: 1, digest: 'preview-digest',
        destination_list: 'County Public Safety', total: 100_000,
        counts: { added: 100_000, updated: 0, unchanged: 0, deleted: 0, error: 0 },
        rows: Array.from({ length: 100 }, (_, index) => ({ name: `Alias ${metadata.offset + index + 1}`,
          row: metadata.offset + index + 1, result: 'added', changes: [] }))
      } }), { headers: { 'Content-Type': 'application/json' } });
    };
    const readText = Blob.prototype.text;
    Blob.prototype.text = function() {
      if (this.size > 16 * 1024) throw new Error('Large CSV data must stay in the original file.');
      return readText.call(this);
    };
  });
  await page.goto('/app.html?view=aliases&list=1&aliasTab=configure');
  await page.locator('.alias-transfer-import-button').click();
  await page.getByRole('button', { name: 'Import from a file', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Import aliases into County Public Safety', exact: true });
  await dialog.locator('input[type="file"]').evaluate(input => {
    const header = 'Decimal,Hex,Alpha Tag,Mode,Description,Tag,Category\n';
    const file = new File([header, 'x'.repeat(128 * 1024 * 1024 - header.length)], 'large-aliases.csv', { type: 'text/csv' });
    window.largeImportFile = file;
    const files = new DataTransfer();
    files.items.add(file);
    input.files = files.files;
    input.dispatchEvent(new Event('change', { bubbles: true }));
  });
  await dialog.getByRole('button', { name: 'Continue', exact: true }).click();
  await startHeartbeat(page);
  await dialog.getByRole('button', { name: 'Review import', exact: true }).click();
  await expect(dialog.locator('.ui-feedback-loading')).toHaveText('Checking import changes…');
  await expect.poll(() => page.evaluate(() => window.importWrites.length)).toBe(1);
  await page.keyboard.press('Escape');
  await expect(dialog).toBeVisible();
  await page.evaluate(() => window.importRelease());
  await expect(dialog.locator('.alias-transfer-row')).toHaveCount(100);
  await dialog.getByRole('button', { name: 'Next', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.importWrites.length)).toBe(2);
  await page.evaluate(() => window.importRelease());
  await expect(dialog.locator('.alias-transfer-row').first()).toContainText('Alias 101');
  await dialog.getByRole('button', { name: 'Import 100,000 changes', exact: true }).click();
  await expect(dialog.locator('.ui-feedback-loading')).toHaveText('Importing aliases…');
  await expect.poll(() => page.evaluate(() => window.importWrites.length)).toBe(3);
  await expectResponsive(page);
  const writes = await page.evaluate(() => window.importWrites);
  expect(writes.map(write => write.originalFile)).toEqual([true, true, true]);
  expect(writes.map(write => write.bytes)).toEqual(Array(3).fill(128 * 1024 * 1024));
  expect(writes.map(write => write.metadata.action)).toEqual(['preview', 'preview', 'apply']);
  expect(writes.map(write => write.metadata.offset)).toEqual([0, 100, undefined]);
  expect(writes[2].metadata).toMatchObject({ revision: 1, digest: 'preview-digest', confirm_replace: false });
  for (const write of writes) {
    expect(write.type).toBeNull();
    expect(write.csrf).toBeTruthy();
    expect(write.metadata).not.toHaveProperty('csv');
  }
  await page.evaluate(() => window.importRelease());
  await expect(page.getByRole('dialog', { name: 'Alias import complete', exact: true })).toBeVisible();
  expect(fixture.pageErrors).toEqual([]);
});

test('large Unicode destination overrides survive multipart import without blocking feedback', async ({ page }) => {
  const fixture = await installLargeAliases(page);
  const streams = Array.from({ length: 500 }, (_, index) => ({ configuration_id: `stream-${index}`,
    name: `${String(index).padStart(3, '0')} ${'🚒'.repeat(124)}` }));
  const scans = Array.from({ length: 500 }, (_, index) => ({ id: index + 1,
    name: `${String(index).padStart(3, '0')} ${'🚑'.repeat(124)}` }));
  const list = { alias_list_id: 1, id: 1, name: 'County Public Safety', family: 'P25', alias_count: 100_000,
    new_alias_behavior: { broadcast_configuration_ids: streams.map(row => row.configuration_id),
      scan_list_ids: scans.map(row => row.id) } };
  await page.route('**/api/v1/admin/alias-lists', route => route.fulfill({ json: { data: {
    revision: 1, alias_lists: [list] } } }));
  await page.route(url => url.pathname === '/api/v1/admin/aliases/options', route => route.fulfill({ json: { data: { revision: 1,
    alias_list: list, streams, scan_lists: scans } } }));
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  let request;
  await page.route('**/api/v1/admin/alias-lists/1/transfer', async route => {
    expect(route.request().headers()['content-type']).toMatch(/^multipart\/form-data; boundary=/);
    request = JSON.parse(route.request().postData().match(/name="options"\r\n\r\n([^\r]+)\r\n/)[1]);
    await gate;
    return route.fulfill({ status: 409, json: { error: { message: 'Saved aliases changed.' } } });
  });
  await page.goto('/app.html?view=aliases&list=1&aliasTab=configure');
  await startHeartbeat(page);
  await page.locator('.alias-transfer-import-button').click();
  await page.getByRole('button', { name: 'Import from a file', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Import aliases into County Public Safety', exact: true });
  await dialog.locator('input[type="file"]').setInputFiles({ name: 'aliases.csv', mimeType: 'text/csv',
    buffer: Buffer.from('Decimal,Hex,Alpha Tag,Mode,Description,Tag,Category\n1,1,Dispatch,D,,,Fire\n') });
  await dialog.getByRole('button', { name: 'Continue', exact: true }).click();
  await dialog.getByText('Optional: override defaults for this import', { exact: true }).click();
  for (const name of ['Override scan lists', 'Override streaming destinations']) {
    const toggle = dialog.getByRole('checkbox', { name, exact: true });
    await toggle.focus();
    await toggle.press('Space');
    await expect(toggle).toBeChecked();
  }
  await dialog.getByRole('button', { name: 'Review import', exact: true }).click();
  await expect(dialog.locator('.ui-feedback-loading')).toHaveText('Checking import changes…');
  await expect.poll(() => Boolean(request)).toBe(true);
  await expectResponsive(page);
  expect(request.defaults.scan_lists).toHaveLength(500);
  expect(request.defaults.streams).toHaveLength(500);
  expect(request.defaults.scan_lists.every((name, index) => name === scans[index].name)).toBe(true);
  expect(request.defaults.streams.every((name, index) => name === streams[index].name)).toBe(true);
  expect(Buffer.byteLength(JSON.stringify(request))).toBeGreaterThan(400_000);
  release();
  await expect(dialog).toContainText('Saved aliases changed.');
  expect(fixture.pageErrors).toEqual([]);
});

test('complete 16 MiB CSV download leaves independent navigation responsive', async ({ page }) => {
  const fixture = await installSiteStyleApplication(page);
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  const csv = 'name\n' + 'x'.repeat(16 * 1024 * 1024 - 5);
  await page.route('**/api/v1/exports/channels.csv', async route => {
    await gate;
    return route.fulfill({ contentType: 'text/csv', body: csv,
      headers: { 'Content-Disposition': 'attachment; filename="large-channels.csv"' } });
  });
  await page.goto('/app.html?view=channel-setup');
  await startHeartbeat(page);
  await page.locator('.channel-catalog-tools .export-csv-action').click();
  await expect(page.locator('.channel-catalog-tools .export-csv-action')).toHaveAttribute('aria-busy', 'true');
  await page.locator('a[data-view="aliases"]').evaluate(element => element.click());
  await expect(page.locator('.alias-editor-workspace')).toBeVisible();
  const download = page.waitForEvent('download');
  release();
  expect((await download).suggestedFilename()).toBe('large-channels.csv');
  await expectResponsive(page);
  await expect(page.locator('.alias-editor-workspace')).toBeVisible();
  expect(fixture.pageErrors).toEqual([]);
});
