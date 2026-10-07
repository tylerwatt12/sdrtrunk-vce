'use strict';

const { test, expect } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { installSiteStyleApplication, channelId } = require('./fixtures/site-style-app.cjs');

const schema = ['status', 'tags', 'channel', 'frequency', 'signal', 'decode-health',
  'source-alias', 'source', 'target-alias', 'target', 'decoder'];
const baseRow = { key: 'identity-call', configuration_id: channelId, lcn: 12,
  frequency_hz: 451012500, status: 'CALL', tags: ['VOICE'], role: 'TRAFFIC', activation_order: 2,
  protocol: 'DMR', decoder: 'DMR', source_id: '65536', source_form: 'RADIO',
  source_alias: 'Portable 6', target_id: '54321', target_form: 'TALKGROUP', target_alias: 'Dispatch' };
let defaults;
test.beforeAll(async () => {
  defaults = (await import(pathToFileURL(path.resolve(__dirname,
    '../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults;
});

async function openLive(page, theme, { hidden = [], row = {}, sourceMode = 'both' } = {}) {
  const snapshot = { revision: 1, tables: [{ table_id: channelId, configuration_id: channelId,
    title: 'Identity columns', channel_running: true, rows: [{ ...baseRow, ...row }] }] };
  const fixture = await installSiteStyleApplication(page, theme, snapshot);
  let preferences = structuredClone(defaults), revision = 1;
  preferences.appearance.theme = theme;
  preferences.presentation.source_name_display = sourceMode;
  preferences.tables['live-channels'] = { schema, column_order: schema,
    column_widths: {}, hidden_columns: hidden, collapsed_groups: [] };
  const writes = [];
  await page.route('**/api/v1/me/preferences', route => {
    const request = route.request();
    if (request.method() === 'PUT') {
      expect(request.headers()['if-match']).toBe(`"${revision}"`);
      preferences = request.postDataJSON();
      writes.push(structuredClone(preferences));
      revision += 1;
    }
    return route.fulfill({ json: { revision, preferences } });
  });
  await page.goto(`/app.html?view=live&channel=${channelId}`);
  const liveRow = page.locator('.channels-live-table tbody tr[data-id="identity-call"]');
  await expect(liveRow).toBeVisible();
  return { row: liveRow, writes, fixture, snapshot };
}

const cell = (row, role) => row.locator(`[data-column="${role}"]`);
async function expectNumericFallback(row, role, mobile) {
  const id = cell(row, role);
  await expect(id).toBeVisible();
  await expect(id).toHaveText(role === 'source' ? '65536' : '54321');
  if (mobile) {
    await expect(id).toHaveCSS('grid-column-start', role === 'source' ? '1' : '7');
    await expect(id).toHaveCSS('grid-column-end', 'span 6');
    await expect.poll(() => id.evaluate((element) => getComputedStyle(element, '::before').content))
      .toBe(role === 'source' ? '"From"' : '"To"');
  }
}
function expectClean(fixture) {
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
  expect(fixture.requests.filter(request => /admin\/channels\/.+\/(start|stop)/.test(request))).toEqual([]);
}

for (const theme of ['light', 'dark']) {
  for (const width of [390, 760, 761, 1280]) {
    test(`Live alias columns and numeric fallbacks ${theme} ${width}px`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      let app = await openLive(page, theme);
      for (const role of ['source', 'target']) {
        await expect(cell(app.row, `${role}-alias`)).toBeVisible();
        if (width <= 760) await expect(cell(app.row, role)).toBeHidden();
        else await expectNumericFallback(app.row, role, false);
      }
      expectClean(app.fixture);
      await page.unrouteAll({ behavior: 'wait' });
      app = await openLive(page, theme, { hidden: ['source-alias', 'target-alias'] });
      for (const role of ['source', 'target']) {
        await expect(cell(app.row, `${role}-alias`)).toHaveCount(0);
        await expectNumericFallback(app.row, role, width <= 760);
      }
      expectClean(app.fixture);
    });
  }

  for (const alias of ['', '   ']) {
    test(`Live empty alias fallback ${theme} ${JSON.stringify(alias)}`, async ({ page }) => {
      await page.setViewportSize({ width: 390, height: 844 });
      const app = await openLive(page, theme, { row: { source_alias: alias, target_alias: alias } });
      for (const role of ['source', 'target']) {
        await expect(cell(app.row, `${role}-alias`)).toBeEmpty();
        await expect(cell(app.row, `${role}-alias`)).toBeHidden();
        await expectNumericFallback(app.row, role, true);
      }
      expectClean(app.fixture);
    });
  }

  for (const sourceMode of ['source_alias', 'talker_alias', 'both']) {
    test(`Live OTA and reference labels follow ${sourceMode} in ${theme}`, async ({ page }) => {
      await page.setViewportSize({ width: 390, height: 844 });
      const app = await openLive(page, theme, { sourceMode, row: { source_alias: '',
        talker_alias: 'Operator 6', target_alias: '', target_aliases: [
          { alias_id: 101, alias_list_id: 1, name: 'Dispatch' }] } });
      await expect(cell(app.row, 'source-alias')).toHaveText('Operator 6');
      await expect(cell(app.row, 'target-alias')).toHaveText('Dispatch');
      for (const role of ['source', 'target']) await expect(cell(app.row, role)).toBeHidden();
      expectClean(app.fixture);
    });
  }

  test(`Live alias arrival and removal refresh numeric fallback in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    const app = await openLive(page, theme);
    for (const role of ['source', 'target']) await expect(cell(app.row, role)).toBeHidden();
    const update = async (revision, values) => {
      const snapshot = { ...app.snapshot, revision, tables: [{ ...app.snapshot.tables[0],
        rows: [{ ...baseRow, ...values }] }] };
      await page.evaluate(snapshot => window.fixtureSendLiveSnapshot(snapshot), snapshot);
    };
    await update(2, { source_alias: '', target_alias: '' });
    for (const role of ['source', 'target']) {
      await expect(cell(app.row, `${role}-alias`)).toBeEmpty();
      await expectNumericFallback(app.row, role, true);
    }
    await update(3, { source_alias: '', talker_alias: 'Operator 6', target_alias: 'Dispatch' });
    await expect(cell(app.row, 'source-alias')).toHaveText('Operator 6');
    await expect(cell(app.row, 'target-alias')).toHaveText('Dispatch');
    for (const role of ['source', 'target']) await expect(cell(app.row, role)).toBeHidden();
    expectClean(app.fixture);
  });

  test(`Live column toggles and reorder preserve independent IDs in ${theme}`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 390, height: 844 });
    const app = await openLive(page, theme);
    await page.locator('.live-channels-title-actions')
      .getByRole('button', { name: 'Choose table columns', exact: true }).click();
    const chooser = page.getByRole('dialog', { name: 'Table columns', exact: true });
    for (const role of ['Source', 'Target']) {
      await chooser.getByRole('checkbox', { name: `Show ${role} Alias column`, exact: true }).uncheck();
      await expect(cell(app.row, `${role.toLowerCase()}-alias`)).toHaveCount(0);
      await expectNumericFallback(app.row, role.toLowerCase(), true);
      await expect(chooser.getByRole('checkbox', { name: `Show ${role} ID column`, exact: true })).toBeChecked();
    }
    await chooser.getByRole('button', { name: 'Move Target ID left', exact: true }).click();
    await expect.poll(() => app.writes.at(-1)?.tables['live-channels'].column_order.indexOf('target'))
      .toBeLessThan(schema.indexOf('target'));
    await expectNumericFallback(app.row, 'target', true);
    await chooser.getByRole('checkbox', { name: 'Show Target ID column', exact: true }).uncheck();
    await expect(cell(app.row, 'target')).toHaveCount(0);
    await expectNumericFallback(app.row, 'source', true);
    await chooser.getByRole('checkbox', { name: 'Show Target ID column', exact: true }).check();
    await expectNumericFallback(app.row, 'target', true);
    await page.keyboard.press('Escape');
    await expect(chooser).toBeHidden();
    await page.locator('.live-channels-section').screenshot({ path: testInfo.outputPath(`identity-fallback-${theme}.png`) });
    await page.goto(`/app.html?view=live&channel=${channelId}`);
    for (const role of ['source', 'target']) {
      await expect(cell(app.row, `${role}-alias`)).toHaveCount(0);
      await expectNumericFallback(app.row, role, true);
    }
    expect(app.writes).toHaveLength(5);
    expectClean(app.fixture);
  });
}

for (const protocol of ['P25_PHASE1', 'NXDN']) {
  test(`Live numeric fallback is shared by ${protocol} conventional rows`, async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    const app = await openLive(page, 'light', { hidden: ['source-alias', 'target-alias'],
      row: { protocol, decoder: protocol, tags: ['CONVENTIONAL'], channel_name: 'Digital channel' } });
    for (const role of ['source', 'target']) await expectNumericFallback(app.row, role, true);
    expectClean(app.fixture);
  });
}
