'use strict';

const { expect, test } = require('@playwright/test');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');

const channel = (configurationId, name, overrides = {}) => ({
  configuration_id: configurationId, name, system: 'Metro', site: `${name} site`,
  protocol_id: 'p25-phase1', protocol_label: 'P25 Phase 1', channel_kind: 'TRUNKED',
  frequencies_hz: [851_012_500, 853_987_500], display_frequency_hz: 851_012_500,
  display_frequency_source: 'active-control', processing_state: 'RUNNING', ...overrides
});
const pageOf = rows => ({ rows, total_count: rows.length, limit: 25, offset: 0,
  has_more: false, next_offset: null });

async function directoryFixture(page, theme = 'light') {
  const fixture = await installSiteStyleApplication(page, theme);
  const state = { polls: 0, channels: [channel('west', 'West'), channel('north', 'North'),
    channel('stale', 'Stale', { display_frequency_source: 'configured', quality_frequency_hz: 852_987_500 }),
    channel('alpha', 'Alpha', { system: 'Alpha system' }),
    channel('conventional', 'Dispatch', { channel_kind: 'CONVENTIONAL', system: 'Conventional',
      protocol_id: 'nbfm', processing_state: 'STOPPED' })] };
  await page.route('**/api/v1/channel-catalog', route => {
    state.polls += 1;
    return route.fulfill({ json: { data: { revision: state.polls, channels: state.channels } } });
  });
  await page.route('**/api/v1/radio-systems?**', route => route.fulfill({ json: { data: pageOf([
    { radio_system_key: 'p25:00001:001', entity_ref: { kind: 'radio_system', key: 'p25:00001:001' },
      system_name: 'Metro', channel_preview: state.channels.filter(row => row.system === 'Metro') },
    { radio_system_key: 'p25:00001:002', entity_ref: { kind: 'radio_system', key: 'p25:00001:002' },
      system_name: 'Alpha system', channel_preview: state.channels.filter(row => row.system === 'Alpha system') }
  ]) } }));
  await page.goto('/app.html?view=dashboard&tab=health');
  const directory = page.locator('.radio-directory-local');
  await directory.getByRole('button', { name: 'All', exact: true }).click();
  await expect(directory.locator('.radio-directory-site-row')).toHaveCount(5);
  return { ...fixture, state, directory };
}

test('Directory polling preserves cards, rows, reading geometry, and focused links', async ({ page }) => {
  const { state, directory, unexpected, pageErrors } = await directoryFixture(page);
  const row = id => directory.locator(`[data-directory-row-key="${id}"]`);
  await expect(directory.locator('.radio-directory-system-name')).toHaveText(['Alpha system', 'Metro', 'Conventional']);
  await expect(directory.locator('[data-directory-card-key="trunked:p25:00001:001"] .channel-name-link'))
    .toHaveText(['North', 'Stale', 'West']);
  await expect(row('north').locator('.radio-directory-frequency')).toHaveText('Current control851.01250 MHz');
  await expect(row('stale').locator('.radio-directory-frequency')).toHaveText('Current controlUnavailable');
  await expect(row('conventional').locator('.radio-directory-frequency')).toHaveText('Frequency851.01250 MHz');
  await expect(directory).not.toContainText(/853\.98750|852\.98750|P25 Phase 1|Alias List:|sites shown|channels shown/);
  await expect(directory.locator('.radio-directory-system-header .radio-directory-live-action')).toHaveCount(3);
  await expect(directory.locator('[data-directory-card-key="trunked:p25:00001:001"] .radio-directory-live-action'))
    .toHaveAttribute('href', /view=live&channel=north/);
  await expect(directory.locator('.radio-directory-conventional-card .radio-directory-live-action'))
    .toHaveAttribute('href', '/?view=live');
  await row('north').getByRole('link', { name: 'North', exact: true }).focus();
  await page.evaluate(() => {
    const host = document.querySelector('.radio-directory-content');
    window.beforeDirectory = { cards: [...host.querySelectorAll('.radio-directory-system-card')],
      rows: [...host.querySelectorAll('.radio-directory-site-row')], focused: document.activeElement,
      top: host.getBoundingClientRect().top,
      boxes: [...host.querySelectorAll('.radio-directory-system-card')].map(element => element.getBoundingClientRect().toJSON()),
      moves: [] };
    window.directoryObserver = new MutationObserver(records => {
      for (const record of records) for (const element of record.removedNodes)
        if (element.nodeType === 1 && element.matches('.radio-directory-site-row, .radio-directory-system-card'))
          window.beforeDirectory.moves.push(element.dataset.directoryRowKey || element.dataset.directoryCardKey);
    });
    window.directoryObserver.observe(host, { childList: true, subtree: true });
  });
  const firstPoll = state.polls;
  state.channels = state.channels.slice().reverse().map(value => ({ ...value,
    display_frequency_hz: value.configuration_id === 'north' ? 852_012_500 : value.display_frequency_hz,
    last_seen_ms: value.configuration_id === 'west' ? Date.now() : 1 }));
  await expect.poll(() => state.polls, { timeout: 8_000 }).toBeGreaterThan(firstPoll);
  await expect(row('north').locator('.radio-directory-frequency')).toContainText('852.01250 MHz');
  const stability = await page.evaluate(() => {
    const host = document.querySelector('.radio-directory-content');
    const previous = window.beforeDirectory;
    const cards = [...host.querySelectorAll('.radio-directory-system-card')];
    const rows = [...host.querySelectorAll('.radio-directory-site-row')];
    return { cards: cards.every((element, index) => element === previous.cards[index]),
      rows: rows.every((element, index) => element === previous.rows[index]),
      focus: document.activeElement === previous.focused, moves: previous.moves,
      geometry: cards.every((element, index) => {
        const next = element.getBoundingClientRect(), prior = previous.boxes[index];
        return next.x === prior.x && next.y === prior.y && next.height === prior.height;
      }) };
  });
  expect(stability).toEqual({ cards: true, rows: true, focus: true, moves: [], geometry: true });
  const secondPoll = state.polls;
  state.channels = state.channels.filter(value => value.configuration_id !== 'west');
  state.channels.unshift(channel('east', 'East'));
  await expect.poll(() => state.polls, { timeout: 8_000 }).toBeGreaterThan(secondPoll);
  await expect(row('west')).toHaveCount(0);
  await expect(directory.locator('[data-directory-card-key="trunked:p25:00001:001"] .channel-name-link'))
    .toHaveText(['North', 'Stale', 'East']);
  await expect(row('north').getByRole('link', { name: 'North', exact: true })).toBeFocused();
  await page.evaluate(() => window.directoryObserver.disconnect());
  for (const width of [1280, 390, 320]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    expect(await directory.locator('.radio-directory-system-card').evaluateAll(elements => elements.every(element =>
      element.scrollWidth <= element.clientWidth && element.scrollHeight <= element.clientHeight))).toBe(true);
  }
  expect(unexpected).toEqual([]);
  expect(pageErrors).toEqual([]);
});

test('Directory Live actions respect access permissions in dark mode', async ({ page }) => {
  await installSiteStyleApplication(page, 'dark');
  await page.route('**/api/v1/auth/session', route => route.fulfill({ json: { data: {
    configured: true, authenticated: true, username: 'listener', tier: 'user', primary: false,
    capabilities: { dashboard: true, radio: true, live: false }
  } } }));
  await page.goto('/app.html?view=dashboard');
  await expect(page.locator('.radio-directory-system-card')).toHaveCount(1);
  await expect(page.locator('.radio-directory-live-action')).toHaveCount(0);
  await expect(page.locator('.radio-directory-site-row .channel-name-link')).toBeVisible();
});

test('Directory reads stable bounded system pages without duplicate channels or cards', async ({ page }) => {
  await installSiteStyleApplication(page);
  const north = channel('north', 'North');
  const west = channel('west', 'West');
  const alpha = channel('alpha', 'Alpha', { system: 'Alpha system' });
  const requests = [];
  await page.route('**/api/v1/channel-catalog', route => route.fulfill({ json: { data: {
    revision: 1, channels: [west, north, alpha]
  } } }));
  await page.route('**/api/v1/radio-systems?**', route => {
    const query = new URL(route.request().url()).searchParams;
    requests.push(Object.fromEntries(query));
    const alphaSystem = { radio_system_key: 'p25:00001:002', system_name: 'Alpha system', channel_preview: [alpha] };
    const rows = query.has('offset') ? [alphaSystem,
      { radio_system_key: 'p25:00001:001', system_name: 'Metro', channel_preview: [north, north, west] }] :
      [...Array.from({ length: 24 }, (_, index) => ({ radio_system_key: `historical:${index}`,
        system_name: `Historical ${index}`, channel_preview: [] })), alphaSystem];
    return route.fulfill({ json: { data: { rows, total_count: 27, limit: 25,
      offset: query.has('offset') ? 25 : 0, has_more: !query.has('offset'),
      next_offset: query.has('offset') ? null : 25 } } });
  });
  await page.goto('/app.html?view=dashboard');
  await expect(page.locator('.radio-directory-system-card')).toHaveCount(2);
  await expect(page.locator('.radio-directory-site-row')).toHaveCount(3);
  await expect(page.locator('[data-directory-row-key="north"]')).toHaveCount(1);
  await expect(page.locator('[data-directory-row-key="alpha"]')).toHaveCount(1);
  expect(requests).toHaveLength(2);
  expect(requests.map(({ sort, direction, limit, offset }) => ({ sort, direction, limit, offset }))).toEqual([
    { sort: 'system_id', direction: 'asc', limit: '25', offset: undefined },
    { sort: 'system_id', direction: 'asc', limit: '25', offset: '25' }
  ]);
});
