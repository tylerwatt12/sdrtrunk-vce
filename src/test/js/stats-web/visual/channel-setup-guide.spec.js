const { expect, test } = require('@playwright/test');
const { installSiteStyleApplication, operatorTuner } = require('./fixtures/site-style-app.cjs');

const eligibleTuner = { ...operatorTuner({ spectrum_target_id: 'target-a', spectrum_available: true }),
  eligible: true, source_type: 'receiver', channel_count: 0, center_frequency_hz: 851012500,
  usable_bandwidth_hz: 9000000, minimum_frequency_hz: 24000000, maximum_frequency_hz: 1800000000 };
const guide = page => page.getByRole('dialog', { name: 'Add a channel', exact: true });
const toolbarAction = (page, name) => page.locator('.channel-creation-actions')
  .getByRole('button', { name, exact: true });
const actionNames = ['Find Trunked Systems', 'New channel'];
const guideRoot = page => page.locator('.ui-setup-guide');

async function startTour(page) {
  await expect(guideRoot(page)).toHaveAttribute('data-guide-step', 'overview');
  await expect(guideRoot(page).locator('.ui-setup-guide-counter')).toHaveText('Overview');
  await expect(guideRoot(page)).not.toContainText(/Step \d+ of/);
  await expect(page.locator('.ui-setup-guide-highlight, [data-guide-sample], .ui-setup-guide-layer')).toHaveCount(0);
  await guideRoot(page).getByRole('button', { name: 'Start tour', exact: true }).click();
}

async function expectWithinViewport(locator, viewport) {
  await expect.poll(async () => {
    const bounds = await locator.boundingBox();
    return bounds !== null && bounds.x >= 0 && bounds.y >= 0 &&
      bounds.x + bounds.width <= viewport.width && bounds.y + bounds.height <= viewport.height;
  }).toBe(true);
}

async function expectSpotlightsFollowToolbar(page, names = ['New channel']) {
  for (const name of names) {
    const action = toolbarAction(page, name);
    const bounds = await action.boundingBox();
    await expect.poll(async () => action.evaluate(element => {
      const bounds = element.getBoundingClientRect();
      return [...document.querySelectorAll('.ui-setup-guide-highlight')].some(highlight => {
        const rect = highlight.getBoundingClientRect();
        return rect.left <= bounds.left && rect.top <= bounds.top &&
          rect.right >= bounds.right && rect.bottom >= bounds.bottom;
      });
    })).toBe(true);
    const panel = await guideRoot(page).boundingBox();
    expect(panel.x < bounds.x + bounds.width && panel.x + panel.width > bounds.x &&
      panel.y < bounds.y + bounds.height && panel.y + panel.height > bounds.y,
    `Guide does not cover ${name}`).toBe(false);
  }
}

async function expectSpotlightOn(page, locator) {
  await expect(locator).toBeVisible();
  await expect.poll(() => locator.evaluate(element => {
    const bounds = element.getBoundingClientRect();
    return [...document.querySelectorAll('.ui-setup-guide-highlight')].some(highlight => {
      const rect = highlight.getBoundingClientRect();
      return rect.left <= bounds.left && rect.top <= bounds.top &&
        rect.right >= bounds.right && rect.bottom >= bounds.bottom;
    });
  })).toBe(true);
}

async function chooserChoices(dialog) {
  return dialog.locator('.ui-choice-card').evaluateAll(elements => elements.map(element => ({
    name: element.getAttribute('aria-label'), classes: element.className,
    disabled: element.disabled, text: element.textContent,
    description: (element.getAttribute('aria-describedby') || '').split(' ')
      .map(id => document.getElementById(id)?.textContent).join(' ')
  })));
}

async function captureToolbarActions(page) {
  return Promise.all(actionNames.map(async name => {
    const button = await toolbarAction(page, name).elementHandle();
    return { name, button, parent: await button.evaluateHandle(element => element.parentElement),
      bounds: await button.boundingBox(), describedBy: await button.getAttribute('aria-describedby') };
  }));
}

async function expectToolbarActionsUnmoved(page, actions) {
  for (const { name, button, parent, bounds } of actions) {
    await expect(toolbarAction(page, name)).toBeVisible();
    await expect(page.getByRole('button', { name, exact: true })).toHaveCount(1);
    expect(await button.evaluate((element, originalParent) => element.isConnected && element.parentElement === originalParent, parent)).toBe(true);
    const current = await button.boundingBox();
    for (const coordinate of ['x', 'y', 'width', 'height']) expect(current[coordinate]).toBeCloseTo(bounds[coordinate], 0);
  }
}

async function openChannels(page, options = {}) {
  const fixture = await installSiteStyleApplication(page, options.theme || 'light');
  const state = { catalogReads: 0, channelReads: 0, revision: 1, channels: options.channels || [], tuners: options.tuners ?? [eligibleTuner],
    catalogFailure: options.catalogFailure, pendingCatalog: null, releaseCatalog: null };
  if (options.holdCatalog) state.pendingCatalog = new Promise(resolve => { state.releaseCatalog = resolve; });
  await page.route('**/api/v1/admin/channels', route => {
    state.channelReads += 1;
    return route.fulfill({ json: { data: { revision: state.revision, channels: state.channels } } });
  });
  await page.route('**/api/v1/channel-catalog', route => route.fulfill({ json: { data: {
    revision: state.revision, channels: state.channels } } }));
  await page.route('**/api/v1/admin/spectrum-search/catalog', async route => {
    state.catalogReads += 1;
    if (state.pendingCatalog) await state.pendingCatalog;
    if (state.catalogFailure) return route.fulfill({ status: 503, json: { error: { message: 'Catalog unavailable' } } });
    return route.fulfill({ json: { data: { tuners: state.tuners, suggested_tuner_id: 'tuner-a',
      presets: [{ id: '800mhz', label: '800 MHz · 851–869 MHz', ranges: [{ minimum_hz: 851000000, maximum_hz: 869000000 }] }],
      bounds: { maximum_ranges: 8, maximum_windows: 64, maximum_candidates: 32, maximum_total_hz: 150000000,
        minimum_dwell_ms: 750, maximum_dwell_ms: 5000, default_dwell_ms: 1500, maximum_scan_ms: 900000 } } } });
  });
  await page.route('**/api/v1/admin/tuners/tuner-a/browse', route => route.fulfill({ json: { data: {
    lease_id: 'fixture-guide-lease', expires_at_epoch_ms: Date.now() + 30000, can_tune: true, tuner: eligibleTuner } } }));
  await page.route('**/api/v1/admin/channels/protocols/*/template', route => route.fulfill({ json: { data: {
    protocol_id: new URL(route.request().url()).pathname.split('/').at(-2), name: '',
    source: { frequencies_hz: [], preferred_tuner: null }, settings: {}, event_logs: [], recorders: [], auxiliary_decoders: [] } } }));
  if (options.noTunerAccess) await page.route('**/api/v1/auth/session', route => route.fulfill({ json: { data: {
    configured: true, authenticated: true, username: 'fixture-admin', tier: 'admin', primary: true,
    csrf_token: 'fixture-csrf', guide_dismissal_scope: 'account-1-100', capabilities: { 'admin-channels': true, 'admin-tuners': false, 'user-settings': true } } } }));
  if (options.noRadioReferenceAccess) await page.route('**/api/v1/auth/session', route => route.fulfill({ json: { data: {
    configured: true, authenticated: true, username: 'fixture-admin', tier: 'admin', primary: true,
    csrf_token: 'fixture-csrf', guide_dismissal_scope: 'account-1-100', capabilities: { 'admin-channels': true, 'admin-tuners': true,
      'admin-aliases': false, 'user-settings': true } } } }));
  await page.goto(`/app.html?view=channel-setup${options.guide ? `&guide=${options.guide}` : ''}`);
  await expect(page.getByRole('heading', { name: 'Channel Setup', exact: true })).toBeVisible();
  if (options.guide && page.viewportSize().width >= 760) await startTour(page);
  return { fixture, state };
}

async function openReceivingStep(page) {
  await expect(guideRoot(page)).toHaveAttribute('data-guide-step', 'new-channel');
  for (const step of ['channel-choice', 'channel-choice-close', 'discover', 'select-channel', 'receiving']) {
    await guideRoot(page).getByRole('button', { name: 'Next', exact: true }).click();
    await expect(guideRoot(page)).toHaveAttribute('data-guide-step', step);
    if (step === 'select-channel') {
      const selection = page.locator('[data-guide-target="channel-sample-select"]');
      await expect(selection).not.toBeChecked();
      await expectSpotlightOn(page, selection);
      await expect(page.locator('[data-guide-target="channel-sample-start"]')).not.toBeVisible();
    }
  }
  await expect(guideRoot(page).getByRole('heading', { name: 'Start receiving', exact: true })).toBeVisible();
}

const savedChannel = { configuration_id: 'existing-channel', name: 'Existing channel',
  protocol: 'P25', protocol_id: 'p25-phase1', protocol_label: 'P25 Phase 1', channel_kind: 'TRUNKED',
  frequencies_hz: [851012500], processing_state: 'STOPPED', editable: true };

const sampleChannel = page => page.locator('.channel-catalog-table tr[data-id]')
  .filter({ hasText: /sample/i });

async function expectSampleChannel(page) {
  await expect(sampleChannel(page)).toHaveCount(1);
  await expect(sampleChannel(page)).toBeVisible();
  await expect(page.locator('.channel-selection-bar').getByRole('button', { name: 'Start', exact: true })).toBeVisible();
  const identity = await sampleChannel(page).locator('.channel-name-summary').boundingBox();
  const panel = await guideRoot(page).boundingBox();
  expect(panel.x < identity.x + identity.width && panel.x + panel.width > identity.x &&
    panel.y < identity.y + identity.height && panel.y + panel.height > identity.y,
  'The guide keeps the sample channel name readable').toBe(false);
}

for (const theme of ['light', 'dark']) {
  for (const width of [1280, 390, 320]) {
    test(`Channel Setup creation actions sit with the catalog at ${width}px ${theme}`, async ({ page }, testInfo) => {
      const viewport = { width, height: 900 };
      await page.setViewportSize(viewport);
      const { fixture } = await openChannels(page, { theme, channels: [{ configuration_id: 'saved',
        name: 'County Simulcast', protocol_id: 'p25-phase1', protocol_label: 'P25 Phase 1',
        processing_state: 'STOPPED', frequencies_hz: [851012500], editable: true }] });
      const actions = page.locator('.channel-creation-actions');
      await expect(actions.locator(':scope > button')).toHaveText(['New channel', 'Find Trunked Systems']);
      const styles = [];
      for (const name of ['New channel', 'Find Trunked Systems']) {
        const action = actions.getByRole('button', { name, exact: true });
        await expect(action).toHaveClass(/\bui-button-primary\b/);
        await expectWithinViewport(action, viewport);
        styles.push(await action.evaluate(element => {
          const style = getComputedStyle(element);
          return { height: element.getBoundingClientRect().height, background: style.backgroundColor,
            color: style.color, fontWeight: style.fontWeight, borderRadius: style.borderRadius };
        }));
      }
      expect(styles[0]).toEqual(styles[1]);
      expect(styles[0].height).toBe(40);
      await page.screenshot({ path: testInfo.outputPath(`catalog-actions-${width}-${theme}.png`), fullPage: true });
      await expect(page.locator('.channel-setup-page-header button')).toHaveCount(0);
      await expect(page.locator('.channel-catalog-header > .channel-catalog-overview > .channel-creation-actions'))
        .toHaveCount(1);
      await expect(page.locator('.channel-catalog-header > .channel-catalog-overview > .channel-summary-row'))
        .toHaveCount(1);
      await expect(page.locator('.channel-catalog-header > .channel-admin-toolbar')).toHaveCount(1);
      const actionBounds = await actions.boundingBox();
      const filterBounds = await page.locator('.channel-admin-toolbar').boundingBox();
      const tableBounds = await page.locator('.channel-catalog-table-host').boundingBox();
      expect(actionBounds.x).toBeCloseTo(filterBounds.x, 0);
      expect(actionBounds.x).toBeCloseTo(tableBounds.x, 0);
      const actionFilterGap = filterBounds.y - (actionBounds.y + actionBounds.height);
      expect(actionFilterGap).toBeGreaterThanOrEqual(8);
      const summaryBounds = await page.locator('.channel-summary-row').boundingBox();
      expect(actionFilterGap).toBeLessThanOrEqual(width >= 760 ? 8 : summaryBounds.height + 16);
      expect(tableBounds.y - (filterBounds.y + filterBounds.height)).toBeLessThanOrEqual(16);
      if (width >= 390) {
        const buttons = await actions.locator(':scope > button').all();
        const [first, second] = await Promise.all(buttons.map(button => button.boundingBox()));
        expect(first.y).toBeCloseTo(second.y, 0);
        expect(second.x).toBeGreaterThan(first.x + first.width);
      }
      await expect(page.locator('.channel-catalog-tools').getByRole('button',
        { name: 'Find Trunked Systems', exact: true })).toHaveCount(0);
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
      expect(fixture.pageErrors).toEqual([]);
    });
  }
}

for (const theme of ['light', 'dark']) {
  test(`channel tour shows the actual chooser after its opener and its Close action before returning ${theme}`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    const { fixture } = await openChannels(page, { theme });
    const chooser = page.getByRole('dialog', { name: 'How would you like to create a channel?', exact: true });
    await toolbarAction(page, 'New channel').click();
    const originalChoices = await chooserChoices(chooser);
    await page.keyboard.press('Escape');
    const writes = [], navigations = [];
    page.on('request', request => {
      if (new URL(request.url()).pathname.startsWith('/api/v1/') && !['GET', 'HEAD'].includes(request.method())) writes.push(request.url());
    });
    page.on('framenavigated', frame => { if (frame === page.mainFrame()) navigations.push(frame.url()); });
    await page.locator('#page-guide-button').click();
    await startTour(page);
    await expectSpotlightsFollowToolbar(page);
    await expect(chooser).toHaveCount(0);
    await guideRoot(page).getByRole('button', { name: 'Next', exact: true }).click();
    await expect(guideRoot(page)).toHaveAttribute('data-guide-step', 'channel-choice');
    await expect(chooser).toBeVisible();
    expect(await chooserChoices(chooser)).toEqual(originalChoices);
    await expectSpotlightOn(page, page.locator('[data-guide-target="channel-choice"]'));
    // Sample isolation also guards direct events on the real production controls.
    for (const button of await chooser.locator('button').all()) {
      await button.evaluate(element => element.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true })));
    }
    await expect(chooser).toBeVisible();
    await guideRoot(page).getByRole('button', { name: 'Next', exact: true }).click();
    await expect(guideRoot(page)).toHaveAttribute('data-guide-step', 'channel-choice-close');
    await expect(chooser).toBeVisible();
    await expectSpotlightOn(page, page.locator('[data-guide-target="channel-choice-close"]'));
    await expect(page.locator('.ui-setup-guide-highlight')).toHaveCount(1);
    await page.screenshot({ path: testInfo.outputPath(`channel-return-${theme}.png`) });
    await guideRoot(page).getByRole('button', { name: 'Next', exact: true }).click();
    await expect(guideRoot(page)).toHaveAttribute('data-guide-step', 'discover');
    await expect(chooser).toHaveCount(0);
    await expectSpotlightsFollowToolbar(page, ['Find Trunked Systems']);
    await guideRoot(page).getByRole('button', { name: 'Back', exact: true }).click();
    await expect(chooser).toBeVisible();
    await expectSpotlightOn(page, page.locator('[data-guide-target="channel-choice-close"]'));
    await page.keyboard.press('Escape');
    await expect(chooser).toHaveCount(0);
    await expect(page.locator('.ui-setup-guide-layer, [data-guide-sample]')).toHaveCount(0);
    expect(writes).toEqual([]);
    expect(navigations).toEqual([]);
    expect(fixture.pageErrors).toEqual([]);
  });
}

for (const theme of ['light', 'dark']) {
  test(`Channel Setup guide uses the shared page tour and follows the original controls ${theme}`, async ({ page }) => {
    const { fixture } = await openChannels(page, { theme });
    const actions = await captureToolbarActions(page);
    await page.locator('#page-guide-button').click();
    await startTour(page);
    await expect(guide(page)).toBeVisible();
    await expect(guideRoot(page)).toContainText('Step 1 of 10');
    await expectToolbarActionsUnmoved(page, actions);
    await expectSpotlightsFollowToolbar(page);
    await expectWithinViewport(guideRoot(page), page.viewportSize());
    await guideRoot(page).getByRole('button', { name: 'Next', exact: true }).click();
    await expect(guideRoot(page)).toContainText('Choose your source');
    await expect(guideRoot(page)).toContainText('Step 2 of 10');
    await expect(page.getByRole('dialog', { name: 'How would you like to create a channel?', exact: true })).toBeVisible();
    await guideRoot(page).getByRole('button', { name: 'Back', exact: true }).click();
    await expect(guide(page)).toBeVisible();
    await guideRoot(page).getByRole('button', { name: 'Back', exact: true }).click();
    await expect(guideRoot(page)).toHaveAttribute('data-guide-step', 'overview');
    await expect(page.locator('.ui-setup-guide-highlight, [data-guide-sample]')).toHaveCount(0);
    await expectToolbarActionsUnmoved(page, actions);
    await startTour(page);
    await expect(guideRoot(page)).toContainText('Step 1 of 10');
    await page.keyboard.press('Escape');
    await expect(guideRoot(page)).toHaveCount(0);
    await expectToolbarActionsUnmoved(page, actions);
    for (const { button, describedBy } of actions) expect(await button.getAttribute('aria-describedby')).toBe(describedBy);
    expect(fixture.pageErrors).toEqual([]);
  });
}

for (const theme of ['light', 'dark']) {
  test(`Channel Setup guide URL replays the complete guide after dismissal ${theme}`, async ({ page }, testInfo) => {
    const { fixture } = await openChannels(page, { theme, guide: 'page' });
    await expect(guide(page)).toBeVisible();
    const player = page.locator('#audio-dock');
    const originalPlayer = await player.evaluate(element => ({
      source: element.dataset.source, state: element.dataset.state
    }));
    const expectListeningStep = async () => {
      await expect(guideRoot(page).getByRole('heading', { name: 'Choose what you hear', exact: true })).toBeVisible();
      await expect(page.locator('.primary-nav details[open]')).toHaveCount(0);
      const lists = page.locator('[data-guide-target="scan-lists-player-lists"]');
      await expect(lists).toBeVisible();
      await expect(page.locator('.ui-setup-guide-highlight')).toHaveCount(1);
      await expect.poll(() => lists.evaluate(element => {
        const bounds = element.getBoundingClientRect();
        const highlight = document.querySelector('.ui-setup-guide-highlight').getBoundingClientRect();
        return highlight.left <= bounds.left && highlight.top <= bounds.top &&
          highlight.right >= bounds.right && highlight.bottom >= bounds.bottom;
      }), { message: 'The final step spotlights the actual player Lists panel' }).toBe(true);
      await expect(lists).toContainText('Public Safety');
      await expect(lists).toContainText('Fire & EMS');
      await expect(lists).not.toContainText('Unidentified');
    };
    const expectedTitles = ['Choose your source', 'Return to your channels', 'Search for trunked systems',
      'Choose a channel to start', 'Start receiving', 'Ready when you are', 'Listen as you browse',
      'Open your listening choices', 'Choose what you hear'];
    for (const title of expectedTitles) {
      await guideRoot(page).getByRole('button', { name: 'Next', exact: true }).click();
      await expect(guideRoot(page)).toContainText(title);
      if (title === 'Listen as you browse') {
        await expect(guideRoot(page)).toHaveAttribute('data-guide-step', 'player-overview');
        await expectSpotlightOn(page, player);
        await expect(player.locator('.audio-dock-lists-trigger:visible').first()).toBeVisible();
        await expect(player.locator('.audio-dock-selection-list')).toHaveCount(0);
        await expect(guideRoot(page)).toContainText('The player plays completed calls from the Scan Lists you choose.');
      }
    }
    await expectListeningStep();
    await page.screenshot({ path: testInfo.outputPath(`channel-listening-step-${theme}.png`) });
    await guideRoot(page).getByRole('button', { name: 'Back', exact: true }).click();
    await expect(guideRoot(page).getByRole('heading', { name: 'Open your listening choices', exact: true })).toBeVisible();
    await expect(page.locator('.primary-nav details[open]')).toHaveCount(0);
    await expect(page.locator('[data-guide-target="scan-lists-player-lists"]')).toHaveCount(0);
    await expect(page.locator('[data-guide-target="player-lists"]')).toBeVisible();
    await guideRoot(page).getByRole('button', { name: 'Next', exact: true }).click();
    await expectListeningStep();
    await guideRoot(page).getByRole('button', { name: 'Finish', exact: true }).click();
    await expect(guideRoot(page)).toHaveCount(0);
    await expect(page.locator('.primary-nav details[open]')).toHaveCount(0);
    await expect(player).toHaveAttribute('data-source', originalPlayer.source);
    await expect(player).toHaveAttribute('data-state', originalPlayer.state);
    await page.goto('/app.html?view=channel-setup&guide=page');
    await startTour(page);
    await expect(guide(page)).toBeVisible();
    await guideRoot(page).getByRole('button', { name: 'Close guide', exact: true }).click();
    await expect(guideRoot(page)).toHaveCount(0);
    // The fixture server mounts the application at /app.html and the gallery at /.
    await page.goto(`/app.html${new URL(page.url()).search}`);
    await expect(page.getByRole('heading', { name: 'Channel Setup', exact: true })).toBeVisible();
    await expect(guideRoot(page)).toHaveCount(0);
    expect(fixture.pageErrors).toEqual([]);
  });
}

for (const channels of [[], [savedChannel]]) {
  for (const exit of ['Next', 'Back', 'Close guide', 'darkened area', 'Escape']) {
    test(`receiving sample restores the ${channels.length ? 'populated' : 'empty'} channel catalog after ${exit}`, async ({ page }, testInfo) => {
      const { fixture } = await openChannels(page, { channels, guide: 'page' });
      await openReceivingStep(page);
      await expectSampleChannel(page);
      await expect(page.locator('.channel-catalog-table tr[data-id="existing-channel"]')).toHaveCount(0);
      if (exit === 'Next' && !channels.length) {
        await page.screenshot({ path: testInfo.outputPath('receiving-sample-light.png') });
      }
      if (exit === 'darkened area') await page.mouse.click(8, 500);
      else if (exit === 'Escape') await page.keyboard.press('Escape');
      else await guideRoot(page).getByRole('button', { name: exit, exact: true }).click();
      if (exit === 'Next') {
        await expectSampleChannel(page);
      } else if (exit === 'Back') {
        await expect(sampleChannel(page)).toBeVisible();
        await expect(page.locator('[data-guide-target="channel-sample-select"]')).not.toBeChecked();
        await expect(page.locator('[data-guide-target="channel-sample-start"]')).not.toBeVisible();
      } else await expect(sampleChannel(page)).toHaveCount(0);
      if (!['Next', 'Back'].includes(exit) && channels.length) {
        await expect(page.locator('.channel-catalog-table tr[data-id="existing-channel"]')).toBeVisible();
      } else if (!['Next', 'Back'].includes(exit)) await expect(page.getByText('No channels match this view', { exact: true })).toBeVisible();
      if (['Next', 'Back'].includes(exit)) {
        await expect(guideRoot(page)).toContainText(exit === 'Next' ? 'Ready when you are' : 'Choose a channel to start');
        await guideRoot(page).getByRole('button', { name: 'Close guide', exact: true }).click();
        await expect(sampleChannel(page)).toHaveCount(0);
      } else await expect(guideRoot(page)).toHaveCount(0);
      expect(fixture.pageErrors).toEqual([]);
    });
  }
}

test('receiving sample keeps real filters and selection, ignores actions, and restores the latest catalog', async ({ page }) => {
  const writes = [], sampleRequests = [];
  page.on('request', request => {
    const path = new URL(request.url()).pathname;
    if (path.startsWith('/api/v1/') && !['GET', 'HEAD'].includes(request.method())) writes.push(path);
    if (path.includes('guide-sample-channel')) sampleRequests.push(path);
  });
  const { fixture, state } = await openChannels(page, { channels: [savedChannel] });
  const search = page.getByRole('searchbox', { name: 'Search channels, systems, protocols, frequencies, or alias lists', exact: true });
  await page.locator('.channel-catalog-table tr[data-id="existing-channel"]')
    .getByRole('checkbox', { name: 'Select Existing channel', exact: true }).check();
  await search.fill('Existing');
  await page.locator('#page-guide-button').click();
  await startTour(page);
  await openReceivingStep(page);
  await expectSampleChannel(page);
  const before = page.url();
  const controls = await sampleChannel(page).locator('button, a, input, select').all();
  controls.push(page.locator('[data-guide-target="channel-sample-start"]'));
  for (const control of controls) {
    // Exercise the handlers directly too: the guide's pointer shield is not the
    // only thing preventing a fixture from changing receiver state.
    await control.evaluate(element => {
      element.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
      element.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
    });
  }
  await expect(page).toHaveURL(before);
  await expect(guideRoot(page)).toContainText('Start receiving');
  const reads = state.channelReads;
  state.channels = [{ ...savedChannel, name: 'Existing channel updated', processing_state: 'RUNNING' }];
  state.revision += 1;
  await expect.poll(() => state.channelReads, { timeout: 7_000 }).toBeGreaterThan(reads);
  await expectSampleChannel(page);
  await expect(page.locator('.channel-catalog-table')).not.toContainText('Existing channel updated');
  await guideRoot(page).getByRole('button', { name: 'Close guide', exact: true }).click();
  await expect(sampleChannel(page)).toHaveCount(0);
  await expect(search).toHaveValue('Existing');
  const restored = page.locator('.channel-catalog-table tr[data-id="existing-channel"]');
  await expect(restored).toContainText('Existing channel updated');
  await expect(restored).toContainText('Running');
  await expect(restored.getByRole('checkbox', { name: 'Select Existing channel updated', exact: true })).toBeChecked();
  await expect(page.locator('.channel-selection-bar')).toContainText('1 selected');
  expect(writes).toEqual([]);
  expect(sampleRequests).toEqual([]);
  expect(fixture.pageErrors).toEqual([]);
});

test('navigating away from a receiving sample removes it before returning to channels', async ({ page }) => {
  const { fixture } = await openChannels(page, { channels: [savedChannel], guide: 'page' });
  await openReceivingStep(page);
  await expectSampleChannel(page);
  await page.locator('.primary-nav [data-view="dashboard"]').evaluate(link => link.click());
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  await expect(guideRoot(page)).toHaveCount(0);
  await expect(sampleChannel(page)).toHaveCount(0);
  await page.locator('.primary-nav [data-view="channel-setup"]').evaluate(link => link.click());
  await expect(page.locator('.channel-catalog-table tr[data-id="existing-channel"]')).toBeVisible();
  await expect(sampleChannel(page)).toHaveCount(0);
  expect(fixture.pageErrors).toEqual([]);
});

test('the RadioReference sign-in page links to premium account creation', async ({ page }) => {
  const { fixture } = await openChannels(page);
  await toolbarAction(page, 'New channel').click();
  await page.getByRole('button', { name: 'Import from RadioReference', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Connect your account', exact: true })).toBeVisible();
  const signup = page.getByRole('link', { name: 'Create a premium account', exact: true });
  await expect(signup).toBeVisible();
  await expect(signup.locator('..')).toHaveText('Need an account? Create a premium account');
  await expect(signup).toHaveAttribute('href', 'https://www.radioreference.com/premium/');
  await expect(signup).toHaveAttribute('target', '_blank');
  await expect(signup).toHaveAttribute('rel', /noopener/);
  expect(fixture.pageErrors).toEqual([]);
});

for (const [action, destination] of [['New channel', 'Create Channel'], ['Find Trunked Systems', 'Find Trunked Systems']]) {
  test(`Channel Setup opens the existing ${action} workflow`, async ({ page }) => {
    const { fixture } = await openChannels(page);
    await toolbarAction(page, action).click();
    await expect(guide(page)).toHaveCount(0);
    if (action === 'New channel') {
      const chooser = page.getByRole('dialog', { name: 'How would you like to create a channel?', exact: true });
      await expect(chooser).toBeVisible();
      await chooser.getByRole('button', { name: 'Create a channel from scratch', exact: true }).click();
      await expect(chooser).toHaveCount(0);
    }
    const workflow = page.getByRole('dialog', { name: destination, exact: true });
    await expect(workflow).toBeVisible();
    if (action === 'New channel') await expect(workflow.getByRole('button', { name: 'Create channel', exact: true })).toBeVisible();
    else await expect(workflow.getByRole('heading', { name: 'Choose where to look', exact: true })).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(workflow).toHaveCount(0);
    await expect(toolbarAction(page, action)).toBeVisible();
    expect(fixture.pageErrors).toEqual([]);
  });
}

for (const { theme, viewport, entry } of [
  { theme: 'light', viewport: { width: 1280, height: 800 }, entry: 'toolbar' },
  { theme: 'dark', viewport: { width: 320, height: 740 }, entry: 'toolbar' }
]) {
  test(`new channel choices open RadioReference from the ${entry} ${theme}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    const editorRequests = [];
    page.on('request', request => {
      const path = new URL(request.url()).pathname;
      if (/\/admin\/channels\/protocols\/[^/]+\/template$/.test(path) ||
          path === '/api/v1/admin/channels' && request.method() === 'POST') editorRequests.push(path);
    });
    const { fixture } = await openChannels(page, { theme, ...(entry === 'toolbar' ? { tuners: [] } : {}) });
    const newChannel = toolbarAction(page, 'New channel');
    await newChannel.focus();
    await page.keyboard.press('Enter');
    const chooser = page.getByRole('dialog', { name: 'How would you like to create a channel?', exact: true });
    await expect(guide(page)).toHaveCount(0);
    await expect(chooser).toBeVisible();
    const importChoice = chooser.getByRole('button', { name: 'Import from RadioReference', exact: true });
    const manualChoice = chooser.getByRole('button', { name: 'Create a channel from scratch', exact: true });
    await expect(importChoice).toBeFocused();
    await expect(importChoice).toBeEnabled();
    await expect(importChoice).toHaveAccessibleDescription(
      'Find radio systems and channels in your area using the RadioReference database. ' +
      'A RadioReference Premium subscription is required.');
    await expect(manualChoice).toBeEnabled();
    await expect(manualChoice).toHaveAccessibleDescription('Enter a known frequency and configure the channel yourself.');
    await expectWithinViewport(chooser, viewport);
    await expectWithinViewport(importChoice, viewport);
    await expectWithinViewport(manualChoice, viewport);
    await expect(chooser).toHaveScreenshot(`new-channel-choice-${theme}-${viewport.width > 760 ? 'desktop' : 'mobile'}.png`);
    await page.screenshot({ path: test.info().outputPath(`new-channel-choice-${theme}-viewport.png`) });
    await page.keyboard.press('Escape');
    await expect(chooser).toHaveCount(0);
    await expect(newChannel).toBeFocused();
    expect(editorRequests).toEqual([]);
    await page.keyboard.press('Enter');
    await expect(importChoice).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(chooser).toHaveCount(0);
    await expect(page).toHaveURL(/view=radioreference&from=channel-setup$/);
    await expect(page.getByRole('heading', { name: 'Connect your account', exact: true })).toBeVisible();
    // The visual server mounts the app at /app.html and uses / for the gallery.
    await page.goto(`/app.html${new URL(page.url()).search}`);
    await expect(page).toHaveURL(/view=radioreference&from=channel-setup$/);
    await expect(page.getByRole('heading', { name: 'Connect your account', exact: true })).toBeVisible();
    expect(editorRequests).toEqual([]);
    expect(fixture.pageErrors).toEqual([]);
  });
}

test('new channel keeps manual creation available without RadioReference access', async ({ page }) => {
  const { fixture } = await openChannels(page, { tuners: [], noRadioReferenceAccess: true });
  await toolbarAction(page, 'New channel').click();
  const chooser = page.getByRole('dialog', { name: 'How would you like to create a channel?', exact: true });
  const manualChoice = chooser.getByRole('button', { name: 'Create a channel from scratch', exact: true });
  await expect(chooser.getByRole('button', { name: 'Import from RadioReference', exact: true })).toBeDisabled();
  await expect(chooser).toContainText('RadioReference import is unavailable for this account.');
  await expect(manualChoice).toBeFocused();
  await page.keyboard.press('Enter');
  const editor = page.getByRole('dialog', { name: 'Create Channel', exact: true });
  await expect(chooser).toHaveCount(0);
  await expect(editor.getByRole('button', { name: 'Create channel', exact: true })).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(editor).toHaveCount(0);
  await expect(toolbarAction(page, 'New channel')).toBeFocused();
  expect(fixture.pageErrors).toEqual([]);
});

for (const options of [{ noTunerAccess: true }, { noRadioReferenceAccess: true }]) {
  test(`channel tour follows available controls with ${Object.keys(options)[0]}`, async ({ page }) => {
    const { fixture } = await openChannels(page, { ...options, guide: 'page' });
    const expected = ['channel-choice', 'channel-choice-close',
      ...(options.noTunerAccess ? [] : ['discover']), 'select-channel', 'receiving', 'startup'];
    await expect(guideRoot(page)).toContainText('Enter a known frequency to create a channel.');
    await expect(guideRoot(page)).toContainText(`Step 1 of ${expected.length + 1}`);
    for (const id of expected) {
      await guideRoot(page).getByRole('button', { name: 'Next', exact: true }).click();
      await expect(guideRoot(page)).toHaveAttribute('data-guide-step', id);
      if (id === 'channel-choice') {
        const chooser = page.getByRole('dialog', { name: 'How would you like to create a channel?', exact: true });
        await expect(chooser.getByRole('button', { name: 'Import from RadioReference', exact: true })).toBeDisabled();
        await expect(chooser.getByRole('button', { name: 'Create a channel from scratch', exact: true })).toBeEnabled();
      }
    }
    await guideRoot(page).getByRole('button', { name: 'Finish', exact: true }).click();
    await expect(guideRoot(page)).toHaveCount(0);
    await expect(page.locator('.ui-setup-guide-layer, [data-guide-sample]')).toHaveCount(0);
    expect(fixture.pageErrors).toEqual([]);
  });
}

test('filters hiding configured channels do not trigger first channel guidance', async ({ page }) => {
  const { fixture } = await openChannels(page, { channels: [{ configuration_id: 'existing-channel', name: 'Existing channel',
    protocol: 'P25', protocol_id: 'p25-phase1', protocol_label: 'P25 Phase 1', channel_kind: 'TRUNKED',
    frequencies_hz: [851012500], processing_state: 'STOPPED', editable: true }] });
  await expect(page.locator('.channel-catalog-table tr[data-id="existing-channel"]')).toBeVisible();
  await page.getByRole('searchbox', { name: 'Search channels, systems, protocols, frequencies, or alias lists', exact: true }).fill('No match');
  await expect(page.getByText('No channels match this view', { exact: true })).toBeVisible();
  await expect(guide(page)).toHaveCount(0);
  expect(fixture.pageErrors).toEqual([]);
});
