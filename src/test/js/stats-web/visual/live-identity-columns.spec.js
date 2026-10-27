'use strict';

const { test, expect } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { installSiteStyleApplication, channelId } = require('./fixtures/site-style-app.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

const schema = ['status', 'tags', 'channel', 'frequency', 'signal', 'decode-health',
  'source', 'target', 'decoder'];
const baseRow = { key: 'identity-call', configuration_id: channelId, lcn: 12,
  frequency_hz: 451012500, status: 'CALL', tags: ['VOICE'], role: 'TRAFFIC', activation_order: 2,
  protocol: 'DMR', decoder: 'DMR', source_id: '65536', source_form: 'RADIO',
  source_alias: 'Portable 6', talker_alias: 'Operator 6', target_id: '54321',
  target_form: 'TALKGROUP', target_alias: 'Dispatch' };
let defaults;
test.beforeAll(async () => {
  defaults = (await import(pathToFileURL(path.resolve(__dirname,
    '../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults;
});

async function openLive(page, theme, { hidden = [], row = {}, sourceMode = 'both', density = 'normal' } = {}) {
  const snapshot = { revision: 1, tables: [{ table_id: channelId, configuration_id: channelId,
    title: 'Identity columns', channel_running: true, rows: [{ ...baseRow, ...row }] }] };
  const fixture = await installSiteStyleApplication(page, theme, snapshot);
  let preferences = structuredClone(defaults), revision = 1;
  preferences.appearance.theme = theme;
  preferences.presentation.source_name_display = sourceMode;
  preferences.presentation.live_row_density = density;
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
async function expectParty(row, role, name, id) {
  const party = cell(row, role);
  await expect(party).toBeVisible();
  await expect(party.locator('.identity-summary-primary')).toHaveText(name || id);
  const secondary = party.locator('.identity-summary-context');
  if (name) {
    await expect(secondary).toBeVisible();
    await expect(secondary).toHaveText(id);
  } else {
    await expect(secondary).toHaveCount(0);
    await expect(party).toHaveText(id);
  }
}
function expectClean(fixture) {
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
  expect(fixture.requests.filter(request => /admin\/channels\/.+\/(start|stop)/.test(request))).toEqual([]);
}

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`Receiver column starts hidden and updates nickname and assignment on ${theme} ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const app = await openLive(page, theme, { row: { receiver_id: 'receiver-a', receiver_nickname: 'North antenna' } });
    await expect(cell(app.row, 'receiver')).toHaveCount(0);
    await page.getByRole('button', { name: 'Choose table columns', exact: true }).first().click();
    await page.getByRole('checkbox', { name: 'Show Receiver column', exact: true }).check();
    await page.keyboard.press('Escape');
    await expect(cell(app.row, 'receiver')).toHaveText('North antenna');
    await expect(cell(app.row, 'receiver')).toHaveAttribute('data-ui-hint', 'receiver-a');
    expect(app.writes.at(-1).tables['live-channels'].hidden_columns).not.toContain('receiver');
    const update = (revision, receiver) => page.evaluate(({ revision, row, tableId }) =>
      window.fixtureSendLiveFrame(1, 'activity_batch', { base_revision: revision - 1, revision,
        changes: [{ table_id: tableId, operation: 'upsert', replace: false, rows: [row] }] }),
    { revision, tableId: channelId, row: { ...baseRow, ...receiver } });
    await update(2, { receiver_id: 'receiver-b', receiver_nickname: 'South antenna' });
    await expect(cell(app.row, 'receiver')).toHaveText('South antenna');
    await expect(cell(app.row, 'receiver')).toHaveAttribute('data-ui-hint', 'receiver-b');
    await update(3, { receiver_id: 'receiver-b' });
    await expect(cell(app.row, 'receiver')).toHaveText('receiver-b');
    await update(4, {});
    await expect(cell(app.row, 'receiver')).toHaveText('—');
    await expect(cell(app.row, 'receiver')).not.toHaveAttribute('data-ui-hint');
    await expectNoHorizontalOverflow(page);
    expectClean(app.fixture);
    if (process.env.WEB_REVIEW_DIR) await page.screenshot({ path: path.join(process.env.WEB_REVIEW_DIR,
      `live-receiver-${theme}-${width}.png`) });
  });
}

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  for (const [sourceMode, sourceName] of [['source_alias', 'Portable 6'],
    ['talker_alias', 'Operator 6'], ['both', 'Portable 6 · Operator 6']]) {
    test(`Live combined identities preserve visible IDs with ${sourceMode} on ${theme} ${width}px`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      const app = await openLive(page, theme, { sourceMode });
      await expectParty(app.row, 'source', sourceName, '65536');
      await expectParty(app.row, 'target', 'Dispatch', '54321');
      await expect(app.row.locator('[data-column="source-alias"], [data-column="target-alias"]')).toHaveCount(0);
      await expectNoHorizontalOverflow(page);
      expectClean(app.fixture);
      if (sourceMode === 'both' && process.env.WEB_REVIEW_DIR) {
        await page.screenshot({ path: path.join(process.env.WEB_REVIEW_DIR,
          `combined-live-${theme}-${width}.png`) });
      }
    });
  }

  for (const density of ['normal', 'dense']) test(`Live identities keep row height through detail changes in ${theme} ${density}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const quality = { vc_quality_pct: 97, vc_decoded_frames: 120 };
    const app = await openLive(page, theme, { density, row: quality });
    const height = (await app.row.boundingBox()).height;
    const expectStableHeight = async () => expect.poll(async () =>
      Math.abs((await app.row.boundingBox()).height - height)).toBeLessThanOrEqual(1);
    for (const role of ['source', 'target']) {
      const name = await cell(app.row, role).locator('.identity-summary-primary').boundingBox();
      const id = await cell(app.row, role).locator('.identity-summary-context').boundingBox();
      expect(id.x).toBeGreaterThanOrEqual(name.x + name.width - 1);
    }
    await expect(cell(app.row, 'channel')).toHaveText('12');
    const update = async (revision, values) => {
      const snapshot = { ...app.snapshot, revision, tables: [{ ...app.snapshot.tables[0],
        rows: [{ ...baseRow, ...values }] }] };
      await page.evaluate(snapshot => window.fixtureSendLiveSnapshot(snapshot), snapshot);
    };
    await expectParty(app.row, 'source', 'Portable 6 · Operator 6', '65536');
    await expect(cell(app.row, 'decode-health').getByRole('img')).toBeVisible();
    await update(2, { source_alias: ' ', talker_alias: '', target_alias: '' });
    await expectParty(app.row, 'source', '', '65536');
    await expectParty(app.row, 'target', '', '54321');
    await expect(cell(app.row, 'decode-health')).toBeEmpty();
    await expectStableHeight();
    const longName = 'New operator with a long changing over-the-air name';
    await update(3, { ...quality, source_alias: '', talker_alias: longName, target_alias: '', remote_origin: { remote: true }, target_aliases: [
      { alias_id: 101, alias_list_id: 1, name: 'Dispatch East' }] });
    await expectParty(app.row, 'source', longName, '65536');
    await expectParty(app.row, 'target', 'Dispatch East', '54321');
    await expectStableHeight();
    // Stale call metadata in the same row clears without collapsing its visible detail slots.
    await update(4, { status: 'IDLE' });
    await expect(cell(app.row, 'source')).toBeEmpty();
    await expect(cell(app.row, 'target')).toBeEmpty();
    await expectStableHeight();
    await expectNoHorizontalOverflow(page);
    expectClean(app.fixture);
  });
}

test('Live combined columns can be hidden, reordered, and restored without separate ID controls', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const app = await openLive(page, 'dark');
  await page.locator('.live-channels-title-actions')
    .getByRole('button', { name: 'Choose table columns', exact: true }).click();
  const chooser = page.getByRole('dialog', { name: 'Table columns', exact: true });
  await expect(chooser.getByRole('checkbox', { name: /Show (Source|Target) (ID|Alias) column/ })).toHaveCount(0);
  await chooser.getByRole('checkbox', { name: 'Show Source column', exact: true }).uncheck();
  await expect(cell(app.row, 'source')).toHaveCount(0);
  await expectParty(app.row, 'target', 'Dispatch', '54321');
  await chooser.getByRole('button', { name: 'Move Target left', exact: true }).click();
  await expect.poll(() => app.writes.at(-1)?.tables['live-channels'].column_order.indexOf('target'))
    .toBeLessThan(schema.indexOf('target'));
  await chooser.getByRole('checkbox', { name: 'Show Source column', exact: true }).check();
  await expectParty(app.row, 'source', 'Portable 6 · Operator 6', '65536');
  await page.keyboard.press('Escape');
  await expect(chooser).toBeHidden();
  // The visual server serves the application at /app.html; its / route is the component gallery.
  await page.goto(`/app.html?view=live&channel=${channelId}`);
  await expectParty(app.row, 'source', 'Portable 6 · Operator 6', '65536');
  await expectParty(app.row, 'target', 'Dispatch', '54321');
  expect(app.writes).toHaveLength(3);
  expectClean(app.fixture);
});

test('Live combined source preserves the formatted foreign P25 identity', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const app = await openLive(page, 'light', { row: { protocol: 'P25_PHASE1', decoder: 'P25_PHASE1',
    serving_system_key: 'p25:00001:001', source_home_system_name: 'West County',
    source_home_system_name_source: 'system', source_canonical_identity: { wacn: 2, system_id: 2, subscriber_id: 65536 },
    source_working_subscriber_id: 1001 } });
  await expectParty(app.row, 'source', 'Portable 6 · Operator 6', 'West County · 65536 (Working ID 1001)');
  await expectNoHorizontalOverflow(page);
  expectClean(app.fixture);
});

for (const protocol of ['P25_PHASE1', 'NXDN']) {
  test(`Live combined identities keep nameless IDs once for ${protocol} conventional rows`, async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    const app = await openLive(page, 'light', { row: { protocol, decoder: protocol,
      source_alias: '', talker_alias: '', target_alias: '', tags: ['CONVENTIONAL'], channel_name: 'Digital channel' } });
    await expectParty(app.row, 'source', '', '65536');
    await expectParty(app.row, 'target', '', '54321');
    expectClean(app.fixture);
  });
}


test('Live identities link directly to their typed entities without call fact dialogs', async ({ page }) => {
  const sourceRef = { kind: 'radio', radio_system_key: 'p25:bee00:348', identity_key: 'v1-r-bee00-348-9601699' };
  const targetRef = { kind: 'talkgroup', radio_system_key: 'p25:00001:047', identity_key: 'v1-g-00001-047-7001' };
  const app = await openLive(page, 'light', { row: { protocol: 'P25_PHASE1', decoder: 'P25_PHASE1',
    source_entity_ref: sourceRef, target_entity_ref: targetRef,
    source_canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 9601699 },
    source_working_subscriber_id: 65536 } });
  for (const [kind, ref, view] of [['source', sourceRef, 'radio'], ['target', targetRef, 'group-identity']]) {
    const links = cell(app.row, kind).getByRole('link');
    await expect(links).toHaveCount(2);
    for (const link of await links.all()) {
      const destination = new URL(await link.getAttribute('href'), page.url()).searchParams;
      expect(destination.get('view')).toBe(view);
      expect(destination.get('radio_system_key')).toBe(ref.radio_system_key);
      expect(destination.get('identity_key')).toBe(ref.identity_key);
      await expect(link).not.toHaveAttribute('aria-haspopup', 'dialog');
      await link.focus();
      await expect(link).toBeFocused();
    }
  }
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByText(/Call facts in Live|Talkgroup identity from this call/)).toHaveCount(0);
  expectClean(app.fixture);
});

test('Live unresolved identities stay plain text', async ({ page }) => {
  const app = await openLive(page, 'light', { row: { protocol: 'P25_PHASE1', decoder: 'P25_PHASE1' } });
  await expectParty(app.row, 'source', 'Portable 6 · Operator 6', '65536');
  await expectParty(app.row, 'target', 'Dispatch', '54321');
  await expect(cell(app.row, 'source').getByRole('link')).toHaveCount(0);
  await expect(cell(app.row, 'target').getByRole('link')).toHaveCount(0);
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expectClean(app.fixture);
});

for (const protocol of ['P25_PHASE1', 'NXDN']) {
  test(`Live ${protocol} conventional identity links use the saved channel collections`, async ({ page }) => {
    const app = await openLive(page, 'light', { row: { protocol, decoder: protocol, tags: ['CONVENTIONAL'],
      entity_ref: { kind: 'channel', key: channelId } } });
    for (const [kind, tab] of [['source', 'radios'], ['target', 'groups']]) {
      const links = cell(app.row, kind).getByRole('link');
      await expect(links).toHaveCount(2);
      for (const link of await links.all()) {
        const destination = new URL(await link.getAttribute('href'), page.url()).searchParams;
        expect(destination.get('view')).toBe('channel');
        expect(destination.get('configuration_id')).toBe(channelId);
        expect(destination.get('tab')).toBe(tab);
        await expect(link).not.toHaveAttribute('aria-haspopup', 'dialog');
      }
    }
    expectClean(app.fixture);
  });
}
