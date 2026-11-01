const { test, expect } = require('@playwright/test');
const path = require('node:path');
const { openAudioApp } = require('./fixtures/audio-dock-app.cjs');
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
    await play(page, 1);
    await expect(cards.nth(0)).not.toHaveClass(/recordings-call-played/);
    await finish(page, 'error');
    await expect(cards.nth(1)).not.toHaveClass(/recordings-call-played/);
    await play(page, 0); await finish(page);
    await expect(cards.nth(0)).toHaveClass(/recordings-call-played/);
    await expect(cards.nth(0).locator('.recordings-call-status')).toHaveText('Played');
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
