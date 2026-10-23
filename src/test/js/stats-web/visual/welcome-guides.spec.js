'use strict';

const { expect, test } = require('@playwright/test');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');

const guide = page => page.locator('.ui-setup-guide');
const next = page => guide(page).getByRole('button', { name: 'Next', exact: true });
const welcomeKey = 'sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:welcome:1';

async function openApplication(page, { theme = 'light', firstRun = false, view = 'channel-setup', replay = true, channelFailure = false } = {}) {
  const fixture = await installSiteStyleApplication(page, theme, undefined, { guides: firstRun });
  const catalogResponse = channelFailure ? { status: 503, json: { error: { message: 'Channel catalog is temporarily unavailable.' } } } :
    { json: { data: { revision: 1, channels: [] } } };
  await page.route('**/api/v1/admin/channels', route => route.fulfill(catalogResponse));
  await page.route('**/api/v1/channel-catalog', route => route.fulfill(catalogResponse));
  await page.goto(`/app.html?view=${view}${replay ? '&guide=welcome' : ''}`);
  return fixture;
}

async function expectSpotlight(page, selector) {
  const target = page.locator(selector).first();
  await expect(target).toBeVisible();
  await expect.poll(async () => target.evaluate(element => {
    const bounds = element.getBoundingClientRect();
    return [...document.querySelectorAll('.ui-setup-guide-highlight')].some(highlight => {
      const rect = highlight.getBoundingClientRect();
      return rect.left <= bounds.left && rect.top <= bounds.top &&
        rect.right >= bounds.right && rect.bottom >= bounds.bottom;
    });
  }), { message: `Spotlight surrounds ${selector}` }).toBe(true);
}

async function expectMenu(page, name, views) {
  const group = page.locator(`.primary-nav [data-nav-group="${name}"]`);
  await expect(group).toHaveAttribute('open', '');
  await expectSpotlight(page, `.primary-nav [data-nav-group="${name}"] > summary`);
  for (const view of views) await expectSpotlight(page, `.primary-nav [data-view="${view}"]`);
}

for (const theme of ['light', 'dark']) {
  test(`welcome guide walks every menu and hands off to Channel Setup ${theme}`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    const writes = [];
    page.on('request', request => {
      if (new URL(request.url()).pathname.startsWith('/api/v1/') && !['GET', 'HEAD'].includes(request.method())) {
        writes.push(`${request.method()} ${new URL(request.url()).pathname}`);
      }
    });
    const fixture = await openApplication(page, { theme });
    await expect(guide(page).getByRole('heading', { name: 'Welcome to sdrtrunk VCE', exact: true })).toBeVisible();
    await expect(guide(page)).toContainText('Step 1 of 11');
    await expect(guide(page).locator('canvas')).toBeVisible();
    await page.screenshot({ path: testInfo.outputPath(`welcome-${theme}.png`) });
    await next(page).click();
    await expect(guide(page)).toContainText('Explore the airwaves');
    await expectMenu(page, 'hardware', ['tuner-spectrum', 'tuners']);
    await guide(page).getByRole('heading', { name: 'Explore the airwaves', exact: true }).click();
    await expectMenu(page, 'hardware', ['tuner-spectrum', 'tuners']);
    await page.screenshot({ path: testInfo.outputPath(`hardware-spotlight-${theme}.png`) });
    await next(page).click();
    await expectMenu(page, 'configuration', ['channel-setup', 'aliases', 'radioreference', 'streaming']);
    await expect(page.locator('.primary-nav [data-nav-group="hardware"]')).not.toHaveAttribute('open');
    await next(page).click();
    await expectMenu(page, 'configuration', ['scan-lists', 'alerts']);
    await expect(guide(page)).toContainText('Scan Lists replace the listening checkbox on Alias Lists');
    await next(page).click();
    await expectMenu(page, 'listen', ['live']);
    await next(page).click();
    await expectMenu(page, 'listen', ['dashboard', 'scanner', 'recordings']);
    await expect(guide(page)).toContainText('VCE records each call before playing it');
    await next(page).click();
    await expect(guide(page)).toContainText('Keep listening as you browse');
    await expectSpotlight(page, '[data-guide-target="player"]');
    await next(page).click();
    await expectSpotlight(page, '#page-guide-button');
    await next(page).click();
    await expectSpotlight(page, '#support-report-indicator');
    await next(page).click();
    await expectMenu(page, 'settings', ['admin', 'settings', 'credits']);
    await next(page).click();
    await expect(guide(page)).toContainText('Step 11 of 11');
    await expectSpotlight(page, '#theme-toggle');
    await guide(page).getByRole('button', { name: 'Set up channels', exact: true }).click();
    await expect(guide(page).getByRole('heading', { name: 'Add your first channel', exact: true })).toBeVisible();
    await expect(guide(page)).toContainText('Step 1 of 6');
    await expect(page.locator('.primary-nav [data-nav-group][open]')).toHaveCount(0);
    expect(writes).toEqual([]);
    expect(fixture.pageErrors).toEqual([]);
  });
}

test('first desktop visit remembers welcome dismissal and the URL can replay it', async ({ page }) => {
  const fixture = await openApplication(page, { firstRun: true, replay: false, view: 'dashboard' });
  await expect(guide(page)).toContainText('Welcome to sdrtrunk VCE');
  await guide(page).getByRole('button', { name: 'Close guide', exact: true }).click();
  await expect(guide(page)).toHaveCount(0);
  expect(await page.evaluate(key => localStorage.getItem(key), welcomeKey)).toBe('1');
  await page.reload();
  await expect(page.locator('#page-guide-button')).toBeVisible();
  await expect(guide(page)).toHaveCount(0);
  await page.goto('/app.html?view=dashboard&guide=welcome');
  await expect(guide(page)).toContainText('Welcome to sdrtrunk VCE');
  expect(fixture.pageErrors).toEqual([]);
});

for (const dismissal of ['Close guide', 'Escape', 'darkened area']) {
  test(`${dismissal} dismisses the welcome tour and restores open menus`, async ({ page }) => {
    const fixture = await openApplication(page, { view: 'dashboard' });
    await expect(guide(page)).toContainText('Welcome to sdrtrunk VCE');
    await next(page).click();
    await expectMenu(page, 'hardware', ['tuner-spectrum', 'tuners']);
    if (dismissal === 'Close guide') await guide(page).getByRole('button', { name: dismissal, exact: true }).click();
    else if (dismissal === 'Escape') await page.keyboard.press('Escape');
    else await page.mouse.click(8, 500);
    await expect(guide(page)).toHaveCount(0);
    await expect(page.locator('.ui-setup-guide-backdrop')).toHaveCount(0);
    await expect(page.locator('.primary-nav [data-nav-group][open]')).toHaveCount(0);
    await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
    expect(fixture.pageErrors).toEqual([]);
  });
}

test('page help opens a fallback template and keeps keyboard focus inside the guide', async ({ page }) => {
  const fixture = await openApplication(page, { replay: false, view: 'dashboard' });
  const help = page.locator('#page-guide-button');
  await help.click();
  await expect(guide(page)).toContainText('Guide coming soon');
  for (let index = 0; index < 5; index += 1) {
    await page.keyboard.press('Tab');
    expect(await page.evaluate(() => Boolean(document.activeElement.closest('.ui-setup-guide')))).toBe(true);
  }
  await page.keyboard.press('Escape');
  await expect(guide(page)).toHaveCount(0);
  await expect(help).toBeFocused();
  await help.click();
  await guide(page).getByRole('button', { name: 'Take the welcome tour', exact: true }).click();
  await expect(guide(page)).toContainText('Welcome to sdrtrunk VCE');
  expect(fixture.pageErrors).toEqual([]);
});

test('phone first visits stay clear of automatic desktop tours', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const fixture = await openApplication(page, { firstRun: true, replay: false, view: 'dashboard' });
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  await expect(guide(page)).toHaveCount(0);
  expect(await page.evaluate(key => localStorage.getItem(key), welcomeKey)).toBeNull();
  expect(fixture.pageErrors).toEqual([]);
});

test('reduced motion preserves welcome artwork and guide navigation', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  const fixture = await openApplication(page);
  await expect(guide(page)).toContainText('Welcome to sdrtrunk VCE');
  await expect(guide(page).locator('canvas')).toBeVisible();
  await next(page).click();
  await expectMenu(page, 'hardware', ['tuner-spectrum', 'tuners']);
  await guide(page).getByRole('button', { name: 'Back', exact: true }).click();
  await expect(guide(page)).toContainText('Step 1 of 11');
  await expect(guide(page).locator('canvas')).toHaveCount(1);
  expect(fixture.pageErrors).toEqual([]);
});

test('the player spotlight restores a minimized listener without starting audio', async ({ page }) => {
  const audioRequests = [];
  page.on('request', request => {
    if (/\/api\/v1\/.*\/audio(?:\?|$)/.test(request.url())) audioRequests.push(request.url());
  });
  const fixture = await openApplication(page, { replay: false, view: 'dashboard' });
  const player = page.locator('#audio-dock');
  await expect(player).toHaveAttribute('data-source', 'live');
  await expect(player).toHaveAttribute('data-state', 'minimal');
  await player.getByRole('button', { name: 'Minimize player to header', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Restore player', exact: true })).toBeVisible();
  const before = audioRequests.length;
  // Replay through the template without reloading or changing the listening session.
  await page.locator('#page-guide-button').click();
  await guide(page).getByRole('button', { name: 'Take the welcome tour', exact: true }).click();
  for (let step = 1; step < 7; step += 1) await next(page).click();
  await expect(guide(page)).toContainText('Keep listening as you browse');
  await expect(page.locator('#audio-dock-presentation')).toBeVisible();
  await expectSpotlight(page, '[data-guide-target="player"]');
  await page.keyboard.press('Escape');
  await expect(guide(page)).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Restore player', exact: true })).toBeVisible();
  await expect(page.locator('#audio-dock-presentation')).toBeHidden();
  await expect(player).toHaveAttribute('data-source', 'live');
  await expect(player).toHaveAttribute('data-state', 'minimal');
  expect(audioRequests.length).toBe(before);
  expect(fixture.pageErrors).toEqual([]);
});

for (const theme of ['light', 'dark']) {
  test(`guide card preserves readable contrast across the full hue palette ${theme}`, async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    const fixture = await openApplication(page, { theme });
    await expect(guide(page)).toContainText('Welcome to sdrtrunk VCE');
    await next(page).click();
    await expect(guide(page)).toContainText('Explore the airwaves');
    for (const hue of [null, 0, 120, 240]) {
      await page.evaluate(async value => {
        const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
        applyThemeHue(value);
      }, hue);
      await expectContrast(page, { rootSelector: '.ui-setup-guide', reportName: `guide-${theme}-hue-${hue}` });
    }
    const minimum = await page.evaluate(async () => {
      const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
      const samples = [...document.querySelectorAll('.ui-setup-guide h2, .ui-setup-guide-eyebrow, .ui-setup-guide-description, .ui-setup-guide-counter, .ui-setup-guide-footer button')]
        .filter(element => element.getClientRects().length);
      document.querySelectorAll('.ui-setup-guide, .ui-setup-guide *').forEach(element => { element.style.transition = 'none'; });
      const canvas = document.createElement('canvas');
      canvas.width = canvas.height = 1;
      const context = canvas.getContext('2d', { willReadFrequently: true });
      const colors = new Map();
      const rgba = color => {
        if (!colors.has(color)) {
          context.clearRect(0, 0, 1, 1);
          context.fillStyle = color;
          context.fillRect(0, 0, 1, 1);
          colors.set(color, [...context.getImageData(0, 0, 1, 1).data]);
        }
        return colors.get(color);
      };
      const background = element => {
        for (let owner = element; owner; owner = owner.parentElement) {
          const color = getComputedStyle(owner).backgroundColor;
          if (rgba(color)[3] === 255) return color;
          if (rgba(color)[3] !== 0) throw new Error('Guide text needs a solid painted owner.');
        }
        throw new Error('Guide text has no painted owner.');
      };
      const luminance = color => rgba(color).slice(0, 3).reduce((total, channel, index) => {
        const value = channel / 255;
        return total + (value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4) * [.2126, .7152, .0722][index];
      }, 0);
      let worst = { ratio: Infinity };
      for (let hue = 0; hue < 360; hue += 1) {
        applyThemeHue(hue);
        for (const sample of samples) {
          const foreground = getComputedStyle(sample).color;
          const behind = background(sample);
          const values = [luminance(foreground), luminance(behind)].sort((a, b) => b - a);
          const ratio = (values[0] + .05) / (values[1] + .05);
          if (ratio < worst.ratio) worst = { ratio, hue, text: sample.textContent.trim(), foreground, background: behind };
        }
      }
      applyThemeHue(null);
      return worst;
    });
    expect(minimum.ratio, JSON.stringify(minimum)).toBeGreaterThanOrEqual(4.5);
    expect(fixture.pageErrors).toEqual([]);
  });
}

test('a failed Channel Setup load keeps the welcome tour out of the error state', async ({ page }) => {
  const fixture = await openApplication(page, { firstRun: true, replay: false, channelFailure: true });
  await expect(page.locator('.async-section-error')).toContainText('Channel catalog is temporarily unavailable.');
  await expect(page.getByRole('button', { name: 'Retry', exact: true })).toBeVisible();
  await expect(guide(page)).toHaveCount(0);
  expect(await page.evaluate(key => localStorage.getItem(key), welcomeKey)).toBeNull();
  expect(fixture.pageErrors).toEqual([]);
});
