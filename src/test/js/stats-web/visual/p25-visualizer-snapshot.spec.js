'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { test, expect } = require('@playwright/test');
const { installSiteStyleApplication, systemKey, channelId, groupKey, radioKey } =
  require('./fixtures/site-style-app.cjs');

async function openVisualizer(page, theme = 'light') {
  const fixture = await installSiteStyleApplication(page, theme);
  const filename = path.resolve(__dirname, '../../../../../stats-web/assets/features/network-visualizer/renderer.js');
  const source = fs.readFileSync(filename, 'utf8').replace("\n  host.dataset.rendererState = 'ready';", `
  window.p25Scene = { nodes: () => graph.graphData().nodes.map(node => ({ ...node })),
    camera: () => cameraPose(), rotating: () => graph.controls().autoRotate,
    opacities: () => [...materials.values()].map(material => material.opacity) };
  host.dataset.rendererState = 'ready';`);
  const indexPath = path.resolve(__dirname, '../../../../../stats-web/assets/features/network-visualizer/index.js');
  const indexSource = fs.readFileSync(indexPath, 'utf8').replace('  const close = () => {',
    '  window.p25SelectTalkgroup = enterTalkgroup;\n  const close = () => {');
  await page.route('**/assets/features/network-visualizer/index.js?**', route =>
    route.fulfill({ contentType: 'text/javascript', body: indexSource }));
  await page.route('**/assets/features/network-visualizer/renderer.js?**', route =>
    route.fulfill({ contentType: 'text/javascript', body: source }));
  await page.goto('/app.html?view=network-visualizer');
  await expect(page.locator('.network-visualizer-canvas')).toHaveAttribute('data-renderer-state', 'ready');
  await expect(page.locator('.network-visualizer-empty')).toBeHidden();
  await expect(page.locator('.network-visualizer-status')).toHaveText('Live');
  return fixture;
}
const row = (id, action = 'CALL') => ({ id, observed_at_ms: Date.parse('2026-10-01T12:00:00Z'), action,
  protocol: 'P25', channel_kind: 'TRUNKED_SITE', radio_system_key: systemKey,
  system_name: 'County P25', configuration_id: channelId, source_identity_key: radioKey,
  source_radio_id: 30914, source_alias_name: 'Engine 4', target_identity_key: groupKey,
  target_kind: 'talkgroup', target_id: 1201, target_alias_name: 'Fire Dispatch' });
const append = (page, after, next, rows) => page.evaluate(({ after, next, rows }) =>
  window.fixtureSendLiveFrame(7, 'activity_append', { source_epoch: 'fixture-p25',
    after_id: after, next_after_id: next, watermark_id: next, rows }), { after, next, rows });

test('Actions counts observed actions once and saves independent choices without reloading data', async ({ page }) => {
  const fixture = await openVisualizer(page);
  await page.getByRole('button', { name: 'Actions', exact: true }).click();
  const actions = page.locator('.network-visualizer-events');
  await expect(actions.locator('.network-visualizer-action')).toHaveCount(10);
  const calls = actions.locator('[data-category="call"]');
  await expect(calls.locator('.ui-pill')).toHaveText('0');
  await expect(calls.getByRole('checkbox', { name: 'Zoom to Calls and grants', exact: true })).not.toBeChecked();
  await append(page, 1, 3, [row(2), row(3)]);
  await expect(calls.locator('.ui-pill')).toHaveText('2');
  await append(page, 1, 3, [row(2), row(3)]);
  await expect(calls.locator('.ui-pill')).toHaveText('2');
  const highlight = calls.getByRole('checkbox', { name: 'Highlight Calls and grants', exact: true });
  await highlight.focus();
  await highlight.press('Space');
  await actions.getByRole('button', { name: 'Save choices', exact: true }).click();
  await expect(actions.locator('form')).not.toHaveClass(/is-dirty/);
  await expect(calls.locator('.ui-pill')).toHaveText('2');
  await append(page, 3, 4, [row(4)]);
  await expect(calls.locator('.ui-pill')).toHaveText('3');
  expect(fixture.requests.filter(url => ['/api/v1/activity', '/api/v1/activity/live/seed'].includes(url))).toEqual([]);
  expect(fixture.pageErrors).toEqual([]);
});

test('current Live table changes control lighting without moving settled entities', async ({ page }) => {
  const fixture = await openVisualizer(page);
  const before = await page.evaluate(() => window.p25Scene.nodes().map(({ id, x, y, z }) => ({ id, x, y, z })));
  await expect.poll(() => page.evaluate(() => window.p25Scene.nodes().filter(node => node.active).length)).toBe(2);
  await page.evaluate(() => window.fixtureSendLiveSnapshot({ revision: 2, tables: [] }));
  await expect.poll(() => page.evaluate(() => window.p25Scene.nodes().filter(node => node.active).length)).toBe(0);
  expect(await page.evaluate(() => window.p25Scene.nodes().map(({ id, x, y, z }) => ({ id, x, y, z })))).toEqual(before);
  await expect.poll(() => page.evaluate(() => window.p25Scene.rotating())).toBe(true);
  expect(fixture.pageErrors).toEqual([]);
});

test('a live affiliation creates both talkgroups and restores rotation after arrival', async ({ page }) => {
  const fixture = await openVisualizer(page, 'dark');
  await page.clock.setSystemTime(new Date('2026-10-01T12:00:00Z'));
  await page.evaluate(({ systemKey, channelId, groupKey, radioKey }) => {
    const previous = { radio_system_key: systemKey, radio_identity_key: radioKey,
      talkgroup_identity_key: groupKey, configuration_id: channelId, confirmed_at_ms: Date.now() - 2_000,
      radio_id: 30914, radio_alias_name: 'Engine 4', talkgroup_id: 1201, talkgroup_alias_name: 'Fire Dispatch' };
    window.fixtureSendLiveFrame(7, 'p25_state_delta', { source_epoch: 'fixture-p25', base_sequence: 0, sequence: 1,
      part: 0, parts: 1, available: true, collection_enabled: true, removed_radio_keys: [],
      affiliations: [{ ...previous, confirmed_at_ms: Date.now(), talkgroup_identity_key: 'v1-g-00001-001-1202',
        talkgroup_id: 1202, talkgroup_alias_name: 'Fire Tactical', previous_affiliation: previous }] });
  }, { systemKey, channelId, groupKey, radioKey });
  await expect.poll(() => page.evaluate(() => window.p25Scene.nodes().filter(node => node.type === 'talkgroup').length)).toBe(2);
  await page.getByRole('button', { name: 'Actions', exact: true }).click();
  await expect(page.locator('.network-visualizer-action[data-category="movement"] .ui-pill')).toHaveText('1');
  await page.getByRole('button', { name: 'Close actions', exact: true }).click();
  await page.clock.fastForward(5_000);
  await page.clock.runFor(300);
  await expect.poll(() => page.evaluate(() => window.p25Scene.rotating())).toBe(true);
  await expect(page.locator('.network-visualizer-auto-focus')).toBeHidden();
  expect(fixture.pageErrors).toEqual([]);
});

test('unrelated affiliations preserve the selected talkgroup while related highlighting and Back remain available', async ({ page }) => {
  const fixture = await openVisualizer(page);
  await page.clock.setSystemTime(new Date('2026-10-01T12:00:00Z'));
  await append(page, 1, 2, [{ ...row(2, 'JOIN'), source_identity_key: 'v1-r-00001-001-30915',
    source_radio_id: 30915, source_alias_name: 'Patrol 2', target_identity_key: 'v1-g-00001-001-1203',
    target_id: 1203, target_alias_name: 'Operations' }]);
  expect(await page.evaluate(() => window.p25SelectTalkgroup('p25:00001:001:v1-g-00001-001-1203'))).toBe(true);
  await expect(page.locator('.network-visualizer-scope-title')).toContainText('Operations');
  const operations = page.locator('.network-visualizer-label[data-type="talkgroup"]').filter({ hasText: 'Operations' });
  await expect(operations).not.toHaveAttribute('data-signal', 'call');
  await page.evaluate(({ systemKey, channelId, groupKey, radioKey }) => {
    const previous = { radio_system_key: systemKey, radio_identity_key: radioKey,
      talkgroup_identity_key: groupKey, configuration_id: channelId, confirmed_at_ms: Date.now() - 2_000,
      radio_id: 30914, radio_alias_name: 'Engine 4', talkgroup_id: 1201, talkgroup_alias_name: 'Fire Dispatch' };
    window.fixtureSendLiveFrame(7, 'p25_state_delta', { source_epoch: 'fixture-p25', base_sequence: 0, sequence: 1,
      part: 0, parts: 1, available: true, collection_enabled: true, removed_radio_keys: [],
      affiliations: [{ ...previous, confirmed_at_ms: Date.now(), talkgroup_identity_key: 'v1-g-00001-001-1202',
        talkgroup_id: 1202, talkgroup_alias_name: 'Fire Tactical', previous_affiliation: previous }] });
  }, { systemKey, channelId, groupKey, radioKey });
  await expect(page.locator('.network-visualizer-back')).toHaveText('← System');
  await expect(page.locator('.network-visualizer-scope-title strong:visible')).toHaveText('Operations');
  await expect(page.locator('.network-visualizer-auto-focus')).toBeHidden();
  expect(await page.evaluate(() => window.p25Scene.nodes().filter(node => node.type === 'talkgroup')
    .map(node => node.id))).toEqual([`${systemKey}:v1-g-00001-001-1203`]);
  await append(page, 2, 3, [{ ...row(3), observed_at_ms: await page.evaluate(() => Date.now()),
    source_identity_key: 'v1-r-00001-001-30915', source_radio_id: 30915, source_alias_name: 'Patrol 2',
    target_identity_key: 'v1-g-00001-001-1203', target_id: 1203, target_alias_name: 'Operations' }]);
  await page.evaluate(({ systemKey, channelId }) => window.fixtureSendLiveSnapshot({ revision: 2,
    tables: [{ table_id: channelId, configuration_id: channelId, channel_running: true, rows: [{
      key: 'operations-call', configuration_id: channelId, protocol: 'P25', serving_system_key: systemKey,
      status: 'CALL', tags: ['VOICE'], activation_order: 1, source_id: 30915, source_alias: 'Patrol 2',
      source_identity_key: 'v1-r-00001-001-30915', source_identity_kind_code: 2,
      target_id: 1203, target_identity_key: 'v1-g-00001-001-1203', target_kind: 'talkgroup', target_alias: 'Operations'
    }] }] }), { systemKey, channelId });
  await expect(operations).toHaveAttribute('data-signal', 'call');
  await page.locator('.network-visualizer-back').click();
  await expect(page.locator('.network-visualizer-back')).toHaveText('← All systems');
  await expect.poll(() => page.evaluate(() => window.p25Scene.nodes().filter(node => node.type === 'talkgroup').length))
    .toBe(3);
  await page.locator('.network-visualizer-back').click();
  await expect(page.locator('.network-visualizer-back')).toBeHidden();
  await expect(page.locator('.network-visualizer-scope-title')).toContainText('P25 radio systems');
  expect(fixture.pageErrors).toEqual([]);
});
