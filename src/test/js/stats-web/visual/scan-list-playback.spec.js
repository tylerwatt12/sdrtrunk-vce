'use strict';

const { expect, test } = require('@playwright/test');
const { openAudioApp, liveCall } = require('./fixtures/audio-dock-app.cjs');
const { selectAudioPanel } = require('./audio-dock-controls.cjs');

const scanLists = [
  { id: 1, name: 'Fire', description: 'Fire dispatch', default: true },
  { id: 2, name: 'Police', description: 'Police dispatch', default: false },
];

async function activeSources(page) {
  return page.evaluate(() => window.audioTest.liveSources.filter(source => source.active).length);
}

for (const scenario of [
  { view: 'dashboard', theme: 'light', width: 1280, height: 900, name: 'desktop dock' },
  { view: 'scanner', theme: 'dark', width: 390, height: 844, name: 'phone Scanner' },
]) {
  test(`${scenario.name} preserves listening, pause and stop while switching Scan Lists`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width: scenario.width, height: scenario.height });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await openAudioApp(page, { view: scenario.view, theme: scenario.theme, feedCallsEnabled: false });
    const feedRequests = [];
    await page.route('**/api/v1/scan-lists', route => route.fulfill({ json: { data: { scan_lists: scanLists } } }));
    await page.route('**/api/v1/calls/feed*', route => {
      const ids = new URL(route.request().url()).searchParams.getAll('scan_list_id');
      feedRequests.push(ids);
      const sequence = feedRequests.length;
      const id = Number(ids[0]);
      const call = { ...liveCall, call_id: `switch-${sequence}`, scan_list_ids: [id],
        started_at_ms: liveCall.started_at_ms + sequence * 60_000,
        completed_at_ms: liveCall.completed_at_ms + sequence * 60_000,
        target_alias: `${scanLists.find(list => list.id === id)?.name || 'Unknown'} Dispatch` };
      return route.fulfill({ json: { data: { cursor: String(sequence), reset: false, calls: ids.length ? [call] : [] } } });
    });
    await page.reload();

    const scanner = scenario.view === 'scanner';
    const dock = page.locator('#audio-dock');
    if (!scanner) {
      await dock.locator('#audio-dock-size-handle').press('End');
      await expect(dock).toHaveAttribute('data-state', 'full');
    }
    const primary = scanner ? page.locator('#scanner-play') : dock.locator('.audio-dock-play:visible');
    const stop = scanner ? page.locator('#scanner-stop') : dock.getByRole('button', { name: 'Stop listening', exact: true });
    const status = scanner ? page.locator('.scanner-live-status') : dock.locator('.audio-dock-listening-status [role="status"]');
    const title = scanner ? page.locator('.scanner-call-title') : dock.locator('h2.audio-dock-title');
    const choose = async (id, checked) => {
      await selectAudioPanel(dock, 'Lists');
      const lists = dock.locator('#audio-dock-panel');
      const name = scanLists.find(list => list.id === id).name;
      await lists.getByRole('checkbox', { name: new RegExp(`^${name}(?:\\s|$)`) }).setChecked(checked);
      if (scanner) await page.keyboard.press('Escape');
    };
    const expectNoPolling = async () => {
      const count = feedRequests.length;
      // More than two normal feed intervals must pass without an empty subscription.
      await page.waitForTimeout(300);
      expect(feedRequests.length).toBe(count);
    };
    const expectSelectionWait = async () => {
      await expect(status).toHaveText(/^Select a (?:scan )?[Ll]ist$/);
      await expect(status).not.toHaveClass(/ui-pill-success/);
      await expect(primary).toHaveAttribute('aria-label', /Pause/);
      await expect(stop).toBeEnabled();
      await expect.poll(() => activeSources(page)).toBe(0);
      await expectNoPolling();
    };

    await primary.click();
    await expect(title).toHaveText('Fire Dispatch');
    await expect.poll(() => activeSources(page)).toBe(1);
    await choose(1, false);
    await expectSelectionWait();
    await page.screenshot({ path: testInfo.outputPath('selection-wait.png') });
    await choose(2, true);
    await expect(title).toHaveText('Police Dispatch');
    await expect(primary).toHaveAttribute('aria-label', /Pause/);
    await expect.poll(() => activeSources(page)).toBe(1);

    await primary.click();
    await expect(primary).toHaveAttribute('aria-label', /Resume/);
    await choose(2, false);
    await expect(primary).toHaveAttribute('aria-label', /Resume/);
    await expect(stop).toBeEnabled();
    await expectNoPolling();
    await choose(1, true);
    await expect(primary).toHaveAttribute('aria-label', /Resume/);
    await expect.poll(() => page.evaluate(() => window.audioTest.contexts.at(-1).state)).toBe('suspended');
    await expect.poll(() => activeSources(page)).toBe(0);

    // Resuming while no list is selected preserves the waiting state.
    await choose(1, false);
    await primary.click();
    await expectSelectionWait();
    await choose(2, true);
    await expect(title).toHaveText('Police Dispatch');
    await expect.poll(() => activeSources(page)).toBe(1);

    // An explicit Stop during that gap cancels the remembered listening intent.
    await choose(2, false);
    await expectSelectionWait();
    await stop.click();
    await expect(stop).toBeDisabled();
    await choose(1, true);
    await expect(primary).toHaveAttribute('aria-label', 'Listen live');
    await expect(stop).toBeDisabled();
    await expectNoPolling();
    expect(await activeSources(page)).toBe(0);
    expect(feedRequests.every(ids => ids.length > 0)).toBe(true);
    expect(errors).toEqual([]);
  });
}
