const { expect, test } = require('@playwright/test');
const path = require('node:path');

const longChannelName = 'MetropolitanEmergencyCommunications';
const longAliasListName = 'Metropolitan Emergency Communications County Public Safety';
const dmrProfile = require(path.resolve(__dirname,
  '../../../../../src/main/resources/channel-protocols.json')).profiles
  .find((profile) => profile.id === 'dmr');

async function expectWithin(element, container) {
  const outer = await container.boundingBox();
  const inner = await element.boundingBox();
  expect(inner).not.toBeNull();
  expect(inner.x).toBeGreaterThanOrEqual(outer.x - 1);
  expect(inner.x + inner.width).toBeLessThanOrEqual(outer.x + outer.width + 1);
}

for (const width of [320, 390, 430]) {
  test(`DMR uplink map and long channel name remain inside the editor at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    const configurationId = '00000000-0000-4000-8000-000000000001';
    const channel = {
      configuration_id: configurationId, protocol_id: 'dmr', name: longChannelName,
      system: 'Metro', site: 'North', alias_list_id: 1,
      source: { frequencies_hz: [451012500] }, settings: { channel_mode: 'TRUNKED' },
      frequency_map: [{ number: 1, downlink_hz: 451012500, uplink_hz: 456012500 }],
      event_logs: [], recorders: [], auxiliary_decoders: []
    };
    await page.route('**/api/v1/**', async (route) => {
      const pathname = new URL(route.request().url()).pathname;
      const data = pathname === '/api/v1/auth/session' ? {
        configured: true, authenticated: true, username: 'operator', tier: 'admin', primary: true,
        csrf_token: 'test-token', capabilities: { 'admin-channels': true }
      } : pathname === '/api/v1/me/preferences' ? {
        revision: 1, preferences: {}
      } : pathname === '/api/v1/admin/channels' ? {
        revision: 1, channels: [{ ...channel, frequencies_hz: channel.source.frequencies_hz,
          protocol_label: 'DMR', channel_kind: 'TRUNKED', processing_state: 'RUNNING',
          alias_list_name: 'Default DMR', editable: true }]
      } : pathname === `/api/v1/admin/channels/${configurationId}` ? {
        revision: 1, channel, processing_state: 'RUNNING'
      } : pathname === '/api/v1/admin/channels/options' ? {
        revision: 1, alias_lists: [{ id: 1, name: 'Default DMR', family: 'DMR' }], tuners: []
      } : pathname === '/api/v1/admin/channels/protocols' ? {
        profiles: [dmrProfile], voice_decryption_module_loaded: false
      } : null;
      await route.fulfill(data ? { json: { data } } : {
        status: 404, json: { error: { message: 'Unavailable in this layout test' } }
      });
    });
    await page.goto(`/app.html?view=channel-setup&channel=${configurationId}`);
    const dialog = page.getByRole('dialog', { name: `Edit ${longChannelName}` });
    await expect(dialog).toBeVisible();
    const uplink = dialog.getByRole('spinbutton', { name: 'Uplink frequency in MHz' });
    await expect(uplink).toBeVisible();
    for (const element of [dialog.locator('.channel-editor-hero strong'),
      dialog.getByLabel('Name', { exact: true }), uplink,
      dialog.getByRole('button', { name: 'Add mapping' }),
      dialog.getByRole('button', { name: 'Save & restart' })]) {
      await expectWithin(element, dialog);
    }
    const form = dialog.locator('.channel-editor-form');
    expect(await form.evaluate((element) => element.scrollWidth - element.clientWidth))
      .toBeLessThanOrEqual(1);
    await uplink.focus();
    expect(await form.evaluate((element) => element.scrollLeft)).toBe(0);
  });

  test(`scanner playback controls and volume fit the popup at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    await page.goto('/design-system.html?theme=light&view=scanner');
    const menu = page.locator('.scanner-player-host .playback-control-menu');
    await menu.locator('summary').click();
    const panel = menu.locator('.playback-control-menu-panel');
    await expect(panel).toBeVisible();
    const panelBounds = await panel.boundingBox();
    expect(panelBounds.x).toBeGreaterThanOrEqual(0);
    expect(panelBounds.x + panelBounds.width).toBeLessThanOrEqual(width);
    for (const button of await panel.locator('button').all()) await expectWithin(button, panel);
    const volume = panel.getByRole('slider', { name: 'Browser playback volume' });
    await expectWithin(volume, panel);
    expect((await volume.boundingBox()).width).toBeGreaterThanOrEqual(120);
    const initialVolume = Number(await volume.inputValue());
    await volume.focus();
    await volume.press('ArrowLeft');
    expect(Number(await volume.inputValue())).toBeCloseTo(initialVolume - 0.05);
  });
}

test('full administrator header keeps theme and other actions visible on a 320px phone', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 740 });
  await page.goto('/design-system.html?theme=light&view=app-chrome');
  const header = page.locator('.visual-app-chrome-example .topbar');
  for (const control of await header.locator('.header-controls a, .header-controls button:visible').all()) {
    await expectWithin(control, header);
    expect((await control.boundingBox()).x + (await control.boundingBox()).width)
      .toBeLessThanOrEqual(320);
  }
  await page.getByRole('button', { name: 'Use dark theme' }).focus();
  expect(await page.evaluate(() => document.documentElement.scrollLeft)).toBe(0);
  await expect(header).toHaveScreenshot('full-administrator-header-light-320.png');
});

for (const theme of ['light', 'dark']) {
  test(`long-content gallery stays bounded on the smallest phone in ${theme} theme`, async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 740 });
    await page.goto(`/design-system.html?theme=${theme}&view=gallery`);
    const example = page.locator('.visual-mobile-boundaries');
    for (const selector of ['.page-title', '.alias-list-summary h2', '.ui-search', 'a.ui-pill']) {
      await expectWithin(example.locator(selector), example);
    }
    const search = example.locator('.ui-search');
    await expectWithin(search, search.locator('..'));
    await expect(example).toHaveScreenshot(`long-content-${theme}-320.png`);
  });
}

test('long Alias List names and routing links stay within their phone cards', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 740 });
  await page.goto('/design-system.html?theme=dark&view=aliases');
  const summary = page.locator('.visual-aliases-example .alias-list-summary');
  await summary.locator('h2').evaluate((heading, name) => { heading.textContent = name; }, longAliasListName);
  await expectWithin(summary.locator('h2'), summary);
  expect(await summary.evaluate((element) => element.scrollWidth - element.clientWidth)).toBeLessThanOrEqual(1);

  await page.goto('/design-system.html?theme=light&view=admin-scan-lists');
  const routes = page.locator('.scan-list-detail-route-links');
  const link = routes.locator('a.ui-pill').first();
  await link.evaluate((element, name) => { element.textContent = name; }, longAliasListName);
  await expectWithin(link, routes);
  expect((await link.boundingBox()).height).toBeGreaterThan(30);
  await link.focus();
  expect(await page.evaluate(() => document.documentElement.scrollLeft)).toBe(0);
});

test('long unbroken detail titles wrap within the padded page header', async ({ page }) => {
  await page.setViewportSize({ width: 430, height: 844 });
  await page.goto('/design-system.html?theme=light&view=entity-details');
  const header = page.locator('.visual-entity-details-example .page-header');
  const title = header.locator('.page-title');
  await title.evaluate((heading, name) => { heading.textContent = name; }, longChannelName);
  await expectWithin(title, header);
  expect(await header.evaluate((element) => element.scrollWidth - element.clientWidth)).toBeLessThanOrEqual(1);
});

test('nested search fields preserve the toolbar padding on a 320px phone', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 740 });
  for (const view of ['channels', 'radio-directory', 'radioreference-results']) {
    await page.goto(`/design-system.html?theme=light&view=${view}`);
    if (view === 'radioreference-results') await page.locator('.radioreference-import-workspace')
      .evaluate((workspace) => workspace.classList.add('radioreference-detail-open'));
    const search = page.locator(`${view === 'radioreference-results' ?
      '.radioreference-sites-toolbar' : '.channel-admin-toolbar'} .ui-search:visible`).first();
    const toolbar = search.locator('..');
    await expectWithin(search, toolbar);
    const rightInset = await search.evaluate((element) => {
      const parent = element.parentElement;
      return parent.getBoundingClientRect().right - element.getBoundingClientRect().right -
        parseFloat(getComputedStyle(parent).paddingRight);
    });
    expect(rightInset).toBeGreaterThanOrEqual(-1);
  }
});

test('short landscape Live keeps the details tabs and content reachable by scrolling', async ({ page }) => {
  await page.setViewportSize({ width: 844, height: 390 });
  await page.goto('/design-system.html?theme=light&view=live-notice');
  await page.evaluate(() => {
    const main = document.querySelector('.visual-live-example');
    main.classList.remove('visual-live-example');
    const header = document.querySelector('.visual-app-chrome-example .app-header');
    const shell = document.createElement('div');
    shell.className = 'app-shell';
    shell.append(header, main);
    document.body.replaceChildren(shell);
    document.body.dataset.view = 'live';
    main.querySelector('.live-split').classList.remove('details-collapsed');
    main.querySelector('.live-details').classList.remove('collapsed');
  });
  const content = page.locator('.content');
  const details = page.locator('.live-details');
  await details.locator('.live-details-tab').first().scrollIntoViewIfNeeded();
  const tabBounds = await details.locator('.live-details-tab').first().boundingBox();
  expect(tabBounds.y).toBeGreaterThanOrEqual(0);
  expect(tabBounds.y + tabBounds.height).toBeLessThanOrEqual(390);
  await content.evaluate((element) => { element.scrollTop = element.scrollHeight; });
  const bottom = (await details.boundingBox()).y + (await details.boundingBox()).height;
  expect(bottom).toBeLessThanOrEqual(391);
});

test('P25 Events shows the entire final entry after its wrapped heading', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 740 });
  await page.goto('/design-system.html?theme=light&view=gallery');
  await page.evaluate(() => {
    document.body.dataset.view = 'network-visualizer';
    document.body.innerHTML = `<div class="app-shell"><main class="content">
      <div class="network-visualizer-layout"><div class="network-visualizer-toolbar">P25 Visualizer</div>
      <div class="network-visualizer-stage"><aside class="network-visualizer-events">
      <header class="network-visualizer-panel-header"><div class="network-visualizer-panel-heading">
      <h2>Noteworthy P25 activity</h2><p>Grouped from saved Activity history</p></div>
      <button class="ui-button ui-button-secondary ui-icon-button" aria-label="Close noteworthy activity">×</button></header>
      <ol class="network-visualizer-event-list">${Array.from({ length: 20 }, (_, index) =>
        `<li class="network-visualizer-event"><strong>Event ${index + 1}</strong>
        <span class="network-visualizer-event-detail">Radio 105 → Fire Dispatch</span><time>12:30</time></li>`).join('')}
      </ol></aside></div></div></main></div>`;
  });
  const list = page.locator('.network-visualizer-event-list');
  await list.evaluate((element) => { element.scrollTop = element.scrollHeight; });
  const finalBounds = await list.locator('li').last().boundingBox();
  const panelBounds = await page.locator('.network-visualizer-events').boundingBox();
  expect(finalBounds.y).toBeGreaterThanOrEqual(panelBounds.y);
  expect(finalBounds.y + finalBounds.height).toBeLessThanOrEqual(panelBounds.y + panelBounds.height + 1);
});
