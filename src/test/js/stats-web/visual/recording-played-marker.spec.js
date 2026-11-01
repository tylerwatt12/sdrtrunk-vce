const { test, expect } = require('@playwright/test');
const path = require('node:path');
const { openAudioApp, recording } = require('./fixtures/audio-dock-app.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');

async function captureAudio(page) {
  await page.evaluate(() => {
    const play = Audio.prototype.play;
    Audio.prototype.play = function (...args) { window.audioTest.recordingPlayer = this; return play.apply(this, args); };
  });
}
async function play(page, index) {
  await page.locator('.recordings-call-info').nth(index).click();
  await page.getByRole('dialog', { name: 'Call actions', exact: true }).getByRole('button', { name: /Play once/ }).click();
  await expect(page.locator('.recordings-call').nth(index)).toHaveClass(/recordings-call-playing/);
}
async function finish(page, event = 'ended') {
  await page.evaluate(event => window.audioTest.recordingPlayer.dispatchEvent(new Event(event)), event);
}

for (const authenticated of [false, true]) for (const theme of ['light', 'dark']) {
  test(`only completed calls have a remembered played marker for ${authenticated ? 'accounts' : 'guests'} in ${theme}`, async ({ page }) => {
    await page.setViewportSize(theme === 'light' ? { width: 1280, height: 900 } : { width: 390, height: 844 });
    const state = await openAudioApp(page, { authenticated, admin: false, theme, feedCallsEnabled: false });
    if (!authenticated && theme === 'dark') await page.getByRole('button', { name: 'Use dark theme', exact: true }).click();
    if (theme === 'dark') await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    await captureAudio(page);
    const cards = page.locator('.recordings-call');
    await play(page, 0);
    await expect(cards.nth(0)).not.toHaveClass(/recordings-call-played/);
    await page.evaluate(() => {
      const play = Audio.prototype.play;
      window.audioTest.resumePlay = play;
      Audio.prototype.play = function (...args) {
        const result = play.apply(this, args);
        return new Promise(resolve => { window.audioTest.finishLoading = () => resolve(result); });
      };
    });
    await play(page, 1);
    const status = cards.nth(1).locator('.recordings-call-status');
    await expect(status).toHaveText('Loading');
    await expect(status).toHaveClass(/visually-hidden/);
    await expect(status).toHaveCSS('clip-path', 'inset(50%)');
    await page.evaluate(() => window.audioTest.finishLoading());
    await expect(status).toHaveText('');
    await page.evaluate(() => window.audioTest.recordingPlayer.pause());
    await expect(status).toHaveText('Paused');
    await expect(status).toHaveCSS('clip-path', 'inset(50%)');
    await page.evaluate(() => { Audio.prototype.play = window.audioTest.resumePlay; });
    await expect(cards.nth(0)).not.toHaveClass(/recordings-call-played/);
    await finish(page, 'error');
    await expect(cards.nth(1)).not.toHaveClass(/recordings-call-played/);
    await play(page, 0); await finish(page);
    await expect(cards.nth(0)).toHaveClass(/recordings-call-played/);
    await expect(cards.nth(0).locator('.recordings-call-status')).toHaveText('Played');
    await expect(cards.nth(0).locator('.recordings-call-status')).toHaveClass(/visually-hidden/);
    await expect(cards.nth(0).locator('.recordings-call-status')).toHaveCSS('clip-path', 'inset(50%)');
    await expect(cards.nth(0).locator('.recordings-call-title')).toHaveAttribute('aria-describedby', /playback-status/);
    if (authenticated) await expectContrast(page, { reportName: `recording-played-${theme}` });
    if (process.env.PLAYED_MARKER_EVIDENCE) await cards.nth(0).screenshot({ path: path.join(process.env.PLAYED_MARKER_EVIDENCE,
      `played-${authenticated ? 'account' : 'guest'}-${theme}.png`) });
    await page.reload();
    await expect(cards.nth(0)).toHaveClass(/recordings-call-played/);
    await expect(cards.nth(1)).not.toHaveClass(/recordings-call-played/);
    await captureAudio(page); await play(page, 0);
    await expect(cards.nth(0)).not.toHaveClass(/recordings-call-played/);
    await finish(page); await expect(cards.nth(0)).toHaveClass(/recordings-call-played/);
    if (authenticated && theme === 'light') {
      await play(page, 1);
      await page.locator('.recordings-call-info').nth(0).click();
      await page.getByRole('dialog', { name: 'Call actions', exact: true })
        .getByRole('button', { name: /Add to queue/ }).click();
      await expect(cards.nth(0)).toHaveClass(/recordings-call-queued/);
      await expect(cards.nth(0)).not.toHaveClass(/recordings-call-played/);
      await finish(page);
      await expect(cards.nth(0)).toHaveClass(/recordings-call-playing/);
      await finish(page);
      await expect(cards.nth(0)).toHaveClass(/recordings-call-played/);
    }
    expect(state.preferenceWrites).toHaveLength(0);
  });
}

test('guest and signed-in played markers stay separate through sign-in, sign-out and a late completion', async ({ page }) => {
  const state = await openAudioApp(page, { authenticated: false, admin: false, feedCallsEnabled: false });
  await captureAudio(page);
  const cards = page.locator('.recordings-call');
  await play(page, 0); await finish(page);
  await expect(cards.nth(0)).toHaveClass(/recordings-call-played/);
  await page.route('**/api/v1/auth/login', route => {
    state.session = { ...state.session, authenticated: true, username: 'sample-listener', tier: 'user' };
    return route.fulfill({ json: { data: state.session } });
  });
  await page.route('**/api/v1/auth/logout', route => {
    state.session = { ...state.session, authenticated: false, username: null, tier: 'public' };
    return route.fulfill({ json: { data: state.session } });
  });
  await page.locator('#auth-action').click();
  const login = page.getByRole('dialog', { name: 'Sign In', exact: true });
  await login.getByRole('textbox', { name: 'Username', exact: true }).fill('sample-listener');
  await login.locator('input[type="password"]').fill('fixture-password');
  await login.getByRole('button', { name: 'Sign In', exact: true }).click();
  await expect(page.locator('#auth-action')).toHaveText('Sign Out');
  await expect(cards.nth(0)).not.toHaveClass(/recordings-call-played/);
  await play(page, 1); await finish(page);
  await expect(cards.nth(1)).toHaveClass(/recordings-call-played/);
  await play(page, 2);
  await page.locator('#auth-action').click();
  await expect(page.locator('#auth-action')).toHaveText('Sign In');
  await finish(page);
  await expect(cards.nth(0)).toHaveClass(/recordings-call-played/);
  await expect(cards.nth(1)).not.toHaveClass(/recordings-call-played/);
  await expect(cards.nth(2)).not.toHaveClass(/recordings-call-played/);
});

for (const theme of ['light', 'dark']) {
  test(`queued, active and played markers have distinct shades across the entire ${theme} palette`, async ({ page }) => {
    await page.setViewportSize({ width: theme === 'dark' ? 390 : 1280, height: 900 });
    await openAudioApp(page, { admin: false, theme, feedCallsEnabled: false,
      calls: [0, 1, 2].map(index => ({ ...recording, id: 17 + index, start_ms: recording.start_ms - index * 60_000 })) });
    await captureAudio(page);
    await play(page, 2); await finish(page);
    await expect(page.locator('.recordings-call').nth(2)).toHaveClass(/recordings-call-played/);
    await play(page, 1);
    await page.locator('.recordings-call-info').nth(0).click();
    await page.getByRole('dialog', { name: 'Call actions', exact: true })
      .getByRole('button', { name: /Add to queue/ }).click();
    const result = await page.evaluate(theme => {
      const root = document.documentElement;
      const originalHue = root.style.getPropertyValue('--theme-hue');
      const originalCustom = root.getAttribute('data-custom-hue');
      const canvas = document.createElement('canvas'); canvas.width = canvas.height = 1;
      const ctx = canvas.getContext('2d', { willReadFrequently: true });
      const luminance = () => {
        const rgb = [...ctx.getImageData(0, 0, 1, 1).data].slice(0, 3).map(value => {
          const s = value / 255; return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
        });
        return rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722;
      };
      const colorY = color => { ctx.clearRect(0, 0, 1, 1); ctx.fillStyle = color; ctx.fillRect(0, 0, 1, 1); return luminance(); };
      const backgroundY = element => {
        const chain = []; for (let current = element; current; current = current.parentElement) chain.unshift(current);
        ctx.clearRect(0, 0, 1, 1);
        for (const node of chain) { ctx.fillStyle = getComputedStyle(node).backgroundColor; ctx.fillRect(0, 0, 1, 1); }
        return luminance();
      };
      const ratio = (a, b) => (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
      let minimumSeparation = Infinity, minimumBoundary = Infinity, minimumPlayedTextContrast = Infinity;
      const violations = [], inconsistentPlayedText = [];
      for (let hue = -1; hue < 360; hue++) {
        if (hue >= 0) { root.setAttribute('data-custom-hue', 'true'); root.style.setProperty('--theme-hue', hue); }
        const style = getComputedStyle(root);
        const values = ['active', 'queue', 'played'].map(state => colorY(style.getPropertyValue(`--recording-${state}-marker`)));
        const ordered = theme === 'dark' ? values[0] > values[1] && values[1] > values[2] : values[0] < values[1] && values[1] < values[2];
        if (!ordered) violations.push(hue);
        minimumSeparation = Math.min(minimumSeparation, ratio(values[0], values[1]), ratio(values[1], values[2]));
        for (const [index, state] of ['playing', 'queued', 'played'].entries()) {
          minimumBoundary = Math.min(minimumBoundary, ratio(values[index], backgroundY(document.querySelector(`.recordings-call-${state}`))));
        }
        const played = document.querySelector('.recordings-call-played');
        const textNodes = played.querySelectorAll('.recordings-call-title, .recordings-call-time, .recordings-call-duration, .recordings-call-source, .recordings-call-frequency, .recordings-call-identity, .recordings-transcript-preview, .recordings-call-status');
        const playedInk = colorY(style.getPropertyValue('--recording-played-ink'));
        for (const node of textNodes) {
          const textInk = colorY(getComputedStyle(node).color);
          if (Math.abs(textInk - playedInk) > 0.003) inconsistentPlayedText.push(hue);
          minimumPlayedTextContrast = Math.min(minimumPlayedTextContrast, ratio(textInk, backgroundY(node)));
        }
      }
      if (originalHue) root.style.setProperty('--theme-hue', originalHue); else root.style.removeProperty('--theme-hue');
      if (originalCustom === null) root.removeAttribute('data-custom-hue'); else root.setAttribute('data-custom-hue', originalCustom);
      return { violations, inconsistentPlayedText, minimumSeparation, minimumBoundary, minimumPlayedTextContrast };
    }, theme);
    expect(result.violations).toEqual([]);
    expect(result.inconsistentPlayedText).toEqual([]);
    expect(result.minimumSeparation).toBeGreaterThanOrEqual(1.5);
    expect(result.minimumBoundary).toBeGreaterThanOrEqual(3);
    expect(result.minimumPlayedTextContrast).toBeGreaterThanOrEqual(4.5);
    await expectContrast(page, { reportName: `recording-state-shades-${theme}` });
    await expect(page.locator('.recordings-call-list')).toHaveScreenshot(`recording-state-shades-${theme}.png`);
    if (process.env.PLAYED_MARKER_EVIDENCE) await page.locator('.recordings-call-list').screenshot({
      path: path.join(process.env.PLAYED_MARKER_EVIDENCE, `state-shades-${theme}.png`) });
  });
}
