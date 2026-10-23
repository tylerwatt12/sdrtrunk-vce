'use strict';

const { test, expect } = require('@playwright/test');
const { installSiteStyleApplication, channelId } = require('./fixtures/site-style-app.cjs');

const systemKey = 'p25:00001:047';
const sourceRef = { kind: 'radio', radio_system_key: systemKey, identity_key: 'v1-r-00001-047-65536' };
const targetRef = { kind: 'talkgroup', radio_system_key: systemKey, identity_key: 'v1-g-00001-047-7001' };
const voice = { key: 'selected-call', configuration_id: channelId, frequency_hz: 851_012_500,
  lcn: 12, status: 'CALL', role: 'TRAFFIC', tags: ['VOICE'], activation_order: 2,
  protocol: 'P25_PHASE1', decoder: 'P25_PHASE1',
  source_id: '65536', source_alias: 'Portable 6', source_form: 'RADIO', source_entity_ref: sourceRef,
  target_id: '7001', target_alias: 'Dispatch', target_form: 'TALKGROUP', target_entity_ref: targetRef,
  signal_dbfs: -50 };
const table = (id, title, rows) => ({ table_id: id, configuration_id: id, title,
  channel_running: true, rows });
const snapshot = { revision: 5, tables: [table(channelId, 'County dispatch', [voice,
  { ...voice, key: 'retired-call', activation_order: 1, source_id: '123' },
  { ...voice, key: 'later-call', activation_order: 3, source_id: '456' }]), table('second-site', 'West site', [])] };
const metadata = value => Object.fromEntries(Object.entries(value).filter(([key]) => key !== 'rows'));

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`Live batches retain focused identity rows and recover at a published boundary ${theme} ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 });
    const fixture = await installSiteStyleApplication(page, theme, snapshot);
    await page.goto(`/app.html?view=live&channel=${channelId}`);
    const selected = page.locator('.channels-live-table tr[data-id="selected-call"]');
    await expect(selected).toBeVisible();
    await page.getByRole('button', { name: 'Change audio player size', exact: true }).press('Home');
    await selected.focus();
    await selected.press('Enter');
    await expect(selected).toHaveAttribute('aria-selected', 'true');
    await selected.focus();
    await expect(selected).toBeFocused();
    await page.evaluate(() => { window.fixtureSelectedRow = document.activeElement; });
    const send = (event, data) => page.evaluate(({ event, data }) => window.fixtureSendLiveFrame(1, event, data), { event, data });
    const expectTypedLinks = async () => {
      for (const [column, reference, view] of [['source', sourceRef, 'radio'], ['target', targetRef, 'group-identity']]) {
        const links = selected.locator(`[data-column="${column}"]`).getByRole('link');
        await expect(links).toHaveCount(2);
        for (const link of await links.all()) {
          const parameters = new URL(await link.getAttribute('href'), page.url()).searchParams;
          expect(parameters.get('view')).toBe(view);
          expect(parameters.get('radio_system_key')).toBe(reference.radio_system_key);
          expect(parameters.get('identity_key')).toBe(reference.identity_key);
        }
      }
    };
    await expectTypedLinks();
    await send('activity_batch', { base_revision: 5, revision: 9, changes: [
      { operation: 'upsert', table_id: channelId, replace: false,
        rows: [{ ...voice, signal_dbfs: -42 }], removed_row_keys: ['retired-call'], row_order: ['selected-call', 'later-call'] },
      { operation: 'upsert', table_id: 'second-site', replace: true,
        table: metadata(table('second-site', 'West site updated', [])), rows: [], row_order: [] }
    ] });
    await expect(selected.locator('[data-column="signal"]')).toContainText('-42.0');
    await expect(page.locator('.channels-live-table tbody tr')).toHaveCount(2);
    await expect(page.locator('.channels-tab-title', { hasText: 'West site updated' })).toHaveCount(1);
    await expect(selected).toBeFocused();
    await expect(selected).toHaveAttribute('aria-selected', 'true');
    expect(await page.evaluate(() => document.activeElement === window.fixtureSelectedRow)).toBe(true);
    await expectTypedLinks();

    // Retained rows and identity links keep focus even when a real ordering change moves their DOM nodes.
    await send('activity_batch', { base_revision: 9, revision: 10, changes: [
      { operation: 'upsert', table_id: channelId, replace: false, rows: [{ ...voice, activation_order: 4 }] }
    ] });
    await expect(page.locator('.channels-live-table tbody tr').last()).toHaveAttribute('data-id', 'selected-call');
    const sourceLink = selected.locator('[data-column="source"]').getByRole('link').first();
    await sourceLink.focus();
    await page.evaluate(() => { window.fixtureFocusedIdentity = document.activeElement; });
    await send('activity_batch', { base_revision: 10, revision: 11, changes: [
      { operation: 'upsert', table_id: channelId, replace: false, rows: [voice] }
    ] });
    await expect(page.locator('.channels-live-table tbody tr').first()).toHaveAttribute('data-id', 'selected-call');
    await expect(sourceLink).toBeFocused();
    expect(await page.evaluate(() => document.activeElement === window.fixtureFocusedIdentity)).toBe(true);
    await selected.focus();

    // A baseline replacement removes old fields and tables without replaying intermediate revisions.
    const replacement = { ...voice, source_alias: 'Portable updated', signal_dbfs: -36 };
    await send('activity_batch', { base_revision: 11, revision: 12, changes: [
      { operation: 'upsert', table_id: channelId, replace: true,
        table: metadata(table(channelId, 'County dispatch', [])), rows: [replacement], row_order: ['selected-call'] },
      { operation: 'remove', table_id: 'second-site', replace: false }
    ] });
    await expect(selected.locator('[data-column="source"]')).toContainText('Portable updated');
    await expect(page.locator('.channels-tab-title', { hasText: 'West site updated' })).toHaveCount(0);
    await expect(selected).toBeFocused();
    await expectTypedLinks();

    await send('activity_resync', { dropped: 2, snapshot: { revision: 20,
      tables: [table(channelId, 'County dispatch', [{ ...replacement, signal_dbfs: -30 }])] } });
    await expect(selected.locator('[data-column="signal"]')).toContainText('-30.0');
    await send('activity_batch', { base_revision: 20, revision: 23, changes: [
      { operation: 'upsert', table_id: channelId, replace: false, rows: [{ ...replacement, signal_dbfs: -28 }] }
    ] });
    await expect(selected.locator('[data-column="signal"]')).toContainText('-28.0');
    await expect(selected).toHaveAttribute('aria-selected', 'true');
    await expectTypedLinks();
    await page.locator('.live-split').screenshot({ path: testInfo.outputPath(`activity-batch-${theme}-${width}.png`) });
    expect(fixture.pageErrors).toEqual([]);
    expect(fixture.unexpected).toEqual([]);
    expect(fixture.requests.filter(request => /admin\/channels\/.+\/(start|stop)/.test(request))).toEqual([]);
  });
}
