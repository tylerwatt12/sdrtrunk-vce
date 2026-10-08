'use strict';

const { test, expect } = require('@playwright/test');
const { installSiteStyleApplication, systemKey, channelId, groupKey, radioKey, systemName } =
  require('./fixtures/site-style-app.cjs');

const now = Date.parse('2026-10-01T12:00:00Z');
const activity = (id, action = 'GRANT', timestamp = now) => ({ id, observed_at_ms: timestamp, action,
  protocol: 'p25', channel_kind: 'trunked_site', radio_system_key: systemKey, system_name: systemName,
  configuration_id: channelId, source_identity_key: radioKey, source_radio_id: 30914,
  source_alias_name: 'Engine 4', target_identity_key: groupKey, target_kind: 'talkgroup',
  target_id: 1201, target_alias_name: 'Fire Dispatch' });

test('visualizer streams committed activity after a buffered catch-up and releases its subscription on navigation',
  async ({ page }) => {
    const fixture = await installSiteStyleApplication(page);
    await page.addInitScript(() => localStorage.setItem('sdrtrunk-vce-p25-visualizer-events-v1:fixture-admin',
      JSON.stringify({ call: { highlight: true, autoZoom: false } })));
    let historyRequests = 0;
    let releaseCatchup;
    let subscriptionId;
    const catchupReady = new Promise((resolve) => { releaseCatchup = resolve; });
    const controls = [];
    await page.route('**/api/v1/activity?**', async (route) => {
      historyRequests += 1;
      if (historyRequests === 2) await catchupReady;
      const rows = historyRequests === 1 ? [activity(1, 'JOIN')] : historyRequests === 2 ? [activity(2)] : [];
      const cursor = historyRequests === 1 ? 1 : Math.max(2,
        Number(new URL(route.request().url()).searchParams.get('after_id')) || 0);
      await route.fulfill({ json: { data: { rows, next_after_id: cursor, watermark_id: cursor,
        has_more: false, traffic_grant_age_out_milliseconds: 1_200 } } });
    });
    await page.route('**/api/v1/live/multiplex/control', async (route) => {
      const subscriptions = route.request().postDataJSON().subscriptions;
      controls.push(subscriptions);
      await route.fulfill({ json: { data: { accepted: true } } });
      if (subscriptions.saved_activity && !subscriptionId) {
        subscriptionId = subscriptions.saved_activity.subscription_id;
        await page.evaluate(({ subscriptionId, now }) => window.fixtureSendLiveFrame(7, 'source_change', {
          subscription_id: subscriptionId, watermark_id: 1, server_time_ms: now,
          traffic_grant_age_out_milliseconds: 1_200, collection_enabled: true
        }), { subscriptionId, now });
      }
    });
    await page.goto('/app.html?view=network-visualizer');
    await expect.poll(() => historyRequests).toBe(2);
    const send = (event, data) => page.evaluate(({ event, data }) =>
      window.fixtureSendLiveFrame(7, event, data), { event, data });
    await send('activity_append', { rows: [activity(2), activity(3)], next_after_id: 3, watermark_id: 3 });
    await expect(page.locator('.network-visualizer-label[data-signal="call"]')).toHaveCount(0);
    releaseCatchup();
    await expect(page.locator('.network-visualizer-scope-title')).toContainText(systemName);
    const group = page.locator('.network-visualizer-label[data-type="talkgroup"][data-signal="call"]');
    await expect(group).toHaveText('Fire Dispatch');
    await expect(page.locator('.network-visualizer-scope-title a.ui-link-text')).toHaveAttribute('href',
      new RegExp(`radio_system_key=${encodeURIComponent(systemKey)}`));
    for (let id = 4; id <= 6; id += 1) {
      await send('activity_append', { rows: [activity(id)], next_after_id: id, watermark_id: id });
      await expect(group).toHaveText('Fire Dispatch');
    }
    expect(historyRequests).toBe(2, 'committed push activity updates the actual scene without another GET');
    await send('source_change', { subscription_id: subscriptionId, watermark_id: 6, server_time_ms: now,
      traffic_grant_age_out_milliseconds: 1_200, collection_enabled: false });
    await expect(page.locator('.network-visualizer-status')).toHaveText('Saved history · updates paused');
    await page.evaluate(() => document.querySelector('.primary-nav a[data-view="credits"]').click());
    await expect(page.locator('.credits-copy').first()).toBeVisible();
    await expect.poll(() => controls.at(-1)?.saved_activity).toBeUndefined();
    await expect(page.locator('.network-visualizer-canvas')).toHaveCount(0);
    expect(fixture.pageErrors).toEqual([]);
    expect(fixture.unexpected).toEqual([]);
  });
