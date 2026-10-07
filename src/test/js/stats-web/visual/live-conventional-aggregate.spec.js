'use strict';

const { test, expect } = require('@playwright/test');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');

const channelA = '11111111-1111-4111-8111-111111111111';
const channelB = '22222222-2222-4222-8222-222222222222';
const origins = [
  { configuration_id: channelA, channel_name: 'Fire dispatch', frequency_hz: 155_730_000, protocol: 'P25_PHASE1' },
  { configuration_id: channelB, channel_name: 'Transit operations', frequency_hz: 451_012_500, protocol: 'NXDN' }
];
const snapshot = { revision: 1, tables: [{ table_id: 'conventional', title: 'Conventional',
  channel_running: true, rows: origins.map((origin, index) => ({ ...origin, key: `source-${index}`,
    tags: ['CONVENTIONAL'], role: 'CONVENTIONAL', status: 'IDLE', decoder: origin.protocol })) }] };
const eventCatalog = { signature: 'events-combined', timeslots: [], groups: [{ key: 'events', label: 'Events',
  children: [{ key: 'CALL', label: 'Calls', children: [] }] }] };
const messageCatalog = { signature: 'messages-combined', timeslots: [1, 2], groups: origins.map((origin, index) => ({
  key: `source/${index}`, label: origin.channel_name,
  children: [{ key: `source/${index}/messages`, label: `${origin.protocol} messages`, children: [] }]
})) };
const event = (origin, id, timestamp, details = 'Decoded call') => ({ ...origin, event_id: id,
  source_frequency_hz: origin.frequency_hz, time_start_ms: timestamp, duration_ms: 1000, event_type: 'CALL', event_label: 'Call', category: 'VOICE', details });
const message = (origin, id, timestamp, text = 'Decoded message') => ({ ...origin, message_id: id,
  timestamp_ms: timestamp, text, valid: true, timeslot: 1,
  filter_key: `source/${origins.indexOf(origin)}/messages`, filter_label: `${origin.protocol} messages` });

async function openLive(page, theme) {
  const fixture = await installSiteStyleApplication(page, theme, snapshot);
  await page.addInitScript(() => localStorage.setItem('sdrtrunk-vce.live-ui-state.v1', JSON.stringify({
    details_collapsed: false, details_active_tab: 'events', details_panel_percent: 50
  })));
  const controls = [];
  let autoAcknowledge = true;
  await page.route('**/api/v1/live/multiplex/control', async route => {
    const subscriptions = route.request().postDataJSON().subscriptions;
    await route.fulfill({ json: { data: { accepted: true } } });
    for (const [name, topic, catalog] of [['decode_events', 2, eventCatalog], ['decode_messages', 3, messageCatalog]]) {
      const parameters = subscriptions[name];
      if (!parameters || !autoAcknowledge) continue;
      await page.evaluate(({ topic, parameters, catalog }) => window.fixtureSendLiveFrame(topic, 'source_change', {
        ...parameters, scope: parameters.scope ? 'CONVENTIONAL' : 'CHANNEL', bound: true, filter_catalog: catalog
      }), { topic, parameters, catalog });
    }
    controls.push(subscriptions);
  });
  await page.goto('/app.html?view=live');
  await expect(page.locator('.channels-live-table tbody tr')).toHaveCount(2);
  await page.getByRole('button', { name: 'Change audio player size', exact: true }).press('Home');
  const latest = () => controls.at(-1) || {};
  const send = (topic, name, data) => page.evaluate(({ topic, name, data }) =>
    window.fixtureSendLiveFrame(topic, name, data), { topic, name, data });
  return { fixture, controls, latest, send, acknowledge: value => { autoAcknowledge = value; } };
}

for (const theme of ['light', 'dark']) for (const width of [1280, 390]) {
  test(`Conventional combined details, selection and lifecycle ${theme} ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 });
    const app = await openLive(page, theme);
    const events = page.locator('.live-events-table tbody tr[data-id]');
    const messages = page.locator('.live-messages-table tbody tr[data-id]');
    await expect.poll(() => app.latest().decode_events?.scope).toBe('conventional');
    expect(app.latest().decode_events?.configuration_id).toBeUndefined();
    await expect(page.getByRole('button', { name: 'Clear channel selection', exact: true })).toBeHidden();

    // Initial backlogs from different sources can arrive in either order, including matching IDs.
    await app.send(2, 'decode_event', event(origins[1], 'shared', 3000, 'Transit latest'));
    await app.send(2, 'decode_event', event(origins[0], 'shared', 1000, 'Fire earlier'));
    await app.send(2, 'decode_event', event(origins[0], 'middle', 2000, 'Fire middle'));
    await expect(events).toHaveCount(3);
    await expect(events.nth(0)).toContainText('Transit latest');
    await expect(events.nth(0).locator('[data-column="channel"]')).toContainText('Transit operations');
    await expect(events.nth(1).locator('[data-column="channel"]')).toContainText('155.730');
    await app.send(2, 'decode_event', event(origins[1], 'shared', 3000, 'Transit updated'));
    await expect(events).toHaveCount(3);
    await expect(events.nth(0)).toContainText('Transit updated');
    await app.send(2, 'live_gap', { dropped: 2 });
    await expect(page.locator('.live-events-pane .live-detail-gap')).toContainText('2 live events');

    // Pausing freezes presentation while bounded capture continues.
    await page.getByRole('button', { name: 'Pause live details', exact: true }).click();
    await app.send(2, 'decode_event', event(origins[0], 'paused', 4000, 'Arrived while paused'));
    await expect(events).toHaveCount(3);
    await page.getByRole('button', { name: 'Resume live details', exact: true }).click();
    await expect(events.nth(0)).toContainText('Arrived while paused');

    await page.getByRole('tab', { name: 'Messages', exact: true }).click();
    await expect.poll(() => app.latest().decode_messages?.scope).toBe('conventional');
    await expect.poll(() => app.latest().decode_events).toBeUndefined();
    await app.send(3, 'decode_message', message(origins[1], 'same', 6000, 'NXDN newest'));
    await app.send(3, 'decode_message', message(origins[0], 'same', 5000, 'P25 earlier'));
    await expect(messages).toHaveCount(2);
    await expect(messages.nth(0)).toContainText('Transit operations');
    await expect(messages.nth(0)).toContainText('451.012');
    await expect(messages.nth(1)).toContainText('Fire dispatch');
    await page.locator('.live-details').screenshot({ path: testInfo.outputPath(`messages-${theme}-${width}.png`) });

    // Catalog choices include every source before more rows arrive and survive selection changes.
    await page.getByRole('button', { name: 'Filter messages', exact: true }).click();
    const filterDialog = page.getByRole('dialog', { name: 'Message filters', exact: true });
    await expect(filterDialog).toContainText('Fire dispatch');
    await expect(filterDialog).toContainText('Transit operations');
    await filterDialog.getByRole('checkbox', { name: 'NXDN messages', exact: true }).uncheck();
    await filterDialog.getByRole('button', { name: 'Done', exact: true }).click();
    await expect(messages).toHaveCount(1);
    await expect(messages).toContainText('P25 earlier');

    // A selected channel narrows both transports; a queued aggregate acknowledgement cannot re-arm it.
    const oldSubscription = app.latest().decode_messages.subscription_id;
    await page.locator('.channels-live-table tr[data-id="source-0"]').click();
    await expect.poll(() => app.latest().decode_messages?.configuration_id).toBe(channelA);
    expect(app.latest().decode_messages?.scope).toBeUndefined();
    await expect(page.getByRole('button', { name: 'Clear channel selection', exact: true })).toBeVisible();
    await app.send(3, 'source_change', { scope: 'CONVENTIONAL', subscription_id: oldSubscription });
    await app.send(3, 'decode_message', message(origins[1], 'stale', 7000, 'Queued from another channel'));
    await expect(messages).toHaveCount(0);
    const current = app.latest().decode_messages;
    await app.send(3, 'source_change', { ...current, scope: 'CHANNEL', filter_catalog: messageCatalog });
    await app.send(3, 'decode_message', message(origins[1], 'wrong-after-ack', 8000, 'Other source after acknowledgement'));
    await app.send(3, 'decode_message', message(origins[0], 'selected', 8000, 'Selected channel message'));
    await expect(messages).toHaveCount(1);
    await expect(messages).toContainText('Selected channel message');
    await page.getByRole('tab', { name: 'Events', exact: true }).click();
    await expect.poll(() => app.latest().decode_events?.configuration_id).toBe(channelA);
    await app.send(2, 'decode_event', event(origins[1], 'wrong', 9000, 'Wrong source'));
    await app.send(2, 'decode_event', event(origins[0], 'selected', 9000, 'Selected event'));
    await expect(events).toHaveCount(1);
    await expect(events).toContainText('Selected event');

    await page.getByRole('button', { name: 'Pause live details', exact: true }).click();
    await page.getByRole('button', { name: 'Clear channel selection', exact: true }).click();
    await expect.poll(() => app.latest().decode_events?.scope).toBe('conventional');
    await expect(events).toHaveCount(0);
    await app.send(2, 'decode_event', event(origins[1], 'returned', 10000, 'Combined again'));
    await expect(events).toHaveCount(0);
    await page.getByRole('button', { name: 'Resume live details', exact: true }).click();
    await expect(events).toContainText('Combined again');
    await page.locator('.live-details').screenshot({ path: testInfo.outputPath(`combined-${theme}-${width}.png`) });

    // Stop/start changes the source catalog without a row selection; the Channel pane stays exact-only.
    const stopped = structuredClone(snapshot);
    stopped.revision = 2;
    stopped.tables[0].rows = [stopped.tables[0].rows[0]];
    await page.evaluate(value => window.fixtureSendLiveSnapshot(value), stopped);
    await expect(page.locator('.channels-live-table tbody tr')).toHaveCount(1);
    expect(app.latest().decode_events.scope).toBe('conventional');
    await page.evaluate(value => window.fixtureSendLiveSnapshot(value), { ...snapshot, revision: 3 });
    await expect(page.locator('.channels-live-table tbody tr')).toHaveCount(2);
    await page.getByRole('tab', { name: 'Channel', exact: true }).click();
    await expect.poll(() => app.latest().decode_events).toBeUndefined();
    await expect.poll(() => app.latest().channel_diagnostics).toBeUndefined();
    await page.getByRole('tab', { name: 'Messages', exact: true }).click();
    await expect.poll(() => app.latest().decode_messages?.scope).toBe('conventional');
    // Hidden pages release captures, and reopening requires the latest scope acknowledgement.
    const beforeHidden = app.latest().decode_messages.subscription_id;
    await page.evaluate(() => {
      Object.defineProperty(document, 'hidden', { configurable: true, value: true });
      document.dispatchEvent(new Event('visibilitychange'));
    });
    await expect.poll(() => app.latest().decode_messages).toBeUndefined();
    app.acknowledge(false);
    await page.evaluate(() => {
      Object.defineProperty(document, 'hidden', { configurable: true, value: false });
      document.dispatchEvent(new Event('visibilitychange'));
    });
    await expect.poll(() => app.latest().decode_messages?.scope).toBe('conventional');
    expect(app.latest().decode_messages.subscription_id).not.toBe(beforeHidden);
    await app.send(3, 'source_change', { scope: 'CONVENTIONAL', subscription_id: beforeHidden });
    await app.send(3, 'decode_message', message(origins[0], 'before-current', 11000, 'Before current acknowledgement'));
    await expect(messages).toHaveCount(0);
    await app.send(3, 'source_change', { scope: 'CONVENTIONAL',
      subscription_id: app.latest().decode_messages.subscription_id, filter_catalog: messageCatalog });
    await app.send(3, 'decode_message', message(origins[0], 'after-current', 12000, 'After current acknowledgement'));
    await expect(messages).toContainText('After current acknowledgement');
    app.acknowledge(true);
    await page.getByRole('button', { name: 'Collapse live details', exact: true }).click();
    await expect.poll(() => app.latest().decode_messages).toBeUndefined();
    const expandDetails = page.getByRole('button', { name: 'Expand live details', exact: true });
    await expandDetails.focus();
    await expandDetails.press('Enter');
    await expect.poll(() => app.latest().decode_messages?.scope).toBe('conventional');
    await page.goto('/app.html?view=dashboard');
    await expect.poll(() => app.latest().decode_messages).toBeUndefined();
    expect(app.fixture.pageErrors).toEqual([]);
    expect(app.fixture.unexpected).toEqual([]);
    expect(app.fixture.requests.filter(path => /admin\/channels\/.+\/(start|stop)/.test(path))).toEqual([]);
  });
}
